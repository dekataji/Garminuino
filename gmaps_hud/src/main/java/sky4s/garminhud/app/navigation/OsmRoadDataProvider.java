package sky4s.garminhud.app.navigation;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenStreetMap speed-limit and speed-camera data via the public Overpass API.
 *
 * <p>Singleton; queries run on a single background thread. Requests are throttled to at
 * most one every {@link #MIN_QUERY_INTERVAL_MS} and only after the device moved at
 * least {@link #MIN_QUERY_DISTANCE_M} since the previous query. The last successful
 * result is cached and returned for throttled calls. No API key is required.</p>
 *
 * <p>Network failures never throw: the callback receives the cached data (or null on a
 * very first failure) and the caller keeps its last known values.</p>
 */
public final class OsmRoadDataProvider {
    private static final String TAG = OsmRoadDataProvider.class.getSimpleName();
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final long MIN_QUERY_INTERVAL_MS = 30_000L;
    private static final float MIN_QUERY_DISTANCE_M = 100f;
    private static final double CAMERA_RADIUS_M = 500.0;
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)");

    /** Road data for one query point. */
    public static final class RoadData {
        /** Speed limit in km/h, or null when OSM knows none nearby. */
        public final Integer speedLimitKmh;
        /** True when an OSM speed_camera node is within {@link #CAMERA_RADIUS_M}. */
        public final boolean speedCameraAhead;

        RoadData(Integer speedLimitKmh, boolean speedCameraAhead) {
            this.speedLimitKmh = speedLimitKmh;
            this.speedCameraAhead = speedCameraAhead;
        }
    }

    public interface Callback {
        /** Invoked on the provider's background thread. */
        void onRoadData(RoadData data);
    }

    private static final OsmRoadDataProvider INSTANCE = new OsmRoadDataProvider();

    public static OsmRoadDataProvider getInstance() {
        return INSTANCE;
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Object mLock = new Object();
    private long mLastQueryAt = 0L;
    private double mLastQueryLat = Double.NaN;
    private double mLastQueryLon = Double.NaN;
    private boolean mQueryInFlight = false;
    private RoadData mCached;

    private OsmRoadDataProvider() {
    }

    /**
     * Requests road data for the given position. Throttled calls receive the cached
     * result (possibly null) without a network request.
     */
    public void query(double lat, double lon, Callback callback) {
        boolean shouldFetch;
        RoadData cached;
        synchronized (mLock) {
            cached = mCached;
            long now = System.currentTimeMillis();
            boolean moved = Double.isNaN(mLastQueryLat)
                    || haversineM(mLastQueryLat, mLastQueryLon, lat, lon) >= MIN_QUERY_DISTANCE_M;
            shouldFetch = !mQueryInFlight && moved
                    && now - mLastQueryAt >= MIN_QUERY_INTERVAL_MS;
            if (shouldFetch) {
                mQueryInFlight = true;
                mLastQueryAt = now;
                mLastQueryLat = lat;
                mLastQueryLon = lon;
            }
        }
        if (shouldFetch) {
            mExecutor.execute(() -> fetch(lat, lon, callback));
        } else if (callback != null) {
            callback.onRoadData(cached);
        }
    }

    private void fetch(double lat, double lon, Callback callback) {
        RoadData result = null;
        HttpURLConnection connection = null;
        try {
            String query = "[out:json];"
                    + "(way(around:60," + lat + "," + lon + ")[maxspeed];"
                    + "node(around:600," + lat + "," + lon + ")[highway=speed_camera];);"
                    + "out tags;";
            URL url = new URL(OVERPASS_URL + "?data=" + URLEncoder.encode(query, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "Garminuino/1.0 (OSM speed limit lookup)");
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();
                result = parse(new JSONObject(body.toString()), lat, lon);
            } else {
                Log.w(TAG, "Overpass HTTP " + connection.getResponseCode());
            }
        } catch (Exception e) {
            Log.w(TAG, "Overpass query failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        RoadData toDeliver;
        synchronized (mLock) {
            mQueryInFlight = false;
            if (result != null) {
                mCached = result;
            }
            toDeliver = mCached;
        }
        if (callback != null) {
            callback.onRoadData(toDeliver);
        }
    }

    private RoadData parse(JSONObject root, double lat, double lon) {
        Integer limit = null;
        boolean camera = false;
        JSONArray elements = root.optJSONArray("elements");
        if (elements == null) {
            return new RoadData(null, false);
        }
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) {
                continue;
            }
            JSONObject tags = element.optJSONObject("tags");
            if (tags == null) {
                continue;
            }
            if ("speed_camera".equals(tags.optString("highway"))) {
                double elLat = element.optDouble("lat", Double.NaN);
                double elLon = element.optDouble("lon", Double.NaN);
                if (!Double.isNaN(elLat) && !Double.isNaN(elLon)
                        && haversineM(lat, lon, elLat, elLon) <= CAMERA_RADIUS_M) {
                    camera = true;
                }
                continue;
            }
            Integer parsed = parseMaxspeed(tags.optString("maxspeed", null));
            if (parsed != null && (limit == null || parsed < limit)) {
                limit = parsed;
            }
        }
        return new RoadData(limit, camera);
    }

    /**
     * Parses an OSM maxspeed tag to km/h. Takes the leading number, converts mph,
     * and returns null for non-numeric values like "signals" or "none".
     */
    static Integer parseMaxspeed(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = LEADING_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        double number;
        try {
            number = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (value.contains("mph")) {
            number *= 1.60934;
        }
        int kmh = (int) Math.round(number);
        return kmh > 0 && kmh <= 200 ? kmh : null;
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000.0 * Math.asin(Math.sqrt(a));
    }
}
