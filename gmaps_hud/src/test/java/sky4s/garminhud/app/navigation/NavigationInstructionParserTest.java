package sky4s.garminhud.app.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import sky4s.garminhud.Arrow;

public class NavigationInstructionParserTest {
    @Test
    public void parsesCurrentGoogleProgressNotification() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "150\u00a0m · Skręć w lewo w Castle End",
                "150\u00a0m",
                "Dotrzesz o\u00a012:44",
                10,
                43809,
                12,
                0);

        assertEquals(150f, result.distanceToTurn.value, 0f);
        assertEquals("m", result.distanceToTurn.unit);
        assertEquals("Skręć w lewo w Castle End", result.instruction);
        assertEquals(Arrow.Left, result.maneuver);
        assertEquals(44, result.remainingMinutes.intValue());
        assertEquals(43799, result.remainingDistanceMetres.intValue());
    }

    @Test
    public void parsesAutoMapaRoundaboutAndPolishMetres() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseAutoMapa(
                "Za 145 metrów na rondzie lekko w prawo.");

        assertNotNull(result.distanceToTurn);
        assertEquals(145f, result.distanceToTurn.value, 0f);
        assertEquals("m", result.distanceToTurn.unit);
        assertEquals(Arrow.LeaveRoundaboutEasyRight, result.maneuver);
    }

    @Test
    public void parsesAutoMapaKeepRightAndRamp() {
        assertEquals(Arrow.KeepRight, NavigationInstructionParser.parseAutoMapa(
                "Za 1.3 km trzymaj się prawego pasa.").maneuver);
        assertEquals(Arrow.LeftToLeave, NavigationInstructionParser.parseAutoMapa(
                "Za 1.5 km zjedź w lewo.").maneuver);
    }

    @Test
    public void parsesArrivalAndMidnightEta() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "340 m · Miejsce docelowe będzie po prawej",
                "340 m",
                "Dotrzesz o 00:05",
                0,
                340,
                23,
                55);

        assertEquals(Arrow.ArrivalsRight, result.maneuver);
        assertEquals(10, result.remainingMinutes.intValue());
    }

    @Test
    public void parsesCompoundThenInstructionAsNextTurnPreview() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "Turn left onto Main St, then turn right onto Oak Ave",
                "500 m",
                "",
                0,
                0,
                12,
                0);

        assertEquals(Arrow.Left, result.maneuver);
        assertEquals(Arrow.Right, result.nextManeuver);
    }

    @Test
    public void thenClauseDoesNotCorruptCurrentManeuver() {
        // Without the split, "left" in the tail would win over "straight" in the head.
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "Continue straight, then turn left onto Oak Ave",
                "1.2 km",
                "",
                0,
                0,
                12,
                0);

        assertEquals(Arrow.Straight, result.maneuver);
        assertEquals(Arrow.Left, result.nextManeuver);
    }

    @Test
    public void noThenClauseMeansNoPreview() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "Turn right onto Oak Ave",
                "200 m",
                "",
                0,
                0,
                12,
                0);

        assertEquals(Arrow.Right, result.maneuver);
        assertEquals(Arrow.None, result.nextManeuver);
    }

    @Test
    public void parsesLeftLaneGuidance() {
        NavigationInstructionParser.LaneGuidance guidance =
                NavigationInstructionParser.parseLaneGuidance(
                        "Use the left 2 lanes to turn left onto Main St");

        assertNotNull(guidance);
        // 2 lanes centered: InnerLeft(0x10) + InnerRight(0x08); both recommended.
        assertEquals(0x18, guidance.outlineMask);
        assertEquals(0x18, guidance.arrowMask);
    }

    @Test
    public void parsesRightLaneGuidance() {
        NavigationInstructionParser.LaneGuidance guidance =
                NavigationInstructionParser.parseLaneGuidance(
                        "Stay in the right lane to take the exit");

        assertNotNull(guidance);
        assertEquals(guidance.outlineMask, guidance.arrowMask);
    }

    @Test
    public void noLaneHintMeansNullGuidance() {
        assertEquals(null, NavigationInstructionParser.parseLaneGuidance(
                "Turn left onto Main St"));
    }

    @Test
    public void parsesRoundaboutExitEnglish() {
        assertEquals(Integer.valueOf(2), NavigationInstructionParser.parseRoundaboutExit(
                "At the roundabout, take the 2nd exit onto Main St"));
        assertEquals(Integer.valueOf(1), NavigationInstructionParser.parseRoundaboutExit(
                "take 1st exit"));
    }

    @Test
    public void parsesRoundaboutExitPolish() {
        assertEquals(Integer.valueOf(3), NavigationInstructionParser.parseRoundaboutExit(
                "Na rondzie zjedź 3. zjazdem"));
    }

    @Test
    public void noExitNumberMeansNull() {
        assertNull(NavigationInstructionParser.parseRoundaboutExit(
                "Turn left onto Main St"));
        assertNull(NavigationInstructionParser.parseRoundaboutExit(
                "At the roundabout, continue straight"));
    }

    @Test
    public void roundaboutExitPopulatesResult() {
        NavigationInstructionParser.Result result = NavigationInstructionParser.parseGoogle(
                "At the roundabout, take the 2nd exit onto Main St",
                "200 m",
                "",
                0,
                0,
                12,
                0);

        assertEquals(Integer.valueOf(2), result.roundaboutExit);
    }
}
