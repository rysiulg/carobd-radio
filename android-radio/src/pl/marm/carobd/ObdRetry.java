package pl.marm.carobd;

/** Krótkie ponowienie po utracie łącza i ograniczenie prób podczas dłuższego postoju. */
final class ObdRetry {
    private static final long[] DELAYS={2000,4000,8000,15000,30000};
    private int failures;
    long failed() {
        long delay=DELAYS[Math.min(failures,DELAYS.length-1)];
        if (failures<DELAYS.length) failures++;
        return delay;
    }
    void reset() { failures=0; }
    boolean possibleBusy(boolean visible) { return failures>=2 && visible; }
    int failures() { return failures; }
}
