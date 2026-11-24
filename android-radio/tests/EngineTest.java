package pl.marm.carobd;

public final class EngineTest {
    private static final long FIVE_MIN=5*60_000L;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] x) {
        Engine e=new Engine();
        e.observe(0,0,0); check(!e.running(0,false));
        e.observe(800,0,1000); check(e.running(1000,false));
        e.observe(0,0,2000); check(e.running(4000,false));
        e.observe(0,0,5000); check(!e.running(5000,false));
        e.observe(900,0,7000); check(e.running(20000,false));
        check(!e.running(52000,false));
        e.observe(800,0,60000); e.observe(0,30,65000);
        check(e.running(71000,true));
        e.observe(0,0,72000); e.observe(0,0,75000);
        check(!e.running(75000,false));

        Engine drive=new Engine();
        drive.observe(800,0,1000);
        check(!drive.obdMotion(0,1000));
        check(!drive.gpsMotion(7,2000));
        check(!drive.gpsMotion(0,2800));
        check(!drive.gpsMotion(7,3600));
        check(!drive.obdMotion(1,4000));
        check(drive.obdMotion(4,4500));
        drive.beginTrip(4500);
        drive.observe(0,0,6000);
        check(drive.continuesTrip(7500,FIVE_MIN));
        check(!drive.continuesTrip(9600,FIVE_MIN));

        Engine gps=new Engine();
        check(!gps.gpsMotion(9,1000));
        check(!gps.gpsMotion(9,6000));
        check(gps.gpsMotion(9,7000));
        gps.beginTrip(7000);
        check(gps.continuesTrip(127000,FIVE_MIN));
        check(!gps.continuesTrip(307000,FIVE_MIN));
        check(gps.gpsMotion(12,8000));
        gps.observe(850,15,9000);
        check(gps.obdMotion(15,9500));
        check(gps.continuesTrip(9500,FIVE_MIN));
        gps.observe(0,0,11000);
        check(!gps.continuesTrip(15000,FIVE_MIN));

        Engine parked=new Engine();
        parked.observe(800,12,0);
        check(parked.obdMotion(12,0));
        parked.beginTrip(0);
        for (long at=1000;at<301000;at+=1000) {
            parked.obdMotion(0,at); parked.observe(800,0,at);
            check(parked.continuesTrip(at,FIVE_MIN));
        }
        parked.obdMotion(0,301000); parked.observe(800,0,301000);
        check(!parked.continuesTrip(301000,FIVE_MIN));
        check(parked.continuesTrip(301000,0));
        check(parked.continuesTrip(301000,30*60_000L));
        check(parked.obdMotion(8,302000));
        parked.beginTrip(302000);
        check(parked.continuesTrip(302000,FIVE_MIN));

        Engine gpsStop=new Engine();
        check(!gpsStop.gpsMotion(10,0));
        check(gpsStop.gpsMotion(10,1000));
        gpsStop.beginTrip(1000);
        for (long at=2000;at<=59000;at+=1000) gpsStop.gpsMotion(0,at);
        check(gpsStop.continuesTrip(70000,60_000L)); // Stara prędkość nie potwierdza postoju.
        gpsStop.gpsMotion(0,71000);
        check(gpsStop.autoStopAt(71000,60_000L)==131000);
        gpsStop.gpsMotion(4,72000); // Powolny ruch zeruje odliczanie postoju.
        for (long at=73000;at<133000;at+=1000) {
            gpsStop.gpsMotion(0,at);
            check(gpsStop.continuesTrip(at,60_000L));
        }
        gpsStop.gpsMotion(0,133000);
        check(!gpsStop.continuesTrip(133000,60_000L));
        check(gpsStop.continuesTrip(133000,0));
        Measurement run=new Measurement();
        run.arm(0); check(run.state()==Measurement.WAITING);
        run.sample(30,1000); check(run.state()==Measurement.RUNNING);
        check(Math.abs(run.times()[0]-2.0/3)<1e-7);
        run.sample(145,2000); check(run.state()==Measurement.RESULT && Double.isFinite(run.times()[6]));
        run.arm(10000); run.tick(71000); check(run.state()==Measurement.NONE);
        run.arm(90000); run.sample(34,91000); run.sample(12,92000);
        check(run.state()==Measurement.RESULT && Double.isNaN(run.times()[2]));
        System.out.println("OK: ruszenie OBD i GPS, ciągłość zapisu, autostop, zgaszenie i ręczne przyspieszenie");
    }
}
