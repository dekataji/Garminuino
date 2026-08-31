package sky4s.garminhud.app;

/** Brightness defaults and conversion for Garmin HUD's ten manual levels. */
public final class HudBrightness {
    public static final boolean DEFAULT_AUTO = true;
    public static final int DEFAULT_MANUAL_LEVEL = 10;
    public static final int MAX_MANUAL_LEVEL = 10;

    private HudBrightness() {
    }

    public static int clampManualLevel(int level) {
        return Math.max(0, Math.min(MAX_MANUAL_LEVEL, level));
    }

    public static int gammaCorrectedLevel(int progress, int maximum) {
        if (maximum <= 0) return DEFAULT_MANUAL_LEVEL;
        int safeProgress = Math.max(0, Math.min(maximum, progress));
        float normalized = safeProgress * 1.0f / maximum;
        float corrected = (float) Math.pow(normalized, 0.45f);
        return Math.round(corrected * maximum);
    }
}
