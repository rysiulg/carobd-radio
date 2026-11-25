package pl.marm.carobd;

import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Przeniesiony z CarOBD ESPHome zapis wyłącznie trzech ustawień przypomnienia CLU. */
final class KiaService {
    private static final String CLUSTER="7C6", CLUSTER_REPLY="7CE";
    private static final int TIMEOUT=1700, VERIFY_ATTEMPTS=3;

    enum Field {
        MODE("0070",1), KM("0072",3), MONTHS("0073",2);
        final String id;
        final int bytes;
        Field(String id,int bytes) { this.id=id; this.bytes=bytes; }
        int value(Elm.ServiceInterval interval) {
            switch (this) {
                case KM: return interval.km;
                case MONTHS: return interval.months;
                default: return interval.mode;
            }
        }
        boolean accepts(int value) {
            switch (this) {
                case KM: return value>=5000 && value<=60000 && value%500==0;
                case MONTHS: return value>=1 && value<=36;
                default: return value==0 || value==1;
            }
        }
        String read(Elm.Link link) throws Exception {
            return EngineHours.payload(link.query("22"+id,TIMEOUT),"62"+id,3+bytes,this==KM ? 11 : 3+bytes);
        }
        String encode(int desired,String original) {
            return String.format(Locale.ROOT,"%0"+(2*bytes)+"X",desired)+original.substring(2*bytes);
        }
    }

    static final class Result {
        final Elm.ServiceInterval values;
        final boolean verified;
        final String message;
        Result(Elm.ServiceInterval values,boolean verified,String message) {
            this.values=values; this.verified=verified; this.message=message;
        }
    }

    static Result saveForVerifiedKia(Elm elm,Elm.Link link,Elm.ServiceInterval expected,Elm.ServiceInterval target,
                                      BooleanSupplier active) throws Exception {
        if (expected==null || target==null || !target.validChangeFrom(expected))
            return failure(null,"Nieprawidłowe ustawienia przeglądu");
        if (!active.getAsBoolean() || elm.speed()!=0) return failure(null,"Zapis dostępny tylko na postoju");
        if (!link.query("ATDPN",900).matches("(?is).*?(?:^|[\\r\\n])\\s*A?6\\s*(?:[>\\r\\n]|$).*"))
            return failure(null,"Adapter nie potwierdził właściwego protokołu Kia");
        int verified=0;
        Elm.ServiceInterval current=expected;
        for (Field field : Field.values()) {
            if (field.value(expected)==field.value(target) || field.value(current)==field.value(target)) continue;
            Result result=saveField(elm,link,field,current,field.value(target),active);
            if (!result.verified) return verified==0 ? result
                : failure(result.values,"Potwierdzono zmiany: "+verified+". "+result.message);
            // Przełączenie trybu może przywrócić wartości domyślne pozostałych pól.
            current=readForVerifiedKia(elm,link);
            if (current==null || field.value(current)!=field.value(target))
                return failure(current,"Odczyt po zapisie różni się od ustawień lub jest niedostępny");
            verified++;
        }
        if (verified==0) return new Result(current,true,"Brak zmian");
        for (Field field : Field.values())
            if (field.value(expected)!=field.value(target) && field.value(current)!=field.value(target))
                return failure(current,"Zegary zwróciły inne wartości niż wybrane");
        return new Result(current,true,"Zapisano i potwierdzono odczytem z zegarów");
    }

    static Elm.ServiceInterval readForVerifiedKia(Elm elm,Elm.Link link) throws Exception {
        try {
            if (!elm.addressKia(CLUSTER,CLUSTER_REPLY)) return null;
            return values(Field.MODE.read(link),Field.KM.read(link),Field.MONTHS.read(link));
        } finally { elm.restoreAddress(); }
    }

    private static Result saveField(Elm elm,Elm.Link link,Field field,Elm.ServiceInterval expected,int desired,
                                    BooleanSupplier active) throws Exception {
        boolean diagnosticSession=false;
        try {
            if (!active.getAsBoolean() || elm.speed()!=0) return failure(null,"Zapis przerwany. Auto musi stać");
            if (!elm.addressKia(CLUSTER,CLUSTER_REPLY)) return failure(null,"Adapter nie przełączył się na zegary");
            String mode=Field.MODE.read(link), km=Field.KM.read(link), months=Field.MONTHS.read(link);
            Elm.ServiceInterval before=values(mode,km,months);
            if (before==null) return failure(null,"Brak pełnego odczytu ustawień zegarów");
            if (!before.same(expected)) return failure(before,"Ustawienia w zegarach zmieniły się. Sprawdź nowy odczyt");
            String original=field==Field.KM ? km : field==Field.MONTHS ? months : mode;

            if (!active.getAsBoolean()) return failure(before,"Przerwano połączenie z profilem Kia");
            link.query("20",TIMEOUT); // Starsze zegary mogą odrzucić tę komendę przygotowawczą.
            diagnosticSession=true;
            if (!positive(link.query("1003",TIMEOUT),"5003",2,6))
                return failure(before,"Zegary odrzuciły sesję zapisu");
            String response=EngineHours.payload(link.query("2701",TIMEOUT),"6701",6);
            if (response==null) return failure(before,"Zegary nie potwierdziły dostępu do ustawień");
            long seed=Long.parseLong(response,16);
            if (seed!=0 && !positive(link.query(String.format(Locale.ROOT,"2702%08X",(int)(12L-seed)),TIMEOUT),"6702",2,2))
                return failure(before,"Zegary odmówiły dostępu do zapisu");

            // Powtórz kontrolę prędkości z rzeczywistego ECU, po rozpoczęciu sesji.
            if (!active.getAsBoolean() || !setup(link,"ATFCSM0","ATCRA7E8","ATSH7E0") || elm.speed()!=0)
                return failure(before,"Zapis przerwany. Brak potwierdzonego postoju");
            if (!setup(link,"ATSH"+CLUSTER,"ATCRA"+CLUSTER_REPLY,"ATFCSM1") ||
                !original.equals(field.read(link)) || !active.getAsBoolean())
                return failure(null,"Nie można potwierdzić aktualnych ustawień. Odczytaj je ponownie");

            String changed=field.encode(desired,original);
            try { link.query("2E"+field.id+changed,TIMEOUT); }
            catch (IOException uncertain) { /* Ustal wynik odczytem. Zapis mógł dotrzeć bez odpowiedzi. */ }
            for (int attempt=0;attempt<VERIFY_ATTEMPTS;attempt++) {
                Thread.sleep(1000);
                if (changed.equals(field.read(link))) return new Result(null,true,"");
            }
            return failure(null,"Zegary nie potwierdziły zapisu. Odczytaj ustawienia ponownie");
        } finally {
            try {
                if (diagnosticSession) {
                    if (!elm.addressKia(CLUSTER,CLUSTER_REPLY) || !positive(link.query("1001",TIMEOUT),"5001",2,6))
                        throw new IOException("Zegary nie potwierdziły zakończenia sesji");
                }
            } finally { elm.restoreAddress(); }
        }
    }

    static Elm.ServiceInterval values(String mode,String km,String months) {
        if (mode==null || km==null || months==null) return null;
        int m=Integer.parseInt(mode,16);
        return m<=1 ? new Elm.ServiceInterval(Integer.parseInt(km.substring(0,6),16),Integer.parseInt(months,16),m) : null;
    }
    private static boolean positive(String reply,String prefix,int min,int max) {
        return EngineHours.payload(reply,prefix,min,max)!=null;
    }
    private static boolean setup(Elm.Link link,String... commands) throws Exception {
        for (String command : commands) if (!link.query(command,900).contains("OK")) return false;
        return true;
    }
    private static Result failure(Elm.ServiceInterval values,String message) { return new Result(values,false,message); }
}
