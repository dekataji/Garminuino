package sky4s.garminhud.app;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Local, opt-in recorder for reverse engineering navigation notifications.
 * No diagnostic data is sent over the network; the user explicitly exports a ZIP file.
 */
public final class NavigationDiagnostics {
    private static final String TAG = "NavigationDiagnostics";
    private static final String PREFS_NAME = "MainActivity";
    private static final String DIAGNOSTICS_DIR = "navigation-diagnostics";
    private static final String EXPORT_DIR = "diagnostic-exports";
    private static final int MAX_TEXT_LENGTH = 16_384;
    private static final int MAX_BUNDLE_DEPTH = 5;

    private static final Set<String> SUPPORTED_PACKAGES = new HashSet<>(Arrays.asList(
            NotificationMonitor.GOOGLE_MAPS_PACKAGE_NAME,
            NotificationMonitor.GOOGLE_MAPS_GO_PACKAGE_NAME,
            NotificationMonitor.SYGIC_PACKAGE_NAME,
            NotificationMonitor.AUTOMAPA_PACKAGE_NAME
    ));

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "navigation-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Object FILE_LOCK = new Object();
    private static File sSessionDirectory;

    private NavigationDiagnostics() {
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(context.getString(R.string.option_navigation_diagnostics), false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (enabled) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(context.getString(R.string.option_navigation_diagnostics), true)
                    .apply();
            startNewSession(context);
            recordLifecycle(context, "diagnostics_enabled");
        } else {
            recordLifecycle(context, "diagnostics_disabled");
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(context.getString(R.string.option_navigation_diagnostics), false)
                    .apply();
        }
    }

    public static void startNewSession(Context context) {
        Context appContext = context.getApplicationContext();
        WRITER.execute(() -> {
            synchronized (FILE_LOCK) {
                sSessionDirectory = null;
                try {
                    ensureSessionDirectory(appContext);
                } catch (IOException | JSONException exception) {
                    Log.e(TAG, "Cannot start diagnostic session", exception);
                }
            }
        });
    }

    public static void recordLifecycle(Context context, String event) {
        if (!isEnabled(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WRITER.execute(() -> {
            try {
                JSONObject object = baseEvent(event);
                synchronized (FILE_LOCK) {
                    appendEvent(ensureSessionDirectory(appContext), object);
                }
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Cannot store diagnostic lifecycle event", exception);
            }
        });
    }

    public static void recordNotification(Context context, String event,
                                          StatusBarNotification statusBarNotification) {
        if (!isEnabled(context) || statusBarNotification == null
                || !SUPPORTED_PACKAGES.contains(statusBarNotification.getPackageName())) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WRITER.execute(() -> writeNotification(appContext, event, statusBarNotification));
    }

    private static void writeNotification(Context context, String event,
                                          StatusBarNotification statusBarNotification) {
        try {
            long sequence = SEQUENCE.incrementAndGet();
            JSONObject root = baseEvent(event);
            root.put("sequence", sequence);
            root.put("package", statusBarNotification.getPackageName());
            root.put("sourceApp", packageInfo(context, statusBarNotification.getPackageName()));
            root.put("notificationKey", valueOrNull(statusBarNotification.getKey()));
            root.put("id", statusBarNotification.getId());
            root.put("tag", valueOrNull(statusBarNotification.getTag()));
            root.put("postTime", statusBarNotification.getPostTime());
            root.put("groupKey", valueOrNull(statusBarNotification.getGroupKey()));
            root.put("overrideGroupKey", valueOrNull(statusBarNotification.getOverrideGroupKey()));
            root.put("ongoing", statusBarNotification.isOngoing());
            root.put("clearable", statusBarNotification.isClearable());

            Notification notification = statusBarNotification.getNotification();
            if (notification != null) {
                JSONObject notificationJson = notificationToJson(notification);
                synchronized (FILE_LOCK) {
                    File sessionDirectory = ensureSessionDirectory(context);
                    JSONObject icons = new JSONObject();
                    saveIcon(context, sessionDirectory, notification.getSmallIcon(),
                            sequence + "_small.png", icons, "small");
                    saveIcon(context, sessionDirectory, notification.getLargeIcon(),
                            sequence + "_large.png", icons, "large");
                    notificationJson.put("savedIcons", icons);
                    root.put("notification", notificationJson);
                    appendEvent(sessionDirectory, root);
                }
            } else {
                root.put("notification", JSONObject.NULL);
                synchronized (FILE_LOCK) {
                    appendEvent(ensureSessionDirectory(context), root);
                }
            }
        } catch (Exception exception) {
            Log.e(TAG, "Cannot store diagnostic notification", exception);
        }
    }

    private static JSONObject notificationToJson(Notification notification) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("when", notification.when);
        object.put("flags", notification.flags);
        object.put("defaults", notification.defaults);
        object.put("priority", notification.priority);
        object.put("visibility", notification.visibility);
        object.put("color", notification.color);
        object.put("category", valueOrNull(notification.category));
        object.put("group", valueOrNull(notification.getGroup()));
        object.put("sortKey", valueOrNull(notification.getSortKey()));
        object.put("localOnly", (notification.flags & Notification.FLAG_LOCAL_ONLY) != 0);
        object.put("groupSummary", (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        object.put("ongoing", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        object.put("channelId", valueOrNull(notification.getChannelId()));
        object.put("timeoutAfter", notification.getTimeoutAfter());
        object.put("badgeIconType", notification.getBadgeIconType());
        object.put("groupAlertBehavior", notification.getGroupAlertBehavior());
        object.put("shortcutId", valueOrNull(notification.getShortcutId()));
        object.put("extras", bundleToJson(notification.extras, 0));
        object.put("contentView", remoteViewsToJson(notification.contentView));
        object.put("bigContentView", remoteViewsToJson(notification.bigContentView));
        object.put("headsUpContentView", remoteViewsToJson(notification.headsUpContentView));
        object.put("actions", actionsToJson(notification.actions));
        return object;
    }

    private static Object remoteViewsToJson(RemoteViews remoteViews) throws JSONException {
        if (remoteViews == null) {
            return JSONObject.NULL;
        }
        JSONObject object = new JSONObject();
        object.put("package", valueOrNull(remoteViews.getPackage()));
        object.put("layoutId", remoteViews.getLayoutId());
        object.put("class", remoteViews.getClass().getName());
        return object;
    }

    private static JSONArray actionsToJson(Notification.Action[] actions) throws JSONException {
        JSONArray result = new JSONArray();
        if (actions == null) {
            return result;
        }
        for (Notification.Action action : actions) {
            if (action == null) {
                result.put(JSONObject.NULL);
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("title", valueOrNull(action.title));
            item.put("allowGeneratedReplies", action.getAllowGeneratedReplies());
            item.put("semanticAction", action.getSemanticAction());
            item.put("contextual", action.isContextual());
            JSONArray inputs = new JSONArray();
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null) {
                for (RemoteInput input : remoteInputs) {
                    JSONObject inputJson = new JSONObject();
                    inputJson.put("resultKey", input.getResultKey());
                    inputJson.put("label", valueOrNull(input.getLabel()));
                    inputJson.put("choices", safeValue(input.getChoices(), 0));
                    inputJson.put("allowFreeForm", input.getAllowFreeFormInput());
                    inputs.put(inputJson);
                }
            }
            item.put("remoteInputs", inputs);
            result.put(item);
        }
        return result;
    }

    private static JSONObject bundleToJson(Bundle bundle, int depth) throws JSONException {
        JSONObject result = new JSONObject();
        if (bundle == null) {
            return result;
        }
        String[] keys = bundle.keySet().toArray(new String[0]);
        Arrays.sort(keys);
        for (String key : keys) {
            try {
                result.put(key, safeValue(bundle.get(key), depth + 1));
            } catch (RuntimeException exception) {
                result.put(key, typedFallback(null, "unreadable: " + exception.getClass().getSimpleName()));
            }
        }
        return result;
    }

    private static Object safeValue(Object value, int depth) throws JSONException {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (depth > MAX_BUNDLE_DEPTH) {
            return typedFallback(value, "maximum nesting depth reached");
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return truncate(value.toString());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Bundle) {
            return bundleToJson((Bundle) value, depth);
        }
        if (value instanceof Person) {
            Person person = (Person) value;
            JSONObject object = new JSONObject();
            object.put("type", value.getClass().getName());
            object.put("name", valueOrNull(person.getName()));
            object.put("key", valueOrNull(person.getKey()));
            object.put("uri", valueOrNull(person.getUri()));
            object.put("bot", person.isBot());
            object.put("important", person.isImportant());
            return object;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            JSONArray array = new JSONArray();
            int length = Math.min(Array.getLength(value), 200);
            for (int index = 0; index < length; index++) {
                array.put(safeValue(Array.get(value, index), depth + 1));
            }
            if (Array.getLength(value) > length) {
                array.put("… " + (Array.getLength(value) - length) + " more values");
            }
            return array;
        }
        if (value instanceof Iterable) {
            JSONArray array = new JSONArray();
            int count = 0;
            for (Object element : (Iterable<?>) value) {
                if (count++ >= 200) {
                    array.put("… more values");
                    break;
                }
                array.put(safeValue(element, depth + 1));
            }
            return array;
        }
        if (value instanceof Bitmap) {
            Bitmap bitmap = (Bitmap) value;
            return typedFallback(value, bitmap.getWidth() + "x" + bitmap.getHeight());
        }
        if (value instanceof Icon || value instanceof Parcelable) {
            return typedFallback(value, truncate(String.valueOf(value)));
        }
        return typedFallback(value, truncate(String.valueOf(value)));
    }

    private static JSONObject typedFallback(Object value, String description) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", value == null ? JSONObject.NULL : value.getClass().getName());
        object.put("value", description);
        return object;
    }

    private static void saveIcon(Context context, File sessionDirectory, Icon icon,
                                 String fileName, JSONObject result, String key) throws JSONException {
        if (icon == null) {
            result.put(key, JSONObject.NULL);
            return;
        }
        try {
            Drawable drawable = icon.loadDrawable(context);
            if (drawable == null) {
                result.put(key, typedFallback(icon, "drawable unavailable"));
                return;
            }
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            File iconDirectory = new File(sessionDirectory, "icons");
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) {
                throw new IOException("Cannot create icon directory");
            }
            File iconFile = new File(iconDirectory, fileName);
            try (FileOutputStream stream = new FileOutputStream(iconFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
            result.put(key, "icons/" + fileName);
        } catch (Exception exception) {
            result.put(key, typedFallback(icon, "save failed: " + exception.getClass().getSimpleName()));
        }
    }

    private static JSONObject baseEvent(String event) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schemaVersion", 1);
        object.put("event", event);
        object.put("timestamp", isoTimestamp(System.currentTimeMillis()));
        object.put("timestampMillis", System.currentTimeMillis());
        return object;
    }

    private static File ensureSessionDirectory(Context context) throws IOException, JSONException {
        if (sSessionDirectory != null && sSessionDirectory.isDirectory()) {
            return sSessionDirectory;
        }
        File root = diagnosticsRoot(context);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create diagnostics directory");
        }
        String sessionName = "session-" + fileTimestamp(System.currentTimeMillis());
        File session = new File(root, sessionName);
        int duplicate = 1;
        while (session.exists()) {
            session = new File(root, sessionName + "-" + duplicate++);
        }
        if (!session.mkdirs()) {
            throw new IOException("Cannot create diagnostic session");
        }
        sSessionDirectory = session;
        writeMetadata(context, session);
        return session;
    }

    private static void writeMetadata(Context context, File session) throws IOException, JSONException {
        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", 1);
        metadata.put("createdAt", isoTimestamp(System.currentTimeMillis()));
        metadata.put("warning", "Contains raw notification text and may include route, street and destination data.");
        metadata.put("localOnly", true);
        metadata.put("androidSdk", Build.VERSION.SDK_INT);
        metadata.put("androidRelease", Build.VERSION.RELEASE);
        metadata.put("manufacturer", Build.MANUFACTURER);
        metadata.put("model", Build.MODEL);
        metadata.put("locale", Locale.getDefault().toLanguageTag());
        metadata.put("recorderApp", packageInfo(context, context.getPackageName()));
        metadata.put("capturedPackages", new JSONArray(SUPPORTED_PACKAGES));
        File metadataFile = new File(session, "metadata.json");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(metadataFile), StandardCharsets.UTF_8))) {
            writer.write(metadata.toString(2));
        }
    }

    private static JSONObject packageInfo(Context context, String packageName) throws JSONException {
        JSONObject result = new JSONObject();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(0));
            result.put("versionName", valueOrNull(info.versionName));
            result.put("versionCode", info.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException exception) {
            result.put("unavailable", true);
        }
        return result;
    }

    private static void appendEvent(File sessionDirectory, JSONObject object) throws IOException {
        File eventsFile = new File(sessionDirectory, "events.jsonl");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(eventsFile, true), StandardCharsets.UTF_8))) {
            writer.write(object.toString());
            writer.newLine();
        }
    }

    public static File createExportArchive(Context context) throws IOException {
        awaitPendingWrites();
        synchronized (FILE_LOCK) {
            File source = diagnosticsRoot(context);
            File[] sessions = source.listFiles(File::isDirectory);
            if (sessions == null || sessions.length == 0) {
                return null;
            }
            File exportDirectory = new File(context.getCacheDir(), EXPORT_DIR);
            if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                throw new IOException("Cannot create export directory");
            }
            File archive = new File(exportDirectory,
                    "navigation-diagnostics-" + fileTimestamp(System.currentTimeMillis()) + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                    new FileOutputStream(archive)))) {
                byte[] buffer = new byte[16_384];
                for (File session : sessions) {
                    addToZip(zip, session, source, buffer);
                }
            }
            return archive;
        }
    }

    private static void addToZip(ZipOutputStream zip, File file, File base, byte[] buffer)
            throws IOException {
        String entryName = base.toURI().relativize(file.toURI()).getPath();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zip, child, base, buffer);
                }
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    public static void clear(Context context) throws IOException {
        awaitPendingWrites();
        synchronized (FILE_LOCK) {
            sSessionDirectory = null;
            deleteChildren(diagnosticsRoot(context));
            deleteChildren(new File(context.getCacheDir(), EXPORT_DIR));
        }
    }

    private static void deleteChildren(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Cannot list " + directory);
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteChildren(child);
            }
            if (!child.delete()) {
                throw new IOException("Cannot delete " + child);
            }
        }
    }

    private static void awaitPendingWrites() throws IOException {
        try {
            WRITER.submit(() -> { }).get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IOException("Timed out while flushing diagnostic data", exception);
        }
    }

    private static File diagnosticsRoot(Context context) {
        return new File(context.getFilesDir(), DIAGNOSTICS_DIR);
    }

    private static Object valueOrNull(Object value) {
        return value == null ? JSONObject.NULL : truncate(String.valueOf(value));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "…[truncated]";
    }

    private static String isoTimestamp(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        return format.format(new Date(timestamp));
    }

    private static String fileTimestamp(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(timestamp));
    }
}
