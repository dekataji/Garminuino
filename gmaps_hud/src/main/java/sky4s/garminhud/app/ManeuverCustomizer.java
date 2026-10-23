package sky4s.garminhud.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;
import sky4s.garminhud.hud.HUDInterface;

/**
 * Per-maneuver HUD mapping overrides, stored in SharedPreferences as JSON.
 *
 * For any maneuver the user can override:
 *  - arrow angle (any eOutAngle)
 *  - arrow type (any eOutType)
 *  - custom 4-char text glyph shown via setAlphabet (empty = none)
 *
 * NotificationMonitor checks getOverride() before its default switch, so an
 * override fully replaces the built-in mapping for that maneuver.
 */
public final class ManeuverCustomizer {

    private static final String PREF_KEY = "custom_maneuver_map";

    private ManeuverCustomizer() {
    }

    /** A single maneuver override. */
    public static final class Override {
        public final eOutAngle angle;
        public final eOutType type;
        public final String text;
        /** Lane pattern bitmask, or -1 when no custom pattern. */
        public final int laneMask;

        Override(eOutAngle angle, eOutType type, String text, int laneMask) {
            this.angle = angle;
            this.type = type;
            this.text = text;
            this.laneMask = laneMask;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(
                MainActivity.class.getSimpleName(), Context.MODE_PRIVATE);
    }

    private static JSONObject loadAll(Context context) {
        try {
            String raw = prefs(context).getString(PREF_KEY, "{}");
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void storeAll(Context context, JSONObject all) {
        prefs(context).edit().putString(PREF_KEY, all.toString()).apply();
    }

    /** Returns the override for a maneuver, or null when it uses the default mapping. */
    public static Override getOverride(Context context, String maneuver) {
        try {
            JSONObject all = loadAll(context);
            if (!all.has(maneuver)) {
                return null;
            }
            JSONObject o = all.getJSONObject(maneuver);
            eOutAngle angle = eOutAngle.valueOf(o.optString("angle", "Straight"));
            eOutType type = eOutType.valueOf(o.optString("type", "Lane"));
            String text = o.optString("text", "");
            int laneMask = o.optInt("laneMask", -1);
            return new Override(angle, type, text, laneMask);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves (or replaces) the override for a maneuver. */
    public static void saveOverride(Context context, String maneuver,
                                    String angleName, String typeName, String text,
                                    int laneMask) {
        try {
            JSONObject all = loadAll(context);
            JSONObject o = new JSONObject();
            o.put("angle", angleName);
            o.put("type", typeName);
            o.put("text", text == null ? "" : text);
            o.put("laneMask", laneMask);
            all.put(maneuver, o);
            storeAll(context, all);
        } catch (Exception ignored) {
        }
    }

    /** Saves (or replaces) the override for a maneuver (no lane pattern). */
    public static void saveOverride(Context context, String maneuver,
                                    String angleName, String typeName, String text) {
        saveOverride(context, maneuver, angleName, typeName, text, -1);
    }

    /** Removes the override for one maneuver, restoring its default mapping. */
    public static void clearOverride(Context context, String maneuver) {
        JSONObject all = loadAll(context);
        all.remove(maneuver);
        storeAll(context, all);
    }

    /** Removes all overrides. */
    public static void clearAll(Context context) {
        storeAll(context, new JSONObject());
    }

    /** True when at least one maneuver has an override. */
    public static boolean hasOverrides(Context context) {
        return loadAll(context).length() > 0;
    }

    /** Returns all overrides as a JSON string for export. */
    public static String exportJson(Context context) {
        return loadAll(context).toString();
    }

    /**
     * Replaces all overrides from an exported JSON string.
     * Returns false when the input is not a valid overrides object.
     */
    public static boolean importJson(Context context, String json) {
        try {
            JSONObject all = new JSONObject(json);
            // validate entries before storing
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                JSONObject o = all.getJSONObject(keys.next());
                eOutAngle.valueOf(o.getString("angle"));
                eOutType.valueOf(o.getString("type"));
                o.optString("text", "");
                o.optInt("laneMask", -1);
            }
            storeAll(context, all);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Applies the override for a maneuver to the HUD. Returns true when an
     * override was applied (caller should skip the default mapping).
     */
    public static boolean applyOverride(Context context, String maneuver, HUDInterface hud) {
        Override ov = getOverride(context, maneuver);
        if (ov == null || hud == null) {
            return false;
        }
        hud.setDirection(ov.angle, ov.type, eOutAngle.AsDirection);
        char[] t = new char[]{0, 0, 0, 0};
        if (ov.text != null) {
            for (int i = 0; i < Math.min(4, ov.text.length()); i++) {
                t[i] = ov.text.charAt(i);
            }
        }
        hud.setAlphabet(t[0], t[1], t[2], t[3]);
        if (ov.laneMask >= 0) {
            hud.setLanes((char) ov.laneMask, (char) 0);
        }
        return true;
    }
}
