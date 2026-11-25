package pl.marm.carobd;

public final class RefuelTest {
    public static void main(String[] args) {
        Refuel monitor=new Refuel(Double.NaN);
        Refuel.Reading baseline=stable(monitor,32,0,false);
        check(baseline!=null && !baseline.refueled && baseline.after==32,"pierwszy poziom bez powiadomienia");

        monitor=new Refuel(baseline.after);
        Refuel.Reading fill=stable(monitor,49,0,false);
        check(fill!=null && fill.refueled && fill.before==32 && fill.after==49,"tankowanie po restarcie");
        check(monitor.sample(49,false,24_000)==null,"poziom powtórzony bez powiadomienia");
        Refuel.Reading senderSettling=stable(monitor,64,30_000,false);
        check(senderSettling!=null && !senderSettling.refueled,"czujnik po tankowaniu bez powtórki");

        monitor=new Refuel(40);
        monitor.sample(60,false,0);
        check(stable(monitor,40,9000,false)!=null,"pojedynczy skok nie jest tankowaniem");
        check(monitor.sample(Double.NaN,false,28_000)==null && monitor.sample(102,false,28_000)==null,"błędny PID");
        for (long at=30_000;at<=210_000;at+=9000) monitor.sample(40,false,at);
        Refuel.Reading slope=stable(monitor,57,217_000,false);
        check(slope!=null && !slope.refueled,"odczyt w ciągłej jeździe bez alarmu");
        monitor.parked(239_000);
        Refuel.Reading parked=stable(monitor,65,245_000,false);
        check(parked!=null && parked.refueled,"tankowanie przy wyłączonym silniku i pracującym radiu");

        monitor=new Refuel(0);
        fill=stable(monitor,10,0,false);
        check(fill!=null && fill.refueled,"zero procent to poprawny pomiar");

        monitor=new Refuel(40);
        check(stable(monitor,40,0,false)!=null,"poziom przed zatrzymaniem");
        monitor.parked(22_000);
        for (long at=30_000;at<=139_000;at+=9_000) monitor.sample(44,false,at);
        fill=stable(monitor,48,148_000,false);
        check(fill!=null && fill.refueled && fill.before==40,"tankowanie mimo powolnego dochodzenia czujnika");
        System.out.println("RefuelTest OK");
    }

    private static Refuel.Reading stable(Refuel monitor,double level,long start,boolean parked) {
        monitor.sample(level,parked,start);
        monitor.sample(level,parked,start+9000);
        return monitor.sample(level,parked,start+18_000);
    }
    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
}
