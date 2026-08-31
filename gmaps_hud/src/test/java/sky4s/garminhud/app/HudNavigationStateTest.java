package sky4s.garminhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class HudNavigationStateTest {
    @After
    public void clearState() {
        HudNavigationState.clearAutoMapaSpeedLimit();
    }

    @Test
    public void returnsRecentAutoMapaSpeedLimit() {
        HudNavigationState.setAutoMapaSpeedLimit(80);

        assertEquals(80, HudNavigationState.getFreshAutoMapaSpeedLimit());
    }

    @Test
    public void clearRemovesAutoMapaSpeedLimit() {
        HudNavigationState.setAutoMapaSpeedLimit(100);
        HudNavigationState.clearAutoMapaSpeedLimit();

        assertEquals(-1, HudNavigationState.getFreshAutoMapaSpeedLimit());
    }
}
