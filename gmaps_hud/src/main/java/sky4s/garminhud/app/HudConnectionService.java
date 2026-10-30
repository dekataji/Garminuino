package sky4s.garminhud.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

public class HudConnectionService extends Service {
    private static final String TAG = HudConnectionService.class.getSimpleName();
    private static final String CHANNEL_ID = "hud_connection";
    private static final int NOTIFICATION_ID = 1001;
    private static volatile boolean sRunning;
    private HudIdleClock mIdleClock;
    private HudCompass mCompass;

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean start(Context context) {
        try {
            ContextCompat.startForegroundService(
                    context, new Intent(context, HudConnectionService.class));
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "The HUD session could not be started from the background", exception);
            return false;
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, HudConnectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        createNotificationChannel();

        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        HudSessionManager.getInstance(this).initialize();

        mIdleClock = new HudIdleClock(this);
        mIdleClock.start();

        mCompass = new HudCompass(this);
        mCompass.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HudSessionManager.getInstance(this).initialize();
        // Sticky: if the process is killed (memory pressure, OEM battery
        // management), the system restarts the service and the HUD link
        // re-establishes without the user opening the app.
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app out of Recents must not drop the HUD link: the
        // connection lives here, not in the activity. Only mute automatic
        // session restarts for the navigation already in progress.
        NotificationMonitor monitor = NotificationMonitor.getInstance();
        if (monitor != null) {
            monitor.suppressAutoStartForCurrentNavigation();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (mIdleClock != null) {
            mIdleClock.stop();
            mIdleClock = null;
        }
        if (mCompass != null) {
            mCompass.stop();
            mCompass = null;
        }
        HudSessionManager.getInstance(this).shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification_foreground)
                .setContentTitle(getString(R.string.hud_service_title))
                .setContentText(getString(R.string.hud_service_description))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hud_service_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.hud_service_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
