package sky4s.garminhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the HUD connection service after boot so the phone auto-connects to
 * the previously paired HUD without opening the app first. The GarminHUD
 * constructor already connects to the saved device address; this just makes
 * sure the service (and therefore the connection attempt) happens on boot.
 * Toggleable via option_auto_connect (default on).
 */
public final class HudBootReceiver extends BroadcastReceiver {
    private static final String TAG = HudBootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        boolean autoConnect = prefs.getBoolean(
                context.getString(R.string.option_auto_connect), true);
        if (!autoConnect) {
            Log.i(TAG, "Auto-connect disabled, not starting HUD service on boot");
            return;
        }
        Log.i(TAG, "Starting HUD connection service on boot");
        HudConnectionService.start(context);
    }
}
