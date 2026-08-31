package sky4s.garminhud.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

public class ScreenRecorderService extends Service {
    private static final String TAG = ScreenRecorderService.class.getSimpleName();
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 1003;
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";

    public interface ProjectionCallback {
        void onProjectionReady(MediaProjection projection);
    }

    private static ProjectionCallback sPendingCallback;
    private MediaProjection mMediaProjection;

    public static void start(
            Context context, int resultCode, Intent resultData, ProjectionCallback callback) {
        sPendingCallback = callback;
        Intent serviceIntent = new Intent(context, ScreenRecorderService.class)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        ProjectionCallback callback = sPendingCallback;
        sPendingCallback = null;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mMediaProjection = manager.getMediaProjection(
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData);
            if (callback != null) {
                callback.onProjectionReady(mMediaProjection);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not obtain MediaProjection", exception);
            if (callback != null) {
                callback.onProjectionReady(null);
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sPendingCallback = null;
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification_foreground)
                .setContentTitle(getString(R.string.capture_service_title))
                .setContentText(getString(R.string.capture_service_description))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_service_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.capture_service_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
