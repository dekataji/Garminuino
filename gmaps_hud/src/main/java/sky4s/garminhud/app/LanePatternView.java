package sky4s.garminhud.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Visual editor for the HUD lane pattern: 8 individually toggleable lane
 * segments (eLane bitmask order, left to right). Tap a segment to toggle it.
 * The resulting bitmask is sent via setLanes as a custom per-maneuver glyph.
 */
public class LanePatternView extends View {

    /** eLane bits left-to-right as drawn. */
    public static final int[] SEGMENT_BITS = {
            0x80, // DotsLeft
            0x40, // OuterLeft
            0x20, // MiddleLeft
            0x10, // InnerLeft
            0x08, // InnerRight
            0x04, // MiddleRight
            0x02, // OuterRight
            0x01, // DotsRight
    };

    public static final String[] SEGMENT_NAMES = {
            "DotsL", "OuterL", "MidL", "InnerL",
            "InnerR", "MidR", "OuterR", "DotsR",
    };

    private int mask = 0;
    private final Paint onPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LanePatternView(Context context) {
        super(context);
        init();
    }

    public LanePatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        onPaint.setColor(0xFF67CEFF);
        onPaint.setStyle(Paint.Style.FILL);
        offPaint.setColor(0xFF3A3A3A);
        offPaint.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    /** Returns the current 8-bit lane mask. 0 = no pattern. */
    public int getMask() {
        return mask;
    }

    public void setMask(int mask) {
        this.mask = mask & 0xFF;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            int idx = (int) (event.getX() / getWidth() * SEGMENT_BITS.length);
            if (idx >= 0 && idx < SEGMENT_BITS.length) {
                mask ^= SEGMENT_BITS[idx];
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float segW = w / SEGMENT_BITS.length;
        float gap = Math.min(8, segW * 0.18f);
        float top = h * 0.12f, bottom = h * 0.88f;
        for (int i = 0; i < SEGMENT_BITS.length; i++) {
            float left = i * segW + gap / 2f;
            float right = (i + 1) * segW - gap / 2f;
            boolean on = (mask & SEGMENT_BITS[i]) != 0;
            canvas.drawRoundRect(left, top, right, bottom, 10, 10,
                    on ? onPaint : offPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        // 8 segments, each ~1:2.2 aspect
        int h = Math.max(80, w / 8 * 2);
        setMeasuredDimension(w, h);
    }
}
