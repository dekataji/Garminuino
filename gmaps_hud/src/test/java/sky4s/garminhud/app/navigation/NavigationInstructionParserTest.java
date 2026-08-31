package sky4s.garminhud.app.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
}
