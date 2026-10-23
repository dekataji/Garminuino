package sky4s.garminhud.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;

/**
 * Draws a Garmin HUD direction glyph: an arrow at any eOutAngle combined
 * with an eOutType style (plain, lane, flag, roundabout...).
 */
public class HudGlyphView extends View {

    private eOutAngle angle = eOutAngle.Straight;
    private eOutType type = eOutType.ArrowOnly;
    private boolean selected = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HudGlyphView(Context context) {
        super(context);
        init();
    }

    public HudGlyphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setColor(0xFF67CEFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        selPaint.setColor(0xFF67CEFF);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(4);
    }

    public void setAngle(eOutAngle angle) {
        this.angle = angle;
        invalidate();
    }

    public void setType(eOutType type) {
        this.type = type;
        invalidate();
    }

    public void setGlyph(eOutAngle angle, eOutType type) {
        this.angle = angle;
        this.type = type;
        invalidate();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    /** Maps eOutAngle to screen degrees (90 = up). */
    public static float angleDegrees(eOutAngle angle) {
        switch (angle) {
            case Straight: return 90;
            case EasyRight: return 45;
            case Right: return 0;
            case SharpRight: return -60;
            case Down: return -90;
            case EasyLeft: return 135;
            case Left: return 180;
            case SharpLeft: return -120;
            case LeftDown: return -150;
            case RightDown: return -30;
            case AsDirection:
            default: return 90;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float size = Math.min(w, h) * 0.30f;
        paint.setStrokeWidth(Math.max(6, size * 0.22f));

        if (selected) {
            canvas.drawRoundRect(4, 4, w - 4, h - 4, 18, 18, selPaint);
        }

        if (type == eOutType.Off) {
            // X mark
            float r = size * 0.8f;
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint);
            return;
        }

        if (type == eOutType.LeftRoundabout || type == eOutType.RightRoundabout) {
            float r = size * 0.75f;
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(cx, cy, r, paint);
            drawArrow(canvas, cx, cy, angleDegrees(angle), size * 1.05f);
            return;
        }

        if (type == eOutType.RightFlag) {
            // flag pole + flag, small arrow below
            float pw = paint.getStrokeWidth();
            canvas.drawLine(cx - size * 0.5f, cy - size, cx - size * 0.5f, cy + size * 0.7f, paint);
            Path flag = new Path();
            flag.moveTo(cx - size * 0.5f, cy - size);
            flag.lineTo(cx + size * 0.6f, cy - size * 0.7f);
            flag.lineTo(cx - size * 0.5f, cy - size * 0.4f);
            flag.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(flag, paint);
            paint.setStyle(Paint.Style.STROKE);
            drawArrow(canvas, cx + size * 0.1f, cy + size * 0.45f, angleDegrees(angle), size * 0.5f);
            paint.setStrokeWidth(pw);
            return;
        }

        if (type == eOutType.Lane || type == eOutType.LongerLane) {
            float laneLen = type == eOutType.LongerLane ? size * 1.3f : size * 0.9f;
            float lx = size * 0.55f;
            canvas.drawLine(cx - lx, cy - laneLen, cx - lx, cy + size * 0.7f, paint);
            canvas.drawLine(cx + lx, cy - laneLen, cx + lx, cy + size * 0.7f, paint);
        }

        drawArrow(canvas, cx, cy, angleDegrees(angle), size);
    }

    private void drawArrow(Canvas canvas, float cx, float cy, float deg, float size) {
        double rad = Math.toRadians(deg);
        float dx = (float) Math.cos(rad), dy = -(float) Math.sin(rad);
        float x2 = cx + dx * size, y2 = cy + dy * size;
        float x1 = cx - dx * size * 0.45f, y1 = cy - dy * size * 0.45f;
        canvas.drawLine(x1, y1, x2, y2, paint);
        double ha = Math.toRadians(28);
        float hl = size * 0.42f;
        for (int s : new int[]{1, -1}) {
            double a = rad + s * ha;
            canvas.drawLine(x2, y2,
                    x2 - (float) Math.cos(a) * hl,
                    y2 + (float) Math.sin(a) * hl, paint);
        }
    }
}
