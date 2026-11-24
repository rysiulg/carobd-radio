package pl.marm.carobd;

import java.util.Arrays;

/** Całkowanie z zegara monotonicznego i czasy od ruszenia po postoju. */
final class TripMath {
    static final class Run {
        final double[] times=new double[7];
        double previous=-1, peak;
        long previousAt=-1;
        double started=-1;
        Run() { Arrays.fill(times, Double.NaN); }
        void sample(double speed,long at) {
            if (previousAt<0 || at<=previousAt || at-previousAt>2500) { started=-1; peak=0; previous=-1; }
            if (speed<=2) { started=-1; peak=0; }
            else if (previous>=0 && previous<=2) { started=previousAt; peak=previous; }
            if (started>=0 && speed < peak-4) started=-1;
            if (started>=0 && speed>previous) {
                peak=Math.max(speed,peak);
                for (int i=0;i<times.length;i++) {
                    int kmh=(i+1)*20;
                    if (previous>=kmh || speed<kmh) continue;
                    double seconds=(previousAt+(at-previousAt)*(kmh-previous)/(speed-previous)-started)/1000;
                    if (!Double.isFinite(times[i]) || seconds<times[i]) times[i]=seconds;
                }
            }
            previous=speed; previousAt=at;
        }
    }
    static double trapezoid(double before,double after,long gap) {
        return (before+after)*0.5*gap/3600000.0;
    }
}
