package sky4s.garminhud.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;

/**
 * Full-screen visual editor for per-maneuver HUD mapping.
 * Top: Google Maps maneuver icons. Middle: HUD arrow glyphs. Then HUD style,
 * optional custom text, a live preview, and Save / Reset / Reset-all.
 */
public class CustomizeMappingActivity extends Activity {

    private String selectedManeuver;
    private eOutAngle selectedAngle = eOutAngle.Straight;
    private eOutType selectedType = eOutType.ArrowOnly;
    private EditText textInput;
    private HudGlyphView previewView;
    private TextView previewLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] maneuvers = ManeuverIconMap.maneuverNames();
        selectedManeuver = maneuvers[0];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(sectionTitle("1 · Maneuver (Google Maps icon)"));
        GridLayout maneuverGrid = new GridLayout(this);
        maneuverGrid.setColumnCount(4);
        root.addView(maneuverGrid);
        final MapsIconView[] maneuverCells = new MapsIconView[maneuvers.length];
        for (int i = 0; i < maneuvers.length; i++) {
            final String m = maneuvers[i];
            MapsIconView v = new MapsIconView(this);
            v.setManeuver(m);
            v.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
            final int idx = i;
            v.setOnClickListener(view -> {
                selectedManeuver = m;
                for (int j = 0; j < maneuverCells.length; j++) {
                    maneuverCells[j].setSelected(j == idx);
                }
                loadManeuverState();
            });
            maneuverGrid.addView(labeledCell(v, m));
            maneuverCells[i] = v;
        }
        maneuverCells[0].setSelected(true);

        root.addView(sectionTitle("2 · HUD arrow glyph"));
        GridLayout arrowGrid = new GridLayout(this);
        arrowGrid.setColumnCount(4);
        root.addView(arrowGrid);
        final eOutAngle[] angles = eOutAngle.values();
        final HudGlyphView[] arrowCells = new HudGlyphView[angles.length];
        for (int i = 0; i < angles.length; i++) {
            final eOutAngle a = angles[i];
            HudGlyphView v = new HudGlyphView(this);
            v.setGlyph(a, eOutType.ArrowOnly);
            v.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
            final int idx = i;
            v.setOnClickListener(view -> {
                selectedAngle = a;
                for (int j = 0; j < arrowCells.length; j++) {
                    arrowCells[j].setSelected(j == idx);
                }
                updatePreview();
            });
            arrowGrid.addView(labeledCell(v, a.name()));
            arrowCells[i] = v;
        }

        root.addView(sectionTitle("3 · HUD style"));
        GridLayout styleGrid = new GridLayout(this);
        styleGrid.setColumnCount(4);
        root.addView(styleGrid);
        final eOutType[] types = eOutType.values();
        final HudGlyphView[] styleCells = new HudGlyphView[types.length];
        for (int i = 0; i < types.length; i++) {
            final eOutType t = types[i];
            HudGlyphView v = new HudGlyphView(this);
            v.setGlyph(eOutAngle.Straight, t);
            v.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
            final int idx = i;
            v.setOnClickListener(view -> {
                selectedType = t;
                for (int j = 0; j < styleCells.length; j++) {
                    styleCells[j].setSelected(j == idx);
                }
                updatePreview();
            });
            styleGrid.addView(labeledCell(v, t.name()));
            styleCells[i] = v;
        }

        root.addView(sectionTitle("4 · Custom text (optional, 4 chars)"));
        textInput = new EditText(this);
        textInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        textInput.setHint("e.g. EXIT");
        textInput.setTextColor(0xFFFFFFFF);
        textInput.setHintTextColor(0xFF808080);
        root.addView(textInput);

        root.addView(sectionTitle("Preview"));
        previewView = new HudGlyphView(this);
        previewView.setLayoutParams(new LinearLayout.LayoutParams(dp(140), dp(140)));
        ((LinearLayout.LayoutParams) previewView.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(previewView);
        previewLabel = new TextView(this);
        previewLabel.setGravity(Gravity.CENTER);
        previewLabel.setTextColor(0xFFB0B0B0);
        previewLabel.setTextSize(12);
        root.addView(previewLabel);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            ManeuverCustomizer.saveOverride(this, selectedManeuver,
                    selectedAngle.name(), selectedType.name(),
                    textInput.getText().toString());
            Toast.makeText(this, selectedManeuver + " saved", Toast.LENGTH_SHORT).show();
        });
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> {
            ManeuverCustomizer.clearOverride(this, selectedManeuver);
            loadManeuverState();
            Toast.makeText(this, selectedManeuver + " reset to default", Toast.LENGTH_SHORT).show();
        });
        Button resetAll = new Button(this);
        resetAll.setText("Reset all");
        resetAll.setOnClickListener(v -> {
            ManeuverCustomizer.clearAll(this);
            loadManeuverState();
            Toast.makeText(this, "All mappings reset", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(save, bp);
        buttons.addView(reset, bp);
        buttons.addView(resetAll, bp);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // sync cell selection with initial maneuver state
        loadManeuverState();
        syncCellSelections(maneuverCells, arrowCells, styleCells, maneuvers, angles, types);
    }

    private void loadManeuverState() {
        String[] def = ManeuverIconMap.defaultAngleType(selectedManeuver);
        ManeuverCustomizer.Override ov = ManeuverCustomizer.getOverride(this, selectedManeuver);
        selectedAngle = ov != null ? ov.angle : eOutAngle.valueOf(def[0]);
        selectedType = ov != null ? ov.type : eOutType.valueOf(def[1]);
        textInput.setText(ov != null ? ov.text : "");
        updatePreview();
    }

    private void syncCellSelections(MapsIconView[] mCells, HudGlyphView[] aCells,
                                    HudGlyphView[] sCells, String[] maneuvers,
                                    eOutAngle[] angles, eOutType[] types) {
        for (int i = 0; i < mCells.length; i++) {
            mCells[i].setSelected(maneuvers[i].equals(selectedManeuver));
        }
        for (int i = 0; i < aCells.length; i++) {
            aCells[i].setSelected(angles[i] == selectedAngle);
        }
        for (int i = 0; i < sCells.length; i++) {
            sCells[i].setSelected(types[i] == selectedType);
        }
        // keep references for later syncs via tags
        getWindow().getDecorView().setTag(new Object[]{mCells, aCells, sCells, maneuvers, angles, types});
    }

    private void updatePreview() {
        previewView.setGlyph(selectedAngle, selectedType);
        String t = textInput.getText().toString();
        previewLabel.setText(selectedManeuver + " → " + selectedAngle.name()
                + " + " + selectedType.name() + (t.isEmpty() ? "" : " + \"" + t + "\""));
        // refresh cell highlights
        Object[] tags = (Object[]) getWindow().getDecorView().getTag();
        if (tags != null) {
            MapsIconView[] mCells = (MapsIconView[]) tags[0];
            HudGlyphView[] aCells = (HudGlyphView[]) tags[1];
            HudGlyphView[] sCells = (HudGlyphView[]) tags[2];
            String[] maneuvers = (String[]) tags[3];
            eOutAngle[] angles = (eOutAngle[]) tags[4];
            eOutType[] types = (eOutType[]) tags[5];
            for (int i = 0; i < mCells.length; i++) {
                mCells[i].setSelected(maneuvers[i].equals(selectedManeuver));
            }
            for (int i = 0; i < aCells.length; i++) {
                aCells[i].setSelected(angles[i] == selectedAngle);
            }
            for (int i = 0; i < sCells.length; i++) {
                sCells[i].setSelected(types[i] == selectedType);
            }
        }
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0xFF67CEFF);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private GridLayout.LayoutParams cellParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(84);
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    /** Wraps a glyph view with its label in a vertical cell for the grid. */
    private LinearLayout labeledCell(View glyph, String labelText) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(6), dp(4), dp(6));
        cell.addView(glyph);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(11);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(0xFFB0B0B0);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        label.setLayoutParams(llp);
        cell.addView(label);
        cell.setLayoutParams(cellParams());
        return cell;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String shortName(String m) {
        if (m.startsWith("LeaveRoundabout")) return "Rndabt";
        if (m.length() > 10) return m.substring(0, 10);
        return m;
    }
}
