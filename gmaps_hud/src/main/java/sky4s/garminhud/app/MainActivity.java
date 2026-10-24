package sky4s.garminhud.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.location.LocationManager;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Typeface;
import android.content.res.ColorStateList;

import com.google.android.material.navigation.NavigationView;

import java.io.File;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;

import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt;
import uk.co.samuelwall.materialtaptargetprompt.extras.backgrounds.RectanglePromptBackground;
import uk.co.samuelwall.materialtaptargetprompt.extras.focals.RectanglePromptFocal;

import chutka.bitman.com.speedometersimplified.LocationService;
import sky4s.garminhud.ImageUtils;
import sky4s.garminhud.app.detect.ImageDetectListener;
import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;
import sky4s.garminhud.hud.DummyHUD;
import sky4s.garminhud.hud.GarminHUD;
import sky4s.garminhud.hud.HUDInterface;

public class MainActivity extends AppCompatActivity {
    //for test with virtual device which no BT device
    public final static boolean IGNORE_BT_DEVICE = (null == BluetoothAdapter.getDefaultAdapter());

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";
    private static final String ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int MY_PERMISSIONS_REQUEST_LOCATION = 99;
    private static final int MY_PERMISSIONS_REQUEST_CONNECTION = 100;
    private static final int MY_PERMISSIONS_REQUEST_NOTIFICATIONS = 101;

    //========================================
    // UI: single settings screen
    //========================================
    //connection
    TextView mHudStatusPill;
    TextView mHudDeviceName;

    //navigation
    SwitchCompat mArrowTypeSwitch;
    SwitchCompat mTrafficAndLaneSwitch;
    SwitchCompat mLaneGuidanceSwitch;
    SwitchCompat mRoundaboutExitNumbersSwitch;
    SwitchCompat mRerouteIndicatorSwitch;
    Spinner mEtaModeSpinner;
    Spinner mSpeedUnitSpinner;

    //safety
    SwitchCompat mShowSpeedSwitch;
    SwitchCompat mOverspeedAlertSwitch;
    SeekBar mOverspeedThresholdSeekbar;
    TextView mOverspeedThresholdLabel;
    SwitchCompat mSpeedCameraAlertsSwitch;

    //display
    SwitchCompat mAutoBrightnessSwitch;
    SeekBar mBrightnessSeekbar;
    SwitchCompat mIdleClockSwitch;
    SwitchCompat mCompassSwitch;
    SwitchCompat mCompassStreetSwitch;
    SeekBar mCompassScrollSeekbar;
    TextView mCompassScrollLabel;

    //session
    SwitchCompat mAutoStartNavigationSwitch;
    SwitchCompat mAutoStartMapsOpenSwitch;
    Button mGrantUsageAccessButton;

    //advanced
    SwitchCompat mNavigationDiagnosticsSwitch;
    TextView mNavigationDiagnosticsStatus;
    Button mExportDiagnosticsButton;
    Button mClearDiagnosticsButton;

    //appearance
    SwitchCompat mDarkModeAutoSwitch;
    SwitchCompat mDarkModeManualSwitch;

    public HUDInterface mHud = new DummyHUD();
    public boolean mIsNavigating = false;

    private boolean mIsNLSEnabled = false;
    private NotificationManager mNotificationManager;
    private MsgReceiver mMsgReceiver;
    private boolean mLastReallyInNavigation = false;
    private BroadcastReceiver mScreenReceiver;

    private NavigationView.OnNavigationItemSelectedListener mNavigationListener = item -> false;

    //========================================================================================
    private SharedPreferences mSharedPrefs;

    /** Scroll speed: progress 0-10, right = faster. Interval = 3000 - 250*progress ms. */
    public static int scrollProgressToIntervalMs(int progress) {
        return 3000 - 250 * Math.max(0, Math.min(10, progress));
    }

    private SeekBar.OnSeekBarChangeListener mCompassScrollSeekbarChangeListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float secs = scrollProgressToIntervalMs(progress) / 1000f;
                    mCompassScrollLabel.setText(getString(R.string.layout_seekbar_compass_scroll, secs));
                    storeIntOptions(R.string.option_compass_scroll_speed, progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            };

    private SeekBar.OnSeekBarChangeListener mBrightnessSeekbarChangeListener = new SeekBar.OnSeekBarChangeListener() {

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            mAutoBrightnessSwitch.setText(getString(R.string.layout_seekbar_brightness, progress * 10));
            storeIntOptions(R.string.option_manual_brightness, progress);
            if (null != mHud) {
                int brightness = getGammaBrightness();
                mHud.setBrightness(brightness);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Slider movement can temporarily collide with navigation packets and Garmin HUD's
            // six-updates-per-second limit. Repeat the final value after that window has cleared.
            seekBar.postDelayed(() -> {
                if (mHud != null && mAutoBrightnessSwitch != null
                        && !mAutoBrightnessSwitch.isChecked()) {
                    mHud.setBrightness(getGammaBrightness());
                }
            }, 1_100L);
        }
    };

    //===============================================================================================
    // location
    //===============================================================================================
    private boolean mLocationServiceConnected = false;
    private boolean mUseLocationService;
    private LocationManager mLocationManager;
    private ServiceConnection mLocationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mLocationServiceConnected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mLocationServiceConnected = false;
        }
    };

    private boolean isInNavigation() {
        return mIsNavigating;
    }

    public void sendBooleanExtraByBroadcast(String receiver, String key, boolean b) {
        Intent intent = new Intent(receiver).setPackage(getPackageName());
        intent.putExtra(key, b);
        sendBroadcast(intent);
    }

    /** Tells LocationService to re-read the cached safety prefs (overspeed / camera alerts). */
    private void notifySafetyPrefsChanged() {
        Intent intent = new Intent(getString(R.string.broadcast_receiver_location_service))
                .setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private SeekBar.OnSeekBarChangeListener mOverspeedThresholdChangeListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (mOverspeedThresholdLabel != null) {
                        mOverspeedThresholdLabel.setText(
                                getString(R.string.layout_overspeed_threshold, progress));
                    }
                    if (fromUser) {
                        storeIntOptions(R.string.overspeed_threshold_kmh, progress);
                        notifySafetyPrefsChanged();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            };

    void loadOptions() {
        mShowSpeedSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);

        mTrafficAndLaneSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mLaneGuidanceSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mRoundaboutExitNumbersSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mRerouteIndicatorSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);

        mOverspeedAlertSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mSpeedCameraAlertsSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);

        mAutoBrightnessSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mIdleClockSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mCompassSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mCompassStreetSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mCompassScrollSeekbar.setOnSeekBarChangeListener(mCompassScrollSeekbarChangeListener);
        mAutoStartNavigationSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mAutoStartMapsOpenSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mGrantUsageAccessButton.setOnClickListener(v -> openUsageAccessSettings());
        mArrowTypeSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);

        //======================================
        // default settings
        //======================================
        final boolean optionShowSpeed = mSharedPrefs.getBoolean(getString(R.string.option_show_speed), false);
        final boolean optionAutoBrightness = mSharedPrefs.getBoolean(
                getString(R.string.option_auto_brightness), HudBrightness.DEFAULT_AUTO);
        final int optionManualBrightness = HudBrightness.clampManualLevel(mSharedPrefs.getInt(
                getString(R.string.option_manual_brightness), HudBrightness.DEFAULT_MANUAL_LEVEL));

        final boolean optionTrafficAndLaneDetect = mSharedPrefs.getBoolean(
                getString(R.string.option_traffic_and_lane_detect), false);
        // MediaProjection consent belongs to the current capture session and cannot be restored
        // after the process/app has been restarted. Never display a stale enabled switch: the
        // user's first tap must request fresh Android consent and really start screen analysis.
        final boolean screenAnalysisActive = optionTrafficAndLaneDetect
                && sMediaProjection != null;
        if (optionTrafficAndLaneDetect && !screenAnalysisActive) {
            storeOptions(R.string.option_traffic_and_lane_detect, false);
        }
        final boolean optionLaneGuidance = mSharedPrefs.getBoolean(
                getString(R.string.option_text_lane_guidance), true);
        final boolean optionRoundaboutExitNumbers = mSharedPrefs.getBoolean(
                getString(R.string.roundabout_exit_numbers), true);
        final boolean optionRerouteIndicator = mSharedPrefs.getBoolean(
                getString(R.string.reroute_indicator), true);
        final int optionEtaDisplayMode = NotificationMonitor.readEtaDisplayModeSetting(this);

        final boolean optionOverspeedAlert = mSharedPrefs.getBoolean(
                getString(R.string.overspeed_alert), true);
        final int optionOverspeedThreshold = mSharedPrefs.getInt(
                getString(R.string.overspeed_threshold_kmh), 5);
        final boolean optionSpeedCameraAlerts = mSharedPrefs.getBoolean(
                getString(R.string.speed_camera_alerts), true);
        final int optionSpeedUnit = mSharedPrefs.getInt(
                getString(R.string.speed_unit), 0);

        final boolean optionIdleClock = mSharedPrefs.getBoolean(
                getString(R.string.option_idle_clock), true);

        final boolean optionAutoStartNavigation = mSharedPrefs.getBoolean(
                getString(R.string.option_auto_start_navigation), false);
        final boolean optionDarkModeAuto = mSharedPrefs.getBoolean(getString(R.string.option_dark_mode_auto), false);
        final boolean optionDarkModeMan = mSharedPrefs.getBoolean(getString(R.string.option_dark_mode_man), false);

        final boolean optionArrowType = mSharedPrefs.getBoolean(getString(R.string.option_arrow_type), true);

        sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                getString(R.string.option_arrow_type), optionArrowType);
        //======================================

        runOnUiThread(() -> {
            mShowSpeedSwitch.setChecked(optionShowSpeed);

            // Set the slider first: changing the switch can invoke its listener immediately.
            mBrightnessSeekbar.setProgress(optionManualBrightness);
            mAutoBrightnessSwitch.setChecked(optionAutoBrightness);
            mBrightnessSeekbar.setEnabled(!optionAutoBrightness);
            mAutoBrightnessSwitch.setText(optionAutoBrightness
                    ? getString(R.string.layout_element_auto_brightness)
                    : getString(R.string.layout_seekbar_brightness, optionManualBrightness * 10));
            mBrightnessSeekbar.setOnSeekBarChangeListener(mBrightnessSeekbarChangeListener);

            mTrafficAndLaneSwitch.setChecked(screenAnalysisActive);
            mLaneGuidanceSwitch.setChecked(optionLaneGuidance);
            mRoundaboutExitNumbersSwitch.setChecked(optionRoundaboutExitNumbers);
            mRerouteIndicatorSwitch.setChecked(optionRerouteIndicator);
            mEtaModeSpinner.setSelection(optionEtaDisplayMode);
            mEtaModeSpinner.setOnItemSelectedListener(mEtaModeSelectedListener);

            mOverspeedAlertSwitch.setChecked(optionOverspeedAlert);
            mOverspeedThresholdSeekbar.setProgress(optionOverspeedThreshold);
            mOverspeedThresholdLabel.setText(
                    getString(R.string.layout_overspeed_threshold, optionOverspeedThreshold));
            mOverspeedThresholdSeekbar.setOnSeekBarChangeListener(mOverspeedThresholdChangeListener);
            mSpeedUnitSpinner.setSelection(optionSpeedUnit);
            mSpeedUnitSpinner.setOnItemSelectedListener(mSpeedUnitSelectedListener);
            mSpeedCameraAlertsSwitch.setChecked(optionSpeedCameraAlerts);

            mIdleClockSwitch.setChecked(optionIdleClock);
            mCompassSwitch.setChecked(mSharedPrefs.getBoolean(
                    getString(R.string.option_compass_mode), true));
            mCompassStreetSwitch.setChecked(mSharedPrefs.getBoolean(
                    getString(R.string.option_compass_street), false));
            int scrollProgress = mSharedPrefs.getInt(
                    getString(R.string.option_compass_scroll_speed), 6);
            mCompassScrollSeekbar.setProgress(scrollProgress);
            mCompassScrollLabel.setText(getString(R.string.layout_seekbar_compass_scroll,
                    scrollProgressToIntervalMs(scrollProgress) / 1000f));

            mAutoStartNavigationSwitch.setChecked(optionAutoStartNavigation);
            mAutoStartMapsOpenSwitch.setChecked(mSharedPrefs.getBoolean(
                    getString(R.string.option_auto_start_maps_open), true));
            updateUsageAccessButton();
            mDarkModeAutoSwitch.setChecked(optionDarkModeAuto);
            mDarkModeManualSwitch.setChecked(optionDarkModeMan);

            mArrowTypeSwitch.setChecked(optionArrowType);

            refreshConnectionUi();
        });

        // Need to be after initially setChecked to avoid loop
        mDarkModeAutoSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
        mDarkModeManualSwitch.setOnCheckedChangeListener(mOnCheckedChangedListener);
    }

    private AdapterView.OnItemSelectedListener mEtaModeSelectedListener =
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    storeIntOptions(R.string.eta_display_mode, position);
                    sendIntExtraByBroadcast(
                            getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.eta_display_mode), position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            };

    private AdapterView.OnItemSelectedListener mSpeedUnitSelectedListener =
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    storeIntOptions(R.string.speed_unit, position);
                    notifySafetyPrefsChanged();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            };

    /**
     * Updates the bound device name from saved preferences.
     */
    void refreshConnectionUi() {
        if (mHudDeviceName == null) {
            return;
        }
        String deviceName = mSharedPrefs.getString(getString(R.string.bt_bind_name_key), null);
        mHudDeviceName.setText(deviceName == null
                ? getString(R.string.hud_device_unknown) : deviceName);
    }

    private void setHudStatusPill(int textRes, int tintColor) {
        if (mHudStatusPill == null) {
            return;
        }
        mHudStatusPill.setText(textRes);
        mHudStatusPill.setBackgroundTintList(ColorStateList.valueOf(tintColor));
    }

    private void showOnboardingPrompt() {
        boolean showPrompt = mSharedPrefs.getBoolean(getString(R.string.option_show_prompt), true);
        if (!showPrompt) {
            return;
        }
        View scanButton = findViewById(R.id.btnScanHUD);
        if (scanButton == null) {
            return;
        }
        new MaterialTapTargetPrompt.Builder(this)
                .setTarget(scanButton)
                .setPrimaryText(getString(R.string.prompt_btn_scan_bt))
                .setPromptBackground(new RectanglePromptBackground())
                .setPromptFocal(new RectanglePromptFocal())
                .create()
                .show();
        mSharedPrefs.edit().putBoolean(getString(R.string.option_show_prompt), false).apply();
    }

    void setupNavigationDiagnostics() {
        if (mNavigationDiagnosticsSwitch == null || mNavigationDiagnosticsStatus == null
                || mExportDiagnosticsButton == null || mClearDiagnosticsButton == null) {
            return;
        }

        boolean enabled = NavigationDiagnostics.isEnabled(this);
        mNavigationDiagnosticsSwitch.setChecked(enabled);
        updateNavigationDiagnosticsStatus(enabled);

        mNavigationDiagnosticsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.title_enable_navigation_diagnostics)
                        .setMessage(R.string.message_enable_navigation_diagnostics)
                        .setPositiveButton(R.string.action_enable, (dialog, which) -> {
                            NavigationDiagnostics.setEnabled(this, true);
                            updateNavigationDiagnosticsStatus(true);
                        })
                        .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                            button.setOnCheckedChangeListener(null);
                            button.setChecked(false);
                            setupNavigationDiagnostics();
                        })
                        .setOnCancelListener(dialog -> {
                            button.setOnCheckedChangeListener(null);
                            button.setChecked(false);
                            setupNavigationDiagnostics();
                        })
                        .show();
            } else {
                NavigationDiagnostics.setEnabled(this, false);
                updateNavigationDiagnosticsStatus(false);
            }
        });

        mExportDiagnosticsButton.setOnClickListener(view -> exportNavigationDiagnostics());
        Button iconMappingButton = findViewById(R.id.buttonIconMapping);
        iconMappingButton.setOnClickListener(view -> showIconMappingDialog());
        Button customizeMappingButton = findViewById(R.id.buttonCustomizeMapping);
        customizeMappingButton.setOnClickListener(view ->
                startActivity(new Intent(this, CustomizeMappingActivity.class)));
        Button exportMappingButton = findViewById(R.id.buttonExportMapping);
        exportMappingButton.setOnClickListener(view -> exportMapping());
        Button importMappingButton = findViewById(R.id.buttonImportMapping);
        importMappingButton.setOnClickListener(view -> importMapping());
        mClearDiagnosticsButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.title_clear_navigation_diagnostics)
                .setMessage(R.string.message_clear_navigation_diagnostics)
                .setPositiveButton(R.string.action_clear, (dialog, which) -> clearNavigationDiagnostics())
                .setNegativeButton(R.string.action_cancel, null)
                .show());
    }

    private void updateNavigationDiagnosticsStatus(boolean enabled) {
        mNavigationDiagnosticsStatus.setText(enabled
                ? R.string.layout_navigation_diagnostics_active
                : R.string.layout_navigation_diagnostics_inactive);
    }

    private void exportNavigationDiagnostics() {
        mExportDiagnosticsButton.setEnabled(false);
        new Thread(() -> {
            try {
                File archive = NavigationDiagnostics.createExportArchive(this);
                runOnUiThread(() -> {
                    mExportDiagnosticsButton.setEnabled(true);
                    if (archive == null) {
                        Toast.makeText(this, R.string.message_diagnostics_empty, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(this,
                            BuildConfig.APPLICATION_ID + ".fileprovider", archive);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/zip");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.setClipData(ClipData.newRawUri("navigation diagnostics", uri));
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share,
                            getString(R.string.share_navigation_diagnostics)));
                });
            } catch (Exception exception) {
                Log.e(TAG, "Cannot export navigation diagnostics", exception);
                runOnUiThread(() -> {
                    mExportDiagnosticsButton.setEnabled(true);
                    Toast.makeText(this, R.string.message_diagnostics_export_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "diagnostics-export").start();
    }

    private void clearNavigationDiagnostics() {
        mClearDiagnosticsButton.setEnabled(false);
        new Thread(() -> {
            try {
                NavigationDiagnostics.clear(this);
                runOnUiThread(() -> {
                    mClearDiagnosticsButton.setEnabled(true);
                    Toast.makeText(this, R.string.message_diagnostics_cleared,
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception exception) {
                Log.e(TAG, "Cannot clear navigation diagnostics", exception);
                runOnUiThread(() -> {
                    mClearDiagnosticsButton.setEnabled(true);
                    Toast.makeText(this, R.string.message_diagnostics_clear_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "diagnostics-clear").start();
    }

    /** Shows the Google Maps maneuver → Garmin HUD mapping in a scrollable dialog. */
    private void showIconMappingDialog() {        TextView textView = new TextView(this);
        textView.setText(ManeuverIconMap.buildDisplayText());
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(13);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_section_icons)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static final int REQUEST_IMPORT_MAPPING = 1401;

    /** Shares the custom maneuver mappings as a JSON file. */
    private void exportMapping() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "mapping-exports");
            dir.mkdirs();
            java.io.File file = new java.io.File(dir, "hud-mapping.json");
            java.io.FileWriter w = new java.io.FileWriter(file);
            w.write(ManeuverCustomizer.exportJson(this));
            w.close();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.action_export)));
        } catch (Exception e) {
            Log.e(TAG, "Cannot export mapping", e);
            Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
        }
    }

    /** Opens a file picker to import custom maneuver mappings. */
    private void importMapping() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/json");
        startActivityForResult(pick, REQUEST_IMPORT_MAPPING);
    }

    private HUDInterface.ConnectionCallback mHudConnectionCallback = new HUDInterface.ConnectionCallback() {
        @Override
        public void onConnectionStateChange(ConnectionState state) {
            switch (state) {
                case CONNECTED:
                    runOnUiThread(() -> {
                        setHudStatusPill(R.string.hud_status_connected,
                                Color.parseColor("#2E7D32"));
                        refreshConnectionUi();
                        if (mUseLocationService && !mLocationServiceConnected) {
                            bindLocationService();
                        }
                        applyStoredHudBrightness();
                    });
                    break;
                case DISCONNECTED:
                    runOnUiThread(() -> {
                        setHudStatusPill(R.string.hud_status_disconnected,
                                Color.parseColor("#9E9E9E"));
                        refreshConnectionUi();
                    });
                    break;
                case FAILED:
                    runOnUiThread(() -> {
                        setHudStatusPill(R.string.hud_status_failed,
                                Color.parseColor("#C62828"));
                        refreshConnectionUi();
                    });
                    break;
            }
        }
    };

    private void initializeHUD() {
        HudSessionManager manager = HudSessionManager.getInstance(this);
        manager.addConnectionCallback(mHudConnectionCallback);
        mHud = manager.initialize();

        if (hasHudConnectionPermission()) {
            HudConnectionService.start(this);
            requestNotificationPermission();
        } else {
            requestHudConnectionPermissions();
        }
    }

    private boolean hasHudConnectionPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestHudConnectionPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                },
                MY_PERMISSIONS_REQUEST_CONNECTION);
    }

    private void requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    MY_PERMISSIONS_REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST_CONNECTION && hasHudConnectionPermission()) {
            mHud = HudSessionManager.getInstance(this).restart();
            HudConnectionService.start(this);
            requestNotificationPermission();
        }
    }

    static Boolean sIsDebug = null;

    /**
     * Sync lib debug with app's debug value. Should be called in module Application
     *
     * @param context context
     */
    private static void syncIsDebug(Context context) {
        if (sIsDebug == null) {
            sIsDebug = context.getApplicationInfo() != null &&
                    (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //sdcard/Android/data/sky4s.garminhud.app/cache/screenshots
        File externalCacheDir = getApplicationContext().getExternalCacheDir();
        File cacheDir = externalCacheDir != null
                ? externalCacheDir : getApplicationContext().getCacheDir();
        File screenshotDir = new File(cacheDir, "screenshots");
        if (!screenshotDir.exists()) {
            screenshotDir.mkdirs();
        }
        SCREENCAP_STORE_DIRECTORY = screenshotDir.getAbsolutePath() + File.separator;

        mNotificationManager = getSystemService(NotificationManager.class);
        mProjectionManager = getSystemService(MediaProjectionManager.class);
        File externalDocumentsDir = getApplicationContext()
                .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        OCR_STORE_DIRECTORY = (externalDocumentsDir != null
                ? externalDocumentsDir : getApplicationContext().getFilesDir()).getAbsolutePath();
        ImageUtils.context = getApplicationContext();

        mSharedPrefs = getPreferences(Context.MODE_PRIVATE);
        final int stateDarkMode = mSharedPrefs.getInt(getString(R.string.state_dark_mode), AppCompatDelegate.MODE_NIGHT_NO);
        AppCompatDelegate.setDefaultNightMode(stateDarkMode);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        syncIsDebug(getApplicationContext());

        //=======================================================================================
        // settings screen: wire every control directly (no fragments)
        //========================================================================================
        mHudStatusPill = findViewById(R.id.textHudStatus);
        mHudDeviceName = findViewById(R.id.textHudDeviceName);

        mArrowTypeSwitch = findViewById(R.id.switchArrowType);
        mTrafficAndLaneSwitch = findViewById(R.id.switchTrafficAndLane);
        mLaneGuidanceSwitch = findViewById(R.id.switchLaneGuidance);
        mRoundaboutExitNumbersSwitch = findViewById(R.id.switchRoundaboutExitNumbers);
        mRerouteIndicatorSwitch = findViewById(R.id.switchRerouteIndicator);
        mEtaModeSpinner = findViewById(R.id.spinnerEtaMode);

        mShowSpeedSwitch = findViewById(R.id.switchShowSpeed);
        mOverspeedAlertSwitch = findViewById(R.id.switchOverspeedAlert);
        mOverspeedThresholdSeekbar = findViewById(R.id.seekbarOverspeedThreshold);
        mOverspeedThresholdLabel = findViewById(R.id.textOverspeedThreshold);
        mSpeedCameraAlertsSwitch = findViewById(R.id.switchSpeedCameraAlerts);
        mSpeedUnitSpinner = findViewById(R.id.spinnerSpeedUnit);

        mAutoBrightnessSwitch = findViewById(R.id.switchAutoBrightness);
        mBrightnessSeekbar = findViewById(R.id.seekBarBrightness);
        mIdleClockSwitch = findViewById(R.id.switchIdleClock);
        mCompassSwitch = findViewById(R.id.switchCompass);
        mCompassStreetSwitch = findViewById(R.id.switchCompassStreet);
        mCompassScrollSeekbar = findViewById(R.id.seekBarCompassScroll);
        mCompassScrollLabel = findViewById(R.id.textCompassScrollSpeed);

        mAutoStartNavigationSwitch = findViewById(R.id.switchAutoStartNavigation);
        mAutoStartMapsOpenSwitch = findViewById(R.id.switchAutoStartMapsOpen);
        mGrantUsageAccessButton = findViewById(R.id.buttonGrantUsageAccess);

        mNavigationDiagnosticsSwitch = findViewById(R.id.switchNavigationDiagnostics);
        mNavigationDiagnosticsStatus = findViewById(R.id.textNavigationDiagnosticsStatus);
        mExportDiagnosticsButton = findViewById(R.id.buttonExportDiagnostics);
        mClearDiagnosticsButton = findViewById(R.id.buttonClearDiagnostics);

        mDarkModeAutoSwitch = findViewById(R.id.switchDarkModeAuto);
        mDarkModeManualSwitch = findViewById(R.id.switchDarkModeMan);

        loadOptions();
        setupNavigationDiagnostics();
        showOnboardingPrompt();
        //========================================================================================
        //========================================================================================
        // HUD connection
        //========================================================================================
        initializeHUD();
        //========================================================================================

        //=======================================================================================
        // toolbar
        //========================================================================================
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar); //when pass toolbar as actionBar, toolbar has title
        ActionBar actionBar = getSupportActionBar();
        String versionName = BuildConfig.VERSION_NAME;

        if (actionBar != null) {
            String title = actionBar.getTitle() + " v" + versionName;
            actionBar.setTitle(title);
        }
        //========================================================================================

        //========================================================================================
        // NavigationView
        //========================================================================================
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(mNavigationListener);
        //========================================================================================

        //========================================================================================
        // message receiver
        //========================================================================================
        mMsgReceiver = new MsgReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(getString(R.string.broadcast_receiver_main_activity));
        ContextCompat.registerReceiver(
                this, mMsgReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // INITIALIZE RECEIVER
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    refreshProjectionAfterScreenOn();
                }
            }
        };

        registerReceiver(mScreenReceiver, filter);
        //========================================================================================

        //========================================================================================
        // MediaProjection
        //========================================================================================
        // start capture handling thread
        mProjectionThread = new HandlerThread("navigation-screen-capture");
        mProjectionThread.start();
        mProjectionHandler = new Handler(mProjectionThread.getLooper());
        mImageDetectListener = new ImageDetectListener(this);
        //========================================================================================
    }

    private void confirmExit() {
        //退出確認
        AlertDialog.Builder ad = new AlertDialog.Builder(MainActivity.this);
        ad.setTitle("Exit");
        ad.setMessage("Leave GoogleMaps HUD app?");
        //退出按鈕
        ad.setPositiveButton("Yes", (dialog, i) -> {
            stopActiveSession(true);
            MainActivity.this.finish();//關閉activity
        });
        ad.setNegativeButton("No", (dialog, i) -> {
            //不退出不用執行任何操作
        });
        ad.show();//顯示對話框
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (HudSessionManager.getInstance(this)
                .handleActivityResult(requestCode, resultCode, data)) {
            // Activity result was handled by HUD class, stop processing here
            return;
        }

        if (requestCode == REQUEST_IMPORT_MAPPING && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.util.Scanner s = new java.util.Scanner(in).useDelimiter("\\A");
                String json = s.hasNext() ? s.next() : "";
                s.close();
                if (ManeuverCustomizer.importJson(this, json)) {
                    Toast.makeText(this, "Mapping imported", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Invalid mapping file", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot import mapping", e);
                Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == SCREENCAP_REQUEST_CODE) {
            if (resultCode != Activity.RESULT_OK || data == null) {
                if (mTrafficAndLaneSwitch != null) {
                    mTrafficAndLaneSwitch.setChecked(false);
                }
                return;
            }

            // display metrics
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            mDensity = metrics.densityDpi;
            mDisplay = getWindowManager().getDefaultDisplay();

            ScreenRecorderService.start(this, resultCode, data, projection -> runOnUiThread(() -> {
                if (projection == null) {
                    handleProjectionStartFailure(null);
                    return;
                }
                try {
                    sMediaProjection = projection;
                    // Android 14+ requires the callback to be registered before the first
                    // createVirtualDisplay() call. Doing this afterwards throws on current
                    // Samsung/Android releases and used to terminate the activity.
                    sMediaProjection.registerCallback(
                            new MediaProjectionStopCallback(), mProjectionHandler);
                    createVirtualDisplay();
                    mOrientationChangeCallback = new OrientationChangeCallback(this);
                    if (mOrientationChangeCallback.canDetectOrientation()) {
                        mOrientationChangeCallback.enable();
                    }
                } catch (RuntimeException exception) {
                    handleProjectionStartFailure(exception);
                }
            }));
        }
    }

    private void handleProjectionStartFailure(RuntimeException exception) {
        if (exception != null) {
            Log.e("ScreenCapture", "Could not start screen analysis", exception);
        } else {
            Log.e("ScreenCapture", "MediaProjectionManager returned no projection");
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        if (mTrafficAndLaneSwitch != null) {
            mTrafficAndLaneSwitch.setChecked(false);
        }
        Toast.makeText(this, R.string.message_screen_analysis_failed, Toast.LENGTH_LONG).show();
        stopProjection();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        HudSessionManager.getInstance(this).removeConnectionCallback(mHudConnectionCallback);
        unbindLocationService();
        if (mNotificationManager != null) {
            mNotificationManager.cancel(1);
        }
        stopProjection();
        if (mImageDetectListener != null) {
            mImageDetectListener.close();
        }
        if (mProjectionThread != null) {
            mProjectionThread.quitSafely();
        }

        unregisterReceiver(mMsgReceiver);
        unregisterReceiver(mScreenReceiver);

        if (isFinishing() && !isChangingConfigurations()) {
            stopActiveSession(true);
        }

    }

    private void stopActiveSession(boolean suppressNavigationRestart) {
        if (suppressNavigationRestart) {
            NotificationMonitor monitor = NotificationMonitor.getInstance();
            if (monitor != null) {
                monitor.suppressAutoStartForCurrentNavigation();
            }
        }
        stopService(new Intent(this, LocationService.class));
        stopService(new Intent(this, ScreenRecorderService.class));
        HudConnectionService.stop(this);
        HudSessionManager.getInstance(this).shutdown();
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsNLSEnabled = isNLSEnabled();
        log("isEnabledNLS = " + mIsNLSEnabled);
        if (!mIsNLSEnabled) {
            showConfirmDialog();
        }
        // Usage access may have been granted in system settings; refresh the button.
        if (mGrantUsageAccessButton != null && mAutoStartMapsOpenSwitch != null) {
            updateUsageAccessButton();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private int getGammaBrightness() {
        if(null == mBrightnessSeekbar) {
            int storedLevel = HudBrightness.clampManualLevel(mSharedPrefs.getInt(
                    getString(R.string.option_manual_brightness),
                    HudBrightness.DEFAULT_MANUAL_LEVEL));
            return HudBrightness.gammaCorrectedLevel(
                    storedLevel, HudBrightness.MAX_MANUAL_LEVEL);
        }

        final int progress = mBrightnessSeekbar.getProgress();
        return HudBrightness.gammaCorrectedLevel(progress, mBrightnessSeekbar.getMax());
    }

    private void applyStoredHudBrightness() {
        if (mHud == null) return;
        boolean auto = mSharedPrefs.getBoolean(
                getString(R.string.option_auto_brightness), HudBrightness.DEFAULT_AUTO);
        if (auto) {
            mHud.setAutoBrightness();
        } else {
            mHud.setBrightness(getGammaBrightness());
        }
    }

    @SuppressLint("ApplySharedPref")
    private void storeOptions(int optionID, boolean option) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putBoolean(getString(optionID), option);
        editor.commit();
    }

    @SuppressLint("ApplySharedPref")
    private void storeIntOptions(int optionID, int option) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putInt(getString(optionID), option);
        editor.commit();
    }

    /**
     * OnCheckedChangedListener and  buttonOnClicked have similar function for UI response.
     * We recommend use "button click" with buttonOnClicked.
     * Other UI (like switch) use OnCheckedChangedListener.
     */
    private CompoundButton.OnCheckedChangeListener mOnCheckedChangedListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton view, boolean b) {
            switch (view.getId()) {
                case R.id.switchArrowType:
                    final boolean arrowType2 = view.isChecked();
                    sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.option_arrow_type), arrowType2);
                    storeOptions(R.string.option_arrow_type, arrowType2);
                    view.setText(arrowType2 ? R.string.layout_element_arrow_type_v2 : R.string.layout_element_arrow_type_v1);
                    break;

                case R.id.switchShowSpeed:
                    final boolean canShowSpeed = showSpeed(view.isChecked());
                    if (!canShowSpeed) {
                        view.setChecked(false);
                    }
                    storeOptions(R.string.option_show_speed, view.isChecked());
                    if (view.isChecked() && mAutoStartNavigationSwitch.isChecked()) {
                        requestBackgroundLocationForAutoStart();
                    }
                    break;

                case R.id.switchTrafficAndLane:
                    if (view.isChecked()) {
                        // AutoMapa speed-limit comparison must use GPS speed, never OCR of the
                        // green current-speed value shown by AutoMapa.
                        if (!mShowSpeedSwitch.isChecked()) {
                            mShowSpeedSwitch.setChecked(true);
                        }
                        if (!mShowSpeedSwitch.isChecked()) {
                            view.setChecked(false);
                            break;
                        }
                        startProjection();
                    } else {
                        stopProjection();
                    }
                    storeOptions(R.string.option_traffic_and_lane_detect, view.isChecked());
                    break;

                case R.id.switchLaneGuidance:
                    storeOptions(R.string.option_text_lane_guidance, view.isChecked());
                    sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.option_text_lane_guidance), view.isChecked());
                    break;

                case R.id.switchRoundaboutExitNumbers:
                    storeOptions(R.string.roundabout_exit_numbers, view.isChecked());
                    sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.roundabout_exit_numbers), view.isChecked());
                    break;

                case R.id.switchRerouteIndicator:
                    storeOptions(R.string.reroute_indicator, view.isChecked());
                    sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.reroute_indicator), view.isChecked());
                    break;

                case R.id.switchOverspeedAlert:
                    storeOptions(R.string.overspeed_alert, view.isChecked());
                    notifySafetyPrefsChanged();
                    break;

                case R.id.switchSpeedCameraAlerts:
                    storeOptions(R.string.speed_camera_alerts, view.isChecked());
                    notifySafetyPrefsChanged();
                    break;

                case R.id.switchIdleClock:
                    storeOptions(R.string.option_idle_clock, view.isChecked());
                    break;

                case R.id.switchCompass:
                    storeOptions(R.string.option_compass_mode, view.isChecked());
                    break;

                case R.id.switchCompassStreet:
                    storeOptions(R.string.option_compass_street, view.isChecked());
                    break;

                case R.id.switchAutoStartNavigation:
                    storeOptions(R.string.option_auto_start_navigation, view.isChecked());
                    if (view.isChecked() && mShowSpeedSwitch.isChecked()) {
                        requestBackgroundLocationForAutoStart();
                    }
                    break;

                case R.id.switchAutoStartMapsOpen:
                    storeOptions(R.string.option_auto_start_maps_open, view.isChecked());
                    updateUsageAccessButton();
                    break;

                case R.id.switchDarkModeAuto:
                    final boolean isDarkModeAuto = view.isChecked();
                    mDarkModeManualSwitch.setEnabled(!isDarkModeAuto);
                    storeOptions(R.string.option_dark_mode_auto, isDarkModeAuto);
                    if (isDarkModeAuto) {
                        storeIntOptions(R.string.state_dark_mode, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    } else {
                        final boolean isDarkModeManualEnabled =
                                mSharedPrefs.getBoolean(getString(R.string.option_dark_mode_man), false);
                        storeIntOptions(R.string.state_dark_mode, isDarkModeManualEnabled ?
                                AppCompatDelegate.MODE_NIGHT_YES :
                                AppCompatDelegate.MODE_NIGHT_NO);
                        recreate();
                    }
                    break;

                case R.id.switchDarkModeMan:
                    final boolean isDarkModeManualEnabled = view.isChecked();
                    storeOptions(R.string.option_dark_mode_man, isDarkModeManualEnabled);
                    storeIntOptions(R.string.state_dark_mode, isDarkModeManualEnabled ?
                            AppCompatDelegate.MODE_NIGHT_YES :
                            AppCompatDelegate.MODE_NIGHT_NO);
                    recreate();
                    break;

                case R.id.switchAutoBrightness:
                    SwitchCompat theAutoBrightness = (SwitchCompat) view;
                    final boolean autoBrightness = theAutoBrightness.isChecked();
                    storeOptions(R.string.option_auto_brightness, autoBrightness);

                    final int brightnessProgress = mBrightnessSeekbar.getProgress();
                    theAutoBrightness.setText(autoBrightness ? getString(R.string.layout_element_auto_brightness)
                            : getString(R.string.layout_seekbar_brightness, brightnessProgress * 10));

                    mBrightnessSeekbar.setEnabled(!autoBrightness);
                    mBrightnessSeekbar.setOnSeekBarChangeListener(mBrightnessSeekbarChangeListener);

                    if (null != mHud) {
                        if (autoBrightness) {
                            mHud.setAutoBrightness();
                        } else {
                            final int brightness = getGammaBrightness();
                            mHud.setBrightness(brightness);
                        }
                    }
                    break;

                default:
                    break;
            }
        }
    };

    public void buttonOnClicked(View view) {
        switch (view.getId()) {
            case R.id.btnScanHUD:
                log("Scan for HUD...");
                if (!IGNORE_BT_DEVICE) {
                    initializeHUD();
                    HudSessionManager.getInstance(this).openConnectionPicker(this);
                }
                break;

            case R.id.btnDisconnectHUD:
                log("Disconnect HUD...");
                if (null != mHud) {
                    mHud.disconnect();
                }
                break;

            default:
                break;
        }
    }

    public void sendIntExtraByBroadcast(String receiver, String key, int value) {
        Intent intent = new Intent(receiver).setPackage(getPackageName());
        intent.putExtra(key, value);
        sendBroadcast(intent);
    }

    private boolean showSpeed(final boolean doShowSpeed) {
        if (doShowSpeed) {
            if (!checkLocationPermission()) {
                return false;
            }
            if (!checkGps()) {
                return false;
            }
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return false;
            }
            if (!mLocationServiceConnected) {
                //Here, the Location Service gets bound and the GPS Speedometer gets Active.
                bindLocationService();
                mUseLocationService = true;
            }
        } else {
            //do not show speed
            if (mLocationServiceConnected) {
                unbindLocationService();
            }
            stopService(new Intent(this, LocationService.class));
            if (null != mHud) {
                //clear according to navigate status
                if (isInNavigation()) {
                    mHud.clearSpeedAndWarning();
                } else {
                    mHud.clearDistance();
                }
            }
            mUseLocationService = false;
        }
        return true;
    }

    private boolean isNLSEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void openNotificationAccess() {
        startActivity(new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void showConfirmDialog() {
        final String app_name = getString(R.string.app_name);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.message_enable_notification_access, app_name))
                .setTitle(getString(R.string.title_enable_notification_access))
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok,
                        (dialog, id) -> openNotificationAccess())
                .setNegativeButton(android.R.string.cancel,
                        (dialog, id) -> {
                            // do nothing
                        })
                .create().show();
    }

    private void log(Object object) {
        Log.i(TAG, object.toString());
    }

    // bind/activate LocationService
    private void bindLocationService() {
        if (mLocationServiceConnected) {
            return;
        }
        Intent i = new Intent(getApplicationContext(), LocationService.class);
        LocationService.start(this);
        bindService(i, mLocationServiceConnection, BIND_AUTO_CREATE);
        mLocationServiceConnected = true;
    }

    // unbind/deactivate LocationService
    private void unbindLocationService() {
        if (!mLocationServiceConnected) {
            return;
        }
        unbindService(mLocationServiceConnection);
        mLocationServiceConnected = false;
    }

    // This method check if GPS is activated (and ask user for activation)
    private boolean checkGps() {
        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGPSDisabledAlertToUser();
            return false;
        }
        return true;
    }

    //This method configures the Alert Dialog box for GPS-Activation
    private void showGPSDisabledAlertToUser() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setMessage("Enable GPS to Show Speed")
                .setCancelable(false)
                .setPositiveButton("Enable GPS",
                        (dialog, id) -> {
                            Intent callGPSSettingIntent = new Intent(
                                    Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            startActivity(callGPSSettingIntent);
                        });
        alertDialogBuilder.setNegativeButton("Cancel",
                (dialog, id) -> dialog.cancel());
        AlertDialog alert = alertDialogBuilder.create();
        alert.show();
    }

    // Check permission for location (and ask user for permission)
    private boolean checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                String message = getString(R.string.message_enable_location_access);
                new AlertDialog.Builder(this)
                        .setTitle("Location Permission")
                        .setMessage(message)
                        .setPositiveButton("Ok", (dialogInterface, i) -> {
                            //Prompt the user once explanation has been shown
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    MY_PERMISSIONS_REQUEST_LOCATION);
                        })
                        .create()
                        .show();
            } else {
                // No explanation needed; request the permission
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        MY_PERMISSIONS_REQUEST_LOCATION);
            }
            return false;
        } else {
            // Permission has already been granted
            return true;
        }
    }

    /** Shows the usage-access grant button when the Maps-open auto-start needs it. */
    private void updateUsageAccessButton() {
        boolean needGrant = mAutoStartMapsOpenSwitch.isChecked()
                && !MapsForegroundWatcher.hasUsageAccess(this);
        mGrantUsageAccessButton.setVisibility(needGrant ? View.VISIBLE : View.GONE);
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    R.string.message_usage_access_required, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void requestBackgroundLocationForAutoStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    MY_PERMISSIONS_REQUEST_LOCATION);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.title_background_location_access)
                .setMessage(R.string.message_enable_background_location_access)
                .setPositiveButton(R.string.action_open_settings, (dialog, which) -> {
                    Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class MsgReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Arrow bitmaps are used internally by the parser. There is no user-facing bitmap
            // debug mode anymore, so no UI work is required for these broadcasts.
            if (intent.hasExtra(getString(R.string.arrow_bitmap))) return;

            //=======================================================================
            // for debug message
            //=======================================================================
            boolean has_notify_msg = intent.hasExtra(getString(R.string.notify_msg));
            if (has_notify_msg) {
                String notify_msg = intent.getStringExtra(getString(R.string.notify_msg));
                Log.d(TAG, notify_msg == null ? "" : notify_msg);
                return;
            }

            //=======================================================================
            // gps speed
            //=======================================================================
            boolean has_gps_speed = intent.hasExtra(getString(R.string.gps_speed));
            if (has_gps_speed) {
                double speed = intent.getDoubleExtra(getString(R.string.gps_speed), 0);
                int int_speed = (int) Math.round(speed);
                mGpsSpeed = int_speed;

                // LocationService has already sent this sample to the shared HUD instance. The
                // activity only mirrors it for OCR and diagnostics. Sending it again consumed
                // two of Garmin HUD's six packet slots per second; worse, the old idle fallback
                // wrote GPS speed into the maneuver-distance field when notification state was
                // briefly stale.

                return;
            }

            //=======================================================================
            // for UI usage, parse notify_parse_failed first
            //=======================================================================
            boolean hasNavigationState = intent.hasExtra(getString(R.string.notify_parse_failed))
                    || intent.hasExtra(getString(R.string.notify_catched))
                    || intent.hasExtra(getString(R.string.gmaps_notify_catched))
                    || intent.hasExtra(getString(R.string.is_in_navigation));
            if (hasNavigationState) {
                boolean parseFailed = intent.getBooleanExtra(
                        getString(R.string.notify_parse_failed), false);
                boolean notificationCaught = intent.getBooleanExtra(
                        getString(R.string.notify_catched), true);
                boolean navigationNotificationCaught = intent.getBooleanExtra(
                        getString(R.string.gmaps_notify_catched), mIsNavigating);
                boolean monitorReportsNavigation = intent.getBooleanExtra(
                        getString(R.string.is_in_navigation), mIsNavigating);
                boolean reallyNavigating = !parseFailed && notificationCaught
                        && navigationNotificationCaught && monitorReportsNavigation;

                if (mLastReallyInNavigation && !reallyNavigating && mHud != null) {
                    mHud.setDirection(eOutAngle.AsDirection);
                }
                mIsNavigating = reallyNavigating;
                mLastReallyInNavigation = reallyNavigating;
            }

            //=======================================================================
            if (intent.hasExtra(getString(R.string.option_arrow_type))) {
                //re-sync arrow type between ui & notify monitor
                boolean arrowTypeV2_in_ui = mArrowTypeSwitch.isChecked();
                boolean arrowTypeV2_in_notify_monitor = intent.getBooleanExtra((getString(R.string.option_arrow_type)), arrowTypeV2_in_ui);

                if (arrowTypeV2_in_notify_monitor != arrowTypeV2_in_ui) {
                    sendBooleanExtraByBroadcast(getString(R.string.broadcast_receiver_notification_monitor),
                            getString(R.string.option_arrow_type), arrowTypeV2_in_ui);
                }

            }
        }
    }

    //================================================================================
    // media projection
    //================================================================================
    private MediaProjectionManager mProjectionManager;
    private ImageReader mImageReader;
    private Handler mProjectionHandler;
    private HandlerThread mProjectionThread;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    public int mWidth;
    public int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;
    public int mGpsSpeed = 0;

    private static final int SCREENCAP_REQUEST_CODE = 100;
    private static final String SCREENCAP_NAME = "screencap";
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    ///sdcard/Android/data/sky4s.garminhud.app/cache/screenshots
    public static String SCREENCAP_STORE_DIRECTORY;
    public static String OCR_STORE_DIRECTORY;
    private static MediaProjection sMediaProjection;

    private class OrientationChangeCallback extends OrientationEventListener {
        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // Android 14+ allows createVirtualDisplay only once per consent. Resize the
                    // existing display and replace its Surface instead of reusing consent.
                    resizeVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e("ScreenCapture", "stopping projection.");
            mProjectionHandler.post(() -> {
                if (mVirtualDisplay != null) mVirtualDisplay.release();
                mVirtualDisplay = null;
                if (mImageReader != null) {
                    mImageReader.setOnImageAvailableListener(null, null);
                    mImageReader.close();
                    mImageReader = null;
                }
                if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                if (sMediaProjection != null) {
                    sMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                    sMediaProjection = null;
                }
                runOnUiThread(() -> {
                    if (mTrafficAndLaneSwitch != null && mTrafficAndLaneSwitch.isChecked()) {
                        mTrafficAndLaneSwitch.setChecked(false);
                    }
                });
            });
        }
    }

    /****************************************** UI Widget Callbacks *******************************/
    private void startProjection() {
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The detector uses fixed full-display coordinates. Do not offer single-app sharing,
            // whose cropped/scaled frames make AutoMapa and Google Maps regions unreliable.
            MediaProjectionConfig config = MediaProjectionConfig.createConfigForDefaultDisplay();
            captureIntent = mProjectionManager.createScreenCaptureIntent(config);
        } else {
            captureIntent = mProjectionManager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, SCREENCAP_REQUEST_CODE);
    }

    private void refreshProjectionAfterScreenOn() {
        if (mProjectionHandler == null) return;
        mProjectionHandler.postDelayed(() -> {
            if (sMediaProjection == null || mVirtualDisplay == null) return;
            try {
                mImageDetectListener.requestFullRefresh();
                // Some devices stop delivering ImageReader frames while the display is off.
                // Replacing only the Surface keeps the same Android 14 projection consent.
                resizeVirtualDisplay();
                Log.i("ScreenCapture", "Screen-on capture surface refreshed");
            } catch (RuntimeException exception) {
                Log.w("ScreenCapture", "Could not refresh capture after screen-on", exception);
            }
        }, 500L);
    }

    private void stopProjection() {
        Runnable stop = () -> {
            if (sMediaProjection != null) {
                sMediaProjection.stop();
                sMediaProjection = null;
            }
            stopService(new Intent(this, ScreenRecorderService.class));
        };
        if (mProjectionHandler != null) {
            mProjectionHandler.post(stop);
        } else {
            stop.run();
        }
    }

    /****************************************** Factoring Virtual Display creation ****************/
    private ImageDetectListener mImageDetectListener;

    private void createVirtualDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        mWidth = dm.widthPixels;
        mHeight = dm.heightPixels;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = sMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mProjectionHandler);
        mImageReader.setOnImageAvailableListener(mImageDetectListener, mProjectionHandler);
    }

    private void resizeVirtualDisplay() {
        if (mVirtualDisplay == null) return;

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int newWidth = dm.widthPixels;
        int newHeight = dm.heightPixels;

        ImageReader newReader = ImageReader.newInstance(
                newWidth, newHeight, PixelFormat.RGBA_8888, 2);
        newReader.setOnImageAvailableListener(mImageDetectListener, mProjectionHandler);
        mVirtualDisplay.resize(newWidth, newHeight, mDensity);
        mVirtualDisplay.setSurface(newReader.getSurface());

        ImageReader oldReader = mImageReader;
        mImageReader = newReader;
        mWidth = newWidth;
        mHeight = newHeight;
        if (oldReader != null) {
            oldReader.setOnImageAvailableListener(null, null);
            oldReader.close();
        }
    }
}
