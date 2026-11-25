package pl.marm.carobd;

/** Rozróżnia ruszenie od pracy silnika na postoju i ustala warunek końca przejazdu. */
final class Engine {
    static final long LOST=35000, OFF=3000;
    private static final long RECENT_MOTION=5000, GPS_SAMPLE_GAP=3000, STOP_SAMPLE_GAP=8000, GPS_ONLY_LOST=5*60_000L;
    private static final long MIN_RECOVERY_WINDOW=120_000L;
    private static final double OBD_MOVING=2, GPS_MOVING=5;
    private long lastRunning=-1, zeroSince=-1;
    private long motionAt=-1, gpsCandidate=-1;
    private long lastSpeedAt=-1, stationarySince=-1;
    private boolean engineSeenInTrip;

    void observe(double rpm,double speed,long at) {
        if (rpm>300) { lastRunning=at; zeroSince=-1; engineSeenInTrip=true; }
        else if (Double.isFinite(rpm) && rpm<=100 && speed<=OBD_MOVING) {
            if (zeroSince<0) zeroSince=at;
        } else if (speed>OBD_MOVING || rpm>100) zeroSince=-1;
    }
    boolean running(long now,boolean moving) {
        return lastRunning>=0 && (moving || ((now-lastRunning<=LOST) && (zeroSince<0 || now-zeroSince<OFF)));
    }
    boolean obdMotion(double speed,long at) {
        sampleStop(speed,at);
        gpsCandidate=-1;
        if (!(speed>OBD_MOVING)) return false;
        motionAt=at;
        return true;
    }
    boolean gpsMotion(double speed,long at) {
        sampleStop(speed,at);
        if (!(speed>GPS_MOVING)) { gpsCandidate=-1; return false; }
        boolean confirmed=gpsCandidate>=0 && at>gpsCandidate && at-gpsCandidate<=GPS_SAMPLE_GAP;
        gpsCandidate=at;
        if (confirmed) motionAt=at;
        return confirmed;
    }
    private void sampleStop(double speed,long at) {
        if (at<=lastSpeedAt) return;
        if (!Double.isFinite(speed) || speed<0) { stationarySince=-1; return; }
        if (speed>OBD_MOVING) stationarySince=-1;
        else if (stationarySince<0 || at-lastSpeedAt>STOP_SAMPLE_GAP) stationarySince=at;
        lastSpeedAt=at;
    }
    void beginTrip(long now) { engineSeenInTrip=running(now,false); }
    // Nawet przy wyłączonym auto-stopie można wznowić zapis po krótkim restarcie procesu.
    static boolean recoverableGap(long gap,long stationaryTimeout) {
        return gap<=Math.max(MIN_RECOVERY_WINDOW,stationaryTimeout);
    }
    void resumeTrip(long checkpoint,long motion,boolean hadObd) {
        lastSpeedAt=checkpoint; motionAt=motion;
        engineSeenInTrip=hadObd; lastRunning=hadObd ? checkpoint : -1;
    }
    long autoStopAt(long now,long timeout) {
        return timeout>0 && stationarySince>=0 && now>=lastSpeedAt && now-lastSpeedAt<=STOP_SAMPLE_GAP
            ? stationarySince+timeout : 0;
    }
    boolean continuesTrip(long now,long timeout) {
        long deadline=autoStopAt(now,timeout);
        if (deadline>0 && now>=deadline) return false;
        return engineSeenInTrip ? running(now,motionAt>=0 && now-motionAt<RECENT_MOTION)
            : lastSpeedAt>=0 && now-lastSpeedAt<GPS_ONLY_LOST;
    }
}
