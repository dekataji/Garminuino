package sky4s.garminhud.app;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.List;

/**
 * Fixes autolaunch when Google Maps (or AutoMapa) is opened: the old
 * auto-start only fired on navigation *notifications*, so merely opening Maps
 * did nothing. This watcher polls UsageStatsManager for the foreground app and
 * starts the HUD connection service when a supported maps app comes forward.
 *
 * Requires PACKAGE_USAGE_STATS ("Usage access" in system settings); without it
 * the watcher stays dormant. Toggleable via option_auto_start_maps_open
 * (default on). Polls every 5 s — cheap, no sensors involved.
 */
public final class MapsForegroundWatcher {

    private static final String TAG = MapsForegroundWatcher.class.getSimpleName();
    private static final long POLL_MS = 5_000L;

    private final Context mAppContext;
    private final Handler mHandler;
    private boolean mRunning;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            checkForeground();
            if (mRunning) {
                mHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    public MapsForegroundWatcher(Context context, Handler handler) {
        mAppContext = context.getApplicationContext();
        mHandler = handler;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mHandler.post(mPoll);
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mPoll);
    }

    /** Whether the system granted usage-access to this app. */
    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void checkForeground() {
        if (!isEnabled() || !hasUsageAccess(mAppContext)) {
            return;
        }
        if (HudConnectionService.isRunning()) {
            return; // already up; nothing to do
        }
        String foreground = getForegroundPackage();
        if (NotificationMonitor.GOOGLE_MAPS_PACKAGE_NAME.equals(foreground)
                || NotificationMonitor.AUTOMAPA_PACKAGE_NAME.equals(foreground)) {
            Log.i(TAG, "Maps opened in foreground; auto-starting HUD service");
            HudConnectionService.start(mAppContext);
        }
    }

    private String getForegroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    mAppContext.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            // Query a short window; the most recently used entry is the foreground app.
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now);
            if (stats == null || stats.isEmpty()) {
                return null;
            }
            UsageStats recent = null;
            for (UsageStats s : stats) {
                if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) {
                    recent = s;
                }
            }
            return recent != null ? recent.getPackageName() : null;
        } catch (Exception e) {
            Log.w(TAG, "Foreground query failed", e);
            return null;
        }
    }

    private boolean isEnabled() {
        SharedPreferences prefs = mAppContext.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        return prefs.getBoolean(
                mAppContext.getString(R.string.option_auto_start_maps_open), true);
    }
}
