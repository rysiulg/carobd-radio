package pl.marm.carobd;

/** Potwierdza wzrost poziomu paliwa po postoju lub powrocie odczytów ECU. */
final class Refuel {
    static final long BASELINE_MAX_AGE=7*24*60*60_000L;
    private static final long WINDOW=180_000, SAMPLE_GAP=45_000, STABLE_TIME=12_000, REFRESH=5*60_000L;
    private static final int MIN_SAMPLES=3;
    private static final double MIN_RISE=7, TOLERANCE=3, BASELINE_STEP=2;
    private double baseline, low, high;
    private long lastSample=-1, stableSince, checkUntil, saved=-1, lastRefuel=-WINDOW;
    private int samples;

    static final class Reading {
        final double before, after;
        final boolean refueled;
        Reading(double before,double after,boolean refueled) {
            this.before=before; this.after=after; this.refueled=refueled;
        }
    }

    Refuel(double baseline) { this.baseline=valid(baseline) ? baseline : Double.NaN; }

    // Silnik może zgasnąć pomiędzy odczytami poziomu; zachowaj wcześniejszy punkt odniesienia.
    void parked(long at) { checkUntil=at+WINDOW; }

    Reading sample(double level,boolean parkedWithEngineOff,long at) {
        if (!valid(level) || at<=lastSample) return null;
        boolean resumed=lastSample<0 || at-lastSample>SAMPLE_GAP;
        lastSample=at;
        if (resumed || parkedWithEngineOff) parked(at);
        if (samples==0 || resumed || Math.max(high,level)-Math.min(low,level)>TOLERANCE) {
            low=high=level; stableSince=at; samples=1;
        } else {
            low=Math.min(low,level); high=Math.max(high,level);
            if (samples<MIN_SAMPLES) samples++;
        }
        if (samples<MIN_SAMPLES || at-stableSince<STABLE_TIME) return null;

        double confirmed=(low+high)/2;
        double change=confirmed-baseline;
        boolean inWindow=at<=checkUntil;
        boolean detected=inWindow && change>=MIN_RISE && at-lastRefuel>=WINDOW;
        // Przez chwilę zachowujemy poprzedni poziom, gdy czujnik po tankowaniu dochodzi do nowej wartości.
        if (valid(baseline) && !detected) {
            if (inWindow && change>=BASELINE_STEP && at-lastRefuel>=WINDOW) return null;
            if (Math.abs(change)<BASELINE_STEP && saved>=0 && at-saved<REFRESH) return null;
        }
        Reading reading=new Reading(baseline,confirmed,detected);
        if (detected) lastRefuel=at;
        baseline=confirmed; saved=at;
        return reading;
    }

    static boolean valid(double level) { return Double.isFinite(level) && level>=0 && level<=100; }
}
