package sky4s.garminhud.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a simplified Google Maps-style maneuver icon for a maneuver name
 * (as listed in ManeuverIconMap).
 */
public class MapsIconView extends View {

    private String maneuver = "Straight";
    private boolean selected = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MapsIconView(Context context) {
        super(context);
        init();
    }

    public MapsIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        selPaint.setColor(0xFF67CEFF);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(4);
    }

    public void setManeuver(String maneuver) {
        this.maneuver = maneuver;
        invalidate();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float s = Math.min(w, h) * 0.30f;
        paint.setStrokeWidth(Math.max(6, s * 0.24f));

        if (selected) {
            canvas.drawRoundRect(4, 4, w - 4, h - 4, 18, 18, selPaint);
        }

        String m = maneuver;
        if (m.equals("Arrivals")) {
            drawFlag(canvas, cx, cy, s);
        } else if (m.equals("ArrivalsLeft")) {
            drawFlag(canvas, cx - s * 0.25f, cy, s * 0.8f);
            drawArrow(canvas, cx + s * 0.35f, cy + s * 0.4f, 180, s * 0.55f);
        } else if (m.equals("ArrivalsRight")) {
            drawFlag(canvas, cx - s * 0.25f, cy, s * 0.8f);
            drawArrow(canvas, cx + s * 0.35f, cy + s * 0.4f, 0, s * 0.55f);
        } else if (m.equals("Convergence")) {
            drawArrow(canvas, cx - s * 0.5f, cy + s * 0.3f, 60, s * 0.8f);
            drawArrow(canvas, cx + s * 0.5f, cy + s * 0.3f, 120, s * 0.8f);
        } else if (m.equals("EasyLeft")) {
            drawArrow(canvas, cx, cy, 135, s);
        } else if (m.equals("EasyRight")) {
            drawArrow(canvas, cx, cy, 45, s);
        } else if (m.equals("GoTo") || m.equals("Straight")) {
            drawArrow(canvas, cx, cy, 90, s);
        } else if (m.equals("KeepLeft")) {
            drawFork(canvas, cx, cy, s, true);
        } else if (m.equals("KeepRight")) {
            drawFork(canvas, cx, cy, s, false);
        } else if (m.startsWith("LeaveRoundabout")) {
            float r = s * 0.62f;
            canvas.drawCircle(cx, cy - s * 0.1f, r, paint);
            drawArrow(canvas, cx, cy - s * 0.1f, 45, r * 1.25f);
        } else if (m.equals("Left")) {
            drawTurn(canvas, cx, cy, s, true, false);
        } else if (m.equals("Right")) {
            drawTurn(canvas, cx, cy, s, false, false);
        } else if (m.equals("SharpLeft")) {
            drawTurn(canvas, cx, cy, s, true, true);
        } else if (m.equals("SharpRight")) {
            drawTurn(canvas, cx, cy, s, false, true);
        } else if (m.equals("LeftDown")) {
            // U-turn (the parser maps u-turn instructions to LeftDown)
            drawUTurn(canvas, cx, cy, s);
        } else if (m.equals("RightDown")) {
            drawArrow(canvas, cx, cy, -45, s);
        } else if (m.equals("LeftToLeave")) {
            drawArrow(canvas, cx, cy - s * 0.2f, 135, s * 0.8f);
            canvas.drawLine(cx + s * 0.1f, cy + s * 0.9f, cx + s * 0.9f, cy + s * 0.9f, paint);
        } else if (m.equals("RightToLeave")) {
            drawArrow(canvas, cx, cy - s * 0.2f, 45, s * 0.8f);
            canvas.drawLine(cx - s * 0.1f, cy + s * 0.9f, cx - s * 0.9f, cy + s * 0.9f, paint);
        } else { // None
            float r = s * 0.7f;
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint);
        }
    }

    private void drawArrow(Canvas c, float cx, float cy, float deg, float size) {
        double rad = Math.toRadians(deg);
        float dx = (float) Math.cos(rad), dy = -(float) Math.sin(rad);
        float x2 = cx + dx * size, y2 = cy + dy * size;
        c.drawLine(cx - dx * size * 0.45f, cy - dy * size * 0.45f, x2, y2, paint);
        double ha = Math.toRadians(28);
        float hl = size * 0.42f;
        for (int sgn : new int[]{1, -1}) {
            double a = rad + sgn * ha;
            c.drawLine(x2, y2, x2 - (float) Math.cos(a) * hl,
                    y2 + (float) Math.sin(a) * hl, paint);
        }
    }

    /** L-shaped turn arrow; sharp adds a hook. */
    private void drawTurn(Canvas c, float cx, float cy, float s, boolean left, boolean sharp) {
        float dir = left ? -1 : 1;
        Path p = new Path();
        p.moveTo(cx - dir * s * 0.15f, cy + s);
        p.lineTo(cx - dir * s * 0.15f, cy - s * 0.1f);
        if (sharp) {
            p.lineTo(cx - dir * s * 0.55f, cy - s * 0.35f);
            p.lineTo(cx - dir * s * 0.55f, cy - s * 0.9f);
        } else {
            p.lineTo(cx + dir * s * 0.75f, cy - s * 0.1f);
        }
        c.drawPath(p, paint);
        // arrowhead at path end
        float ex, ey, deg;
        if (sharp) {
            ex = cx - dir * s * 0.55f; ey = cy - s * 0.9f; deg = 90;
        } else {
            ex = cx + dir * s * 0.75f; ey = cy - s * 0.1f; deg = left ? 180 : 0;
        }
        drawHead(c, ex, ey, deg, s * 0.4f);
    }

    private void drawHead(Canvas c, float x, float y, float deg, float size) {
        double rad = Math.toRadians(deg);
        double ha = Math.toRadians(28);
        for (int sgn : new int[]{1, -1}) {
            double a = rad + sgn * ha;
            c.drawLine(x, y, x - (float) Math.cos(a) * size,
                    y + (float) Math.sin(a) * size, paint);
        }
    }

    /** Keep left/right: straight trunk with a branch. */
    private void drawFork(Canvas c, float cx, float cy, float s, boolean left) {
        float dir = left ? -1 : 1;
        c.drawLine(cx, cy + s, cx, cy - s * 0.9f, paint);
        drawHead(c, cx, cy - s * 0.9f, 90, s * 0.38f);
        c.drawLine(cx, cy + s * 0.1f, cx + dir * s * 0.7f, cy - s * 0.5f, paint);
        drawHead(c, cx + dir * s * 0.7f, cy - s * 0.5f, left ? 135 : 45, s * 0.32f);
    }

    /** U-turn: arrow goes up, loops around and points back down. */
    private void drawUTurn(Canvas c, float cx, float cy, float s) {
        Path p = new Path();
        p.moveTo(cx - s * 0.45f, cy + s * 0.9f);
        p.lineTo(cx - s * 0.45f, cy - s * 0.2f);
        // loop over the top
        p.cubicTo(cx - s * 0.45f, cy - s * 0.9f,
                cx + s * 0.45f, cy - s * 0.9f,
                cx + s * 0.45f, cy - s * 0.2f);
        p.lineTo(cx + s * 0.45f, cy + s * 0.55f);
        c.drawPath(p, paint);
        drawHead(c, cx + s * 0.45f, cy + s * 0.55f, -90, s * 0.42f);
    }

    private void drawFlag(Canvas c, float cx, float cy, float s) {
        c.drawLine(cx - s * 0.4f, cy - s, cx - s * 0.4f, cy + s * 0.8f, paint);
        Path flag = new Path();
        flag.moveTo(cx - s * 0.4f, cy - s);
        flag.lineTo(cx + s * 0.7f, cy - s * 0.65f);
        flag.lineTo(cx - s * 0.4f, cy - s * 0.3f);
        flag.close();
        paint.setStyle(Paint.Style.FILL);
        c.drawPath(flag, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
