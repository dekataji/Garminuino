package chutka.bitman.com.speedometersimplified;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import sky4s.garminhud.app.navigation.OsmRoadDataProvider;
import sky4s.garminhud.eUnits;
import sky4s.garminhud.hud.HUDInterface;

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

    // Cached safety preferences (read once per service start; the service is
    // restarted when settings change via the existing broadcast flow).
    private boolean mOverspeedAlert = true;
    private int mOverspeedThresholdKmh = 5;
    private boolean mSpeedCameraAlerts = true;

    private void reloadSafetyPrefs() {
        SharedPreferences prefs = getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        mOverspeedAlert = prefs.getBoolean(getString(R.string.overspeed_alert), true);
        mOverspeedThresholdKmh = prefs.getInt(getString(R.string.overspeed_threshold_kmh), 5);
        mSpeedCameraAlerts = prefs.getBoolean(getString(R.string.speed_camera_alerts), true);
    }

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

            // Refresh OSM road data in the background; results land in
            // HudNavigationState on the provider thread. Failures keep the last
            // known values, so this never crashes the service.
            OsmRoadDataProvider.getInstance().query(
                    location.getLatitude(), location.getLongitude(), data -> {
                        if (data == null) {
                            return;
                        }
                        if (data.speedLimitKmh != null) {
                            HudNavigationState.setOsmSpeedLimit(data.speedLimitKmh);
                        }
                        HudNavigationState.setSpeedCameraAhead(data.speedCameraAhead);
                    });

            HUDInterface hud = HudSessionManager.getInstance(LocationService.this).getHud();
            // Limit priority: fresh OSM lookup first, then AutoMapa screen OCR.
            int osmLimit = HudNavigationState.getFreshOsmSpeedLimit();
            int automapaLimit = NotificationMonitor.AUTOMAPA_PACKAGE_NAME.equals(
                    NotificationMonitor.getActiveNavigationPackage())
                    ? HudNavigationState.getFreshAutoMapaSpeedLimit() : -1;
            int speedLimit = osmLimit > 0 ? osmLimit : automapaLimit;

            if (speedLimit > 0) {
                boolean speeding = mOverspeedAlert
                        && roundedSpeed > speedLimit + mOverspeedThresholdKmh;
                // setSpeedWarning(speed, limit, speeding, showIcon, showSlash):
                // slash overlay disabled - the threshold-gated warning is less
                // aggressive than the old immediate trigger, so the slash would
                // over-signal. Icon still shows.
                hud.setSpeedWarning(roundedSpeed, speedLimit, speeding, true, false);
            } else {
                hud.setSpeed(roundedSpeed, true);
            }
            hud.setCameraIcon(mSpeedCameraAlerts && HudNavigationState.isFreshSpeedCameraAhead());
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
        reloadSafetyPrefs();
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
        // The camera icon is only driven while this service runs; clear it so a
        // stale alert cannot linger on the HUD.
        try {
            HudSessionManager.getInstance(this).getHud().setCameraIcon(false);
        } catch (Exception ignored) {
        }
        HudNavigationState.clearOsmSpeedLimit();
        HudNavigationState.clearSpeedCamera();
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
