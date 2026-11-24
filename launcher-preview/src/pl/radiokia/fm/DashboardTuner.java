package pl.radiokia.fm;

import android.content.Context;
import java.util.function.Consumer;

/** Pulpit używa tego samego interfejsu tunera co RadioKIA FM. */
public final class DashboardTuner implements AutoCloseable {
    private TopwayFmEvents events;
    public DashboardTuner(Consumer<Integer> channel) {
        events=TopwayFmEvents.open(new TopwayFmEvents.Listener() {
            @Override public void onTunerChannel(int value) { channel.accept(value); }
            @Override public void onTunerSteering(int direction,boolean seek) { }
        });
    }
    public void tune(Context context,int value,Consumer<String> done) {
        if (value<Config.FM_MIN || value>Config.FM_MAX) { done.accept("Częstotliwość poza zakresem FM."); return; }
        XtRadio.send(context,Config.XT_TUNE,value,done);
    }
    public void seek(Context context,boolean up,Consumer<String> done) {
        XtRadio.send(context,up ? Config.XT_SEEK_UP : Config.XT_SEEK_DOWN,0,done);
    }
    @Override public void close() { if (events!=null) { events.close(); events=null; } }
}
