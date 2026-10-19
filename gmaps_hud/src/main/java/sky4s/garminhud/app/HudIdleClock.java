package sky4s.garminhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;

import sky4s.garminhud.hud.HUDInterface;

/**
 * Shows a live clock on the HUD whenever it is connected but no navigation is
 * active. The HUD's own "PAIR" flash (shown with no Bluetooth link at all) is
 * firmware behaviour and cannot be changed by the app; once the SPP link is up
 * this makes the HUD useful without starting navigation first.
 *
 * Both the auto-connect on boot and the idle clock are toggleable via
 * SharedPreferences (option_auto_connect / option_idle_clock, default on).
 */
public final class HudIdleClock {
    private static final long TICK_MS = 30_000L;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mConnected;
    private boolean mRunning;

    private final HUDInterface.ConnectionCallback mConnectionCallback =
            this::onConnectionStateChange;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            sendClockIfIdle();
            if (mRunning) {
                mHandler.postDelayed(this, TICK_MS);
            }
        }
    };

    public HudIdleClock(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /** Attach to the HUD session; safe to call more than once. */
    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        HudSessionManager.getInstance(mAppContext).addConnectionCallback(mConnectionCallback);
        maybeStartTick();
    }

    public void stop() {
        mRunning = false;
        mConnected = false;
        mHandler.removeCallbacks(mTick);
        HudSessionManager.getInstance(mAppContext).removeConnectionCallback(mConnectionCallback);
    }

    private void onConnectionStateChange(HUDInterface.ConnectionCallback.ConnectionState state) {
        mConnected = state == HUDInterface.ConnectionCallback.ConnectionState.CONNECTED;
        maybeStartTick();
    }

    private void maybeStartTick() {
        mHandler.removeCallbacks(mTick);
        if (mRunning && mConnected && isIdleClockEnabled()) {
            // Align the repeating tick to the next minute boundary so the clock
            // stays precise, but send once immediately so it appears right away.
            sendClockIfIdle();
            long now = System.currentTimeMillis();
            long delay = 60_000L - (now % 60_000L);
            mHandler.postDelayed(mTick, delay);
        }
    }

    private void sendClockIfIdle() {
        if (!mRunning || !mConnected || !isIdleClockEnabled()) {
            return;
        }
        NotificationMonitor monitor = NotificationMonitor.getInstance();
        if (monitor != null && monitor.isNavigating()) {
            return; // navigation owns the display while active
        }
        HUDInterface hud = HudSessionManager.getInstance(mAppContext).getHud();
        if (hud == null) {
            return;
        }
        Calendar now = Calendar.getInstance();
        hud.setCurrentTime(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
    }

    private boolean isIdleClockEnabled() {
        SharedPreferences prefs = mAppContext.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        return prefs.getBoolean(
                mAppContext.getString(R.string.option_idle_clock), true);
    }
}
