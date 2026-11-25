package pl.marm.carobd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TelemetryTest {
    private static void equal(double actual,double expected) {
        if (Math.abs(actual-expected)>1e-8 || !Double.isFinite(actual)) throw new AssertionError(actual+" != "+expected);
    }
    private static void missing(double actual) {
        if (!Double.isNaN(actual)) throw new AssertionError("Oczekiwano braku pomiaru, jest "+actual);
    }
    private static void gear(String command,String prefix,int selector,int current,double speed,String expected) {
        String reply=prefix+String.join("",java.util.Collections.nCopies(20,"00"))+String.format("%02X%02X",selector,current);
        KiaGear data=KiaGear.decode(command,reply);
        if (data==null || !expected.equals(data.label(speed))) throw new AssertionError("Bieg "+expected);
    }
    public static void main(String[] args) throws Exception {
        equal(Elm.decode(0x0d,"SEARCHING...\r41 0D 50\r>"),80);
        equal(Elm.decode(0x0c,"410C1F40\r>"),2000);
        equal(Elm.decode(0x5e,"415E00C8>"),10);
        equal(Elm.decode(0x9d,"419D00190000>"),0.5);
        equal(Elm.decode(0x42,"41423778>"),14.2);
        equal(Elm.decode(Elm.BAROMETER,"0133\r41 33 63\r>"),99);
        missing(Elm.decode(Elm.BAROMETER,"4133\rNO DATA>"));
        equal(Elm.decodeKiaAmbient("00C\r0:6201007E507C\r1:C8FF7C506B0500\r>"),0);
        equal(Elm.decodeKiaAmbient("6201007E507CC8FF7C4F6B05>"),-0.5);
        missing(Elm.decodeKiaAmbient("6201007E507CC8FF7CFF6B05>"));
        missing(Elm.decodeKiaAmbient("6201007E507CC8FF7C>"));
        equal(TripMath.estimatedPressureHpa(0),1013.25);
        equal(Math.round(TripMath.estimatedPressureHpa(268)),981);
        missing(TripMath.estimatedPressureHpa(Double.NaN));
        missing(TripMath.estimatedPressureHpa(12000));
        Elm fallback=new Elm(new Elm.Link() {
            public String query(String cmd,int timeout) { return "ATRV".equals(cmd) ? "ATRV\r12.4V\r>" : "NO DATA>"; }
            public void close() {}
        });
        Elm.Voltage battery=fallback.voltage(); equal(battery.volts,12.4);
        if (!battery.adapter) throw new AssertionError("Źródło napięcia zastępczego");
        equal(SpeedBands.band(69,70),6); equal(SpeedBands.band(70,-1),7);
        equal(SpeedBands.band(-1,-1),SpeedBands.UNKNOWN);
        missing(Elm.decode(0x10,"NO DATA"));
        missing(Elm.decode(0x0d,"7F0112"));
        missing(Elm.decode(0x0d,"410D"));
        equal(Elm.decode(Elm.FUEL_LEVEL,"412F00"),0);
        equal(Elm.decode(Elm.FUEL_LEVEL,"41 2F 38\r>"),56*100/255.0);
        equal(Elm.decode(Elm.FUEL_LEVEL,"412FFF"),100);
        missing(Elm.decode(Elm.FUEL_LEVEL,"NO DATA>"));
        equal(Elm.decode(Elm.COOLANT,"41 05 82\r>"),90);
        equal(Elm.decode(Elm.COOLANT,"41051E>"),-10);
        equal(Elm.decode(Elm.OIL,"415C91>"),105);
        missing(Elm.decode(Elm.OIL,"41 5C\rNO DATA>"));
        String tank="00A\r0:62B002C00000\r1:008AB400000000\r>";
        equal(Elm.decodeKiaTank(tank),69);
        equal(Elm.decodeKiaTank(tank.replace("008A","0000")),0);
        missing(Elm.decodeKiaTank(tank.replace("008A","00FF")));
        missing(Elm.decodeKiaTank("00A\r0:62B002C00000\r>"));
        String kiaOdo="043\r0:62ED03FFE7FF\r1:E0000100010000\r2:00000010000100\r3:00000000190000\r4:00000000000000\r"+
            "5:000000FFFF0003\r6:02000014DF6178\r7:0000560400BC61\r8:4E0003000A0000\r9:000000FFFF5555\r>";
        equal(Elm.decodeKiaOdometer(kiaOdo),12345.678);
        missing(Elm.decodeKiaOdometer(kiaOdo.replace("8:4E","A:4E")));
        missing(Elm.decodeKiaOdometer("7F2231>"));
        gear(KiaGear.UDS,"6201A0",8,4,71,"D4");
        gear(KiaGear.UDS,"6201A0",12,3,42,"M3");
        gear(KiaGear.UDS,"6201A0",12,4,71,"M4");
        gear(KiaGear.UDS,"6201A0",8,0,0,"D");
        gear(KiaGear.LEGACY,"61A0",11,0,0,"P");
        gear(KiaGear.LEGACY,"61A0",11,0,60,"");
        gear(KiaGear.LEGACY,"61A0",10,0,0,"R");
        gear(KiaGear.LEGACY,"61A0",9,0,0,"N");
        gear(KiaGear.LEGACY,"61A0",255,6,82,"6");
        String tcm="01A\r0:6201A0000000\r1:00000000000000\r2:00000000000000\r3:0000000C040000\r>";
        if (!"M4".equals(KiaGear.decode(KiaGear.UDS,tcm).label(50)) ||
            KiaGear.decode(KiaGear.UDS,tcm.replace("2:","4:"))!=null ||
            KiaGear.decode(KiaGear.LEGACY,tcm)!=null || KiaGear.decode(KiaGear.UDS,"7F2231>")!=null)
            throw new AssertionError("Błędna interpretacja ramek skrzyni");
        equal(KiaGear.nextPoll(1000,1300,true,true),2100);
        equal(KiaGear.nextPoll(1000,2100,true,true),2300);
        equal(KiaGear.nextPoll(1000,1300,false,true),3700);
        equal(KiaGear.nextPoll(1000,1300,false,false),26300);
        equal(TripMath.trapezoid(80,100,1000),0.025);
        equal(TripMath.fuelPer100Km(4,80),5);
        equal(TripMath.fuelPer100Km(0,80),0);
        missing(TripMath.fuelPer100Km(0.9,0));
        missing(TripMath.fuelPer100Km(0.9,2));
        missing(TripMath.fuelPer100Km(4,Double.NaN));
        TripMath.Group group=new TripMath.Group();
        missing(group.mean()); missing(group.recordedLiters()); missing(group.estimatedCost());
        group.add(80,4200,5,4,0.95,27.20);
        group.add(20,1800,10,Double.NaN,Double.NaN,Double.NaN);
        group.add(30,1200,Double.NaN,0.4,0.2,3.80);
        equal(group.recordedLiters(),4.4); equal(group.estimatedCost(),31);
        if (group.fuelMeasurements!=2 || group.partialFuelMeasurements!=1 || group.pricedTrips!=2)
            throw new AssertionError("Dostępność litrów i kosztów w sumie tras");
        equal(group.distance,130); equal(group.seconds,7200); equal(group.mean(),6);
        if (group.fuelTrips!=2) throw new AssertionError("Wyłączenie częściowego pomiaru ze spalania grupy");
        TripMath.FuelLevel direct=TripMath.fuelLevel(true,50,37,71);
        equal(direct.value,37);
        if (direct.estimated) throw new AssertionError("Litry z zegarów mają pierwszeństwo");
        TripMath.FuelLevel estimated=TripMath.fuelLevel(true,50,Double.NaN,71);
        equal(estimated.value,35.5);
        if (!estimated.estimated) throw new AssertionError("Przeliczone litry są szacunkiem");
        equal(TripMath.fuelLevel(false,50,37,71).value,50);
        equal(TripMath.fuelLevel(false,Double.NaN,35.5,71).value,50);
        equal(TripMath.fuelLevel(true,50,0,71).value,0);
        missing(TripMath.fuelLevel(true,50,Double.NaN,Double.NaN).value);
        TripMath.Run run=new TripMath.Run();
        run.sample(0,1000); run.sample(30,2000); run.sample(60,3000); run.sample(145,5000);
        equal(run.times[0],2.0/3);
        equal(run.times[1],4.0/3);
        equal(run.times[4],2+2*40.0/85);
        equal(run.times[5],2*20.0/85); equal(run.times[6],2*40.0/85);
        TripMath.Run rolling=new TripMath.Run();
        rolling.sample(90,1000); rolling.sample(110,2000); rolling.sample(130,3000);
        rolling.sample(170,4000); rolling.sample(200,5500);
        missing(rolling.times[4]); equal(rolling.times[5],1); equal(rolling.times[9],4);
        rolling.sample(88,6000); rolling.sample(125,7000);
        equal(rolling.times[5],20.0/37); equal(rolling.times[9],4);
        TripMath.Run gap=new TripMath.Run();
        gap.sample(0,1000); gap.sample(25,5000); gap.sample(145,9000);
        missing(gap.times[0]); missing(gap.times[6]);
        gap.resetMotion();
        gap.sample(0,7000); gap.sample(15,8000); gap.sample(6,9000); gap.sample(64,10000); missing(gap.times[0]);
        TripMath.Run automatic=new TripMath.Run();
        List<Long> launches=new ArrayList<>(); List<Double> elapsed=new ArrayList<>();
        TripMath.SplitListener crossings=(band,launchedAt,seconds) -> {
            if (band==0) { launches.add(launchedAt); elapsed.add(seconds); }
        };
        automatic.sample(0,1000,crossings); automatic.sample(0,1900,crossings);
        automatic.sample(20,2900,crossings); automatic.sample(18,3500,crossings); automatic.sample(21,4100,crossings);
        automatic.sample(2,4700,crossings); automatic.sample(21,5200,crossings);
        automatic.sample(0,6000,crossings); automatic.sample(1,6400,crossings); automatic.sample(20,7300,crossings);
        automatic.resetMotion(); automatic.sample(10,9000,crossings); automatic.sample(21,9800,crossings);
        if (!launches.equals(Arrays.asList(1900L,6000L))) throw new AssertionError("Dwie próby po pełnym zatrzymaniu");
        equal(elapsed.get(0),1); equal(elapsed.get(1),1.3); equal(automatic.times[0],1);
        List<String> seen=new ArrayList<>();
        Elm.Link dummy=new Elm.Link() {
            public String query(String request,int timeout) {
                seen.add(request);
                if (request.startsWith("AT")) return "ATZ".equals(request) ? "ELM327 v1.5>" : "OK>";
                switch (request) {
                    case "010D": return "410D46>";
                    case "010C": return "410C1F40>";
                    case "012F": return "412F80>";
                    case "0105": return "410582>";
                    case "019D": return "419D0019>";
                    case "01A6": return "41A60012D687>";
                    default: return "NO DATA>";
                }
            }
            public void close() {}
        };
        Elm session=new Elm(dummy);
        session.init(); equal(session.speed(),70);
        Elm.Sample sample=session.read("diesel",0);
        equal(sample.rpm,2000); equal(sample.rate,0.5*3600/832); equal(sample.source,4);
        equal(sample.fuel,128*100/255.0);
        missing(session.read("diesel",1).fuel);
        equal(session.temperature(true),90); missing(session.temperature(false));
        equal(session.odometer(),123456.7);
        if (!seen.containsAll(Arrays.asList("0105","015C"))) throw new AssertionError(seen);
        if (seen.contains("0110") || seen.contains("04") || !seen.containsAll(Arrays.asList("ATSP0","0100")))
            throw new AssertionError(seen);
        rediscoverAfterEcuLoss();
        System.out.println("OK: ELM, ponowne wykrywanie ECU, spalanie, odstępy w pomiarze, progi 0–100 i 100–200");
    }

    private static void rediscoverAfterEcuLoss() throws Exception {
        List<String> sent=new ArrayList<>();
        Elm session=new Elm(new Elm.Link() {
            private int discoveryAttempts;
            @Override public String query(String request,int timeout) {
                sent.add(request);
                switch (request) {
                    case "ATSP0": return "OK>";
                    case "0100": return ++discoveryAttempts>1 ? "SEARCHING...\r41 00 BE 1F A8 13\r4100983BA013>" : "NO DATA>";
                    case "010D": return discoveryAttempts>1 ? "410D00>" : "UNABLE TO CONNECT>";
                    default: throw new AssertionError("Nieoczekiwane polecenie "+request);
                }
            }
            @Override public void close() {}
        });
        missing(session.speed());
        if (session.rediscoverProtocol()) throw new AssertionError("NO DATA to brak odpowiedzi ECU");
        missing(session.speed());
        if (!session.rediscoverProtocol()) throw new AssertionError("ECU nie zostało ponownie wykryte");
        equal(session.speed(),0);
        if (!sent.equals(Arrays.asList("010D","ATSP0","0100","010D","ATSP0","0100","010D")))
            throw new AssertionError(sent);
    }
}
