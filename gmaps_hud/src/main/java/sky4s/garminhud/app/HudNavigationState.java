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

    private static volatile int autoMapaSpeedLimit = -1;
    private static volatile long autoMapaSpeedLimitUpdatedAt;

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
}
