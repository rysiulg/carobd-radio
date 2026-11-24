package pl.marm.carobd;

/** Jeden pomiar uzbrojony na postoju. Po hamowaniu lub utracie próbek zachowuje osiągnięte progi. */
final class Measurement {
    static final int NONE=0, WAITING=1, RUNNING=2, RESULT=3;
    private TripMath.Run run;
    private long armed;
    private int state;
    void arm(long at) { run=new TripMath.Run(); armed=at; state=WAITING; run.sample(0,at); }
    void cancel() { run=null; state=NONE; }
    void tick(long at) {
        if (state==WAITING && at-armed>60000) cancel();
        if (state==RUNNING && (at-armed>90000 || at-run.previousAt>2500)) state=RESULT;
    }
    void sample(double speed,long at) {
        tick(at);
        if (state!=WAITING && state!=RUNNING) return;
        if (state==WAITING && speed>2) { state=RUNNING; }
        if (state==RUNNING && (speed<run.peak-4 || at-run.previousAt>2500)) { state=RESULT; return; }
        run.sample(speed,at);
        if (state==RUNNING && speed>=140) state=RESULT;
    }
    int state() { return state; }
    double[] times() { return run==null ? new double[0] : run.times.clone(); }
}
