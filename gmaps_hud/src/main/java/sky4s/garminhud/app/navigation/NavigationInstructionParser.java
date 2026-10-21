package sky4s.garminhud.app.navigation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sky4s.garminhud.Arrow;
import sky4s.garminhud.eLane;

/**
 * Parses the public text fields exposed by navigation notifications.  This deliberately does not
 * depend on private RemoteViews internals, which are blocked on current Android releases.
 */
public final class NavigationInstructionParser {
    private static final Pattern DISTANCE = Pattern.compile(
            "(?iu)(?:^|\\bza\\s+)(\\d+(?:[\\.,]\\d+)?)\\s*(km|m|mi|ft|metr(?:y|ow|ów|a)?)\\b");
    private static final Pattern LEADING_DISTANCE = Pattern.compile(
            "(?iu)^\\s*(\\d+(?:[\\.,]\\d+)?)\\s*(km|m|mi|ft|metr(?:y|ow|ów|a)?)\\s*(?:[·•\\-–—]|$)");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    // Google Maps merges two close maneuvers into one notification, e.g.
    // "Turn left onto Main St, then turn right onto Oak Ave".
    private static final Pattern THEN_CLAUSE = Pattern.compile("(?iu)(?:,|;)?\\s+then\\s+(.+?)\\s*$");
    // Lane hints embedded in the instruction, e.g. "Use the left 2 lanes to turn left",
    // "Stay in the right lane to take the exit".
    private static final Pattern LANE_HINT = Pattern.compile(
            "(?iu)\\b(?:use|stay in|keep to)\\s+the\\s+(left|right|middle)\\s+(\\d+)?\\s*lanes?\\b");
    // Roundabout exit numbers, e.g. "take the 2nd exit" / "take 2nd exit" (EN),
    // "zjedź 3. zjazdem" / "zjazd nr 3" (PL).
    private static final Pattern ROUNDABOUT_EXIT_EN = Pattern.compile(
            "(?iu)\\btake\\s+(?:the\\s+)?(\\d+)(?:st|nd|rd|th)?\\s+exit\\b");
    private static final Pattern ROUNDABOUT_EXIT_PL = Pattern.compile(
            "(?iu)(?:(\\d+)\\.\\s*zjazd|zjazd\\s*(?:nr\\s*\\.?\\s*)?(\\d+))");

    private NavigationInstructionParser() {
    }

    public static Result parseGoogle(String title, String primaryInfo, String etaText,
                                     int progress, int progressMax, int nowHour, int nowMinute) {
        String safeTitle = clean(title);
        String instruction = stripLeadingDistance(safeTitle);
        Distance distance = parseDistance(primaryInfo);
        if (distance == null) {
            distance = parseDistance(safeTitle);
        }

        // Split a compound "X, then Y" instruction so the current maneuver is parsed
        // from X only; Y becomes the next-turn preview.
        String[] thenSplit = splitThenClause(instruction);
        String currentInstruction = thenSplit[0];
        String thenClause = thenSplit[1];

        Clock eta = parseClock(etaText);
        Integer remainingMinutes = eta == null
                ? null : minutesUntil(nowHour, nowMinute, eta.hour, eta.minute);
        Integer remainingDistanceMetres = progressMax > 0
                ? Math.max(0, progressMax - Math.max(0, progress)) : null;

        Arrow maneuver = parseManeuver(currentInstruction);
        Arrow nextManeuver = thenClause == null ? Arrow.None : parseManeuver(thenClause);
        if (nextManeuver == maneuver) {
            nextManeuver = Arrow.None;
        }

        return new Result(
                instruction,
                distance,
                maneuver,
                nextManeuver,
                parseLaneGuidance(currentInstruction),
                eta,
                remainingMinutes,
                remainingDistanceMetres,
                isRerouting(instruction),
                parseRoundaboutExit(currentInstruction));
    }

    public static Result parseAutoMapa(String title) {
        String safeTitle = clean(title);
        String[] thenSplit = splitThenClause(stripAutoMapaDistance(safeTitle));
        Arrow maneuver = parseManeuver(thenSplit[0]);
        Arrow nextManeuver = thenSplit[1] == null ? Arrow.None : parseManeuver(thenSplit[1]);
        if (nextManeuver == maneuver) {
            nextManeuver = Arrow.None;
        }
        return new Result(
                stripAutoMapaDistance(safeTitle),
                parseDistance(safeTitle),
                maneuver,
                nextManeuver,
                parseLaneGuidance(thenSplit[0]),
                null,
                null,
                null,
                isRerouting(safeTitle),
                parseRoundaboutExit(thenSplit[0]));
    }

    /**
     * Extracts the roundabout exit number (1..8) from the current-maneuver text,
     * e.g. "take the 2nd exit" or "zjedź 3. zjazdem". Returns null when the
     * instruction names no exit.
     */
    public static Integer parseRoundaboutExit(String instruction) {
        String value = clean(instruction);
        if (value.isEmpty()) {
            return null;
        }
        Matcher en = ROUNDABOUT_EXIT_EN.matcher(value);
        if (en.find()) {
            return clampExitNumber(en.group(1));
        }
        Matcher pl = ROUNDABOUT_EXIT_PL.matcher(fold(value));
        if (pl.find()) {
            String digits = pl.group(1) != null ? pl.group(1) : pl.group(2);
            return clampExitNumber(digits);
        }
        return null;
    }

    private static Integer clampExitNumber(String digits) {
        try {
            int exit = Integer.parseInt(digits);
            return (exit >= 1 && exit <= 8) ? exit : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Splits "X, then Y" into {"X", "Y"}. Returns {instruction, null} when there is no
     * "then" clause.
     */
    static String[] splitThenClause(String instruction) {
        Matcher matcher = THEN_CLAUSE.matcher(clean(instruction));
        if (!matcher.find()) {
            return new String[]{instruction, null};
        }
        String head = instruction.substring(0, matcher.start()).trim();
        String tail = matcher.group(1).trim();
        if (head.isEmpty() || tail.isEmpty()) {
            return new String[]{instruction, null};
        }
        return new String[]{head, tail};
    }

    /**
     * Extracts lane guidance from hints like "Use the left 2 lanes to ...".
     * Returns null when the instruction carries no usable lane hint.
     */
    public static LaneGuidance parseLaneGuidance(String instruction) {
        Matcher matcher = LANE_HINT.matcher(fold(instruction));
        if (!matcher.find()) {
            return null;
        }
        String side = matcher.group(1).toLowerCase(Locale.ROOT);
        String countGroup = matcher.group(2);
        int recommendedCount = countGroup == null ? 1 : safeParseInt(countGroup, 1);
        if (recommendedCount < 1 || recommendedCount > 6) {
            return null;
        }
        // Without a total lane count in the text, model the road as exactly the
        // recommended lanes; the outline then matches the recommendation.
        int totalLanes = recommendedCount;
        int firstRecommended;
        if ("left".equals(side)) {
            firstRecommended = 0;
        } else if ("right".equals(side)) {
            firstRecommended = totalLanes - recommendedCount;
        } else {
            // "middle": center the recommendation.
            firstRecommended = Math.max(0, (totalLanes - recommendedCount) / 2);
        }
        int outlineMask = 0;
        int arrowMask = 0;
        for (int i = 0; i < totalLanes; i++) {
            int bit = laneBit(i, totalLanes);
            outlineMask |= bit;
            if (i >= firstRecommended && i < firstRecommended + recommendedCount) {
                arrowMask |= bit;
            }
        }
        if (outlineMask == 0 || arrowMask == 0) {
            return null;
        }
        return new LaneGuidance(outlineMask, arrowMask);
    }

    /**
     * Maps a 0-based lane index (from the left) to an eLane bit, using the same
     * left-to-right, centered convention as the screen-based lane detectors.
     */
    static int laneBit(int indexFromLeft, int totalLanes) {
        int start = totalLanes >= 5 ? 0 : totalLanes >= 3 ? 1 : 2;
        int x = start + indexFromLeft;
        if (x < 0 || x > 6) {
            return 0;
        }
        return eLane.OuterLeft.value >> x;
    }

    private static int safeParseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static Arrow parseManeuver(String instruction) {
        String value = fold(instruction);
        if (value.isEmpty()) {
            return Arrow.None;
        }

        if (containsAny(value, "do celu", "dotar", "docelow", "destination", "arrive", "arrival")) {
            if (containsAny(value, "po lewej", "z lewej", "destination left")) {
                return Arrow.ArrivalsLeft;
            }
            if (containsAny(value, "po prawej", "z prawej", "destination right")) {
                return Arrow.ArrivalsRight;
            }
            return Arrow.Arrivals;
        }

        if (containsAny(value, "zawroc", "u-turn", "u turn")) {
            return Arrow.LeftDown;
        }

        boolean roundabout = containsAny(value, "rond", "roundabout");
        boolean left = containsAny(value, "w lewo", "lewej", "left");
        boolean right = containsAny(value, "w prawo", "prawej", "right");
        boolean slight = containsAny(value, "lekko", "lagod", "slight", "easy");
        boolean sharp = containsAny(value, "ostro", "sharp");

        if (roundabout) {
            if (sharp && left) return Arrow.LeaveRoundaboutSharpLeft;
            if (sharp && right) return Arrow.LeaveRoundaboutSharpRight;
            if (slight && left) return Arrow.LeaveRoundaboutEasyLeft;
            if (slight && right) return Arrow.LeaveRoundaboutEasyRight;
            if (left) return Arrow.LeaveRoundaboutLeft;
            if (right) return Arrow.LeaveRoundaboutRight;
            if (containsAny(value, "zjedz z ronda", "exit the roundabout")) {
                return Arrow.LeaveRoundabout;
            }
            return Arrow.LeaveRoundaboutStraight;
        }

        if (containsAny(value, "trzymaj sie lewej", "trzymaj sie lewego", "keep left", "fork left")) {
            return Arrow.KeepLeft;
        }
        if (containsAny(value, "trzymaj sie prawej", "trzymaj sie prawego", "keep right", "fork right")) {
            return Arrow.KeepRight;
        }

        boolean rampOrExit = containsAny(value, "zjedz", "zjazd", "ramp", "exit");
        if (rampOrExit && left) return Arrow.LeftToLeave;
        if (rampOrExit && right) return Arrow.RightToLeave;
        if (sharp && left) return Arrow.SharpLeft;
        if (sharp && right) return Arrow.SharpRight;
        if (slight && left) return Arrow.EasyLeft;
        if (slight && right) return Arrow.EasyRight;
        if (left) return Arrow.Left;
        if (right) return Arrow.Right;
        if (containsAny(value, "prosto", "straight", "kieruj sie", "head ", "continue")) {
            return Arrow.Straight;
        }
        return Arrow.None;
    }

    private static boolean isRerouting(String instruction) {
        String value = fold(instruction);
        return containsAny(value, "zmieniam trase", "wyznaczam trase", "rerouting", "recalculating");
    }

    private static Distance parseDistance(String text) {
        String value = clean(text);
        if (value.isEmpty()) {
            return null;
        }
        Matcher matcher = LEADING_DISTANCE.matcher(value);
        if (!matcher.find()) {
            matcher = DISTANCE.matcher(value);
            if (!matcher.find()) {
                return null;
            }
        }
        try {
            return new Distance(
                    Float.parseFloat(matcher.group(1).replace(',', '.')),
                    normalizeUnit(matcher.group(2)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stripLeadingDistance(String title) {
        if (title == null) return "";
        Matcher matcher = LEADING_DISTANCE.matcher(title);
        if (!matcher.find()) return title.trim();
        String remainder = title.substring(matcher.end()).trim();
        return remainder.isEmpty() ? title.trim() : remainder;
    }

    private static String stripAutoMapaDistance(String title) {
        if (title == null) return "";
        return title.replaceFirst(
                "(?iu)^\\s*za\\s+\\d+(?:[\\.,]\\d+)?\\s*(?:km|m|mi|ft|metr(?:y|ow|ów|a)?)\\s*",
                "").trim();
    }

    private static Clock parseClock(String text) {
        Matcher matcher = CLOCK.matcher(clean(text));
        if (!matcher.find()) return null;
        return new Clock(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private static int minutesUntil(int nowHour, int nowMinute, int arrivalHour, int arrivalMinute) {
        int now = nowHour * 60 + nowMinute;
        int arrival = arrivalHour * 60 + arrivalMinute;
        int delta = arrival - now;
        return delta < 0 ? delta + 24 * 60 : delta;
    }

    private static String normalizeUnit(String unit) {
        String value = fold(unit);
        if (value.startsWith("metr")) return "m";
        return value;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String fold(String value) {
        String normalized = Normalizer.normalize(clean(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    public static final class Result {
        public final String instruction;
        public final Distance distanceToTurn;
        public final Arrow maneuver;
        public final Arrow nextManeuver;
        public final LaneGuidance laneGuidance;
        public final Clock arrival;
        public final Integer remainingMinutes;
        public final Integer remainingDistanceMetres;
        public final boolean rerouting;
        public final Integer roundaboutExit;

        Result(String instruction, Distance distanceToTurn, Arrow maneuver, Arrow nextManeuver,
               LaneGuidance laneGuidance, Clock arrival,
               Integer remainingMinutes, Integer remainingDistanceMetres, boolean rerouting,
               Integer roundaboutExit) {
            this.instruction = instruction;
            this.distanceToTurn = distanceToTurn;
            this.maneuver = maneuver;
            this.nextManeuver = nextManeuver;
            this.laneGuidance = laneGuidance;
            this.arrival = arrival;
            this.remainingMinutes = remainingMinutes;
            this.remainingDistanceMetres = remainingDistanceMetres;
            this.rerouting = rerouting;
            this.roundaboutExit = roundaboutExit;
        }

        public boolean hasGuidance() {
            return maneuver != Arrow.None || distanceToTurn != null || arrival != null;
        }

        public boolean hasNextTurnPreview() {
            return nextManeuver != null && nextManeuver != Arrow.None;
        }
    }

    /**
     * Lane guidance as HUD bitmasks: {@code outlineMask} marks every lane, {@code arrowMask}
     * marks the recommended lanes. Masks use {@link eLane} bit values.
     */
    public static final class LaneGuidance {
        public final int outlineMask;
        public final int arrowMask;

        LaneGuidance(int outlineMask, int arrowMask) {
            this.outlineMask = outlineMask;
            this.arrowMask = arrowMask;
        }

        public boolean hasRecommendation() {
            return arrowMask != 0;
        }
    }

    public static final class Distance {
        public final float value;
        public final String unit;

        Distance(float value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    public static final class Clock {
        public final int hour;
        public final int minute;

        Clock(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }
    }
}
