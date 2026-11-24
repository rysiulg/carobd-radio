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
        missing(Elm.decode(0x10,"NO DATA"));
        missing(Elm.decode(0x0d,"7F0112"));
        missing(Elm.decode(0x0d,"410D"));
        equal(Elm.decode(0x2f,"412F00"),0);
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
            "5:000000FFFF0003\r6:020000075BD9A0\r7:00005604075BCD\r8:0C0003000A0000\r9:000000FFFF5555\r>";
        equal(Elm.decodeKiaOdometer(kiaOdo),123456.78);
        missing(Elm.decodeKiaOdometer(kiaOdo.replace("8:0C","A:0C")));
        missing(Elm.decodeKiaOdometer("7F2231>"));
        gear(KiaGear.UDS,"6201A0",8,4,71,"D4");
        gear(KiaGear.UDS,"6201A0",12,3,42,"M3");
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
        equal(TripMath.trapezoid(80,100,1000),0.025);
        TripMath.Run run=new TripMath.Run();
        run.sample(0,1000); run.sample(30,2000); run.sample(60,3000); run.sample(145,5000);
        equal(run.times[0],2.0/3);
        equal(run.times[1],4.0/3);
        equal(run.times[6],2+2*80.0/85);
        TripMath.Run gap=new TripMath.Run();
        gap.sample(0,1000); gap.sample(25,5000); gap.sample(145,6000);
        missing(gap.times[0]); missing(gap.times[6]);
        gap.sample(0,7000); gap.sample(15,8000); gap.sample(6,9000); gap.sample(64,10000); missing(gap.times[0]);
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
        equal(session.temperature(true),90); missing(session.temperature(false));
        equal(session.odometer(),123456.7);
        if (!seen.containsAll(Arrays.asList("0105","015C"))) throw new AssertionError(seen);
        if (seen.contains("0110") || seen.contains("04") || !seen.containsAll(Arrays.asList("ATSP0","0100")))
            throw new AssertionError(seen);
        System.out.println("OK: ELM, spalanie, odstępy w pomiarze, progi 20–140");
    }
}
