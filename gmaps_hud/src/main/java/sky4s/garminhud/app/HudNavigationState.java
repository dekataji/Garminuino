package sky4s.garminhud.app;

/**
 * Small process-wide state shared by screen recognition and the GPS service.
 *
 * <p>The Garmin HUD uses one packet for both the current speed and the speed limit. Sending a
 * plain GPS-speed packet therefore removes a limit that was recognized from AutoMapa. Keeping
 * the last recent limit here lets every GPS update preserve the complete display.</p>
 */
public final class HudNavigationState {
    private static final long SPEED_LIMIT_MAX_AGE_MS = 5_000L;
    private static final long OSM_SPEED_LIMIT_MAX_AGE_MS = 60_000L;
    private static final long SPEED_CAMERA_MAX_AGE_MS = 60_000L;

    private static volatile int autoMapaSpeedLimit = -1;
    private static volatile long autoMapaSpeedLimitUpdatedAt;

    private static volatile int osmSpeedLimit = -1;
    private static volatile long osmSpeedLimitUpdatedAt;

    private static volatile boolean speedCameraAhead = false;
    private static volatile long speedCameraUpdatedAt;

    private HudNavigationState() {
    }

    public static void setAutoMapaSpeedLimit(int speedLimit) {
        if (speedLimit <= 0) return;
        autoMapaSpeedLimit = speedLimit;
        autoMapaSpeedLimitUpdatedAt = System.currentTimeMillis();
    }

    public static int getFreshAutoMapaSpeedLimit() {
        return getFreshAutoMapaSpeedLimit(System.currentTimeMillis());
    }

    static int getFreshAutoMapaSpeedLimit(long now) {
        int limit = autoMapaSpeedLimit;
        if (limit <= 0 || now - autoMapaSpeedLimitUpdatedAt > SPEED_LIMIT_MAX_AGE_MS) {
            return -1;
        }
        return limit;
    }

    public static void clearAutoMapaSpeedLimit() {
        autoMapaSpeedLimit = -1;
        autoMapaSpeedLimitUpdatedAt = 0L;
    }

    public static void setOsmSpeedLimit(int speedLimitKmh) {
        if (speedLimitKmh <= 0) {
            clearOsmSpeedLimit();
            return;
        }
        osmSpeedLimit = speedLimitKmh;
        osmSpeedLimitUpdatedAt = System.currentTimeMillis();
    }

    public static int getFreshOsmSpeedLimit() {
        return getFreshOsmSpeedLimit(System.currentTimeMillis());
    }

    static int getFreshOsmSpeedLimit(long now) {
        int limit = osmSpeedLimit;
        if (limit <= 0 || now - osmSpeedLimitUpdatedAt > OSM_SPEED_LIMIT_MAX_AGE_MS) {
            return -1;
        }
        return limit;
    }

    public static void clearOsmSpeedLimit() {
        osmSpeedLimit = -1;
        osmSpeedLimitUpdatedAt = 0L;
    }

    public static void setSpeedCameraAhead(boolean ahead) {
        speedCameraAhead = ahead;
        speedCameraUpdatedAt = System.currentTimeMillis();
    }

    public static boolean isFreshSpeedCameraAhead() {
        return isFreshSpeedCameraAhead(System.currentTimeMillis());
    }

    static boolean isFreshSpeedCameraAhead(long now) {
        return speedCameraAhead && now - speedCameraUpdatedAt <= SPEED_CAMERA_MAX_AGE_MS;
    }

    public static void clearSpeedCamera() {
        speedCameraAhead = false;
        speedCameraUpdatedAt = 0L;
    }
}
