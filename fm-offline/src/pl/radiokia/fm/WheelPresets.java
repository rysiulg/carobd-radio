package pl.radiokia.fm;

import android.os.Handler;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Liczy każde naciśnięcie według kolejności P1–P8 i czeka na fabryczną reakcję tunera. */
public final class WheelPresets {
    private static final long SETTLE_MS=280;
    private final Handler main;
    private final BooleanSupplier available;
    private final IntConsumer tune, confirmed;
    private final Runnable settle=this::settled;
    private int target, observed;
    private boolean ready, issued;

    public WheelPresets(Handler main,BooleanSupplier available,IntConsumer tune,IntConsumer confirmed) {
        this.main=main; this.available=available; this.tune=tune; this.confirmed=confirmed;
    }
    public boolean step(int direction,int frequency,int[] presets) {
        int size=Math.min(presets.length,Config.MAX_PRESETS);
        if (size==0) return false;
        int current=target==0 ? frequency : target, index=-1;
        for (int i=0;i<size;i++) if (presets[i]==current) { index=i; break; }
        int next=index<0 ? (direction>0 ? 0 : size-1) : Math.floorMod(index+direction,size);
        target=presets[next]; observed=0; ready=issued=false;
        main.removeCallbacks(settle); main.postDelayed(settle,SETTLE_MS);
        return true;
    }
    private void settled() { ready=true; flush(); }
    public void flush() {
        if (!ready || target==0 || !available.getAsBoolean()) return;
        int wanted=target;
        // Jeśli fabryczny tuner już trafił w ten sam preset, jego odczyt wystarczy.
        if (observed==wanted) {
            clear(); confirmed.accept(wanted);
            return;
        }
        ready=false; issued=true; tune.accept(wanted);
    }
    public boolean pending() { return target!=0; }
    public boolean awaiting(int frequency) { return issued && target==frequency; }
    public boolean acceptChannel(int frequency) {
        if (!pending()) return true;
        observed=frequency;
        if (!awaiting(frequency)) return false;
        clear(); return true;
    }
    public void finish(int frequency) {
        if (awaiting(frequency)) clear();
    }
    public void clear() {
        main.removeCallbacks(settle);
        target=observed=0; ready=issued=false;
    }
}
