package pl.marm.carobd;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wyłącznie odczyt OBD. Skalowanie jak w CarOBD ESP. */
final class Elm {
    static final int COOLANT=0x05, OIL=0x5c, FUEL_LEVEL=0x2f, BAROMETER=0x33, AMBIENT=0x46;
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
    double rpm() throws Exception { return value(0x0c); }
    static final class Voltage {
        final double volts;
        final boolean adapter;
        Voltage(double volts,boolean adapter) { this.volts=volts; this.adapter=adapter; }
    }
    Voltage voltage() throws Exception {
        double ecu=value(0x42);
        if (ecu>=6 && ecu<=32) return new Voltage(ecu,false);
        double adapter=adapterVoltage(sample("ATRV",900));
        return adapter>=6 && adapter<=32 ? new Voltage(adapter,true) : null;
    }
    private static double adapterVoltage(String response) {
        for (String row:response.split("[\\r\\n>]+")) {
            String value=row.trim().toUpperCase(Locale.ROOT).replace(',', '.');
            if (value.matches("[0-9]{1,2}(?:\\.[0-9]{1,3})?\\s*V")) return Double.parseDouble(value.substring(0,value.length()-1).trim());
        }
        return Double.NaN;
    }
    double temperature(boolean coolant) throws Exception { return value(coolant ? COOLANT : OIL); }
    double barometricKpa() throws Exception { return value(BAROMETER); }
    double ambientC() throws Exception {
        double temperature=value(AMBIENT);
        return temperature>=-40 && temperature<=70 ? temperature : Double.NaN;
    }
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
    double kiaAmbientC() throws Exception {
        return decodeKiaAmbient(readKiaFrame("7B3","7BB","220100",1700));
    }
    static double decodeKiaAmbient(String reply) {
        String data=EngineHours.payload(reply,"620100",10,64);
        if (data==null) return Double.NaN;
        double temperature=Integer.parseInt(data.substring(12,14),16)/2.0-40;
        return temperature>-40 && temperature<=60 ? temperature : Double.NaN;
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
        boolean same(ServiceInterval other) {
            return other!=null && km==other.km && months==other.months && mode==other.mode;
        }
        boolean validChangeFrom(ServiceInterval before) {
            for (KiaService.Field field : KiaService.Field.values())
                if (field.value(this)!=field.value(before) && !field.accepts(field.value(this))) return false;
            return true;
        }
    }
    ServiceInterval kiaService() throws Exception {
        return confirmKia() ? KiaService.readForVerifiedKia(this,link) : null;
    }
    static ServiceInterval serviceInterval(String rawMode,String rawKm,String rawMonths) {
        String mode=EngineHours.payload(rawMode,"620070",4);
        String months=EngineHours.payload(rawMonths,"620073",5);
        return KiaService.values(mode,EngineHours.payload(rawKm,"620072",6,11),months);
    }
    KiaService.Result saveKiaService(ServiceInterval before,ServiceInterval target,java.util.function.BooleanSupplier active)
        throws Exception {
        if (!active.getAsBoolean() || !EngineHours.matchesKia(diagnostic("0902")))
            return new KiaService.Result(null,false,"ECU nie potwierdziło zgodności pojazdu z profilem Kia");
        kiaVerified=true;
        return KiaService.saveForVerifiedKia(this,link,before,target,active);
    }
    private String readKiaFrame(String tx,String rx,String command,int timeout) throws Exception {
        if (!kiaVerified) return "";
        try {
            return addressKia(tx,rx) ? link.query(command,timeout) : "";
        } finally { restoreAddress(); }
    }
    boolean addressKia(String tx,String rx) throws Exception {
        for (String setup:new String[]{"ATSH"+tx,"ATCRA"+rx,"ATFCSH"+tx,"ATFCSD300000","ATFCSM1"})
            if (!link.query(setup,900).contains("OK")) return false;
        return true;
    }
    void restoreAddress() throws Exception {
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
        int length=(pid==0x0d || pid==FUEL_LEVEL || pid==COOLANT || pid==OIL || pid==BAROMETER || pid==AMBIENT) ? 2 : 4;
        Pattern payload=Pattern.compile("^"+tag+"([0-9A-F]{"+length+"})(?:[0-9A-F]{2})*$");
        for (String row : response.toUpperCase(Locale.ROOT).split("[\r\n>]")) {
            Matcher m=payload.matcher(row.replaceAll("\\s+", ""));
            if (!m.matches()) continue;
            int number=Integer.parseInt(m.group(1),16);
            switch (pid) {
                case COOLANT: case OIL: case AMBIENT: return number-40;
                case 0x0d: case BAROMETER: return number;
                case 0x0c: return number/4.0;
                case FUEL_LEVEL: return number*100/255.0;
                case 0x5e: return number/20.0;
                case 0x9d: return number*0.02;
                case 0x10: return number/100.0;
                case 0x42: return number/1000.0;
                default: break;
            }
        }
        return Double.NaN;
    }
}
