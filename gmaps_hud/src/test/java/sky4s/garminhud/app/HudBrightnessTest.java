package sky4s.garminhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudBrightnessTest {
    @Test
    public void automaticBrightnessRemainsTheDefault() {
        assertTrue(HudBrightness.DEFAULT_AUTO);
    }

    @Test
    public void maximumSliderPositionUsesMaximumHudLevel() {
        assertEquals(10, HudBrightness.gammaCorrectedLevel(10, 10));
    }

    @Test
    public void invalidProgressIsClamped() {
        assertEquals(0, HudBrightness.gammaCorrectedLevel(-1, 10));
        assertEquals(10, HudBrightness.gammaCorrectedLevel(11, 10));
    }

    @Test
    public void manualLevelIsClampedToGarminRange() {
        assertEquals(0, HudBrightness.clampManualLevel(-1));
        assertEquals(10, HudBrightness.clampManualLevel(255));
    }
}
