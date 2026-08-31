package sky4s.garminhud.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.akexorcist.bluetotohspp.library.BluetoothState;
import app.akexorcist.bluetotohspp.library.DeviceList;
import sky4s.garminhud.hud.DummyHUD;
import sky4s.garminhud.hud.GarminHUD;
import sky4s.garminhud.hud.HUDInterface;

/**
 * Process-wide owner of the HUD connection. The foreground service keeps this object alive;
 * activities and the notification listener only attach to it as clients.
 */
public final class HudSessionManager {
    private static volatile HudSessionManager sInstance;

    private final Context mAppContext;
    private final Set<HUDInterface.ConnectionCallback> mCallbacks = new CopyOnWriteArraySet<>();
    private HUDInterface mHud = new DummyHUD();
    private boolean mInitialized;

    private HudSessionManager(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static HudSessionManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (HudSessionManager.class) {
                if (sInstance == null) {
                    sInstance = new HudSessionManager(context);
                }
            }
        }
        return sInstance;
    }

    public synchronized HUDInterface initialize() {
        if (mInitialized) {
            return mHud;
        }

        mHud.disconnect();
        if (canUseBluetooth()) {
            mHud = new GarminHUD(mAppContext);
        } else {
            mHud = new DummyHUD();
        }

        mHud.registerConnectionCallback(this::dispatchConnectionState);
        NotificationMonitor.updateHud(mHud);
        mInitialized = true;
        return mHud;
    }

    public synchronized HUDInterface restart() {
        mInitialized = false;
        return initialize();
    }

    public synchronized HUDInterface getHud() {
        return mHud;
    }

    public void addConnectionCallback(HUDInterface.ConnectionCallback callback) {
        if (callback != null) {
            mCallbacks.add(callback);
        }
    }

    public void removeConnectionCallback(HUDInterface.ConnectionCallback callback) {
        mCallbacks.remove(callback);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return getHud().handleActivityResult(requestCode, resultCode, data);
    }

    public void openConnectionPicker(Activity activity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            activity.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    BluetoothState.REQUEST_ENABLE_BT);
            return;
        }

        activity.startActivityForResult(
                new Intent(activity, DeviceList.class), BluetoothState.REQUEST_CONNECT_DEVICE);
    }

    public synchronized void shutdown() {
        mHud.disconnect();
        mHud = new DummyHUD();
        NotificationMonitor.updateHud(mHud);
        mInitialized = false;
    }

    private boolean canUseBluetooth() {
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(mAppContext, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void dispatchConnectionState(HUDInterface.ConnectionCallback.ConnectionState state) {
        for (HUDInterface.ConnectionCallback callback : mCallbacks) {
            callback.onConnectionStateChange(state);
        }
    }
}
