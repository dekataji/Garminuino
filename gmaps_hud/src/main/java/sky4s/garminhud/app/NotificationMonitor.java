package sky4s.garminhud.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import sky4s.garminhud.Arrow;
import sky4s.garminhud.ArrowImage;
import sky4s.garminhud.ArrowV2;
import sky4s.garminhud.ImageUtils;
import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;
import sky4s.garminhud.eUnits;
import sky4s.garminhud.app.navigation.NavigationInstructionParser;
import sky4s.garminhud.hud.HUDInterface;
import chutka.bitman.com.speedometersimplified.LocationService;

public class NotificationMonitor extends NotificationListenerService {
    private static final boolean STORE_IMG = true;

    private String mImageDir;

    public static final String GOOGLE_MAPS_PACKAGE_NAME = "com.google.android.apps.maps";
    public static final String GOOGLE_MAPS_GO_PACKAGE_NAME = "com.google.android.apps.navlite";
    public static final String GOOGLE_MAPS_NOTIFICATION_GROUP_NAVIGATION = "navigation_status_notification_group";

    public static final String OSMAND_PACKAGE_NAME = "net.osmand";
    public static final String OSMAND_NOTIFICATION_GROUP_NAVIGATION = "NAVIGATION";
    public static final String SYGIC_PACKAGE_NAME = "com.sygic.aura";
    public static final String AUTOMAPA_PACKAGE_NAME = "pl.aqurat.automapa";

    private static final String TAG = NotificationMonitor.class.getSimpleName();
    private static final int EVENT_UPDATE_CURRENT_NOS = 0;

    public static List<StatusBarNotification[]> sCurrentNotifications = new ArrayList<StatusBarNotification[]>();
    public static int sCurrentNotificationsCounts = 0;
    public static StatusBarNotification sPostedNotification;
    public static StatusBarNotification sRemovedNotification;

    static HUDInterface sHud = null;

    static void updateHud(HUDInterface hud) {
        sHud = hud;
    }

    @SuppressLint("HandlerLeak")
    private Handler mMonitorHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_UPDATE_CURRENT_NOS:
                    updateCurrentNotifications();
                    break;
                default:
                    break;
            }
        }
    };

    private long mLastNotifyTimeMillis = 0;
    private long mNotifyPeriodTime = 0;

    private volatile Arrow mFoundArrow = Arrow.None;
    private Arrow mLastFoundArrow = Arrow.None;
    private volatile ArrowV2 mFoundArrowV2 = ArrowV2.None;
    private ArrowV2 mLastFoundArrowV2 = ArrowV2.None;

    // Next-turn preview: parsed from compound "X, then Y" instructions. The HUD alternates
    // between the current and the next maneuver so the driver sees what follows.
    private volatile Arrow mNextManeuver = Arrow.None;
    private volatile ArrowV2 mNextManeuverV2 = ArrowV2.None;
    private volatile boolean mShowingNextPreview = false;
    private static final long NEXT_PREVIEW_INTERVAL_MS = 2500L;
    private boolean mNextTurnPreviewEnabled = true;
    private boolean mTextLaneGuidanceEnabled = true;

    // Lane guidance parsed from notification text ("Use the left 2 lanes ...").
    // -1 means no text lane state was ever sent (screen detector owns the lanes then).
    private int mLastTextLaneOutline = -1;
    private int mLastTextLaneArrow = -1;

    private volatile String mDistanceNum = null;
    private static volatile String mDistanceUnit = null;
    private String mRemainingHours = null;
    private String mRemainingMinutes = null;
    private String mRemainingDistance = null;
    private String mRemainingDistanceUnits = null;
    private int mArrivalHours = -1;
    private int mArrivalMinutes = -1;
    private int mLastArrivalHours = -1;
    private int mLastArrivalMinutes = -1;

    private ExecutorService mExecutor;
    private RejectedExecutionHandler mRejectHandler = new ThreadPoolExecutor.DiscardOldestPolicy();

    private static void logi(String msg) {
        Log.i(TAG, msg);
    }

    public static StatusBarNotification[] getCurrentNotifications() {
        if (sCurrentNotifications.size() == 0) {
            logi("mCurrentNotifications size is ZERO!!");
            return null;
        }
        return sCurrentNotifications.get(0);
    }

    private MsgReceiver mMsgReceiver;

    private class MsgReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Parcelable parcelablelocation = intent.getParcelableExtra(getString(R.string.location));
            int newEtaMode = intent.getIntExtra(getString(R.string.eta_display_mode), mEtaDisplayMode);
            if (newEtaMode != mEtaDisplayMode) {
                mEtaDisplayMode = newEtaMode;
                mLastArrivalMinutes = -1; // Force a time refresh after a mode change
                if (mEtaDisplayMode == ETA_MODE_CYCLE) {
                    startEtaCycler();
                } else {
                    stopEtaCycler();
                }
            }
            mRoundaboutExitNumbersEnabled = intent.getBooleanExtra(
                    getString(R.string.roundabout_exit_numbers), mRoundaboutExitNumbersEnabled);
            mRerouteIndicatorEnabled = intent.getBooleanExtra(
                    getString(R.string.reroute_indicator), mRerouteIndicatorEnabled);
            mTextLaneGuidanceEnabled = intent.getBooleanExtra(
                    getString(R.string.option_text_lane_guidance), mTextLaneGuidanceEnabled);
            mBusyTraffic = intent.getBooleanExtra(getString(R.string.busy_traffic), mBusyTraffic);
            mArrowTypeV2 = intent.getBooleanExtra(getString(R.string.option_arrow_type), mArrowTypeV2);
        }
    }

    /** ETA display modes for the HUD clock field. */
    public static final int ETA_MODE_OFF = 0;
    public static final int ETA_MODE_REMAINING = 1;
    public static final int ETA_MODE_ARRIVAL = 2;
    public static final int ETA_MODE_CYCLE = 3;

    private int mEtaDisplayMode = ETA_MODE_OFF;
    private boolean mEtaCycleShowRemaining = true;
    private boolean mEtaCyclerRunning = false;
    private static final long ETA_CYCLE_INTERVAL_MS = 10_000L;

    private boolean mRoundaboutExitNumbersEnabled = true;
    private boolean mRerouteIndicatorEnabled = true;
    private boolean mReroutingActive = false;
    private Integer mRoundaboutExit = null;

    /**
     * Reads the ETA display mode, migrating the legacy boolean option_show_eta
     * (true -> remaining time, false -> off) on first run.
     */
    public static int readEtaDisplayModeSetting(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
        int mode = prefs.getInt(context.getString(R.string.eta_display_mode), -1);
        if (mode < ETA_MODE_OFF || mode > ETA_MODE_CYCLE) {
            boolean legacyShowEta = prefs.getBoolean(context.getString(R.string.option_show_eta), false);
            mode = legacyShowEta ? ETA_MODE_REMAINING : ETA_MODE_OFF;
            prefs.edit().putInt(context.getString(R.string.eta_display_mode), mode).apply();
        }
        return mode;
    }

    private static NotificationMonitor sStaticInstance;

    public static NotificationMonitor getInstance() {
        return sStaticInstance;
    }

    /**
     * True only while the system is actually bound to this listener.
     * "Notification access granted" in Settings does NOT imply this —
     * the system still has to bind (it cannot if the service is not exported).
     */
    private static volatile boolean sListenerConnected = false;

    public static boolean isListenerConnected() {
        return sListenerConnected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sHud = HudSessionManager.getInstance(this).getHud();
        mEtaDisplayMode = readEtaDisplayModeSetting(this);
        mRoundaboutExitNumbersEnabled = getSharedPreferences(MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.roundabout_exit_numbers), true);
        mRerouteIndicatorEnabled = getSharedPreferences(MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.reroute_indicator), true);
        mNextTurnPreviewEnabled = getSharedPreferences(MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.option_next_turn_preview), true);
        mTextLaneGuidanceEnabled = getSharedPreferences(MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.option_text_lane_guidance), true);
        mImageDir = getExternalCacheDir() == null
                ? getCacheDir().getAbsolutePath()
                : getExternalCacheDir().getAbsolutePath() + "/screenshots/";
        new File(mImageDir).mkdirs();
        // Ensure queue notifications are around 1 second old
        final int maxQueueSize = sHud.getMaxUpdatesPerSecond();
        mExecutor = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(maxQueueSize),
                mRejectHandler);

        logi("onCreate...");
        mMonitorHandler.sendMessage(mMonitorHandler.obtainMessage(EVENT_UPDATE_CURRENT_NOS));

        //========================================================================================
        // message receiver
        //========================================================================================
        mMsgReceiver = new MsgReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(getString(R.string.broadcast_receiver_notification_monitor));

        ContextCompat.registerReceiver(
                this, mMsgReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        //========================================================================================
        sStaticInstance = this;
        mPostman = MainActivityPostman.toMainActivityInstance(this, getString(R.string.broadcast_sender_notification_monitor));

        //========================================================================================

        loadArrowImagesInAssets();
    }

    private Bitmap[] mArrowBitmaps;
    private MainActivityPostman mPostman;

    private void loadArrowImagesInAssets() {
        final String dir = "arrow3";
        AssetManager assetManager = getApplicationContext().getAssets();

        try {
            String[] filePathList = assetManager.list(dir);
            mArrowBitmaps = new Bitmap[filePathList.length];

            for (int i = 0; i < filePathList.length; i++) {
                InputStream is = assetManager.open(dir + "/" + filePathList[i]);
                mArrowBitmaps[i] = BitmapFactory.decodeStream(is);
                is.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        mMonitorHandler.removeCallbacks(mStopNavigationSession);
        mMonitorHandler.removeCallbacks(mRetryArrowUpdate);
        mMonitorHandler.removeCallbacks(mRetryDistanceUpdate);
        mMonitorHandler.removeCallbacks(mNextPreviewAlternator);
        stopEtaCycler();
        if (mMsgReceiver != null) {
            unregisterReceiver(mMsgReceiver);
        }
        if (mExecutor != null) {
            mExecutor.shutdown();
        }
        sStaticInstance = null;
        sListenerConnected = false;
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sListenerConnected = true;
        NavigationDiagnostics.recordLifecycle(this, "notification_listener_connected");
        sHud = HudSessionManager.getInstance(this).getHud();
        mMonitorHandler.post(() -> {
            updateCurrentNotifications();
            processAlreadyActiveNavigation();
        });
    }

    /**
     * NotificationListenerService does not replay onNotificationPosted() after this service or
     * the application is recreated. Recover an AutoMapa/Google session which was already active,
     * otherwise screen analysis is silently routed to the wrong detector (or skipped entirely).
     */
    private void processAlreadyActiveNavigation() {
        StatusBarNotification[] activeNotifications = getCurrentNotifications();
        if (activeNotifications == null) return;

        for (StatusBarNotification notification : activeNotifications) {
            if (!isSupportedNavigation(notification)) continue;

            sActiveNavigationPackage = notification.getPackageName();
            mMonitorHandler.removeCallbacks(mStopNavigationSession);
            boolean sessionStartRequested = startNavigationSessionIfEnabled();
            if (sessionStartRequested) {
                mMonitorHandler.postDelayed(() -> {
                    sHud = HudSessionManager.getInstance(this).getHud();
                    processNotification(notification);
                }, 500L);
            } else {
                processNotification(notification);
            }
            return;
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sListenerConnected = false;
        NavigationDiagnostics.recordLifecycle(this, "notification_listener_disconnected");
        requestRebind(new android.content.ComponentName(this, NotificationMonitor.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        logi("onBind...");
        return super.onBind(intent);
    }


    private void processNotification(StatusBarNotification sbn) {
        if (null == mExecutor) {
            return;
        }
        // Parsing a notification may be slow, do this in the background
        mExecutor.execute(() -> {
            mPostman.addBooleanExtra(getString(R.string.notify_catched), true);
            mPostman.sendIntent2MainActivity();

            Notification notification = sbn.getNotification();
            if (null != notification) {
                String packageName = sbn.getPackageName();
                switch (packageName) {
                    case GOOGLE_MAPS_PACKAGE_NAME:
                        mNotifySource = 0;
                        parseGmapsNotification(notification);
                        break;

                    case GOOGLE_MAPS_GO_PACKAGE_NAME:
                        mNotifySource = 1;
                        parseGmapsNotification(notification);
                        break;

                    case AUTOMAPA_PACKAGE_NAME:
                        mNotifySource = 2;
                        parseAutoMapaNotification(notification);
                        break;
                    /*case OSMAND_PACKAGE_NAME:
                        parseOsmandNotification(notification);
                        break;
                    case SYGIC_PACKAGE_NAME:
                        parseSygicNotification(notification);
                        break;
                    */
                    default:
//                        String notifyMessage = "No gmaps' notification!?!?" + " (found: " + packageName + ")";
//                        mPostman.addStringExtra(getString(R.string.notify_msg), notifyMessage);
//                        mPostman.sendIntent2MainActivity();
                        break;
                }
            } else {
                mPostman.addBooleanExtra(getString(R.string.notify_catched), true);
                mPostman.addBooleanExtra(getString(R.string.is_in_navigation), false);
                mPostman.sendIntent2MainActivity();

                String notifyMessage = "No notification found!?!?";
                mPostman.addStringExtra(getString(R.string.notify_msg), notifyMessage);
                mPostman.sendIntent2MainActivity();
            }
        });
    }

    private void parseSygicNotification(Notification notification) {
        long currentTime = System.currentTimeMillis();
        mNotifyPeriodTime = currentTime - mLastNotifyTimeMillis;
        mLastNotifyTimeMillis = currentTime;

        parseSygicNotificationByExtras(notification);
    }

    private void parseSygicNotificationByExtras(Notification notification) {
        if (null == notification) {
            return;
        }

        Bundle extras = notification.extras;

        if ((null != extras)) {
            Object big = extras.get(Notification.EXTRA_BIG_TEXT);

            Object titleObj = extras.get(Notification.EXTRA_TITLE);
            Object textObj = extras.get(Notification.EXTRA_TEXT);
            Object subTextObj = extras.get(Notification.EXTRA_SUB_TEXT);

            String title = null != titleObj ? titleObj.toString() : null;
            String text = null != textObj ? textObj.toString() : null;
            String subText = null != subTextObj ? subTextObj.toString() : null;
            subText = null == subText ? text : subText;

            Icon large = notification.getLargeIcon();
            Icon small = notification.getSmallIcon();
            if (null != small) {
                Drawable drawableIco = small.loadDrawable(this);
                Bitmap bitmapImage = ImageUtils.drawableToBitmap(drawableIco);

                if (null != bitmapImage) {
                    if (STORE_IMG) {
                        ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow0_sygic.png");
                    }
                    bitmapImage = ImageUtils.removeAlpha(bitmapImage);
                    if (STORE_IMG) {
                        ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow_sygic.png");
                    }
                }
            }
            // Check if subText is empty (" ·  · ") --> don't parse subText
            // Occurs for example on NagivationChanged
        }
    }

    private void parseOsmandNotification(Notification notification) {
        long currentTime = System.currentTimeMillis();
        mNotifyPeriodTime = currentTime - mLastNotifyTimeMillis;
        mLastNotifyTimeMillis = currentTime;

        parseOsmandNotificationByExtras(notification);
    }

    private int mParseMethod = -1;
    private int mNotifySource = -1;

    private void parseGmapsNotification(Notification notification) {
        long currentTime = System.currentTimeMillis();
        mNotifyPeriodTime = currentTime - mLastNotifyTimeMillis;
        mLastNotifyTimeMillis = currentTime;

        mParseMethod = 0;
        boolean parseResult = parseGmapsNotificationByExtras(notification);

        if (!parseResult) {
            mPostman.addBooleanExtra(getString(R.string.notify_parse_failed), true);
            mPostman.addBooleanExtra(getString(R.string.gmaps_notify_catched), true);
            mPostman.addBooleanExtra(getString(R.string.is_in_navigation), false);
            mPostman.sendIntent2MainActivity();

            String notifyMessage = "Notify parsing failed. " + (noViewsSituation ? "(non-SDK interface restrictions)" : "");
            mPostman.addStringExtra(getString(R.string.notify_msg), notifyMessage);
        } else {
            mPostman.addBooleanExtra(getString(R.string.notify_parse_failed), false);
            mPostman.addBooleanExtra(getString(R.string.gmaps_notify_catched), true);
            mPostman.addBooleanExtra(getString(R.string.is_in_navigation), mIsNavigating);
            mPostman.addBooleanExtra(getString(R.string.option_arrow_type), mArrowTypeV2);
        }
        mPostman.sendIntent2MainActivity();
    }

    private void parseAutoMapaNotification(Notification notification) {
        long currentTime = System.currentTimeMillis();
        mNotifyPeriodTime = currentTime - mLastNotifyTimeMillis;
        mLastNotifyTimeMillis = currentTime;

        Bundle extras = notification == null ? null : notification.extras;
        String title = extras == null ? null : parseString(extras.get(Notification.EXTRA_TITLE));
        String road = extras == null ? null : parseString(extras.get(Notification.EXTRA_TEXT));
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseAutoMapa(title);

        boolean hasGuidance = result.hasGuidance();
        mIsNavigating = notification != null
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
                && hasGuidance;
        if (mIsNavigating) {
            if (result.rerouting) {
                handleRerouting();
            } else {
                mReroutingActive = false;
                applyParsedNavigationResult(result, false);
                updateHudInformation();
            }
        }

        mPostman.addBooleanExtra(getString(R.string.notify_parse_failed), !mIsNavigating);
        mPostman.addBooleanExtra(getString(R.string.gmaps_notify_catched), mIsNavigating);
        mPostman.addBooleanExtra(getString(R.string.is_in_navigation), mIsNavigating);
        mPostman.addStringExtra(getString(R.string.gmaps_notify_msg),
                TextUtils.isEmpty(road) ? title : title + " / " + road);
        mPostman.sendIntent2MainActivity();
        logParseMessage();
    }

    private static boolean viewsHasActionsField(RemoteViews views) {
        try {
            Class<?> viewsClass = views.getClass();
            viewsClass.getDeclaredField("mActions");
            return true;
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    private static Object getObjectProperty(Object object, String propertyName) {
        try {
            Field f = object.getClass().getDeclaredField(propertyName);
            f.setAccessible(true);
            return f.get(object);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static RemoteViews getRemoteViews(Notification notification) {
        // We have to extract the information from the view
        RemoteViews views = notification.bigContentView;
//        views.writeToParcel();
        if (!viewsHasActionsField(views)) {
            //check mActions is exist, we use it to parse notification
            views = null;
        }
        if (views == null) {
            views = notification.contentView;
        }
        if (!viewsHasActionsField(views)) {
            //check mActions again
            views = null;
        }

        if (views == null) {
            views = notification.headsUpContentView;
        }
        if (!viewsHasActionsField(views)) {
            //check mActions again
            views = null;
        }
        return views;
    }

    private boolean parseGmapsGoNotificationByReflection(Notification notification) {
        RemoteViews views = getRemoteViews(notification);
        if (views == null) return false;

        // Use reflection to examine the m_actions member of the given RemoteViews object.
        // It's not pretty, but it works.
        try {

            Class<?> viewsClass = views.getClass();
            Field fieldActions = viewsClass.getDeclaredField("mActions");
            fieldActions.setAccessible(true);

            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> actions = (ArrayList<Parcelable>) fieldActions.get(views);

            int indexOfActions = 0;

            // Find the setText() and setTime() reflection actions
            for (Parcelable action : actions) {
                Object methodName = getObjectProperty(action, "methodName");
                Object type = getObjectProperty(action, "type");
                Object value = getObjectProperty(action, "value");

                if (methodName == null) {
                    continue;
                    // Save strings
                } else if (methodName.equals("setText")) {
                    if (value instanceof String) {
                        switch (indexOfActions) {
                            case 4:
                                //distance
                                break;
                            case 6:
                                //road
                                break;
                            case 9:
                                //time
                                break;
                        }
                    }
                } else if (methodName.equals("setImageBitmap")) {
                    Object bitmapId = getObjectProperty(action, "bitmapId");
                    Object bitmapCache = getObjectProperty(views, "mBitmapCache");
                    Object bitmapsObject = getObjectProperty(bitmapCache, "mBitmaps");

                    if (bitmapId instanceof Integer && null != bitmapsObject) {
                        Integer integerBitmapId = (Integer) bitmapId;
                        ArrayList<Bitmap> bitmapList = (ArrayList<Bitmap>) bitmapsObject;

                        Bitmap bitmapImage = bitmapList.get(integerBitmapId);
                        if (STORE_IMG) {
                            ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow0.png");
                        }
                        bitmapImage = ImageUtils.removeAlpha(bitmapImage);
                        if (STORE_IMG) {
                            ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow.png");
                        }
                        ArrowImage arrowImage = new ArrowImage(bitmapImage);
                        mFoundArrow = getArrow(arrowImage);
                    }
                }
                indexOfActions++;
            }

            logParseMessage();
            return true;
        }
        // It's not usually good style to do this, but then again, neither is the use of reflection...
        catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    /**
     * @param notification notification
     * @return true is successful, false if not
     */
    private boolean parseGmapsNotificationByReflection(Notification notification) {
        RemoteViews views = getRemoteViews(notification);
        if (views == null) return false;

        // Use reflection to examine the m_actions member of the given RemoteViews object.
        // It's not pretty, but it works.
        try {

            Class<?> viewsClass = views.getClass();
            Field fieldActions = viewsClass.getDeclaredField("mActions");
            fieldActions.setAccessible(true);

            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> actions = (ArrayList<Parcelable>) fieldActions.get(views);

            int indexOfActions = 0;
            int updateCount = 0;
            boolean inNavigation = false;
            int validActionCount = 0;

            // Find the setText() and setTime() reflection actions
            for (Parcelable p : actions) {
                Parcel parcel = Parcel.obtain();
                if (null == p) {
                    continue;
                }
                p.writeToParcel(parcel, 0);
//                p.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                parcel.setDataPosition(0);

                // The tag tells which type of action it is (2 is ReflectionAction, from the source)
                int tag = parcel.readInt();
                String simpleClassName = p.getClass().getSimpleName();

                if ((tag != 2 && tag != 12) && (!simpleClassName.equals("ReflectionAction") && !simpleClassName.equals("BitmapReflectionAction")))
                    continue;

                String methodName = parcel.readString();
                String str = p.toString();
                //methodName = null == methodName ? p.
                String textOnGmapsNotify = "";
                if (methodName == null) continue;
                    // Save strings
                else if (methodName.equals("setText")) {
                    // Parameter type (10 = Character Sequence)
                    parcel.readInt();

                    // Store the actual string
                    String t = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel).toString().trim();

                    switch (indexOfActions) {
                        case 2:
                            inNavigation = t.equalsIgnoreCase(getString(R.string.exit_navigation));
                            break;
                        case 3:
                            //distance to turn
                            parseDistanceToTurn(t);
                            break;
                        case 8:
                            //time, distance, arrived time
                            parseTimeAndDistanceToDest(t);
                            updateCount++;
                            break;
                        default:
                            break;
                    }
                    validActionCount++;
                } else if (methodName.equals("setImageBitmap")) {
                    int bitmapId = parcel.readInt();
                    Field fieldBitmapCache = views.getClass().getDeclaredField("mBitmapCache");
                    fieldBitmapCache.setAccessible(true);

                    Object bitmapCache = fieldBitmapCache.get(views);
                    Field fieldBitmaps = bitmapCache.getClass().getDeclaredField("mBitmaps");
                    fieldBitmaps.setAccessible(true);
                    Object bitmapsObject = fieldBitmaps.get(bitmapCache);

                    if (null != bitmapsObject) {
                        ArrayList<Bitmap> bitmapList = (ArrayList<Bitmap>) bitmapsObject;
                        Bitmap bitmapImage = bitmapList.get(bitmapId);
                        if (STORE_IMG) {
                            ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow0.png");
                        }
                        bitmapImage = ImageUtils.removeAlpha(bitmapImage);
                        if (STORE_IMG) {
                            ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow.png");
                        }

                        ArrowImage arrowImage = new ArrowImage(bitmapImage);

                        if (mArrowTypeV2) {
                            final int index = getArrowV2Index(bitmapImage);
                            mFoundArrowV2 = ArrowV2.values()[index];
                            mLastFoundArrowV2 = mFoundArrowV2;

                            Bitmap foundArrowBitmap = mArrowBitmaps[index];
                            mPostman.addParcelableExtra(getString(R.string.arrow_bitmap), foundArrowBitmap);
                            mPostman.addStringExtra(getString(R.string.gmaps_notify_msg), textOnGmapsNotify);
                            mPostman.sendIntent2MainActivity();
                        } else {
                            mFoundArrow = getArrow(arrowImage);
                            mLastFoundArrow = mFoundArrow;

                            Bitmap foundArrowBitmap = arrowImage.binaryImage;
                            mPostman.addParcelableExtra(getString(R.string.arrow_bitmap), foundArrowBitmap);
                            mPostman.addStringExtra(getString(R.string.gmaps_notify_msg), textOnGmapsNotify);
                            mPostman.sendIntent2MainActivity();
                        }


                        updateCount++;
                    }
                    validActionCount++;
                }

                parcel.recycle();
                indexOfActions++;
            }
            if (validActionCount != 0) {
                logParseMessage();
            }
            //can update to garmin hud
            if (0 != updateCount && inNavigation) {
                updateHudInformation();
            }
            return validActionCount != 0;
        }
        // It's not usually good style to do this, but then again, neither is the use of reflection...
        catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    String textOnGmapsNotifyByJavaReflection = "";
    boolean noViewsSituation = false;

    private boolean parseGmapsNotificationByJavaReflection(Notification notification) {

        RemoteViews views = getRemoteViews(notification);
        if (views == null) {
            noViewsSituation = true;
            return false;
        }

        int indexOfActions = 0;
        int updateCount = 0;
        boolean inNavigation = false;
        int validActionCount = 0;

        // Use reflection to examine the m_actions member of the given RemoteViews object.
        // It's not pretty, but it works.
        try {

            Class<?> viewsClass = views.getClass();
            Field outerFields[] = viewsClass.getDeclaredFields();

            for (int i = 0; i < outerFields.length; i++) {
                if (!outerFields[i].getName().equals("mActions")) continue;

                outerFields[i].setAccessible(true);

                ArrayList<Object> actions = (ArrayList<Object>) outerFields[i]
                        .get(views);
                //String textOnGmapsNotify = "";
                for (Object action : actions) {
                    Field innerFields[] = action.getClass().getDeclaredFields();
                    Integer viewId = null;

                    int fieldCount = 0;
                    boolean isText = false;

                    for (Field field : innerFields) {
                        field.setAccessible(true);
                        String fieldName = field.getName();
                        boolean isImageBitmap = false;
                        Object fieldOfAction = field.get(action);

                        if (0 == fieldCount) {
                            isText = false;
                        }
                        if (fieldName.equals("methodName")) {
                            Object methodName = field.get(action);
                            if (methodName.equals("setText")) {
                                isText = true;
                            } else if (methodName.equals("setImageBitmap")) {
                                isImageBitmap = true;
                            }

                        } else if (fieldName.equals("bitmapId")) {
                            viewId = field.getInt(action);
                        }

                        if (isText && 3 == fieldCount) {
                            Object value = fieldOfAction;
                            if (value instanceof SpannableString || value instanceof String) {
                                String t = value.toString().trim();
                                switch (indexOfActions) {
                                    case 2:
                                        inNavigation = t.equalsIgnoreCase(getString(R.string.exit_navigation));
                                        break;
                                    case 4:
                                        //distance to turn
                                        parseDistanceToTurn(t);
                                        updateCount++;
                                        validActionCount++;
                                        textOnGmapsNotifyByJavaReflection = t;
                                        break;
                                    case 6:
                                        textOnGmapsNotifyByJavaReflection += " " + t;
                                        break;
                                    case 9:
                                        //time, distance, arrived time
                                        parseTimeAndDistanceToDest(t);
                                        updateCount++;
                                        validActionCount++;

                                        break;
                                    default:
                                        break;
                                }
                            }
                        }

                        if (isImageBitmap) {
                            Field fieldBitmapCache = views.getClass().getDeclaredField("mBitmapCache");
                            fieldBitmapCache.setAccessible(true);

                            Object bitmapCache = fieldBitmapCache.get(views);
                            Field fieldBitmaps = bitmapCache.getClass().getDeclaredField("mBitmaps");
                            fieldBitmaps.setAccessible(true);
                            Object bitmapsObject = fieldBitmaps.get(bitmapCache);

                            if (null != bitmapsObject) {
                                ArrayList<Bitmap> bitmapList = (ArrayList<Bitmap>) bitmapsObject;
                                Bitmap bitmapImage = bitmapList.get(viewId);
                                if (STORE_IMG) {
                                    ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow0.png");
                                }
                                bitmapImage = ImageUtils.removeAlpha(bitmapImage);
                                if (STORE_IMG) {
                                    ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow.png");
                                }


                                Bitmap foundArrowBitmap = null;
                                if (mArrowTypeV2) {
                                    final int index = getArrowV2Index(bitmapImage);
                                    mFoundArrowV2 = ArrowV2.values()[index];
                                    mLastFoundArrowV2 = mFoundArrowV2;

                                    foundArrowBitmap = mArrowBitmaps[index];
                                } else {
                                    ArrowImage arrowImage = new ArrowImage(bitmapImage);
                                    mFoundArrow = getArrow(arrowImage);
                                    mLastFoundArrow = mFoundArrow;

                                    foundArrowBitmap = arrowImage.binaryImage;
                                }
                                mPostman.addParcelableExtra(getString(R.string.arrow_bitmap), foundArrowBitmap);
                                mPostman.addStringExtra(getString(R.string.gmaps_notify_msg), textOnGmapsNotifyByJavaReflection);
                                mPostman.sendIntent2MainActivity();

                                validActionCount++;
                                updateCount++;
                            }

                        }
                        fieldCount++;
                    }
                    indexOfActions++;
                }
            }

            if (validActionCount != 0) {
                logParseMessage();
                mIsNavigating = true;
            } else {
                mIsNavigating = false;
            }
            //can update to garmin hud
            if (0 != updateCount && inNavigation) {
                updateHudInformation();
            }
            return validActionCount != 0;
        }
        // It's not usually good style to do this, but then again, neither is the use of reflection...
        catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    private void logParseMessage() {
        String arrowString = mArrowTypeV2 ? mFoundArrowV2.toString() : mFoundArrow.toString();
        String notifySourceString = mNotifySource == 0 ? "gmaps"
                : mNotifySource == 1 ? "gmaps go"
                : mNotifySource == 2 ? "automapa" : "unknown";
        String nextArrowString = mArrowTypeV2 ? mNextManeuverV2.toString() : mNextManeuver.toString();
        String notifyMessage = arrowString + "(" + (mArrowTypeV2 ? "v2:" : "v1:") + sArrowMinSad + ") " +
                "next:" + nextArrowString + " " +
                mDistanceNum + "/" + mDistanceUnit + " " +
                (null == mRemainingHours ? 0 : mRemainingHours) + ":" + mRemainingMinutes + " " +
                mRemainingDistance + mRemainingDistanceUnits + " " +
                mArrivalHours + ":" + mArrivalMinutes +
                " busy: " + (mBusyTraffic ? "1" : "0") +
                " parseMethod: (" + mParseMethod + ")" +
                " source: (" + notifySourceString + ")" +
                " (period: " + mNotifyPeriodTime + ")";
        logi(notifyMessage);

        mPostman.addStringExtra(getString(R.string.notify_msg), notifyMessage);
        mPostman.sendIntent2MainActivity();

        mPostman.addBooleanExtra(getString(R.string.is_in_navigation), mIsNavigating);
        mPostman.sendIntent2MainActivity();
    }

    private boolean mIsNavigating = false;
    private boolean mSuppressAutoStartUntilNavigationEnds;
    private static volatile String sActiveNavigationPackage;

    public static String getActiveNavigationPackage() {
        return sActiveNavigationPackage;
    }

    /** True while a supported navigation app is actively guiding. */
    public boolean isNavigating() {
        return mIsNavigating;
    }

    private final Runnable mStopNavigationSession = () -> {
        if (hasActiveSupportedNavigation()) {
            return;
        }

        mIsNavigating = false;
        sActiveNavigationPackage = null;
        HudNavigationState.clearAutoMapaSpeedLimit();
        mSuppressAutoStartUntilNavigationEnds = false;
        stopNextTurnPreview();
        stopEtaCycler();
        mReroutingActive = false;
        mNextManeuver = Arrow.None;
        mNextManeuverV2 = ArrowV2.None;
        if (sHud != null && mLastTextLaneOutline != -1) {
            sHud.setLanes((char) 0, (char) 0);
            mLastTextLaneOutline = -1;
            mLastTextLaneArrow = -1;
        }
        if (mPostman != null) {
            // AutoMapa replaces its ongoing notification instead of updating it in place on
            // some releases. Only announce the end after the grace period above confirmed that
            // no replacement notification exists. Announcing it in onNotificationRemoved()
            // made the GPS update overwrite the maneuver distance for a fraction of a second.
            mPostman.addBooleanExtra(getString(R.string.notify_parse_failed), false);
            mPostman.addBooleanExtra(getString(R.string.notify_catched), true);
            mPostman.addBooleanExtra(getString(R.string.gmaps_notify_catched), false);
            mPostman.addBooleanExtra(getString(R.string.is_in_navigation), false);
            mPostman.sendIntent2MainActivity();
        }
        stopService(new Intent(this, chutka.bitman.com.speedometersimplified.LocationService.class));
        stopService(new Intent(this, ScreenRecorderService.class));
        if (shouldAutoStartWithNavigation()) {
            closeAutomaticNavigationSession();
        }
    };

    /**
     * Auto-start makes the HUD application part of the navigation application's lifetime. Once
     * the grace-period check confirms that Google Maps or AutoMapa is no longer navigating, stop
     * every foreground component, disconnect the HUD and remove our activity from Recents.
     * NotificationMonitor itself remains enabled so Android can start the next automatic session.
     */
    private void closeAutomaticNavigationSession() {
        Log.i(TAG, "Closing application after automatic navigation session ended");
        HudConnectionService.stop(this);
        HudSessionManager.getInstance(this).shutdown();

        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager == null) return;
        for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
            task.finishAndRemoveTask();
        }
    }

    public void suppressAutoStartForCurrentNavigation() {
        mSuppressAutoStartUntilNavigationEnds = hasActiveSupportedNavigation();
    }

    private boolean isGoogleMapsNavigation(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return false;
        }
        String packageName = sbn.getPackageName();
        boolean isGoogleMaps = GOOGLE_MAPS_PACKAGE_NAME.equals(packageName)
                || GOOGLE_MAPS_GO_PACKAGE_NAME.equals(packageName);
        return isGoogleMaps && GOOGLE_MAPS_NOTIFICATION_GROUP_NAVIGATION.equals(
                sbn.getNotification().getGroup());
    }

    private boolean isAutoMapaNavigation(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null
                || !AUTOMAPA_PACKAGE_NAME.equals(sbn.getPackageName())) {
            return false;
        }
        Notification notification = sbn.getNotification();
        // AutoMapa first publishes its persistent "application running" notification and may
        // add the maneuver title only in a later update. Waiting for a non-empty title makes
        // automatic HUD startup depend on that second notification and therefore intermittent.
        // Parsing still validates the title separately before sending navigation information.
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private boolean isSupportedNavigation(StatusBarNotification sbn) {
        return isGoogleMapsNavigation(sbn) || isAutoMapaNavigation(sbn);
    }

    private boolean hasActiveSupportedNavigation() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null) {
                return false;
            }
            for (StatusBarNotification notification : activeNotifications) {
                if (isSupportedNavigation(notification)) {
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not inspect active navigation notifications", exception);
        }
        return false;
    }

    private boolean shouldAutoStartWithNavigation() {
        return getSharedPreferences(MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.option_auto_start_navigation), false);
    }

    private boolean startNavigationSessionIfEnabled() {
        if (mSuppressAutoStartUntilNavigationEnds || !shouldAutoStartWithNavigation()) {
            return false;
        }
        boolean sessionStartRequested = false;
        if (HudConnectionService.isRunning()) {
            sHud = HudSessionManager.getInstance(this).getHud();
        } else {
            sessionStartRequested = HudConnectionService.start(this);
        }

        // Android 14+ permits a location FGS started from this background listener only when the
        // user granted background location. Without it, leave navigation running without GPS
        // instead of throwing SecurityException and terminating the process.
        boolean showSpeed = getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.option_show_speed), false);
        boolean canStartBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (showSpeed && canStartBackgroundLocation) {
            if (LocationService.start(this)) {
                Log.i(TAG, "GPS speed service requested for automatic navigation session");
                sessionStartRequested = true;
            }
        } else if (showSpeed) {
            Log.w(TAG, "GPS speed skipped: background location permission is not granted");
        }
        return sessionStartRequested;
    }

    private boolean parseOsmandNotificationByExtras(Notification notification) {
        if (null == notification) {
            return false;
        }
        Bundle extras = notification.extras;
        String group_name = notification.getGroup();

        if ((null != extras) && (null != group_name) && group_name.equals(OSMAND_NOTIFICATION_GROUP_NAVIGATION)) {
            Object remainingTime = extras.get(Notification.EXTRA_BIG_TEXT);
            Object distance = extras.get(Notification.EXTRA_TITLE);

            Icon largeIcon = notification.getLargeIcon();
            Icon smallIcon = notification.getSmallIcon();

            Object msg = extras.get(Notification.EXTRA_MESSAGES);
            Object textObj = extras.get(Notification.EXTRA_TEXT);
            Object subTextObj = extras.get(Notification.EXTRA_SUB_TEXT);

            if (null != largeIcon) {
                Drawable drawableIco = largeIcon.loadDrawable(this);
                Bitmap bitmapImage = ImageUtils.drawableToBitmap(drawableIco);

                if (null != bitmapImage) {
                    if (STORE_IMG) {
                        ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow0_osm.png");
                    }
                    bitmapImage = ImageUtils.removeAlpha(bitmapImage);
                    if (STORE_IMG) {
                        ImageUtils.storeBitmap(bitmapImage, mImageDir, "arrow_osm.png");
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static String parseString(Object o) {
        return null != o ? o.toString() : null;
    }

    private boolean parseGmapsNotificationByExtras(Notification notification) {
        if (notification == null || notification.extras == null) {
            return false;
        }
        Bundle extras = notification.extras;
        String group_name = notification.getGroup();
        if (!GOOGLE_MAPS_NOTIFICATION_GROUP_NAVIGATION.equals(group_name)) {
            return false;
        }

        String title = parseString(extras.get(Notification.EXTRA_TITLE));
        String text = parseString(extras.get(Notification.EXTRA_TEXT));
        String subText = parseString(extras.get(Notification.EXTRA_SUB_TEXT));
        String primaryInfo = firstNonEmpty(
                parseString(extras.get("android.ongoingActivityNoti.primaryInfo")),
                parseString(extras.get("android.ongoingActivityNoti.nowbarPrimaryInfo")),
                parseString(extras.get("android.ongoingActivityNoti.chipExpandedText")));

        Calendar now = Calendar.getInstance();
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                title,
                primaryInfo,
                TextUtils.isEmpty(subText) ? text : subText,
                extras.getInt(Notification.EXTRA_PROGRESS, 0),
                extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE));

        // A navigation startup/rerouting notification is still a valid active session, although it
        // temporarily has no maneuver to send to the HUD.
        if (TextUtils.isEmpty(title)) {
            return false;
        }

        if (result.rerouting) {
            handleRerouting();
        } else {
            mReroutingActive = false;
            applyParsedNavigationResult(result, true);
            updateHudInformation();
        }

        Icon largeIcon = notification.getLargeIcon();
        if (largeIcon != null) {
            Drawable drawable = largeIcon.loadDrawable(this);
            Bitmap bitmap = ImageUtils.drawableToBitmap(drawable);
            if (bitmap != null) {
                mPostman.addParcelableExtra(getString(R.string.arrow_bitmap), bitmap);
            }
        }
        mPostman.addStringExtra(getString(R.string.gmaps_notify_msg), title);
        mPostman.sendIntent2MainActivity();
        logParseMessage();
        return true;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private void applyParsedNavigationResult(NavigationInstructionParser.Result result,
                                             boolean includesTripSummary) {
        NavigationInstructionParser.Distance distance = result.distanceToTurn;
        if (distance != null) {
            mDistanceNum = Float.toString(distance.value);
            mDistanceUnit = distance.unit;
        } else {
            mDistanceNum = "-1";
            mDistanceUnit = "m";
        }

        setParsedManeuver(result.maneuver);
        setParsedNextManeuver(result.nextManeuver);
        mRoundaboutExit = result.roundaboutExit;
        applyTextLaneGuidance(result.laneGuidance, result.maneuver);

        if (!includesTripSummary) {
            mRemainingHours = null;
            mRemainingMinutes = null;
            mRemainingDistance = null;
            mRemainingDistanceUnits = null;
            mArrivalHours = -1;
            mArrivalMinutes = -1;
            return;
        }

        if (result.arrival != null) {
            mArrivalHours = result.arrival.hour;
            mArrivalMinutes = result.arrival.minute;
        }
        if (result.remainingMinutes != null) {
            mRemainingHours = Integer.toString(result.remainingMinutes / 60);
            mRemainingMinutes = Integer.toString(result.remainingMinutes % 60);
        }
        if (result.remainingDistanceMetres != null) {
            mRemainingDistance = Integer.toString(result.remainingDistanceMetres);
            mRemainingDistanceUnits = "m";
        }
    }

    /**
     * Shows a neutral HUD state while the navigation app recalculates the route.
     * The last distance stays on screen; only the direction arrow is blanked.
     *
     * <p>Assumption: {@code setDirection(AsDirection, ArrowOnly, AsDirection)} renders as an
     * empty arrow slot — angle 0x00 carries no direction and ArrowOnly suppresses the
     * roundabout/flag overlays.</p>
     */
    private void handleRerouting() {
        if (!mRerouteIndicatorEnabled) {
            return;
        }
        if (mReroutingActive) {
            return;
        }
        mReroutingActive = true;
        if (sHud != null) {
            sHud.setDirection(eOutAngle.AsDirection, eOutType.ArrowOnly, eOutAngle.AsDirection);
        }
        Log.i(TAG, "Rerouting: showing neutral HUD state");
    }

    private void setParsedManeuver(Arrow maneuver) {
        mFoundArrow = maneuver == null ? Arrow.None : maneuver;
        mLastFoundArrow = mFoundArrow;
        try {
            mFoundArrowV2 = ArrowV2.valueOf(mFoundArrow.name());
        } catch (IllegalArgumentException exception) {
            mFoundArrowV2 = ArrowV2.None;
        }
        mLastFoundArrowV2 = mFoundArrowV2;
    }

    private void setParsedNextManeuver(Arrow nextManeuver) {
        Arrow next = nextManeuver == null ? Arrow.None : nextManeuver;
        boolean changed = next != mNextManeuver;
        mNextManeuver = next;
        try {
            mNextManeuverV2 = ArrowV2.valueOf(next.name());
        } catch (IllegalArgumentException exception) {
            mNextManeuverV2 = ArrowV2.None;
        }
        if (changed) {
            if (next == Arrow.None) {
                stopNextTurnPreview();
            } else {
                startNextTurnPreview();
            }
        }
    }

    /**
     * Sends lane guidance parsed from notification text to the HUD. This source works even
     * when screen capture is off (phone locked / Maps in background), complementing the
     * screen-based lane detector, which keeps sending its own richer state when active.
     */
    private void applyTextLaneGuidance(NavigationInstructionParser.LaneGuidance guidance,
                                      Arrow maneuver) {
        if (sHud == null || !mTextLaneGuidanceEnabled) {
            return;
        }
        if (guidance != null && guidance.hasRecommendation()) {
            if (guidance.outlineMask != mLastTextLaneOutline
                    || guidance.arrowMask != mLastTextLaneArrow) {
                sHud.setLanes((char) guidance.arrowMask, (char) guidance.outlineMask);
                mLastTextLaneOutline = guidance.outlineMask;
                mLastTextLaneArrow = guidance.arrowMask;
                logi("Text lane guidance sent: outline=0x"
                        + Integer.toHexString(guidance.outlineMask)
                        + " arrow=0x" + Integer.toHexString(guidance.arrowMask));
            }
        } else if (mLastTextLaneOutline != -1) {
            // Lane hint gone (maneuver changed): clear only if we were the last text sender,
            // so we never stomp a live screen-detector state with no information.
            sHud.setLanes((char) 0, (char) 0);
            mLastTextLaneOutline = -1;
            mLastTextLaneArrow = -1;
        }
    }

    private static eUnits get_eUnits(String unit) {
        if (null == unit) {
            return eUnits.None;
        }

        switch (unit) {
            case "km":
                return eUnits.Kilometres;
            case "m":
                return eUnits.Metres;
            case "mi":
                return eUnits.Miles;
            case "ft":
                return eUnits.Foot;
            default:
                return eUnits.None;
        }
    }

    // Translates the units (distance and time) from local language and charset in common values
    private String translate(String local_language_string) {
        if (local_language_string.equalsIgnoreCase(getString(R.string.km))) {
            return "km";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.meter))) {
            return "m";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.feet))) {
            return "ft";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.miles))) {
            return "mi";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.minute))) {
            return "m";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.minute2))) {
            return "m";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.hour))) {
            return "h";
        } else if (local_language_string.equalsIgnoreCase(getString(R.string.hour2))) {
            return "h";
        }
        return null;
    }

    // Returns the current Unit (Kilometres or Miles) based on distanceToTurn
    public static eUnits getCurrentUnit() {
        if (get_eUnits(mDistanceUnit) == eUnits.Kilometres ||
                get_eUnits(mDistanceUnit) == eUnits.Metres) {
            return eUnits.Kilometres;
        } else if (get_eUnits(mDistanceUnit) == eUnits.Miles ||
                get_eUnits(mDistanceUnit) == eUnits.Foot) {
            return eUnits.Miles;
        }
        return eUnits.None;
    }

    private static Arrow getArrow(ArrowImage image) {
        sArrowMinSad = Integer.MAX_VALUE;
        Arrow minSadArrow = Arrow.None;

        for (Arrow a : Arrow.values()) {
            int sad = image.getSAD(a.valueLeft);
            if (sad < sArrowMinSad) {
                sArrowMinSad = sad;
                minSadArrow = a;
            }
            if (0 == sad) {
                String integerString = Long.toString(a.valueLeft);
                Log.d(TAG, "Recognize " + a.name() + " " + integerString);
                return a;

            }
        }
        Log.d(TAG, "No Recognize, minSad: " + sArrowMinSad + " arrow:" + minSadArrow);
        return minSadArrow;
    }

    static int sArrowMinSad = 0;

    private static int getGreenSAD(Bitmap image1, Bitmap image2) {
        if (null == image1 || null == image2) {
            return -1;
        }
        if (image1.getWidth() != image2.getWidth() || image1.getHeight() != image2.getHeight()) {
            return -1;
        }
        final int height = image1.getHeight();
        final int width = image1.getWidth();
        int sad = 0;
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int pixel1 = image1.getPixel(w, h);
                int pixel2 = image2.getPixel(w, h);
                int green1 = Color.green(pixel1);
                int green2 = Color.green(pixel2);
                sad += Math.abs(green1 - green2);
            }
        }
        return sad;
    }

    private static int getNotWhiteSAD(Bitmap image1, Bitmap image2) {
        if (null == image1 || null == image2) {
            return -1;
        }
        if (image1.getWidth() != image2.getWidth() || image1.getHeight() != image2.getHeight()) {
            return -1;
        }
        final int height = image1.getHeight();
        final int width = image1.getWidth();
        int sad = 0;
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int pixel1 = image1.getPixel(w, h);
                int pixel2 = image2.getPixel(w, h);
                int green1 = Color.green(pixel1);
                int green2 = Color.green(pixel2);
                if (!(green1 == green2 && green1 >= 250)) {
                    sad++;
                }
            }
        }
        return sad;
    }

    private int getArrowV2Index(Bitmap image) {
        if (null == mArrowBitmaps) {
            return -1;
        }
        final ArrowV2[] arrows = ArrowV2.values();
        if (mArrowBitmaps.length + 1 != arrows.length) {
            return -1;
        }

        final int targetWidth = mArrowBitmaps[0].getWidth();
        final int targetHeight = mArrowBitmaps[0].getHeight();
        Bitmap noAlphaImage = ImageUtils.removeAlpha(image);
        Bitmap scaleImage = ImageUtils.getScaleBitmap(noAlphaImage, targetWidth, targetHeight);
//        scaleImage = ImageUtils.removeAlpha(scaleImage);
        final int length = mArrowBitmaps.length;

        sArrowMinSad = Integer.MAX_VALUE;
        int minSADIndex = -1;
        int sadArray[] = new int[mArrowBitmaps.length];

        for (int x = 0; x < length; x++) {
//            int sad = getNotWhiteSAD(scaleImage, mArrowBitmaps[x]);
            //getGreenSAD better than getNotWhiteSAD(?)
            int sad = getGreenSAD(scaleImage, mArrowBitmaps[x]);
            sadArray[x] = sad;
            if (-1 != sad && sad < sArrowMinSad) {
                sArrowMinSad = sad;
                minSADIndex = x;
            }
        }

        ArrowV2 arrow = arrows[minSADIndex];
        if (0 == sArrowMinSad) {
            Log.d(TAG, "Recognize " + arrow.name());
        } else {
            Log.d(TAG, "No Recognize, minSad: " + sArrowMinSad + " arrow:" + arrow);
        }
        return minSADIndex;
    }

    private ArrowV2 getArrowV2(Bitmap image) {
        if (null == mArrowBitmaps) {
            return null;
        }
        final ArrowV2[] arrows = ArrowV2.values();
        if (mArrowBitmaps.length + 1 != arrows.length) {
            return null;
        }

        final int targetWidth = mArrowBitmaps[0].getWidth();
        final int targetHeight = mArrowBitmaps[0].getHeight();
        Bitmap scaleImage = ImageUtils.getScaleBitmap(image, targetWidth, targetHeight);
        scaleImage = ImageUtils.removeAlpha(scaleImage);
        final int length = mArrowBitmaps.length;

        sArrowMinSad = Integer.MAX_VALUE;
        int minSADIndex = -1;
        for (int x = 0; x < length; x++) {
            int sad = getNotWhiteSAD(scaleImage, mArrowBitmaps[x]);
            if (-1 != sad && sad < sArrowMinSad) {
                sArrowMinSad = sad;
                minSADIndex = x;
            }
        }

        ArrowV2 arrow = arrows[minSADIndex];
        if (0 == sArrowMinSad) {
            Log.d(TAG, "Recognize " + arrow.name());
        } else {
            Log.d(TAG, "No Recognize, minSad: " + sArrowMinSad + " arrow:" + arrow);
        }
        return arrow;
    }

    private static ArrowV2 getArrowV2(ArrowImage image) {
        sArrowMinSad = Integer.MAX_VALUE;
        ArrowV2 minSadArrow = ArrowV2.None;

        for (ArrowV2 a : ArrowV2.values()) {
            int sad = image.getSAD(a.valueLeft);
            if (sad < sArrowMinSad) {
                sArrowMinSad = sad;
                minSadArrow = a;
            }
            if (0 == sad) {
                String integerString = Long.toString(a.valueLeft);
                Log.d(TAG, "Recognize " + a.name() + " " + integerString);
                return a;
            }
        }
        Log.d(TAG, "No Recognize, minSad: " + sArrowMinSad + " arrow:" + minSadArrow);
        return minSadArrow;
    }

    /**
     * Maps a parsed roundabout exit number (1..8) to the HUD spoke angle sent as
     * nRoundaboutOut. Exit 1 is the first exit in the driving direction; spoke
     * handedness follows the roundabout type (RightRoundabout = counterclockwise,
     * the left-hand-traffic variants).
     */
    private eOutAngle roundaboutExitSpoke(eOutAngle defaultAngle, eOutType roundaboutType) {
        if (!mRoundaboutExitNumbersEnabled || mRoundaboutExit == null) {
            return defaultAngle;
        }
        boolean counterClockwise = roundaboutType == eOutType.RightRoundabout;
        int exit = mRoundaboutExit;
        if (counterClockwise) {
            // Left-hand traffic: exits count to the left.
            if (exit == 1) return eOutAngle.Left;
            if (exit == 2) return eOutAngle.Straight;
            if (exit == 3) return eOutAngle.Right;
            return eOutAngle.SharpRight;
        }
        // Right-hand traffic: exits count to the right.
        if (exit == 1) return eOutAngle.Right;
        if (exit == 2) return eOutAngle.Straight;
        if (exit == 3) return eOutAngle.Left;
        return eOutAngle.SharpLeft;
    }

    private Arrow mPreArrowV1 = Arrow.None;

    void updateArrow(Arrow arrow) {
        if (null == sHud) {
            return;
        }
        if (mPreArrowV1 != arrow) {
            mPreArrowV1 = arrow;
        }
        switch (arrow) {
            case Arrivals:
                sHud.setDirection(eOutAngle.Straight, eOutType.RightFlag, eOutAngle.AsDirection);
                break;

            case ArrivalsLeft:
                sHud.setDirection(eOutAngle.Left, eOutType.RightFlag, eOutAngle.AsDirection);
                break;

            case ArrivalsRight:
                sHud.setDirection(eOutAngle.Right, eOutType.RightFlag, eOutAngle.AsDirection);
                break;

            case EasyLeft:
            case KeepLeft:
                sHud.setDirection(eOutAngle.EasyLeft);
                break;

            case EasyRight:
            case KeepRight:
                sHud.setDirection(eOutAngle.EasyRight);
                break;

            case GoTo:
            case Straight:
                sHud.setDirection(eOutAngle.Straight);
                break;

            case LeaveRoundabout:
                //1 checked
            case LeaveRoundaboutLeft:
                //9 checked
                sHud.setDirection(eOutAngle.Left, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Left, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutAsUTurn:
                sHud.setDirection(eOutAngle.Down, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Down, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutAsUTurnCC:
                sHud.setDirection(eOutAngle.Down, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Down, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutEasyLeft:
                //4 checked
                sHud.setDirection(eOutAngle.EasyLeft, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.EasyLeft, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutEasyLeftCC:
                //5 checked
                sHud.setDirection(eOutAngle.EasyLeft, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.EasyLeft, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutEasyRight:
                //6 checked
                sHud.setDirection(eOutAngle.EasyRight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.EasyRight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutEasyRightCC:
                //7 checked
                sHud.setDirection(eOutAngle.EasyRight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.EasyRight, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutCC:
                //8 checked
                sHud.setDirection(eOutAngle.Right, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutLeftCC:
                //10 checked
                sHud.setDirection(eOutAngle.Left, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Left, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutRight:
                //11 checked
                sHud.setDirection(eOutAngle.Right, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutRightCC:
                //12 checked
                sHud.setDirection(eOutAngle.Right, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutSharpLeft:
                //13 checked
                sHud.setDirection(eOutAngle.SharpLeft, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.SharpLeft, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutSharpLeftCC:
                //14 checked
                sHud.setDirection(eOutAngle.SharpLeft, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.SharpLeft, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutSharpRight:
                //15 checked
                sHud.setDirection(eOutAngle.SharpRight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.SharpRight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutSharpRightCC:
                //16
                sHud.setDirection(eOutAngle.SharpRight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.SharpRight, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutStraight:
                sHud.setDirection(eOutAngle.Straight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Straight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutStraightCC:
                sHud.setDirection(eOutAngle.Straight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Straight, eOutType.RightRoundabout));
                break;

            case Left:
                sHud.setDirection(eOutAngle.Left);
                break;

            case LeftDown:
                sHud.setDirection(eOutAngle.LeftDown);
                break;

            case LeftToLeave:
                sHud.setDirection(eOutAngle.EasyLeft, eOutType.LongerLane, eOutAngle.AsDirection);
                break;

            case Right:
                sHud.setDirection(eOutAngle.Right);
                break;

            case RightDown:
                sHud.setDirection(eOutAngle.RightDown);
                break;

            case RightToLeave:
                sHud.setDirection(eOutAngle.EasyRight, eOutType.LongerLane, eOutAngle.AsDirection);
                break;

            case SharpLeft:
                sHud.setDirection(eOutAngle.SharpLeft);
                break;

            case SharpRight:
                sHud.setDirection(eOutAngle.SharpRight);
                break;

            case Convergence:
            case None:
            default:
                sHud.setDirection(eOutAngle.AsDirection);
                break;
        }
    }

    private ArrowV2 mPreArrowV2 = ArrowV2.None;

    void updateArrow(ArrowV2 arrow) {
        if (null == sHud) {
            return;
        }
        if (mPreArrowV2 != arrow) {
            mPreArrowV2 = arrow;
        }
        switch (arrow) {
            case ArrivalsLeft:
                sHud.setDirection(eOutAngle.Left, eOutType.RightFlag, eOutAngle.AsDirection);
                break;

            case ArrivalsRight:
                sHud.setDirection(eOutAngle.Right, eOutType.RightFlag, eOutAngle.AsDirection);
                break;

            case EasyLeft:
            case KeepLeft:
                sHud.setDirection(eOutAngle.EasyLeft);
                break;

            case EasyRight:
            case KeepRight:
                sHud.setDirection(eOutAngle.EasyRight);
                break;

            case GoTo:
            case Straight:
                sHud.setDirection(eOutAngle.Straight);
                break;

            case LeaveRoundabout:
            case LeaveRoundaboutLeft:
                //9 checked
                //1 checked
                sHud.setDirection(eOutAngle.Left, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Left, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutAsUTurn:
                sHud.setDirection(eOutAngle.Down, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Down, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutAsUTurnCC:
                sHud.setDirection(eOutAngle.Down, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Down, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutEasyLeft:
                //4 checked
                sHud.setDirection(eOutAngle.EasyLeft, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.EasyLeft, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutEasyLeftCC:
                //5 checked
                sHud.setDirection(eOutAngle.EasyLeft, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.EasyLeft, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutEasyRight:
                //6 checked
                sHud.setDirection(eOutAngle.EasyRight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.EasyRight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutEasyRightCC:
                //7 checked
                sHud.setDirection(eOutAngle.EasyRight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.EasyRight, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutCC:
                //8 checked
                sHud.setDirection(eOutAngle.Right, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutLeftCC:
                //10 checked
                sHud.setDirection(eOutAngle.Left, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Left, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutRight:
                //11 checked
                sHud.setDirection(eOutAngle.Right, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutRightCC:
                //12 checked
                sHud.setDirection(eOutAngle.Right, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Right, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutSharpLeft:
                //13 checked
                sHud.setDirection(eOutAngle.SharpLeft, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.SharpLeft, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutSharpLeftCC:
                //14 checked
                sHud.setDirection(eOutAngle.SharpLeft, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.SharpLeft, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutSharpRight:
                //15 checked
                sHud.setDirection(eOutAngle.SharpRight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.SharpRight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutSharpRightCC:
                //16
                sHud.setDirection(eOutAngle.SharpRight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.SharpRight, eOutType.RightRoundabout));
                break;

            case LeaveRoundaboutStraight:
                sHud.setDirection(eOutAngle.Straight, eOutType.LeftRoundabout, roundaboutExitSpoke(eOutAngle.Straight, eOutType.LeftRoundabout));
                break;

            case LeaveRoundaboutStraightCC:
                sHud.setDirection(eOutAngle.Straight, eOutType.RightRoundabout, roundaboutExitSpoke(eOutAngle.Straight, eOutType.RightRoundabout));
                break;

            case Left:
                sHud.setDirection(eOutAngle.Left);
                break;

            case LeftDown:
                sHud.setDirection(eOutAngle.LeftDown);
                break;

            case Right:
                sHud.setDirection(eOutAngle.Right);
                break;
            case RightDown:
                sHud.setDirection(eOutAngle.RightDown);
                break;

            case SharpLeft:
                sHud.setDirection(eOutAngle.SharpLeft);
                break;
            case SharpRight:
                sHud.setDirection(eOutAngle.SharpRight);
                break;

            case Convergence:
            case None:
            default:
                sHud.setDirection(eOutAngle.AsDirection);
                break;
        }
    }

    private boolean mBusyTraffic = false;
    private volatile boolean mArrowTypeV2 = false;
    private static final long ARROW_RETRY_DELAY_MS = 1_100L;
    private static final long DISTANCE_RETRY_DELAY_MS = 1_250L;
    private boolean mArrowRetryScheduled;
    private boolean mDistanceRetryScheduled;

    private final Runnable mRetryArrowUpdate = () -> {
        mArrowRetryScheduled = false;
        sHud = HudSessionManager.getInstance(this).getHud();
        sendCurrentArrow();
        boolean sent = sHud != null && sHud.getSendResult();
        logi("Arrow retry " + currentArrowName() + " sent: " + (sent ? '1' : '0'));
        if (!sent) scheduleArrowRetry();
    };

    /**
     * Alternates the HUD arrow between the current maneuver and the parsed next-turn
     * preview. Any real navigation update snaps the display back to the current maneuver
     * via sendCurrentArrow(); the alternator then resumes from there.
     */
    private final Runnable mNextPreviewAlternator = new Runnable() {
        @Override
        public void run() {
            if (!mNextTurnPreviewEnabled || sHud == null) {
                return;
            }
            ArrowV2 nextV2 = mNextManeuverV2;
            Arrow nextV1 = mNextManeuver;
            boolean hasPreview = mArrowTypeV2
                    ? nextV2 != null && nextV2 != ArrowV2.None
                    : nextV1 != null && nextV1 != Arrow.None;
            if (!hasPreview) {
                return;
            }
            mShowingNextPreview = !mShowingNextPreview;
            if (mShowingNextPreview) {
                if (mArrowTypeV2) {
                    updateArrow(nextV2);
                } else {
                    updateArrow(nextV1);
                }
                logi("Next-turn preview showing: " + (mArrowTypeV2 ? nextV2 : nextV1));
            } else {
                sendCurrentArrow();
            }
            mMonitorHandler.postDelayed(this, NEXT_PREVIEW_INTERVAL_MS);
        }
    };

    private void startNextTurnPreview() {
        stopNextTurnPreview();
        if (!mNextTurnPreviewEnabled) {
            return;
        }
        mMonitorHandler.postDelayed(mNextPreviewAlternator, NEXT_PREVIEW_INTERVAL_MS);
    }

    private void stopNextTurnPreview() {
        mMonitorHandler.removeCallbacks(mNextPreviewAlternator);
        if (mShowingNextPreview) {
            mShowingNextPreview = false;
            sendCurrentArrow();
        }
    }

    /**
     * Alternates the HUD clock field between remaining time and arrival time
     * while ETA display mode is CYCLE.
     */
    private final Runnable mEtaCycler = new Runnable() {
        @Override
        public void run() {
            if (mEtaDisplayMode != ETA_MODE_CYCLE || sHud == null || !mIsNavigating) {
                mEtaCyclerRunning = false;
                return;
            }
            mEtaCycleShowRemaining = !mEtaCycleShowRemaining;
            pushTimeDisplay(mEtaCycleShowRemaining ? ETA_MODE_REMAINING : ETA_MODE_ARRIVAL);
            mMonitorHandler.postDelayed(this, ETA_CYCLE_INTERVAL_MS);
        }
    };

    private void startEtaCycler() {
        if (mEtaDisplayMode != ETA_MODE_CYCLE) {
            return;
        }
        // Don't reset an already-running cycler: updateHudInformation() calls this
        // on every HUD tick, and resetting mEtaCycleShowRemaining would pin the
        // display on REMAINING forever. An explicit flag is used instead of
        // Handler.hasCallbacks(): the callback is dequeued while run() executes,
        // so hasCallbacks() can briefly return false mid-cycle and allow a
        // duplicate cycler to be posted.
        if (mEtaCyclerRunning) {
            return;
        }
        mEtaCycleShowRemaining = true;
        mEtaCyclerRunning = true;
        mMonitorHandler.postDelayed(mEtaCycler, ETA_CYCLE_INTERVAL_MS);
    }

    private void stopEtaCycler() {
        mEtaCyclerRunning = false;
        mMonitorHandler.removeCallbacks(mEtaCycler);
    }

    /**
     * Pushes the requested time display to the HUD. Returns the send result.
     */
    private boolean pushTimeDisplay(int mode) {
        if (mode == ETA_MODE_ARRIVAL) {
            return pushArrivalTime();
        }
        return pushRemainingTime();
    }

    private boolean pushRemainingTime() {
        if (sHud == null || mRemainingMinutes == null) {
            return false;
        }
        int hh = null != mRemainingHours ? Integer.parseInt(mRemainingHours) : 0;
        int mm = Integer.parseInt(mRemainingMinutes);
        sHud.setRemainTime(hh, mm, mBusyTraffic);
        return sHud.getSendResult();
    }

    private boolean pushArrivalTime() {
        if (sHud == null || mArrivalHours == -1 || mArrivalMinutes == -1) {
            return false;
        }
        boolean sameAsLast = mArrivalHours == mLastArrivalHours
                && mArrivalMinutes == mLastArrivalMinutes;
        if (sameAsLast) {
            return false;
        }
        // Arrival clock: setTime renders a clock time (packet 0x05 clock variant).
        sHud.setTime(mArrivalHours, mArrivalMinutes, false, mBusyTraffic, true, false);
        mLastArrivalMinutes = mArrivalMinutes;
        mLastArrivalHours = mArrivalHours;
        return sHud.getSendResult();
    }

    private void sendCurrentArrow() {
        if (mArrowTypeV2) {
            updateArrow(mFoundArrowV2);
        } else {
            updateArrow(mFoundArrow);
        }
    }

    private boolean currentArrowChanged() {
        return mArrowTypeV2 ? mPreArrowV2 != mFoundArrowV2 : mPreArrowV1 != mFoundArrow;
    }

    private String currentArrowName() {
        return mArrowTypeV2 ? mFoundArrowV2.toString() : mFoundArrow.toString();
    }

    private void scheduleArrowRetry() {
        if (mArrowRetryScheduled) return;
        mArrowRetryScheduled = true;
        mMonitorHandler.postDelayed(mRetryArrowUpdate, ARROW_RETRY_DELAY_MS);
    }

    private void recordArrowSendResult(boolean sent) {
        if (sent) {
            mArrowRetryScheduled = false;
            mMonitorHandler.removeCallbacks(mRetryArrowUpdate);
        } else {
            scheduleArrowRetry();
        }
    }

    private final Runnable mRetryDistanceUpdate = () -> {
        mDistanceRetryScheduled = false;
        sHud = HudSessionManager.getInstance(this).getHud();
        boolean sent = sendCurrentDistance();
        logi("Distance retry " + mDistanceNum + "/" + mDistanceUnit
                + " sent: " + (sent ? '1' : '0'));
        if (!sent) scheduleDistanceRetry();
    };

    private boolean sendCurrentDistance() {
        if (sHud == null) return false;

        String distanceNum = mDistanceNum;
        String distanceUnit = mDistanceUnit;
        if (distanceNum != null && distanceUnit != null) {
            float distance = Float.parseFloat(distanceNum);
            if ((int) distance != -1) {
                sHud.setDistance(distance, get_eUnits(distanceUnit));
            } else {
                sHud.clearDistance();
            }
        } else {
            sHud.clearDistance();
        }
        return sHud.getSendResult();
    }

    private void scheduleDistanceRetry() {
        if (mDistanceRetryScheduled) return;
        mDistanceRetryScheduled = true;
        mMonitorHandler.postDelayed(mRetryDistanceUpdate, DISTANCE_RETRY_DELAY_MS);
    }

    private void recordDistanceSendResult(boolean sent) {
        if (sent) {
            mDistanceRetryScheduled = false;
            mMonitorHandler.removeCallbacks(mRetryDistanceUpdate);
        } else {
            scheduleDistanceRetry();
        }
    }

    private void updateHudInformation() {
        Log.i(TAG, "hud: " + sHud);

        // A maneuver change is more important than a distance tick. Send it before all other
        // fields so the final free Garmin packet slot cannot be consumed by the distance first.
        boolean arrowChanged = currentArrowChanged();
        boolean arrowSendResult = false;
        if (arrowChanged) {
            sendCurrentArrow();
            arrowSendResult = sHud != null && sHud.getSendResult();
            recordArrowSendResult(arrowSendResult);
        }

        //===================================================================================
        // distance
        //===================================================================================
        final boolean distanceSendResult = sendCurrentDistance();
        recordDistanceSendResult(distanceSendResult);
        //===================================================================================

        //===================================================================================
        // remaining distance
        //===================================================================================
        if (null != mRemainingDistance && null != mRemainingDistanceUnits) {
            float float_distance = Float.parseFloat(mRemainingDistance);
            int int_distance = (int) float_distance;
            eUnits units = get_eUnits(mRemainingDistanceUnits);

            if (null != sHud) {
                if (-1 != int_distance) {
                    sHud.setRemainingDistance(float_distance, units);
                } else {
                    sHud.clearRemainingDistance();
                }
            }
        } else {
            if (null != sHud) {
                sHud.clearRemainingDistance();
            }
        }

        final boolean remainingDistanceSendResult = null != sHud && sHud.getSendResult();
        //===================================================================================

        //===================================================================================
        // time
        //===================================================================================
        boolean timeSendResult = false;

        if (null != mRemainingMinutes) {
            switch (mEtaDisplayMode) {
                case ETA_MODE_REMAINING:
                    timeSendResult = pushRemainingTime();
                    break;
                case ETA_MODE_ARRIVAL:
                    timeSendResult = pushArrivalTime();
                    break;
                case ETA_MODE_CYCLE:
                    timeSendResult = pushTimeDisplay(
                            mEtaCycleShowRemaining ? ETA_MODE_REMAINING : ETA_MODE_ARRIVAL);
                    startEtaCycler();
                    break;
                case ETA_MODE_OFF:
                default:
                    break;
            }
        }
        //===================================================================================

        //===================================================================================
        // arrow
        // if same as last arrow, should be process, because GARMIN Hud will erase the arrow without data receive during sometime..
        //===================================================================================
        if (!arrowChanged) {
            sendCurrentArrow();
            arrowSendResult = sHud != null && sHud.getSendResult();
            recordArrowSendResult(arrowSendResult);
        }
        //===================================================================================

        String sendResultInfo = "SendResult dist: " + (distanceSendResult ? '1' : '0')
                + " remaining dist: " + (remainingDistanceSendResult ? '1' : '0')
                + " time: " + (timeSendResult ? '1' : '0')
                + " arrow: " + (arrowSendResult ? '1' : '0');
        logi(sendResultInfo);
    }

    private void parseTimeAndDistanceToDest(String timeDistanceStirng) {
        String[] timeDistanceSplit = timeDistanceStirng.split("·");
        String arrivalTime;
        mRemainingHours = mRemainingMinutes = mRemainingDistance = mRemainingDistanceUnits = null;

        if (3 == timeDistanceSplit.length) {
            String timeToDest = timeDistanceSplit[0].trim();
            String distanceToDest = timeDistanceSplit[1].trim();
            String timeToArrived = timeDistanceSplit[2].trim();

            //======================================================================================
            // remain time
            //======================================================================================
            String[] timeSplit = timeToDest.split(" ");
            if (4 == timeSplit.length) {
                mRemainingHours = timeSplit[0].trim();
                mRemainingMinutes = timeSplit[2].trim();
                // Remove spaces, .trim() seems not working
                mRemainingHours = mRemainingHours.replaceAll("\u00A0", "");
                mRemainingMinutes = mRemainingMinutes.replaceAll("\u00A0", "");
            } else if (2 == timeSplit.length) {
                final int hour_index = timeToDest.indexOf(getString(R.string.hour));
                final int minute_index = timeToDest.indexOf(getString(R.string.minute));
                if (-1 != hour_index && -1 != minute_index) {
                    mRemainingHours = timeToDest.substring(0, hour_index).trim();
                    mRemainingMinutes = timeToDest.substring(hour_index + getString(R.string.hour).length(), minute_index).trim();
                    // Remove spaces, .trim() seems not working
                    mRemainingHours = mRemainingHours.replaceAll("\u00A0", "");
                    mRemainingMinutes = mRemainingMinutes.replaceAll("\u00A0", "");
                } else if (-1 != hour_index && -1 == minute_index) {
                    mRemainingHours = timeSplit[0].trim();
                    mRemainingMinutes = "0";
                } else {
                    mRemainingMinutes = timeSplit[0].trim();
                    mRemainingMinutes = mRemainingMinutes.replaceAll("\u00A0", "");
                }
            } else if (1 == timeSplit.length) {
                timeSplit = splitDigitAndNonDigit(timeToDest);
                mRemainingMinutes = 2 == timeSplit.length ? timeSplit[0] : null;
            }
            //======================================================================================

            //======================================================================================
            // remaining distance
            //======================================================================================
            String[] distSplit = distanceToDest.split("\\s");
            if (2 != distSplit.length) {
                distSplit = splitDigitAndNonDigit(distanceToDest);
            }
            if (2 == distSplit.length) {
                // Remove spaces, .trim() doesn't work
                mRemainingDistance = distSplit[0].replaceAll("\u00A0", "");
                mRemainingDistance = mRemainingDistance.replace(",", ".");
                // Remove spaces
                mRemainingDistanceUnits = distSplit[1].replaceAll("\u00A0", "");
                mRemainingDistanceUnits = translate(mRemainingDistanceUnits);
            }
            //======================================================================================

            //======================================================================================
            //ETA
            //======================================================================================
            final String ETA = getString(R.string.ETA);
            final int indexOfETA = timeToArrived.indexOf(ETA);
            String[] arrivedSplit;
            final boolean etaAtFirst = 0 == indexOfETA;
            // Separate EAT-String from leftValue
            arrivedSplit = timeToArrived.split(ETA);
            if (etaAtFirst) {
                // ETA-String first, then leftValue (chinese)
                arrivalTime = 2 == arrivedSplit.length ? arrivedSplit[1] : null;
            } else {
                // ETA-leftValue first, then string (english)
                arrivalTime = arrivedSplit[0];
            }
            arrivalTime = null != arrivalTime ? arrivalTime.trim() : arrivalTime;

            final int amIndex = arrivalTime.indexOf(getString(R.string.am));
            final int pmIndex = arrivalTime.indexOf(getString(R.string.pm));
            final boolean ampmAtFirst = 0 == amIndex || 0 == pmIndex;

            if (-1 != amIndex || -1 != pmIndex) { // 12-hour-format
                final int index = Math.max(amIndex, pmIndex);  // index of "am" or "pm"
                arrivalTime = ampmAtFirst ? arrivalTime.substring(index + 2) : arrivalTime.substring(0, index);
                arrivalTime = arrivalTime.trim();

                String[] split = arrivalTime.split(":");
                int hh = Integer.parseInt(split[0]);
                if (-1 != pmIndex && 12 != hh) {
                    hh += 12;
                }
                mArrivalHours = hh;
                mArrivalMinutes = Integer.parseInt(split[1]);
            } else { // 24-hour-format
                arrivalTime = arrivalTime.trim();

                String[] split = arrivalTime.split(":");
                if (2 == split.length) {
                    try {
                        mArrivalHours = Integer.parseInt(split[0]);
                        mArrivalMinutes = Integer.parseInt(split[1]);
                    } catch (NumberFormatException ex) {
                        mArrivalHours = mArrivalMinutes = 0;
                    }
                }
            }
            //======================================================================================
        }
    }

    private static String[] splitDigitAndAlphabetic(String str) {
        String[] result = new String[2];
        for (int x = 0; x < str.length(); x++) {
            char c = str.charAt(x);
            if (Character.isAlphabetic(c)) {
                result[0] = str.substring(0, x).replaceAll("\u00A0", ""); // Remove spaces, .trim() doesn't work
                result[1] = str.substring(x).replaceAll("\u00A0", ""); // Remove spaces
                break;
            }
        }
        return result;
    }

    private static String[] splitDigitAndNonDigit(String str) {
        String[] result = new String[2];
        for (int x = 0; x < str.length(); x++) {
            char c = str.charAt(x);
            if (!Character.isDigit(c) && '.' != c) {
                result[0] = str.substring(0, x);
                result[1] = str.substring(x);
                break;
            }
        }
        return result;
    }

    private static boolean containsOnlyWhitespaces(String str) {
        for (int x = 0; x < str.length(); x++) {
            if (!Character.isWhitespace(str.charAt(x))) {
                return false;
            }
        }

        return true;
    }

    private void parseDistanceToTurn(String distanceString) {
        final int indexOfChineseAfter = distanceString.indexOf(getString(R.string.chinese_after));
        if (-1 != indexOfChineseAfter) {
            distanceString = distanceString.substring(0, indexOfChineseAfter);
        }
        String[] splitArray = distanceString.split(" ");
        if (2 != splitArray.length) {
            splitArray = splitDigitAndAlphabetic(distanceString);
        }
        String num = null;
        String unit = null;
        if (splitArray.length == 2) {
            num = splitArray[0].replace(",", ".");
            unit = splitArray[1];
        }

        if (null != unit) {
            unit = translate(unit);
        }
        mDistanceNum = num;
        mDistanceUnit = unit;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NavigationDiagnostics.recordNotification(this, "notification_posted", sbn);
        mMonitorHandler.post(() -> {
            updateCurrentNotifications();
            logi("onNotificationPosted...");
            logi("have " + sCurrentNotificationsCounts + " active notifications");
            sPostedNotification = sbn;
            if (isSupportedNavigation(sbn)) {
                sActiveNavigationPackage = sbn.getPackageName();
                mMonitorHandler.removeCallbacks(mStopNavigationSession);
                boolean sessionStartRequested = startNavigationSessionIfEnabled();
                if (sessionStartRequested) {
                    mMonitorHandler.postDelayed(() -> {
                        sHud = HudSessionManager.getInstance(this).getHud();
                        processNotification(sbn);
                    }, 500L);
                } else {
                    processNotification(sbn);
                }
            } else {
                processNotification(sbn);
            }
        });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NavigationDiagnostics.recordNotification(this, "notification_removed", sbn);
        mMonitorHandler.post(() -> {
            logi("removed...");
            logi("have " + sCurrentNotificationsCounts + " active notifications");
            sRemovedNotification = sbn;

            if (isSupportedNavigation(sbn)) {
                mMonitorHandler.removeCallbacks(mStopNavigationSession);
                mMonitorHandler.postDelayed(mStopNavigationSession, 3000L);
                return;
            }
        });
    }

    private void updateCurrentNotifications() {
        try {
            StatusBarNotification[] activeNos = getActiveNotifications();
            if (null == activeNos) {
                return;
            }
            if (sCurrentNotifications.size() == 0) {
                sCurrentNotifications.add(null);
            }
            sCurrentNotifications.set(0, activeNos);
            sCurrentNotificationsCounts = activeNos.length;
        } catch (Exception e) {
            Log.e(TAG, "Should not be here!!");
            e.printStackTrace();
        }
    }
}
