package pl.radiokia.preview;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Przełącza stronę po poziomym geście poza kontrolkami obsługującymi własne przeciąganie. */
final class SwipePages extends FrameLayout {
    private final List<View> controls=new ArrayList<>();
    private final Rect visible=new Rect();
    private final int[] rootLocation=new int[2];
    private final float threshold;
    private final IntConsumer step;
    private float startX,startY;
    private boolean candidate,intercepted;

    SwipePages(Context context,IntConsumer step) {
        super(context);
        this.step=step;
        threshold=56*getResources().getDisplayMetrics().density;
    }
    void keepGesture(View control) { controls.add(control); }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            startX=event.getRawX(); startY=event.getRawY();
            intercepted=false;
            candidate=!insideControl(startX,startY);
        } else if (action==MotionEvent.ACTION_MOVE && candidate) {
            float dx=Math.abs(event.getRawX()-startX),dy=Math.abs(event.getRawY()-startY);
            if (dy>threshold/2 && dy>dx) candidate=false;
            if (horizontal(dx,dy)) { intercepted=true; return true; }
        } else if (action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_CANCEL) candidate=false;
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled=intercepted || candidate;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                candidate=false;
                break;
            case MotionEvent.ACTION_UP:
                float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                if (candidate && horizontal(Math.abs(dx),Math.abs(dy))) step.accept(dx<0 ? 1 : -1);
                candidate=false;
                break;
            default: break;
        }
        return handled || super.onTouchEvent(event);
    }
    private boolean horizontal(float dx,float dy) { return candidate && dx>=threshold && dx>=dy*1.5f; }
    private boolean insideControl(float x,float y) {
        getRootView().getLocationOnScreen(rootLocation);
        for (View view:controls) {
            if (view.isShown() && view.getGlobalVisibleRect(visible) &&
                visible.contains((int)(x-rootLocation[0]),(int)(y-rootLocation[1]))) return true;
        }
        return false;
    }
}
