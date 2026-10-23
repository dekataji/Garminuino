package sky4s.garminhud.app;

/**
 * Single source of truth for the Google Maps maneuver → Garmin HUD direction
 * mapping. Mirrors the switch statements in
 * NotificationMonitor.updateArrow(Arrow) and updateArrow(ArrowV2).
 *
 * Each row: { maneuver name, Google Maps meaning, HUD display }.
 * Shown in the settings "Direction icons" panel via buildDisplayText().
 */
public final class ManeuverIconMap {

    private ManeuverIconMap() {
    }

    private static final String[][] ROWS = {
            {"Arrivals", "Destination reached", "Straight arrow + arrival flag"},
            {"ArrivalsLeft", "Destination on the left", "Left arrow (plain)"},
            {"ArrivalsRight", "Destination on the right", "Right arrow (plain)"},
            {"Convergence", "Merge / lanes converge", "Direction cleared"},
            {"EasyLeft", "Slight left", "Slight-left arrow"},
            {"EasyRight", "Slight right", "Slight-right arrow"},
            {"GoTo", "Head / continue", "Straight arrow"},
            {"KeepLeft", "Keep left", "Slight-left arrow"},
            {"KeepRight", "Keep right", "Slight-right arrow"},
            {"LeaveRoundabout*", "Roundabout exit (16 variants:\nstraight/left/right/sharp, clockwise +\ncounter-clockwise)", "Roundabout icon + exit-spoke arrow"},
            {"Left", "Turn left", "Left arrow"},
            {"LeftDown", "U-turn", "U-turn arrow"},
            {"LeftToLeave", "Take exit on the left", "Slight-left arrow + lane"},
            {"Right", "Turn right", "Right arrow"},
            {"RightDown", "Bear right-down (rare)", "Right-down arrow"},
            {"RightToLeave", "Take exit on the right", "Slight-right arrow + lane"},
            {"SharpLeft", "Sharp left", "Sharp-left arrow"},
            {"SharpRight", "Sharp right", "Sharp-right arrow"},
            {"Straight", "Go straight", "Straight arrow"},
            {"None", "No instruction", "Direction cleared"},
    };

    /** Number of mapping rows. */
    public static int rowCount() {
        return ROWS.length;
    }

    /** Maneuver names available for customization (matches ROWS order). */
    public static String[] maneuverNames() {
        String[] names = new String[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            names[i] = ROWS[i][0];
        }
        return names;
    }

    /**
     * Default HUD angle/type per maneuver, as enum names. Mirrors the
     * NotificationMonitor.updateArrow switch statements. Used to pre-fill the
     * customization editor; the live behavior stays in NotificationMonitor.
     */
    public static String[] defaultAngleType(String maneuver) {
        switch (maneuver) {
            case "Arrivals": return new String[]{"Straight", "RightFlag"};
            case "ArrivalsLeft": return new String[]{"Left", "ArrowOnly"};
            case "ArrivalsRight": return new String[]{"Right", "ArrowOnly"};
            case "Convergence": return new String[]{"AsDirection", "Off"};
            case "EasyLeft":
            case "KeepLeft": return new String[]{"EasyLeft", "Lane"};
            case "EasyRight":
            case "KeepRight": return new String[]{"EasyRight", "Lane"};
            case "GoTo":
            case "Straight": return new String[]{"Straight", "Lane"};
            case "LeaveRoundabout*": return new String[]{"Straight", "LeftRoundabout"};
            case "Left": return new String[]{"Left", "Lane"};
            case "LeftDown": return new String[]{"LeftDown", "Lane"};
            case "LeftToLeave": return new String[]{"EasyLeft", "LongerLane"};
            case "Right": return new String[]{"Right", "Lane"};
            case "RightDown": return new String[]{"RightDown", "Lane"};
            case "RightToLeave": return new String[]{"EasyRight", "LongerLane"};
            case "SharpLeft": return new String[]{"SharpLeft", "Lane"};
            case "SharpRight": return new String[]{"SharpRight", "Lane"};
            case "None":
            default: return new String[]{"AsDirection", "Off"};
        }
    }

    /** Builds the human-readable mapping table for the settings panel. */
    public static String buildDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Google Maps maneuver → Garmin HUD\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        for (String[] row : ROWS) {
            sb.append("◆ ").append(row[0]).append('\n');
            sb.append("  Maps: ").append(row[1]).append('\n');
            sb.append("  HUD:  ").append(row[2]).append("\n\n");
        }
        return sb.toString();
    }
}
