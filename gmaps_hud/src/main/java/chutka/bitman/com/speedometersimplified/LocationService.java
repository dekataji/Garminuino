package chutka.bitman.com.speedometersimplified;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import sky4s.garminhud.app.HudSessionManager;
import sky4s.garminhud.app.HudNavigationState;
import sky4s.garminhud.app.MainActivity;
import sky4s.garminhud.app.NotificationMonitor;
import sky4s.garminhud.app.R;
import sky4s.garminhud.eUnits;

public class LocationService extends Service {
    private static final String TAG = LocationService.class.getSimpleName();
    private static final String CHANNEL_ID = "location_speed";
    private static final int NOTIFICATION_ID = 1002;
    private static final long UPDATE_INTERVAL_MS = 1000L;
    private static final AtomicBoolean sStartRequested = new AtomicBoolean(false);

    public static double speed;

    private final IBinder mBinder = new LocalBinder();
    private FusedLocationProviderClient mLocationClient;
    private boolean mReceivingUpdates;

    private final LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            Location location = result.getLastLocation();
            if (location == null || !location.hasSpeed()) {
                return;
            }

            eUnits units = NotificationMonitor.getCurrentUnit();
            speed = units == eUnits.Miles
                    ? location.getSpeed() * 2.23693629
                    : location.getSpeed() * 3.6;

            int roundedSpeed = (int) Math.round(speed);
            Log.v(TAG, "GPS speed=" + roundedSpeed);
            int speedLimit = NotificationMonitor.AUTOMAPA_PACKAGE_NAME.equals(
                    NotificationMonitor.getActiveNavigationPackage())
                    ? HudNavigationState.getFreshAutoMapaSpeedLimit() : -1;
            if (speedLimit > 0) {
                HudSessionManager.getInstance(LocationService.this).getHud().setSpeedWarning(
                        roundedSpeed, speedLimit, roundedSpeed > speedLimit, true, true);
            } else {
                HudSessionManager.getInstance(LocationService.this)
                        .getHud().setSpeed(roundedSpeed, true);
            }
            sendSpeedToVisibleActivity(speed);
        }
    };

    public static boolean start(Context context) {
        if (!sStartRequested.compareAndSet(false, true)) {
            return false;
        }
        try {
            ContextCompat.startForegroundService(context, new Intent(context, LocationService.class));
            return true;
        } catch (RuntimeException exception) {
            sStartRequested.set(false);
            Log.w(TAG, "GPS foreground service could not be started", exception);
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (RuntimeException exception) {
            // Android 14+ rejects location FGS promotion when a background component races with
            // the activity becoming invisible. Never let that system policy kill the process;
            // navigation can continue without GPS speed and the user can enable it by opening
            // the activity again.
            Log.w(TAG, "GPS foreground service promotion was rejected", exception);
            sStartRequested.set(false);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        startLocationUpdates();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        startLocationUpdates();
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (mReceivingUpdates) {
            mLocationClient.removeLocationUpdates(mLocationCallback);
            mReceivingUpdates = false;
        }
        sStartRequested.set(false);
        super.onDestroy();
    }

    private void startLocationUpdates() {
        if (mReceivingUpdates
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(500L)
                .build();
        mLocationClient.requestLocationUpdates(request, mLocationCallback, Looper.getMainLooper());
        mReceivingUpdates = true;
    }

    private void sendSpeedToVisibleActivity(double currentSpeed) {
        Intent intent = new Intent(getString(R.string.broadcast_receiver_main_activity))
                .setPackage(getPackageName());
        intent.putExtra(getString(R.string.whoami),
                getString(R.string.broadcast_sender_location_service));
        intent.putExtra(getString(R.string.gps_speed), currentSpeed);
        sendBroadcast(intent);
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
                .setContentTitle(getString(R.string.location_service_title))
                .setContentText(getString(R.string.location_service_description))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_service_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.location_service_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }
}
