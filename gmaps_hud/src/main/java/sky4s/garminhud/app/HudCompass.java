package sky4s.garminhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;
import sky4s.garminhud.hud.HUDInterface;

/**
 * Shows a live compass heading on the HUD whenever it is connected but no
 * navigation is active. Heading comes from the rotation-vector sensor
 * (accelerometer + magnetometer fallback). The display is an 8-wind arrow plus
 * a 4-char cardinal/degrees readout (e.g. "N045").
 *
 * Navigation always takes precedence: updates are skipped while
 * NotificationMonitor reports an active navigation session. Toggleable via
 * option_compass_mode (default on).
 */
public final class HudCompass implements SensorEventListener {

    private static final long MIN_UPDATE_MS = 2000L;
    private static final float DEGREE_CHANGE_THRESHOLD = 10f;

    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final eOutAngle[] ANGLES = {
            eOutAngle.Straight,   // N
            eOutAngle.EasyRight,  // NE
            eOutAngle.Right,      // E
            eOutAngle.SharpRight, // SE
            eOutAngle.Down,       // S
            eOutAngle.SharpLeft,  // SW
            eOutAngle.Left,       // W
            eOutAngle.EasyLeft,   // NW
    };

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SensorManager mSensorManager;
    private final Sensor mRotationSensor;
    private final Sensor mAccelSensor;
    private final Sensor mMagSensor;

    private boolean mConnected;
    private boolean mRunning;
    private boolean mListening;
    private final float[] mAccelReading = new float[3];
    private final float[] mMagReading = new float[3];
    private boolean mHaveAccel;
    private boolean mHaveMag;

    private long mLastUpdateMs;
    private int mLastSector = -1;
    private float mLastDegrees = -1f;

    private final HUDInterface.ConnectionCallback mConnectionCallback =
            this::onConnectionStateChange;

    public HudCompass(Context context) {
        mAppContext = context.getApplicationContext();
        mSensorManager = (SensorManager) mAppContext.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mRotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        } else {
            mRotationSensor = null;
            mAccelSensor = null;
            mMagSensor = null;
        }
    }

    /** Attach to the HUD session; safe to call more than once. */
    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        HudSessionManager.getInstance(mAppContext).addConnectionCallback(mConnectionCallback);
        maybeListen();
    }

    public void stop() {
        mRunning = false;
        stopListening();
        HudSessionManager.getInstance(mAppContext).removeConnectionCallback(mConnectionCallback);
    }

    private void onConnectionStateChange(HUDInterface.ConnectionCallback.ConnectionState state) {
        mConnected = state == HUDInterface.ConnectionCallback.ConnectionState.CONNECTED;
        mHandler.post(this::maybeListen);
    }

    private void maybeListen() {
        if (mRunning && mConnected && isCompassEnabled() && !mListening && mSensorManager != null) {
            if (mRotationSensor != null) {
                mSensorManager.registerListener(this, mRotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
                mListening = true;
            } else if (mAccelSensor != null && mMagSensor != null) {
                mSensorManager.registerListener(this, mAccelSensor, SensorManager.SENSOR_DELAY_NORMAL);
                mSensorManager.registerListener(this, mMagSensor, SensorManager.SENSOR_DELAY_NORMAL);
                mListening = true;
            }
        } else if (mListening && (!mRunning || !mConnected || !isCompassEnabled())) {
            stopListening();
        }
    }

    private void stopListening() {
        if (mListening && mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        mListening = false;
        mHaveAccel = false;
        mHaveMag = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mRunning || !mConnected || !isCompassEnabled()) {
            return;
        }
        float[] rotationMatrix = new float[9];
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, mAccelReading, 0, 3);
            mHaveAccel = true;
            if (!mHaveMag) return;
            SensorManager.getRotationMatrix(rotationMatrix, null, mAccelReading, mMagReading);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, mMagReading, 0, 3);
            mHaveMag = true;
            if (!mHaveAccel) return;
            SensorManager.getRotationMatrix(rotationMatrix, null, mAccelReading, mMagReading);
        } else {
            return;
        }
        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);
        float degrees = (float) Math.toDegrees(orientation[0]);
        if (degrees < 0) degrees += 360f;
        maybeSendHeading(degrees);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not used
    }

    private void maybeSendHeading(float degrees) {
        // Navigation owns the display while active.
        NotificationMonitor monitor = NotificationMonitor.getInstance();
        if (monitor != null && monitor.isNavigating()) {
            return;
        }
        int sector = ((int) ((degrees + 22.5f) / 45f)) % 8;
        long now = System.currentTimeMillis();
        boolean sectorChanged = sector != mLastSector;
        boolean degreesChanged = mLastDegrees < 0
                || Math.abs(degrees - mLastDegrees) >= DEGREE_CHANGE_THRESHOLD;
        if (!sectorChanged && !(degreesChanged && now - mLastUpdateMs >= MIN_UPDATE_MS)) {
            return;
        }
        if (now - mLastUpdateMs < MIN_UPDATE_MS && !sectorChanged) {
            return;
        }
        HUDInterface hud = HudSessionManager.getInstance(mAppContext).getHud();
        if (hud == null) {
            return;
        }
        hud.setDirection(ANGLES[sector], eOutType.ArrowOnly, eOutAngle.AsDirection);
        String text = CARDINALS[sector] + String.format("%03d", Math.round(degrees) % 360);
        char[] t = new char[]{' ', ' ', ' ', ' '};
        for (int i = 0; i < Math.min(4, text.length()); i++) {
            t[i] = text.charAt(i);
        }
        hud.setAlphabet(t[0], t[1], t[2], t[3]);
        mLastSector = sector;
        mLastDegrees = degrees;
        mLastUpdateMs = now;
    }

    private boolean isCompassEnabled() {
        SharedPreferences prefs = mAppContext.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        return prefs.getBoolean(
                mAppContext.getString(R.string.option_compass_mode), true);
    }
}
