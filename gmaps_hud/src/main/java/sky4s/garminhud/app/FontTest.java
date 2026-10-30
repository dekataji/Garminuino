package sky4s.garminhud.app;

import sky4s.garminhud.hud.HUDInterface;

/**
 * HUD font test: steps through fixed 4-char groups on the HUD's alphabet
 * field so the operator can note which characters the HUD actually renders.
 * Some HUD firmware glyph sets blank certain letters (observed on the street
 * scroller: U, D, J, L never render); this test maps the renderable set so
 * street names can later be transliterated to it.
 *
 * While the test is active (HudCompass.sFontTestActive), the compass yields
 * the alphabet field: the street scroller and heading text do not overwrite
 * the test group.
 *
 * Round 1 (2026-10-30 results): of the uppercase groups, only
 * A C E G I K M N O P R S W render; B D F H J L Q T U V X Y Z and all digits
 * are blank. Round 2 appends lowercase groups: lowercase is an untested
 * region of the firmware font table, and a full lowercase set would let
 * street names be sent as-is in lowercase.
 */
public final class FontTest {
    /** Groups shown in order. First and last groups contain A, known to render. */
    public static final String[] GROUPS = {
            "ABCD", "EFGH", "IJKL", "MNOP", "QRST",
            "UVWX", "YZ01", "2345", "6789", "A A ",
            "abcd", "efgh", "ijkl", "mnop", "qrst",
            "uvwx", "yz  ",
    };

    private FontTest() {
    }

    /** Sends one group to the HUD. Returns false when no HUD is connected. */
    public static boolean sendGroup(android.content.Context context, int index) {
        if (index < 0 || index >= GROUPS.length) {
            return false;
        }
        HUDInterface hud = HudSessionManager.getInstance(context).getHud();
        if (hud == null) {
            return false;
        }
        String g = GROUPS[index];
        hud.setAlphabet(g.charAt(0), g.charAt(1), g.charAt(2), g.charAt(3));
        return true;
    }
}
