package pl.marm.carobd;

import java.util.*;

public final class DtcTest {
    private static void equal(Object actual,Object expected) {
        if (!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) throws Exception {
        equal(Dtc.status("0101\r410100076504\r41 01 82 07 65 04>").flags,0x82);
        equal(Dtc.status("410181\rNO DATA>").flags,-1);
        Dtc.Readiness diesel=Dtc.status("41 01 82 2F EA 48\r4101000F4000>");
        equal(diesel.monitors.get("Filtr cząstek stałych"),false);
        equal(diesel.monitors.get("Sterowanie doładowaniem"),false);
        equal(diesel.monitors.get("Układ paliwowy"),false);
        equal(diesel.monitors.get("Układ oczyszczania NOx / SCR"),true);
        equal(diesel.monitors.get("Grzałka sondy lambda"),null);
        Dtc.Readiness spark=Dtc.status("410100171504>");
        equal(spark.monitors.get("Katalizator"),true);
        equal(spark.monitors.get("Odparowanie paliwa"),false);
        equal(spark.monitors.get("Wypadanie zapłonu"),false);
        equal(Dtc.status("NO DATA>").monitors.size(),0);
        equal(Dtc.confirmed("43 01 33 00 00 00 00\r>"),Collections.singletonList("P0133"));
        equal(Dtc.confirmed("43 01 02 99\r43 02 02 99 C1 00\r>"),Arrays.asList("P0299","U0100"));
        equal(Dtc.confirmed("00A\r0: 43 04 02 99 04 01\r1: 04 20 03 01 00 00\r009\r0: 43 03 02 99 40 05\r1: 80 01 00 00\r>"),
            Arrays.asList("P0299","P0401","P0420","P0301","C0005","B0001"));
        equal(Dtc.confirmed("0:43 02 02 99\r2:04 01\r>"),null);
        equal(Dtc.confirmed("43 03 04 01\r>"),null);
        equal(Dtc.confirmed("NO DATA>"),null);
        equal(Dtc.confirmed("43 00>"),Collections.emptyList());
        equal(Dtc.description("P0401"),"Za mały przepływ spalin przez układ EGR");
        equal(Dtc.description("P1234"),"Brak opisu tego kodu w aplikacji");
        equal(Dtc.category("P1234"),"Układ napędowy");
        equal(Dtc.category("U0100"),"Komunikacja między sterownikami");

        List<String> commands=new ArrayList<>();
        Queue<String> replies=new ArrayDeque<>(Arrays.asList("410181076504>","43010299>","470202990401>","4A010299>",
            "NO DATA>","4300>","NO DATA>","4A00>"));
        Elm session=new Elm(new Elm.Link() {
            public String query(String command,int timeout) { commands.add(command); return replies.remove(); }
            public void close() {}
        });
        Dtc.Monitor monitor=new Dtc.Monitor();
        equal(monitor.poll(session,0),null);
        equal(monitor.poll(session,1000),null);
        equal(monitor.poll(session,2000),null);
        Dtc.Report report=monitor.poll(session,3000);
        equal(report.mil(),true); equal(report.storedCount(),1); equal(report.clearable(),true);
        equal(report.codes().get("P0299"),7); equal(report.codes().get("P0401"),2);
        equal(report.firstCode(),"P0299");
        equal(monitor.poll(session,4500),null);
        monitor.poll(session,15000); monitor.poll(session,16000); monitor.poll(session,17000);
        Dtc.Report partial=monitor.poll(session,18000);
        equal(partial.flags,-1); equal(partial.results.get(1),null); equal(partial.clearable(),false);
        equal(partial.firstCode(),"");
        equal(commands,Arrays.asList("0101","03","07","0A","0101","03","07","0A"));
        equal(Dtc.readCodes("4A010420>","4A"),Collections.singletonList("P0420"));
        Dtc.Report permanent=new Dtc.Report(Dtc.status("410100000000"),Arrays.asList(null,null,Collections.singletonList("P0420")));
        equal(permanent.clearable(),false);
        equal(permanent.engineWarning(),false);
        equal(report.engineWarning(),true);
        Dtc.Readiness noMil=Dtc.status("410100000000");
        equal(new Dtc.Report(noMil,Arrays.asList(Collections.singletonList("P0299"),null,null)).engineWarning(),true);
        equal(new Dtc.Report(noMil,Arrays.asList(null,Collections.singletonList("P0401"),null)).engineWarning(),true);
        equal(new Dtc.Report(noMil,Arrays.asList(Collections.singletonList("U0100"),null,null)).engineWarning(),false);
        equal(Elm.decode(Elm.AMBIENT,"41 46 2B\r>"),3.0);
        equal(Elm.decode(Elm.AMBIENT,"NO DATA>"),Double.NaN);

        commands.clear(); replies.addAll(Arrays.asList("410D00>","410C0000>","44>"));
        if (!Dtc.clear(session,() -> true).startsWith("Sterownik potwierdził")) throw new AssertionError("Brak potwierdzenia");
        equal(commands,Arrays.asList("010D","010C","04"));
        commands.clear(); replies.addAll(Arrays.asList("410D00>","410C0C00>"));
        Dtc.clear(session,() -> true); equal(commands,Arrays.asList("010D","010C"));
        commands.clear(); replies.addAll(Arrays.asList("410D00>","410C0000>","NO DATA>"));
        if (!Dtc.clear(session,() -> true).startsWith("Brak potwierdzenia")) throw new AssertionError("Fałszywe potwierdzenie");
        equal(commands,Arrays.asList("010D","010C","04"));
        System.out.println("OK: MIL, zapisane/oczekujące/trwałe DTC oraz pojedyncze kasowanie tylko przy zgaszonym silniku");
    }
}
