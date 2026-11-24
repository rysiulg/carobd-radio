package pl.marm.carobd;

import java.util.*;

/** Potwierdzone kody EOBD oraz stan kontrolki MIL, bez poleceń zapisu. */
final class Dtc {
    private static final long PERIOD_MS=15000;
    private static final int MIL=0x80;

    static final class Monitor {
        private long due;
        private int flags=-1;
        private boolean readCodes;
        private List<String> codes=Collections.emptyList();

        // Rozdzielamy status ECU i odczyt kodów, aby pomiędzy nimi zebrać prędkość i telemetrię.
        List<String> poll(Elm session,long now) throws Exception {
            if (readCodes) {
                readCodes=false;
                List<String> reply=confirmed(session.diagnostic("03"));
                if (reply!=null) codes=reply;
            } else {
                if (now<due) return null;
                due=now+PERIOD_MS;
                int reply=readiness(session.diagnostic("0101"));
                if (reply>=0) flags=reply;
                readCodes=reply!=0;
                if (!readCodes) codes=Collections.emptyList();
            }
            if (codes.isEmpty() && flags<=0) return Collections.emptyList();
            List<String> lines=new ArrayList<>();
            for (String code:codes) lines.add(code+" — "+description(code));
            if (lines.isEmpty()) lines.add((flags&MIL)!=0 ? "Kontrolka Check Engine · brak odczytanych kodów" : "Sterownik zgłasza zapisane usterki · brak odczytanych kodów");
            return Collections.unmodifiableList(lines);
        }
    }

    // Kilka ECU może odpowiedzieć osobno. Zera uznajemy tylko z prawidłowej odpowiedzi 41 01.
    static int readiness(String response) {
        int result=-1;
        for (String row:rows(response)) {
            if (!row.matches("4101[0-9A-F]{8}(?:[0-9A-F]{2}){0,8}")) continue;
            int status=Integer.parseInt(row.substring(4,6),16), previous=Math.max(0,result);
            result=((status|previous)&MIL) | Math.max(status&0x7f,previous&0x7f);
        }
        return result;
    }

    // ELM bez nagłówków zwraca osobne linie albo bloki CAN opisane 0:, 1:, ... od każdego ECU.
    static List<String> confirmed(String response) {
        LinkedHashSet<String> codes=new LinkedHashSet<>();
        StringBuilder block=new StringBuilder();
        boolean valid=false;
        int expected=0;
        for (String row:rows(response)) {
            if (row.matches("[0-9A-F]:[0-9A-F]+")) {
                int frame=Character.digit(row.charAt(0),16);
                if (frame==0) { valid|=payload(block.toString(),true,codes); block.setLength(0); expected=0; }
                if (frame!=expected || (frame!=0 && block.length()==0)) { block.setLength(0); continue; }
                block.append(row.substring(2)); expected=(frame+1)&15;
            } else {
                valid|=payload(block.toString(),true,codes); block.setLength(0); expected=0;
                valid|=payload(row,false,codes);
            }
        }
        valid|=payload(block.toString(),true,codes);
        return valid ? new ArrayList<>(codes) : null;
    }

    private static String[] rows(String response) {
        return response.toUpperCase(Locale.ROOT).replaceAll("[ \\t]","").split("[\\r\\n>]+");
    }
    private static boolean payload(String row,boolean multiline,Set<String> codes) {
        if (row.length()>1040 || !row.matches("43(?:[0-9A-F]{2})+")) return false;
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
