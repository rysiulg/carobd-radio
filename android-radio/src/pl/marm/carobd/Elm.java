package pl.marm.carobd;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wyłącznie odczyt OBD. Skalowanie jak w CarOBD ESP. */
final class Elm {
    static final int COOLANT=0x05, OIL=0x5c, FUEL_LEVEL=0x2f;
    static final double KIA_TANK_L=71;
    private static final String AUTO_PROTOCOL="ATSP0";
    private static final Pattern PID_LIST=Pattern.compile("(?im)^4100[0-9a-f]{8}(?:[0-9a-f]{2})*(?=[\\r\\n>]|$)");
    private static final double KIA_ODO_CORRECTION_KM=0.0; // Opcjonalna korekta kalibracji w kilometrach.
    private static final int KIA_ODO_OFFSET=49*2;
    interface Link extends AutoCloseable {
        String query(String command, int timeoutMs) throws Exception;
        void close();
    }
    static final class Sample {
        double speed=Double.NaN, rpm=Double.NaN, rate=Double.NaN, fuel=Double.NaN;
        int source;
    }
    private final Link link;
    private final ObdExchange exchange;
    private int fuelPid, missingFuel;
    private boolean kiaVerified;
    private String gearCommand;
    Elm(Link link) { this(link,null); }
    Elm(Link link,ObdExchange exchange) { this.link=link; this.exchange=exchange; }
    void init() throws Exception {
        String response=link.query("ATZ", 3200);
        if (response.trim().isEmpty()) throw new java.io.IOException("Adapter nie odpowiada");
        for (String command : new String[]{"ATE0","ATL0","ATS0","ATH0",AUTO_PROTOCOL,"ATAT1"}) {
            if (!link.query(command, 1800).contains("OK")) throw new java.io.IOException("Brak OK: " + command);
        }
        sample("0100",10500);
    }
    /** Ponawia wykrywanie protokołu po utracie ECU, bez resetowania łącza do adaptera. */
    boolean rediscoverProtocol() throws Exception {
        if (!link.query(AUTO_PROTOCOL,1800).contains("OK")) throw new java.io.IOException("Adapter odrzucił wykrywanie protokołu");
        String reply=sample("0100",10500).replace(" ","").replace("\t","");
        return PID_LIST.matcher(reply).find();
    }
    double speed() throws Exception { return value(0x0d); }
    double temperature(boolean coolant) throws Exception { return value(coolant ? COOLANT : OIL); }
    double odometer() throws Exception {
        String data=EngineHours.payload(sample("01A6",1700),"41A6",6);
        return data==null || "FFFFFFFF".equals(data) ? Double.NaN : Long.parseLong(data,16)/10.0;
    }
    String diagnostic(String command) throws Exception { return sample(command,"0101".equals(command) ? 1700 : 2500); }
    boolean confirmKia() throws Exception {
        if (!kiaVerified) kiaVerified=EngineHours.matchesKia(diagnostic("0902"));
        return kiaVerified;
    }
    // Ta sama sekwencja adresowania i przywracania ELM co w ESPHome.
    double kiaTankLiters() throws Exception {
        return decodeKiaTank(readKiaFrame("7C6","7CE","22B002",1700));
    }
    double kiaOdometer() throws Exception {
        return decodeKiaOdometer(readKiaFrame("7E0","7E8","22ED03",2500));
    }
    KiaGear kiaGear() throws Exception {
        if (!confirmKia()) return null;
        try {
            if (!addressKia("7E1","7E9")) return null;
            if (gearCommand!=null) return KiaGear.decode(gearCommand,link.query(gearCommand,950));
            for (String command:new String[]{KiaGear.UDS,KiaGear.LEGACY}) {
                KiaGear reading=KiaGear.decode(command,link.query(command,950));
                if (reading!=null) { gearCommand=command; return reading; }
            }
            return null;
        } finally { restoreAddress(); }
    }
    static final class ServiceInterval {
        final int km,months,mode;
        ServiceInterval(int km,int months,int mode) { this.km=km; this.months=months; this.mode=mode; }
    }
    ServiceInterval kiaService() throws Exception {
        if (!confirmKia()) return null;
        try {
            if (!addressKia("7C6","7CE")) return null;
            String mode=link.query("220070",1700), km=link.query("220072",1700), months=link.query("220073",1700);
            return serviceInterval(mode,km,months);
        } finally { restoreAddress(); }
    }
    static ServiceInterval serviceInterval(String rawMode,String rawKm,String rawMonths) {
        String mode=EngineHours.payload(rawMode,"620070",4);
        String months=EngineHours.payload(rawMonths,"620073",5);
        String km=null;
        for (int bytes=6;bytes<=11 && km==null;bytes++) km=EngineHours.payload(rawKm,"620072",bytes);
        if (km==null || months==null || mode==null) return null;
        int m=Integer.parseInt(mode,16);
        if (m>1) return null;
        return new ServiceInterval(Integer.parseInt(km.substring(0,6),16),Integer.parseInt(months,16),m);
    }
    private String readKiaFrame(String tx,String rx,String command,int timeout) throws Exception {
        if (!kiaVerified) return "";
        try {
            return addressKia(tx,rx) ? link.query(command,timeout) : "";
        } finally { restoreAddress(); }
    }
    private boolean addressKia(String tx,String rx) throws Exception {
        for (String setup:new String[]{"ATSH"+tx,"ATCRA"+rx,"ATFCSH"+tx,"ATFCSD300000","ATFCSM1"})
            if (!link.query(setup,900).contains("OK")) return false;
        return true;
    }
    private void restoreAddress() throws Exception {
        boolean restored=true;
        for (String setup:new String[]{"ATFCSM0","ATAR","ATSH7DF"})
            try { restored &= link.query(setup,900).contains("OK"); }
            catch (InterruptedException stopped) { throw stopped; }
            catch (Exception unavailable) { restored=false; }
        if (!restored) throw new java.io.IOException("Nie można przywrócić adresowania ELM");
    }
    static double decodeKiaOdometer(String reply) {
        String data=EngineHours.payload(reply,"62ED03",0x43);
        if (data==null) return Double.NaN;
        long meters=Long.parseLong(data.substring(KIA_ODO_OFFSET,KIA_ODO_OFFSET+8),16);
        double km=meters/1000.0+KIA_ODO_CORRECTION_KM;
        return km>=0 && km<=3000000 ? km : Double.NaN;
    }
    static double decodeKiaTank(String reply) {
        String data=EngineHours.payload(reply,"62B002",10);
        if (data==null) return Double.NaN;
        double liters=Integer.parseInt(data.substring(8,10),16)/2.0;
        return liters<=KIA_TANK_L ? liters : Double.NaN;
    }
    Sample read(String fuel, int tick) throws Exception {
        Sample data=new Sample();
        data.rpm=value(0x0c);
        if (tick % 5 == 0) data.fuel=value(FUEL_LEVEL);
        boolean diesel="diesel".equals(fuel);
        if (fuelPid==0 && tick % 8 == 0) {
            for (int pid : diesel ? new int[]{0x5e,0x9d} : new int[]{0x5e,0x9d,0x10}) {
                double test=value(pid);
                if (Double.isFinite(test) && test>=0) { fuelPid=pid; apply(data, test, diesel); break; }
            }
        } else if (fuelPid!=0) {
            apply(data, value(fuelPid), diesel);
            missingFuel = Double.isFinite(data.rate) ? 0 : missingFuel+1;
            if (missingFuel >= 3) { fuelPid=0; missingFuel=0; }
        }
        return data;
    }
    private void apply(Sample data, double raw, boolean diesel) {
        if (!Double.isFinite(raw)) return;
        double density=diesel ? 832 : 745;
        switch (fuelPid) {
            case 0x5e: data.rate=raw; data.source=1; break;
            case 0x9d: data.rate=raw*3600/density; data.source=4; break;
            case 0x10: data.rate=raw*3600/(14.7*density); data.source=2; break;
            default: return;
        }
        if (data.rate<0 || data.rate>200) { data.rate=Double.NaN; data.source=0; }
    }
    private double value(int pid) throws Exception {
        return decode(pid,sample(String.format(Locale.ROOT,"01%02X",pid),1700));
    }
    private String sample(String command,int timeout) throws Exception {
        String reply=link.query(command,timeout);
        if (exchange!=null) exchange.publish(command,reply);
        return reply;
    }
    void serveProxy() throws Exception {
        if (exchange==null) return;
        String command=exchange.take();
        if (command!=null) sample(command,1700);
    }
    static double decode(int pid, String response) {
        String tag=String.format(Locale.ROOT,"41%02X",pid);
        int length=(pid==0x0d || pid==FUEL_LEVEL || pid==COOLANT || pid==OIL) ? 2 : 4;
        Pattern payload=Pattern.compile("^"+tag+"([0-9A-F]{"+length+"})(?:[0-9A-F]{2})*$");
        for (String row : response.toUpperCase(Locale.ROOT).split("[\r\n>]")) {
            Matcher m=payload.matcher(row.replaceAll("\\s+", ""));
            if (!m.matches()) continue;
            int number=Integer.parseInt(m.group(1),16);
            switch (pid) {
                case COOLANT: case OIL: return number-40;
                case 0x0d: return number;
                case 0x0c: return number/4.0;
                case FUEL_LEVEL: return number*100/255.0;
                case 0x5e: return number/20.0;
                case 0x9d: return number*0.02;
                case 0x10: return number/100.0;
                default: break;
            }
        }
        return Double.NaN;
    }
}
