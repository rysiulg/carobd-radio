package pl.marm.carobd;

import java.util.Arrays;

/** Całkowanie z zegara monotonicznego i czasy od ruszenia po postoju. */
final class TripMath {
    static final int SPLIT_KMH=20, FIRST_LIMIT=100, LIMIT=200;
    static final int SERIES_SPLITS=FIRST_LIMIT/SPLIT_KMH, SPLITS=LIMIT/SPLIT_KMH;
    static int splitStart(int band) { return band<SERIES_SPLITS ? 0 : FIRST_LIMIT; }
    static int splitEnd(int band) { return (band+1)*SPLIT_KMH; }
    static boolean fuelByDistance(double speed) { return Double.isFinite(speed) && speed>2; }
    static double fuelPer100Km(double litersPerHour,double speed) {
        return fuelByDistance(speed) && Double.isFinite(litersPerHour) && litersPerHour>=0
            ? litersPerHour*100/speed : Double.NaN;
    }
    /** Ciśnienie atmosfery standardowej na wysokości GPS, w zakresie troposfery. */
    static double estimatedPressureHpa(double altitude) {
        return altitude>=-500 && altitude<=11000 ? 1013.25*Math.pow(1-altitude/44330.77,5.25588) : Double.NaN;
    }
    static final class FuelLevel {
        final double value;
        final boolean estimated;
        FuelLevel(double value,boolean estimated) { this.value=value; this.estimated=estimated; }
    }
    static FuelLevel fuelLevel(boolean litersMode,double percent,double liters,double capacity) {
        double measured=litersMode ? liters : percent;
        if (Double.isFinite(measured)) return new FuelLevel(measured,false);
        double other=litersMode ? percent : liters;
        if (!Double.isFinite(other) || !Double.isFinite(capacity) || capacity<=0) return new FuelLevel(Double.NaN,false);
        return new FuelLevel(litersMode ? other*capacity/100 : other*100/capacity,true);
    }

    /** Brak pomiaru paliwa w jednej trasie nie obniża średniej pozostałych. */
    static final class Group {
        double distance, seconds, fuelDistance, fuel, recordedFuel, cost;
        int distances, durations, fuelTrips, fuelMeasurements, partialFuelMeasurements, pricedTrips;
        void add(double km,double duration,double rate,double liters,double coverage,double price) {
            if (valid(price)) { cost+=price; pricedTrips++; }
            if (valid(liters)) {
                recordedFuel+=liters; fuelMeasurements++;
                if (Double.isFinite(coverage) && coverage<.60) partialFuelMeasurements++;
            }
            if (valid(duration)) { seconds+=duration; durations++; }
            if (!valid(km)) return;
            distance+=km; distances++;
            if (km<=0) return;
            if (!valid(liters) || Double.isFinite(coverage) && coverage<.60) liters=valid(rate) ? rate*km/100 : Double.NaN;
            if (valid(liters)) { fuel+=liters; fuelDistance+=km; fuelTrips++; }
        }
        double recordedLiters() { return fuelMeasurements>0 ? recordedFuel : Double.NaN; }
        double estimatedCost() { return pricedTrips>0 ? cost : Double.NaN; }
        double mean() { return fuelDistance>0 ? 100*fuel/fuelDistance : Double.NaN; }
        private static boolean valid(double value) { return Double.isFinite(value) && value>=0; }
    }

    interface SplitListener { void reached(int split,long launchedAt,double seconds); }
    static final class Run {
        final double[] times=new double[SPLITS];
        double previous=-1, peak;
        long previousAt=-1;
        private long started=-1;
        private double rollingStart=-1,rollingPeak;
        private int crossed,rollingCrossed;
        Run() { Arrays.fill(times, Double.NaN); }
        void sample(double speed,long at) {
            sample(speed,at,null);
        }
        void resetMotion() { started=-1; rollingStart=-1; peak=0; previous=-1; previousAt=-1; crossed=0; }
        void sample(double speed,long at,SplitListener listener) {
            if (at<=previousAt || !Double.isFinite(speed) || speed<0) return;
            if (at-previousAt>2500) resetMotion();
            if (speed==0) { started=-1; peak=0; crossed=0; }
            else if (previous==0) { started=previousAt; peak=0; }
            if (started>=0 && (speed<peak-4 || at-started>90000)) started=-1;
            if (rollingStart>=0 && (speed<rollingPeak-4 || at-rollingStart>90000)) rollingStart=-1;

            if (previousAt>=0 && speed>previous) {
                double msPerKmh=(at-previousAt)/(speed-previous);
                if (rollingStart<0 && previous<FIRST_LIMIT && speed>=FIRST_LIMIT) {
                    rollingStart=previousAt+(FIRST_LIMIT-previous)*msPerKmh;
                    rollingPeak=FIRST_LIMIT; rollingCrossed=0;
                }
                if (started>=0) {
                    peak=Math.max(speed,peak);
                    crossed=crossings(0,SERIES_SPLITS,crossed,started,speed,msPerKmh,listener);
                }
                if (rollingStart>=0) {
                    rollingPeak=Math.max(speed,rollingPeak);
                    rollingCrossed=crossings(SERIES_SPLITS,SPLITS,rollingCrossed,rollingStart,speed,msPerKmh,listener);
                    if (speed>=LIMIT) rollingStart=-1;
                }
            }
            previous=speed; previousAt=at;
        }
        private int crossings(int from,int end,int mask,double start,double speed,double msPerKmh,SplitListener listener) {
            for (int band=from;band<end;band++) {
                int kmh=splitEnd(band),bit=1<<band;
                if ((mask&bit)!=0 || previous>=kmh || speed<kmh) continue;
                double seconds=(previousAt+(kmh-previous)*msPerKmh-start)/1000;
                mask|=bit;
                if (!Double.isFinite(times[band]) || seconds<times[band]) times[band]=seconds;
                if (listener!=null) listener.reached(band,Math.round(start),seconds);
            }
            return mask;
        }
    }
    static double trapezoid(double before,double after,long gap) {
        return (before+after)*0.5*gap/3600000.0;
    }
}
