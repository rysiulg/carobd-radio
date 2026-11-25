package pl.marm.carobd;

import java.util.*;

public final class ServiceTest {
    private static void equal(Object got,Object wanted) {
        if (!Objects.equals(got,wanted)) throw new AssertionError(got+" != "+wanted);
    }
    public static void main(String[] args) throws Exception {
        String data="03017FB0F3005A200A00000000";
        String frames="00F\r\n0: 417F03017FB0\r\n1: F3005A200A0000\r\n2: 00000000000000\r>";
        equal(EngineHours.payload(frames,"417F",15),data);
        equal(EngineHours.payload("017F\r417F"+data+"\r>","417F",15),data);
        equal(EngineHours.payload(frames.replace("1:","3:"),"417F",15),null);
        equal(EngineHours.payload(frames.replace("00F","010"),"417F",15),null);
        equal(EngineHours.payload("417F"+data+"\r417F"+data.replace("B0F3","B0F4")+">","417F",15),null);
        equal(EngineHours.decode(data,false,0).total,25145587L);
        equal(EngineHours.decode(data,false,0).idle,5906442L);
        equal(EngineHours.decode("01"+data.substring(2),false,0).idle,-1L);
        String zeroMask="00"+data.substring(2);
        equal(EngineHours.decode(zeroMask,false,0),null);
        equal(EngineHours.decode(zeroMask,true,0).total,25145587L);
        equal(EngineHours.matchesKia("490201"+String.join("",Collections.nCopies(17,"31"))+">"),false);

        List<String> calls=new ArrayList<>();
        Queue<String> replies=new ArrayDeque<>(Arrays.asList(frames,"417F"+data+">","417F"+zeroMask+">","NO DATA>"));
        Elm elm=new Elm(new Elm.Link() {
            public String query(String cmd,int timeout) { calls.add(cmd); return replies.remove(); }
            public void close() {}
        });
        EngineHours.Monitor hours=new EngineHours.Monitor();
        equal(hours.poll(elm,"other",0),null);
        equal(hours.due(2999),false);
        equal(hours.poll(elm,"other",3000).total,25145587L);
        equal(hours.due(32999),false);
        equal(hours.poll(elm,"kia-radio",33000),null);
        equal(hours.poll(elm,"kia-radio",36000),null);
        equal(calls,Arrays.asList("017F","017F","017F","0902"));
        equal(hours.due(40000),false);
        repeatedObsoleteReading(data);
        Elm.ServiceInterval interval=Elm.serviceInterval("62007001>","620072007530FF>","620073000F>");
        equal(interval.km,30000); equal(interval.months,15); equal(interval.mode,1);
        equal(Elm.serviceInterval("62007000>","620072007530>","62007300>"),null);
        equal(Elm.serviceInterval("62007002>","620072007530>","620073000F>"),null);

        System.out.println("OK: motogodziny ISO-TP, kontrola próbek, identyfikacja ECU i odczyt przeglądu");
    }

    private static void repeatedObsoleteReading(String data) throws Exception {
        String obsolete=data.replace("017FB0F3","017FA000");
        Queue<String> replies=new ArrayDeque<>(Arrays.asList(data,data,obsolete,obsolete,data));
        Elm elm=new Elm(new Elm.Link() {
            public String query(String cmd,int timeout) { return "417F"+replies.remove()+">"; }
            public void close() {}
        });
        EngineHours.Monitor hours=new EngineHours.Monitor();
        equal(hours.poll(elm,"other",0),null);
        equal(hours.poll(elm,"other",3000).total,25145587L);
        equal(hours.poll(elm,"other",33000),null);
        equal(hours.poll(elm,"other",36000),null);
        equal(hours.poll(elm,"other",39000).total,25145587L);
    }
}
