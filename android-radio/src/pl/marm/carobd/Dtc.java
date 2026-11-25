package pl.marm.carobd;

import java.util.*;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Statusy kodów i kontrolka MIL w standardowym EOBD. */
final class Dtc {
    private static final long PERIOD_MS=15000;
    private static final int MIL=0x80;
    static final String[] STATUS={"Zapisany", "Oczekujący", "Trwały"};
    private static final String[] COMMAND={"03","07","0A"}, REPLY={"43","47","4A"};
    private static final String[] CONTINUOUS={"Wypadanie zapłonu","Układ paliwowy","Podzespoły silnika"};
    private static final String[] SPARK={"Katalizator","Podgrzewany katalizator","Odparowanie paliwa","Powietrze wtórne",
        null,"Sonda lambda","Grzałka sondy lambda","EGR / zmienne fazy rozrządu"};
    private static final String[] DIESEL={"Katalizator NMHC","Układ oczyszczania NOx / SCR",null,"Sterowanie doładowaniem",
        null,"Czujniki spalin","Filtr cząstek stałych","EGR / zmienne fazy rozrządu"};

    static final class Report {
        final int flags;
        final List<List<String>> results;
        final Map<String,Boolean> monitors;
        Report(Readiness readiness,List<List<String>> results) {
            flags=readiness.flags; monitors=readiness.monitors; this.results=results;
        }
        boolean mil() { return flags>=0 && (flags&MIL)!=0; }
        int storedCount() { return flags<0 ? -1 : flags&0x7f; }
        boolean engineWarning() {
            if (mil()) return true;
            for (int i=0;i<2;i++) {
                List<String> found=results.get(i);
                if (found!=null) for (String code:found) if (code.startsWith("P")) return true;
            }
            return false;
        }
        boolean clearable() {
            return mil() || storedCount()>0 || (results.get(0)!=null && !results.get(0).isEmpty()) ||
                (results.get(1)!=null && !results.get(1).isEmpty());
        }
        String firstCode() {
            for (List<String> found:results) if (found!=null && !found.isEmpty()) return found.get(0);
            return "";
        }
        Map<String,Integer> codes() {
            Map<String,Integer> status=new LinkedHashMap<>();
            for (int i=0;i<results.size();i++) {
                List<String> found=results.get(i);
                if (found==null) continue;
                int bit=1<<i;
                for (String code:found) status.merge(code,bit,(a,b) -> a|b);
            }
            return status;
        }
        List<String> lines() {
            List<String> lines=new ArrayList<>();
            for (String code:codes().keySet()) lines.add(code+" — "+description(code));
            if (lines.isEmpty() && clearable()) lines.add(mil() ? "Kontrolka Check Engine · brak odczytanych kodów"
                : "Sterownik zgłasza zapisane usterki · brak odczytanych kodów");
            return Collections.unmodifiableList(lines);
        }
    }

    static final class Monitor {
        private long due;
        private int stage=-1;
        private Readiness readiness;
        private final List<List<String>> results=new ArrayList<>(Arrays.asList(null,null,null));

        // Rozdzielamy status ECU i odczyt kodów, aby pomiędzy nimi zebrać prędkość i telemetrię.
        Report poll(Elm session,long now) throws Exception {
            if (stage<0) {
                if (now<due) return null;
                due=now+PERIOD_MS;
                readiness=status(session.diagnostic("0101")); stage=0;
                return null;
            }
            List<String> codes=readCodes(session.diagnostic(COMMAND[stage]),REPLY[stage]);
            results.set(stage,codes==null ? null : Collections.unmodifiableList(codes));
            if (++stage<COMMAND.length) return null;
            stage=-1;
            return new Report(readiness,Collections.unmodifiableList(new ArrayList<>(results)));
        }
    }

    static String clear(Elm session,BooleanSupplier stillAllowed) throws Exception {
        if (!stillAllowed.getAsBoolean() || session.speed()!=0 || session.rpm()!=0 || !stillAllowed.getAsBoolean())
            return "Kasowanie wymaga postoju, włączonego zapłonu i zgaszonego silnika";
        // Jedno żądanie. Brak odpowiedzi może oznaczać, że ECU już wykonał operację.
        try {
            for (String row:rows(session.diagnostic("04"))) if (row.matches("44(?:00){0,7}"))
                return "Sterownik potwierdził przyjęcie kasowania";
        } catch (IOException uncertain) { /* O wyniku poinformuje kolejny odczyt. */ }
        return "Brak potwierdzenia kasowania · sprawdź aktualny status kodów";
    }

    static final class Readiness {
        final int flags;
        final Map<String,Boolean> monitors;
        Readiness(int flags,Map<String,Boolean> monitors) {
            this.flags=flags; this.monitors=Collections.unmodifiableMap(monitors);
        }
    }
    // Obsługa i gotowość są parami bitów z tej samej odpowiedzi ECU.
    static Readiness status(String response) {
        int result=-1;
        Map<String,Boolean> monitors=new LinkedHashMap<>();
        for (String row:rows(response)) {
            if (!row.matches("4101[0-9A-F]{8}(?:[0-9A-F]{2}){0,8}")) continue;
            int status=Integer.parseInt(row.substring(4,6),16), previous=Math.max(0,result);
            result=((status|previous)&MIL) | Math.max(status&0x7f,previous&0x7f);
            int continuous=Integer.parseInt(row.substring(6,8),16);
            int supported=Integer.parseInt(row.substring(8,10),16), unfinished=Integer.parseInt(row.substring(10,12),16);
            addMonitors(monitors,CONTINUOUS,continuous,continuous>>>4);
            addMonitors(monitors,(continuous&8)==0 ? SPARK : DIESEL,supported,unfinished);
        }
        return new Readiness(result,monitors);
    }
    private static void addMonitors(Map<String,Boolean> monitors,String[] names,int supported,int unfinished) {
        for (int i=0;i<names.length;i++) {
            int bit=1<<i;
            if (names[i]!=null && (supported&bit)!=0)
                monitors.merge(names[i],(unfinished&bit)==0,(ready,other) -> ready && other);
        }
    }

    // ELM bez nagłówków zwraca osobne linie albo bloki CAN opisane 0:, 1:, ... od każdego ECU.
    static List<String> confirmed(String response) {
        return readCodes(response,"43");
    }
    static List<String> readCodes(String response,String expectedReply) {
        LinkedHashSet<String> codes=new LinkedHashSet<>();
        StringBuilder block=new StringBuilder();
        boolean valid=false;
        int expected=0;
        for (String row:rows(response)) {
            if (row.matches("[0-9A-F]:[0-9A-F]+")) {
                int frame=Character.digit(row.charAt(0),16);
                if (frame==0) { valid|=payload(block.toString(),true,codes,expectedReply); block.setLength(0); expected=0; }
                if (frame!=expected || (frame!=0 && block.length()==0)) { block.setLength(0); continue; }
                block.append(row.substring(2)); expected=(frame+1)&15;
            } else {
                valid|=payload(block.toString(),true,codes,expectedReply); block.setLength(0); expected=0;
                valid|=payload(row,false,codes,expectedReply);
            }
        }
        valid|=payload(block.toString(),true,codes,expectedReply);
        return valid ? new ArrayList<>(codes) : null;
    }

    private static String[] rows(String response) {
        return response.toUpperCase(Locale.ROOT).replaceAll("[ \\t]","").split("[\\r\\n>]+");
    }
    private static boolean payload(String row,boolean multiline,Set<String> codes,String reply) {
        if (row.length()>1040 || !row.startsWith(reply) || !row.matches("[0-9A-F]{2}(?:[0-9A-F]{2})+")) return false;
        int body=row.length()-2, offset=2, count=body/4;
        if (multiline || body%4==2) {
            offset=4; count=Integer.parseInt(row.substring(2,4),16);
            if (offset+count*4>row.length()) return false;
        }
        for (int i=0;i<count;i++,offset+=4) {
            int value=Integer.parseInt(row.substring(offset,offset+4),16);
            if (value!=0) codes.add(String.format(Locale.ROOT,"%c%d%03X","PCBU".charAt(value>>>14),(value>>>12)&3,value&0xfff));
        }
        return true;
    }

    static String category(String code) {
        switch (code.charAt(0)) {
            case 'P': return "Układ napędowy";
            case 'C': return "Podwozie";
            case 'B': return "Nadwozie";
            case 'U': return "Komunikacja między sterownikami";
            default: return "";
        }
    }
    // Najczęstsze kody ogólne, w tym usterki doładowania, EGR i filtra diesla.
    static String description(String code) {
        switch (code) {
            case "P0087": return "Za niskie ciśnienie paliwa w układzie";
            case "P0088": return "Za wysokie ciśnienie paliwa w układzie";
            case "P0101": return "Sygnał przepływomierza powietrza poza zakresem";
            case "P0113": return "Wysoki sygnał czujnika temperatury powietrza dolotowego";
            case "P0118": return "Wysoki sygnał czujnika temperatury płynu chłodzącego";
            case "P0128": return "Temperatura płynu chłodzącego poniżej wartości regulacji termostatu";
            case "P0133": return "Powolna odpowiedź sondy lambda, bank 1, czujnik 1";
            case "P0171": return "Zbyt uboga mieszanka, bank 1";
            case "P0172": return "Zbyt bogata mieszanka, bank 1";
            case "P0191": return "Sygnał czujnika ciśnienia na listwie paliwowej poza zakresem";
            case "P0200": return "Usterka obwodu sterowania wtryskiwaczy";
            case "P0234": return "Za wysokie ciśnienie doładowania";
            case "P0236": return "Sygnał czujnika ciśnienia doładowania A poza zakresem";
            case "P0299": return "Za niskie ciśnienie doładowania";
            case "P0300": return "Wypadanie zapłonu na jednym lub kilku cylindrach";
            case "P0301": case "P0302": case "P0303": case "P0304": case "P0305": case "P0306": case "P0307": case "P0308":
                return "Wypadanie zapłonu na cylindrze "+code.charAt(4);
            case "P0380": return "Usterka obwodu świec żarowych A";
            case "P0401": return "Za mały przepływ spalin przez układ EGR";
            case "P0402": return "Za duży przepływ spalin przez układ EGR";
            case "P0420": return "Sprawność katalizatora poniżej progu, bank 1";
            case "P0562": return "Za niskie napięcie instalacji pojazdu";
            case "P0700": return "Sterownik skrzyni biegów zgłosił usterkę";
            case "P2002": return "Sprawność filtra cząstek stałych poniżej progu, bank 1";
            case "P2453": return "Sygnał czujnika różnicy ciśnień filtra DPF poza zakresem";
            case "P2463": return "Nagromadzenie sadzy ogranicza przepływ przez filtr DPF";
            case "U0100": return "Brak komunikacji ze sterownikiem silnika";
            default: return "Brak opisu tego kodu w aplikacji";
        }
    }
}
