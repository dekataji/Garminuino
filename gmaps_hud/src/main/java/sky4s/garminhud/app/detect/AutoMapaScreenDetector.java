package sky4s.garminhud.app.detect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sky4s.garminhud.app.MainActivity;
import sky4s.garminhud.app.HudNavigationState;
import sky4s.garminhud.app.HudSessionManager;
import sky4s.garminhud.app.R;
import sky4s.garminhud.eLane;
import sky4s.garminhud.eUnits;
import sky4s.garminhud.hud.HUDInterface;

/** Reads the fixed AutoMapa navigation header after the user grants MediaProjection consent. */
public final class AutoMapaScreenDetector extends ScreenDetector {
    private static final String TAG = AutoMapaScreenDetector.class.getSimpleName();
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern DISTANCE = Pattern.compile("(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(km|m)\\b");
    private static final Pattern HOURS_MINUTES = Pattern.compile("(?iu)(\\d+)\\s*h\\s*(\\d{1,2})");
    private static final Pattern MINUTES = Pattern.compile("(?iu)(\\d+)\\s*min");
    private static final Pattern SECONDS = Pattern.compile("(?iu)(\\d+)\\s*sek");
    private static final Pattern SPEED_LIMIT = Pattern.compile("^(?:[5-9]|[1-9]\\d|1[0-4]0)$");

    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final AtomicBoolean recognitionInProgress = new AtomicBoolean(false);
    private final StableValue<Integer> stableSpeedLimit = new StableValue<>();
    private final StableValue<String> stableTripSummary = new StableValue<>();
    private String lastTripSummarySent;
    private long lastTripSummarySentAt;
    private int lastLaneArrow = -1;
    private int lastLaneOutline = -1;
    private long lastLaneSentAt;

    public AutoMapaScreenDetector(MainActivity activity) {
        super(activity);
    }

    @Override
    void screenDetection(Bitmap screen) {
        if (screen == null || screen.getWidth() < 100 || screen.getHeight() < 200) return;
        detectLanes(screen);
        detectText(screen);
    }

    public void close() {
        recognizer.close();
    }

    public void requestFullRefresh() {
        lastTripSummarySent = null;
        lastTripSummarySentAt = 0L;
        lastLaneArrow = -1;
        lastLaneOutline = -1;
        lastLaneSentAt = 0L;
    }

    private void detectText(Bitmap screen) {
        if (!recognitionInProgress.compareAndSet(false, true)) return;

        int cropHeight = Math.max(1, Math.min(screen.getHeight(), Math.round(screen.getHeight() * 0.31f)));
        Bitmap header = Bitmap.createBitmap(screen, 0, 0, screen.getWidth(), cropHeight).copy(
                Bitmap.Config.ARGB_8888, false);
        int screenWidth = screen.getWidth();
        int screenHeight = screen.getHeight();

        recognizer.process(InputImage.fromBitmap(header, 0))
                .addOnSuccessListener(text -> {
                    try {
                        applyRecognizedText(text, screenWidth, screenHeight);
                    } catch (RuntimeException exception) {
                        // OCR callbacks run outside ImageDetectListener's try/catch. A malformed
                        // recognition result or a transient HUD disconnect must not terminate the
                        // application while driving.
                        Log.w(TAG, "Could not apply AutoMapa OCR result", exception);
                    }
                })
                .addOnFailureListener(error -> Log.w(TAG, "AutoMapa OCR failed", error))
                .addOnCompleteListener(task -> {
                    header.recycle();
                    recognitionInProgress.set(false);
                });
    }

    private void applyRecognizedText(Text text, int width, int height) {
        StringBuilder remainingTime = new StringBuilder();
        StringBuilder eta = new StringBuilder();
        StringBuilder remainingDistance = new StringBuilder();
        Integer speedLimit = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (box == null) continue;
                    float x = box.exactCenterX() / width;
                    float y = box.exactCenterY() / height;
                    String value = element.getText().trim();

                    if (y >= 0.035f && y <= 0.125f) {
                        if (x >= 0.20f && x < 0.48f) append(remainingTime, value);
                        else if (x >= 0.48f && x < 0.75f) append(eta, value);
                        else if (x >= 0.75f) append(remainingDistance, value);
                    } else if (x <= 0.23f && y >= 0.16f && y <= 0.30f
                            && SPEED_LIMIT.matcher(value).matches()) {
                        try {
                            speedLimit = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            // Ignore a transient OCR error and keep the last stable value.
                        }
                    }
                }
            }
        }

        HUDInterface currentHud = HudSessionManager.getInstance(activity).getHud();
        boolean stableLimit = speedLimit != null && stableSpeedLimit.accept(speedLimit);
        if (stableLimit) {
            int gpsSpeed = Math.max(0, activity.mGpsSpeed);
            HudNavigationState.setAutoMapaSpeedLimit(speedLimit);
            currentHud.setSpeedWarning(gpsSpeed, speedLimit, gpsSpeed > speedLimit, true, true);
            Log.i(TAG, "OCR speed limit=" + speedLimit + " gps=" + gpsSpeed
                    + " sent=" + currentHud.getSendResult());
        }

        Integer durationMinutes = parseDurationMinutes(remainingTime.toString());
        int[] arrival = parseClock(eta.toString());
        ParsedDistance distance = parseDistance(remainingDistance.toString());
        // Remaining distance changes between virtually every OCR frame, especially during an
        // AutoMapa simulation. Requiring the entire header to be identical meant this condition
        // was never reached and no trip data was sent. ETA and duration are stable enough to
        // validate the header; the distance is then taken from that accepted frame.
        String arrivalKey = arrival == null ? "-" : arrival[0] + ":" + arrival[1];
        String summaryKey = durationMinutes + "/" + arrivalKey;
        boolean stableSummary = (durationMinutes != null || arrival != null)
                && stableTripSummary.accept(summaryKey);
        Log.i(TAG, "OCR header time='" + remainingTime + "' eta='" + eta
                + "' distance='" + remainingDistance + "' parsed=" + summaryKey
                + " speedLimit=" + speedLimit + " stable=" + stableSummary);
        if (!stableSummary) return;

        long now = System.currentTimeMillis();
        if (summaryKey.equals(lastTripSummarySent) && now - lastTripSummarySentAt < 5_000L) {
            return;
        }

        boolean showEta = activity.getPreferences(Context.MODE_PRIVATE).getBoolean(
                activity.getString(R.string.option_show_eta), false);
        if (showEta && arrival != null) {
            currentHud.setRemainTime(arrival[0], arrival[1], false);
        } else if (durationMinutes != null) {
            currentHud.setRemainTime(durationMinutes / 60, durationMinutes % 60, false);
        }
        boolean timeSent = currentHud.getSendResult();
        if (timeSent) {
            lastTripSummarySent = summaryKey;
            lastTripSummarySentAt = now;
        }
        if (distance != null) {
            currentHud.setRemainingDistance(distance.value, distance.units);
        }
        Log.i(TAG, "OCR trip data sent=" + timeSent + " showEta=" + showEta);
    }

    private static void append(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append(' ');
        builder.append(value);
    }

    private static Integer parseDurationMinutes(String text) {
        Matcher matcher = HOURS_MINUTES.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        }
        matcher = MINUTES.matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        matcher = SECONDS.matcher(text);
        if (matcher.find()) return Math.max(1, (Integer.parseInt(matcher.group(1)) + 59) / 60);
        return null;
    }

    private static int[] parseClock(String text) {
        Matcher matcher = CLOCK.matcher(text);
        if (!matcher.find()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static ParsedDistance parseDistance(String text) {
        Matcher matcher = DISTANCE.matcher(text);
        if (!matcher.find()) return null;
        try {
            float value = Float.parseFloat(matcher.group(1).replace(',', '.'));
            eUnits units = matcher.group(2).toLowerCase(Locale.ROOT).equals("km")
                    ? eUnits.Kilometres : eUnits.Metres;
            return new ParsedDistance(value, units);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void detectLanes(Bitmap screen) {
        int width = screen.getWidth();
        int height = screen.getHeight();
        int left = Math.round(width * 0.15f);
        int right = Math.round(width * 0.85f);
        int top = Math.round(height * 0.085f);
        int bottom = Math.round(height * 0.112f);
        if (right <= left || bottom <= top) return;

        boolean[] activeColumn = new boolean[right - left];
        int[] scanRows = {
                Math.round(height * 0.092f),
                Math.round(height * 0.100f),
                Math.round(height * 0.107f)
        };
        for (int x = left; x < right; x++) {
            int matches = 0;
            for (int scanY : scanRows) {
                if (isLaneTileColor(screen.getPixel(x, scanY))) matches++;
            }
            activeColumn[x - left] = matches >= 2;
        }

        // Arrow stems briefly interrupt the background-colour run. Fill only narrow gaps; the
        // resulting wide run is split into AutoMapa's equal-width lane tiles below.
        int maxGap = Math.max(4, Math.round(width * 0.028f));
        for (int index = 0; index < activeColumn.length; ) {
            if (activeColumn[index]) {
                index++;
                continue;
            }
            int gapStart = index;
            while (index < activeColumn.length && !activeColumn[index]) index++;
            if (gapStart > 0 && index < activeColumn.length && index - gapStart <= maxGap) {
                for (int fill = gapStart; fill < index; fill++) activeColumn[fill] = true;
            }
        }

        List<int[]> tiles = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= activeColumn.length; i++) {
            boolean active = i < activeColumn.length && activeColumn[i];
            if (active && start < 0) start = i;
            if (!active && start >= 0) {
                int tileWidth = i - start;
                if (tileWidth >= width * 0.055f) {
                    int tileCount = Math.max(1, Math.round(tileWidth / (width * 0.115f)));
                    if (tileCount <= 6) {
                        for (int tile = 0; tile < tileCount; tile++) {
                            int tileLeft = left + start + tileWidth * tile / tileCount;
                            int tileRight = left + start + tileWidth * (tile + 1) / tileCount;
                            tiles.add(new int[]{tileLeft, tileRight});
                        }
                    }
                }
                start = -1;
            }
        }

        if (tiles.size() < 2 || tiles.size() > 6) {
            sendLaneState((char) 0, (char) 0, 0);
            return;
        }

        ArrayList<Boolean> recommended = new ArrayList<>();
        for (int[] tile : tiles) {
            recommended.add(isRecommendedLane(screen, tile[0], tile[1], top, bottom));
        }
        sendLanesToHud(recommended);
    }

    private static boolean isLaneTileColor(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        boolean light = r >= 175 && g >= 175 && b >= 175;
        boolean green = g >= 105 && g >= r * 1.35f && g >= b * 1.25f;
        return light || green;
    }

    private static boolean isRecommendedLane(Bitmap image, int left, int right, int top, int bottom) {
        int greenBackground = 0;
        int lightBackground = 0;
        int whiteArrow = 0;
        int blueArrow = 0;
        for (int x = left + 3; x < right - 3; x += 2) {
            for (int y = top + 3; y < bottom - 3; y += 2) {
                int color = image.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                if (g >= 105 && g >= r * 1.35f && g >= b * 1.25f) greenBackground++;
                if (r >= 175 && g >= 175 && b >= 175) lightBackground++;
                if (r >= 235 && g >= 235 && b >= 235) whiteArrow++;
                if (r <= 90 && g <= 125 && b >= 80 && b >= g) blueArrow++;
            }
        }
        if (greenBackground > lightBackground) return whiteArrow >= 20;
        return blueArrow >= 20;
    }

    private void sendLanesToHud(ArrayList<Boolean> recommended) {
        int lanes = recommended.size();
        int start = lanes >= 5 ? 0 : lanes >= 3 ? 1 : 2;
        int outline = 0;
        int selected = 0;
        boolean anySelected = false;
        for (int x = start, index = 0; index < lanes; x++, index++) {
            int value = eLane.OuterLeft.value >> x;
            outline |= value;
            if (recommended.get(index)) {
                selected |= value;
                anySelected = true;
            }
        }
        sendLaneState((char) (anySelected ? selected : 0), (char) outline, lanes);
    }

    private void sendLaneState(char arrow, char outline, int detectedTiles) {
        long now = System.currentTimeMillis();
        boolean changed = arrow != lastLaneArrow || outline != lastLaneOutline;
        // Refresh visible lane guidance occasionally, but do not spend a packet every OCR frame
        // repeatedly clearing an already empty lane display.
        long refreshInterval = outline == 0 ? Long.MAX_VALUE : 5_000L;
        if (!changed && now - lastLaneSentAt < refreshInterval) return;

        HUDInterface currentHud = HudSessionManager.getInstance(activity).getHud();
        currentHud.setLanes(arrow, outline);
        boolean sent = currentHud.getSendResult();
        Log.i(TAG, "Lane tiles=" + detectedTiles + " arrow=0x"
                + Integer.toHexString(arrow) + " outline=0x" + Integer.toHexString(outline)
                + " sent=" + sent);
        if (sent) {
            lastLaneArrow = arrow;
            lastLaneOutline = outline;
            lastLaneSentAt = now;
        }
    }

    private static final class ParsedDistance {
        final float value;
        final eUnits units;

        ParsedDistance(float value, eUnits units) {
            this.value = value;
            this.units = units;
        }
    }

    private static final class StableValue<T> {
        private T candidate;
        private int count;

        boolean accept(T value) {
            if (value == null) return false;
            if (value.equals(candidate)) count++;
            else {
                candidate = value;
                count = 1;
            }
            return count >= 2;
        }
    }
}
