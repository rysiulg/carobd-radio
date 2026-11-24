package pl.marm.carobd;

import java.util.*;

public final class DtcTest {
    private static void equal(Object actual,Object expected) {
        if (!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) throws Exception {
        equal(Dtc.readiness("0101\r410100076504\r41 01 82 07 65 04>"),0x82);
        equal(Dtc.readiness("410181\rNO DATA>"),-1);
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

        List<String> commands=new ArrayList<>();
        Queue<String> replies=new ArrayDeque<>(Arrays.asList("410100076504>","410181076504>","43010299>","NO DATA>","NO DATA>","410100076504>"));
        Elm session=new Elm(new Elm.Link() {
            public String query(String command,int timeout) { commands.add(command); return replies.remove(); }
            public void close() {}
        });
        Dtc.Monitor monitor=new Dtc.Monitor();
        equal(monitor.poll(session,0),Collections.emptyList());
        equal(monitor.poll(session,1000),null);
        equal(monitor.poll(session,15000),Collections.singletonList("Kontrolka Check Engine · brak odczytanych kodów"));
        List<String> warning=Collections.singletonList("P0299 — Za niskie ciśnienie doładowania");
        equal(monitor.poll(session,15500),warning);
        equal(monitor.poll(session,30000),warning);
        equal(monitor.poll(session,30500),warning);
        equal(monitor.poll(session,45000),Collections.emptyList());
        equal(commands,Arrays.asList("0101","0101","03","0101","03","0101"));
        System.out.println("OK: kody OBD, wieloramkowe CAN, MIL, brak odpowiedzi, cykl tylko do odczytu");
    }
}
