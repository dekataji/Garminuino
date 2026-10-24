package sky4s.garminhud.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reverse-geocodes a position to a short street/district name via the public
 * Nominatim API, for the compass street-name scroller. Throttled singleton
 * (60 s / 100 m); never called on the UI thread. Follows the same pattern as
 * OsmRoadDataProvider.
 *
 * Returns the road name, falling back to suburb / city district, uppercased
 * for the HUD's 4-char field. Null when unknown or on failure (caller falls
 * back to the heading readout).
 */
public final class StreetNameProvider {

    private static final String TAG = StreetNameProvider.class.getSimpleName();
    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/reverse?format=json&zoom=16&lat=%f&lon=%f";
    private static final long MIN_QUERY_INTERVAL_MS = 60_000L;
    private static final double MIN_QUERY_DISTANCE_M = 100.0;

    private static final StreetNameProvider INSTANCE = new StreetNameProvider();

    public static StreetNameProvider getInstance() {
        return INSTANCE;
    }

    public interface Callback {
        void onStreetName(String streetName); // null when unknown
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Object mLock = new Object();
    private long mLastQueryAt = 0L;
    private double mLastQueryLat = Double.NaN;
    private double mLastQueryLon = Double.NaN;
    private boolean mQueryInFlight = false;
    private String mCached;

    private StreetNameProvider() {
    }

    /**
     * Requests the street name for the given position. Throttled calls receive
     * the cached result (possibly null) without a network request.
     */
    public void query(double lat, double lon, Callback callback) {
        boolean shouldFetch;
        String cached;
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
            callback.onStreetName(cached);
        }
    }

    private void fetch(double lat, double lon, Callback callback) {
        String result = null;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(String.format(Locale.US, NOMINATIM_URL, lat, lon));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "Garminuino/1.0");
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                result = parseStreetName(sb.toString());
            } else {
                Log.w(TAG, "Nominatim HTTP " + connection.getResponseCode());
            }
        } catch (Exception e) {
            Log.w(TAG, "Nominatim query failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        synchronized (mLock) {
            mQueryInFlight = false;
            if (result != null) {
                mCached = result;
            }
        }
        if (callback != null) {
            String out;
            synchronized (mLock) {
                out = mCached;
            }
            callback.onStreetName(out);
        }
    }

    private static String parseStreetName(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject address = root.optJSONObject("address");
            if (address == null) {
                return null;
            }
            String name = firstNonEmpty(address,
                    "road", "pedestrian", "footway", "cycleway",
                    "suburb", "city_district", "quarter", "neighbourhood");
            if (name == null || name.isEmpty()) {
                return null;
            }
            return name.toUpperCase(Locale.US);
        } catch (Exception e) {
            Log.w(TAG, "Nominatim parse failed", e);
            return null;
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }
}
