package pl.radiokia.fm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.function.IntConsumer;

public final class FmScale extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final IntConsumer tune;
    private final float density;
    private final int slop;
    private float startX, startY;
    private boolean tuning, vertical;
    private int channel, preview;

    public FmScale(Context context, IntConsumer tune) {
        super(context);
        this.tune = tune;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("Skala FM. Przeciągnij i puść, aby dostroić.");
        setClickable(true);
        setFocusable(false);
    }

    public void setFrequency(int frequency) {
        if (channel == frequency) return;
        channel = frequency;
        invalidate();
    }

    /** Rysuje skalę co 0,5 MHz i zaznacza ostatnie wysłane żądanie strojenia. */
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float start = inset(), end = getWidth() - start, baseline = getHeight() - 28 * density;
        paint.setColor(Config.LINE);
        paint.setStrokeWidth(2 * density);
        canvas.drawLine(start, baseline, end, baseline, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13 * density);
        for (int tick = Config.FM_MIN; tick <= Config.FM_MAX; tick += 5) {
            boolean major = tick == Config.FM_MIN || tick >= 920 && tick % 40 == 0;
            float x = position(tick);
            paint.setColor(major ? Config.MUTED : Config.LINE);
            paint.setStrokeWidth((major ? 2 : 1) * density);
            canvas.drawLine(x, baseline, x, baseline - (major ? 32 : 16) * density, paint);
            if (major) canvas.drawText(tick == Config.FM_MIN ? "87,5" : Integer.toString(tick / 10),
                x, baseline + 20 * density, paint);
        }
        int marker = preview != 0 ? preview : channel;
        if (marker == 0) return;
        float x = position(marker), top = Math.max(27 * density, baseline - 49 * density);
        paint.setColor(Config.INDICATOR);
        paint.setStrokeWidth(4 * density);
        canvas.drawLine(x, baseline + 3 * density, x, top, paint);
        canvas.drawCircle(x, top, 4 * density, paint);
        if (preview != 0) {
            paint.setTextSize(15 * density);
            float center = Math.max(32 * density, Math.min(getWidth() - 32 * density, x));
            canvas.drawText(Config.formatChannel(preview), center, top - 10 * density, paint);
        }
    }

    /** Podgląd częstotliwości przy przeciąganiu. Jedno polecenie po zdjęciu palca. */
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX(); startY = event.getY(); tuning = vertical = false;
                preview = frequency(startX);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - startX), dy = Math.abs(event.getY() - startY);
                if (!tuning && !vertical && Math.max(dx, dy) > slop) {
                    vertical = dy > dx; tuning = !vertical;
                    if (tuning) getParent().requestDisallowInterceptTouchEvent(true);
                }
                preview = vertical ? 0 : frequency(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                preview = 0;
                invalidate();
                if (vertical) return true;
                performClick();
                tune.accept(frequency(event.getX()));
                return true;
            case MotionEvent.ACTION_CANCEL:
                preview = 0;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private float inset() {
        return 24 * density;
    }

    private float position(int frequency) {
        return inset() + (getWidth() - 2 * inset()) * (frequency - Config.FM_MIN) / (Config.FM_MAX - Config.FM_MIN);
    }

    private int frequency(float position) {
        float fraction = (position - inset()) / Math.max(1, getWidth() - 2 * inset());
        fraction = Math.max(0, Math.min(1, fraction));
        return Config.FM_MIN + Math.round(fraction * (Config.FM_MAX - Config.FM_MIN));
    }
}
