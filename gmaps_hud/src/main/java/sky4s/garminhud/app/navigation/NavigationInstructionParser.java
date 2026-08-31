package sky4s.garminhud.app.navigation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sky4s.garminhud.Arrow;

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

        Clock eta = parseClock(etaText);
        Integer remainingMinutes = eta == null
                ? null : minutesUntil(nowHour, nowMinute, eta.hour, eta.minute);
        Integer remainingDistanceMetres = progressMax > 0
                ? Math.max(0, progressMax - Math.max(0, progress)) : null;

        return new Result(
                instruction,
                distance,
                parseManeuver(instruction),
                eta,
                remainingMinutes,
                remainingDistanceMetres,
                isRerouting(instruction));
    }

    public static Result parseAutoMapa(String title) {
        String safeTitle = clean(title);
        return new Result(
                stripAutoMapaDistance(safeTitle),
                parseDistance(safeTitle),
                parseManeuver(safeTitle),
                null,
                null,
                null,
                isRerouting(safeTitle));
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
        public final Clock arrival;
        public final Integer remainingMinutes;
        public final Integer remainingDistanceMetres;
        public final boolean rerouting;

        Result(String instruction, Distance distanceToTurn, Arrow maneuver, Clock arrival,
               Integer remainingMinutes, Integer remainingDistanceMetres, boolean rerouting) {
            this.instruction = instruction;
            this.distanceToTurn = distanceToTurn;
            this.maneuver = maneuver;
            this.arrival = arrival;
            this.remainingMinutes = remainingMinutes;
            this.remainingDistanceMetres = remainingDistanceMetres;
            this.rerouting = rerouting;
        }

        public boolean hasGuidance() {
            return maneuver != Arrow.None || distanceToTurn != null || arrival != null;
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
