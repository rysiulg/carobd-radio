package pl.marm.carobd;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import android.util.Log;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Rejestruje w tle po uruchomieniu z pulpitu. GPS działa również po utracie ECU. */
public final class Recorder extends Service {
    static final String STOP="pl.marm.carobd.STOP", PROFILE="profile_id", MEASURE="pl.marm.carobd.MEASURE", RETRY="pl.marm.carobd.RETRY_OBD";
    static final String READ_SERVICE="pl.marm.carobd.READ_SERVICE", WRITE_SERVICE="pl.marm.carobd.WRITE_SERVICE";
    static final String RESET_TPMS="pl.marm.carobd.RESET_TPMS";
    static final String CLEAR_DTC="pl.marm.carobd.CLEAR_DTC";
    static final int LINK_OFF=0, LINK_SEARCH=1, LINK_ADAPTER=2;
    static final String SERVICE_KM="service_km", SERVICE_MONTHS="service_months", SERVICE_MODE="service_mode";
    static final class Live {
        boolean armed, gps, gpsProvider, gpsReception, obd, journalError, serviceBusy, dtcBusy;
        int measurement,obdLink;
        double[] times=new double[0];
        String status,profileName="",profileId="",vehicle="",proxyStatus="",proxyName="",gear="";
        List<String> faults=Collections.emptyList();
        Dtc.Report dtcs;
        EngineHours.Reading hours;
        Elm.ServiceInterval service;
        String serviceStatus="",dtcStatus="";
        double speed=Double.NaN,rpm=Double.NaN,rate=Double.NaN,fuel=Double.NaN,km,mean=Double.NaN,tripLiters=Double.NaN;
        double coolant=Double.NaN,oil=Double.NaN,altitude=Double.NaN,odometer=Double.NaN,tankLiters=Double.NaN;
        double barometricKpa=Double.NaN,ambientC=Double.NaN;
        boolean ambientCan;
        Elm.Voltage voltage;
        int fuelSource;
        long started,retryAt,autoStopAt;
        Live(String status) { this.status=status; }
        Live(Live from) {
            this(from.status); armed=from.armed; gps=from.gps; obd=from.obd; retryAt=from.retryAt; profileName=from.profileName; profileId=from.profileId; vehicle=from.vehicle; tripLiters=from.tripLiters;
            gpsProvider=from.gpsProvider; gpsReception=from.gpsReception; obdLink=from.obdLink;
            measurement=from.measurement; times=from.times; speed=from.speed; rpm=from.rpm; rate=from.rate;
            fuel=from.fuel; km=from.km; mean=from.mean; fuelSource=from.fuelSource; started=from.started;
            coolant=from.coolant; oil=from.oil; altitude=from.altitude; autoStopAt=from.autoStopAt; proxyStatus=from.proxyStatus; proxyName=from.proxyName;
            barometricKpa=from.barometricKpa; ambientC=from.ambientC; ambientCan=from.ambientCan;
            faults=from.faults; hours=from.hours; journalError=from.journalError; odometer=from.odometer; tankLiters=from.tankLiters;
            service=from.service; serviceStatus=from.serviceStatus; serviceBusy=from.serviceBusy; gear=from.gear;
            dtcs=from.dtcs; dtcBusy=from.dtcBusy; dtcStatus=from.dtcStatus; voltage=from.voltage;
        }
        boolean canClearDtc() {
            return armed && obd && !serviceBusy && !dtcBusy && speed==0 && rpm==0 && dtcs!=null && dtcs.clearable() &&
                measurement!=Measurement.WAITING && measurement!=Measurement.RUNNING;
        }
    }
    static volatile Live live=new Live("Gotowy do jazdy");
    private static final long FRESH=6500, TEMP_FRESH=24000, RESUME_GRACE=35_000;
    private static final String REFUEL_CHANNEL="refuel";
    private static final int REFUEL_NOTIFICATION=12;
    private static final String ROAD_CHANNEL="road_measurements";
    static final int ROAD_NOTIFICATION=13;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Live state=new Live("Oczekiwanie na GPS i OBD");
    private final ExecutorService obd=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService linkDeadline=Executors.newSingleThreadScheduledExecutor();
    private final ObdExchange exchange=new ObdExchange();
    private ObdLan lan;
    private HandlerThread logThread;
    private Handler log;
    private Trips store;
    private Settings settings;
    private volatile Profiles.Profile profile;
    private Engine ignition=new Engine();
    private Refuel refuel;
    private String refuelVehicle="";
    private final Measurement measurement=new Measurement();
    private final TripMath.Run automaticRun=new TripMath.Run();
    private final RoadAlert roadAlerts=new RoadAlert();
    private volatile Elm.Link link;
    private volatile boolean active;
    private LocationManager location;
    private Trips.Trip trip;
    private int bootCount;
    private long resumedUntil;
    private long ecuAt=-1,gpsAt=-1,maintenanceAt=-30000;
    private long coolantAt=-1,oilAt=-1,odometerAt=-1,fuelLevelAt=-1,tankAt=-1,gearAt=-1,proxyAt=-3000;
    private long voltageAt=-1,barometerAt=-1,ambientAt=-1,instrumentPrunedAt=-1;
    private long canAmbientAt=-1,nextAmbientRequest;
    private double canAmbientC=Double.NaN,obdAmbientC=Double.NaN;
    private final TopwayAmbient ambient=new TopwayAmbient(this::receiveCanAmbient);
    private String instrumentVehicle="";
    private final long[] instrumentSlots={-1,-1,-1};
    private static final long ODOMETER_PERIOD=30000, ODOMETER_FRESH=90000, FUEL_LEVEL_FRESH=45000;
    private static final long BAROMETER_PERIOD=30000, BAROMETER_FRESH=90000, BAROMETER_RETRY=120000;
    private static final long CAN_AMBIENT_PERIOD=60000, CAN_AMBIENT_FRESH=180000;
    private static final int ECU_MISSES=3;
    private static final long ECU_RECHECK_MS=15000;
    private Location lastLocation;
    private String adapterStatus="Brak połączenia OBD";
    private NotificationManager notifications;
    private TpmsMonitor tpms;
    private PendingIntent openDashboard;
    private final Object retryLock=new Object();
    private volatile int reconnect;
    private final AtomicReference<ServiceRequest> serviceRequest=new AtomicReference<>();
    private final AtomicReference<ClearCodes> clearRequest=new AtomicReference<>();
    private static final class ClearCodes {
        final Profiles.Profile profile;
        final Elm.Link link;
        final long expires=SystemClock.elapsedRealtime()+10_000L;
        ClearCodes(Profiles.Profile profile,Elm.Link link) { this.profile=profile; this.link=link; }
    }
    private boolean serviceWriting;
    private static final class ServiceRequest {
        final Profiles.Profile profile;
        final int connection;
        final Elm.ServiceInterval before,target;
        ServiceRequest(Profiles.Profile profile,int connection,Elm.ServiceInterval before,Elm.ServiceInterval target) {
            this.profile=profile; this.connection=connection; this.before=before; this.target=target;
        }
    }
    private int wake;
    private boolean receiverRegistered;
    private final BroadcastReceiver wakeReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            wakeRetry();
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) ambient.request();
        }
    };
    private final LocationListener fixes=new LocationListener() {
        @Override public void onLocationChanged(Location l) { gps(l); }
        @Override public void onProviderDisabled(String p) { gpsAt=-1; state.gpsProvider=false; state.gpsReception=false; }
        @Override public void onProviderEnabled(String p) { state.gpsProvider=true; }
        @Override public void onStatusChanged(String p,int s,Bundle b) {}
    };
    private final GnssStatus.Callback satellites=new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) { state.gpsReception=status.getSatelliteCount()>0; }
        @Override public void onStopped() { state.gpsReception=false; }
    };

    @Override public void onCreate() {
        super.onCreate();
        settings=new Settings(this);
        tpms=new TpmsMonitor(this);
        bootCount=android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
        lan=new ObdLan(this,exchange);
        store=new Trips(this);
        logThread=new HandlerThread("CarOBD journal"); logThread.start(); log=new Handler(logThread.getLooper());
        location=getSystemService(LocationManager.class);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (intent!=null && STOP.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
        if (intent!=null && RESET_TPMS.equals(intent.getAction())) {
            if (active) tpms.refresh(); else stopSelf();
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (intent!=null && (READ_SERVICE.equals(intent.getAction()) || WRITE_SERVICE.equals(intent.getAction()))) {
            if (!active) { stopSelf(id); return START_NOT_STICKY; }
            log.post(() -> queueService(intent));
            return START_STICKY;
        }
        if (intent!=null && CLEAR_DTC.equals(intent.getAction())) {
            if (!active) { stopSelf(id); return START_NOT_STICKY; }
            log.post(() -> {
                if (state.dtcBusy) return;
                Elm.Link current=link;
                if (state.canClearDtc() && current!=null && profile.id.equals(intent.getStringExtra(PROFILE)) &&
                        fresh(ecuAt,SystemClock.elapsedRealtime())) {
                    state.dtcBusy=true; state.dtcStatus="Kasuję kody EOBD…";
                    clearRequest.set(new ClearCodes(profile,current));
                } else state.dtcStatus="Kasowanie wymaga postoju, włączonego zapłonu i zgaszonego silnika";
                publish();
            });
            return START_STICKY;
        }
        if (intent!=null && RETRY.equals(intent.getAction())) {
            if (active && !live.obd) { reconnect++; wakeRetry(); }
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (intent!=null && MEASURE.equals(intent.getAction())) {
            log.post(() -> {
                int stage=measurement.state();
                if (stage==Measurement.WAITING || stage==Measurement.RUNNING) measurement.cancel();
                else if (active && !state.serviceBusy && !state.dtcBusy && fresh(ecuAt,SystemClock.elapsedRealtime()) && state.speed<=2)
                    measurement.arm(SystemClock.elapsedRealtime());
                publish();
            });
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (active && intent!=null && intent.hasExtra(PROFILE)) {
            Profiles.Profile requested=settings.profiles.find(intent.getStringExtra(PROFILE));
            log.post(() -> {
                if (trip==null && !state.serviceBusy && !state.dtcBusy && differentProfile(profile,requested)) {
                    profile=requested; ignition=new Engine(); ecuAt=-1; state.obdLink=LINK_OFF; wakeRetry();
                    obdAmbientC=Double.NaN; ambientAt=-1;
                    state.speed=Double.NaN; state.rpm=Double.NaN; state.profileName=requested.name; state.profileId=requested.id; state.vehicle=requested.vehicle;
                    state.serviceStatus=""; state.dtcStatus="";
                    measurement.cancel(); showObd("Zmiana profilu OBD",false,0);
                }
            });
        }
        if (!active) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                stopSelf(); return START_NOT_STICKY;
            }
            String requested=intent==null ? null : intent.getStringExtra(PROFILE);
            profile=settings.profiles.find(requested);
            state.profileName=profile.name; state.profileId=profile.id; state.vehicle=profile.vehicle;
            notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("drive",UiLanguage.tr("Rejestrator tras"),NotificationManager.IMPORTANCE_LOW));
            NotificationChannel fuelAlerts=new NotificationChannel(REFUEL_CHANNEL,UiLanguage.tr("Tankowania"),NotificationManager.IMPORTANCE_HIGH);
            fuelAlerts.setSound(null,null);
            notifications.createNotificationChannel(fuelAlerts);
            notifications.createNotificationChannel(new NotificationChannel(ROAD_CHANNEL,UiLanguage.tr("Fotoradary i pomiary odcinkowe"),NotificationManager.IMPORTANCE_HIGH));
            openDashboard=PendingIntent.getActivity(this,0,new Intent(this,Dashboard.class),PendingIntent.FLAG_IMMUTABLE);
            Notification note=notification("Oczekiwanie na adapter OBD");
            try {
                if (Build.VERSION.SDK_INT>=29) startForeground(11,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                else startForeground(11,note);
            } catch (SecurityException denied) { stopSelf(); return START_NOT_STICKY; }
            IntentFilter wakes=new IntentFilter(Intent.ACTION_SCREEN_ON);
            wakes.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT>=33) registerReceiver(wakeReceiver,wakes,Context.RECEIVER_EXPORTED);
            else registerReceiver(wakeReceiver,wakes);
            receiverRegistered=true;
            active=true; state.armed=true; tpms.start(); ambient.start(); publish(); log.post(() -> {
                nextAmbientRequest=SystemClock.elapsedRealtime()+CAN_AMBIENT_PERIOD;
                try {
                    long now=SystemClock.elapsedRealtime();
                    trip=store.recover(profile,settings.profiles.list(),requested!=null,settings.recordingEnabled(),
                        now,System.currentTimeMillis(),bootCount,stationaryTimeout());
                    if (trip!=null) {
                        if (requested==null && !trip.profileId.isEmpty()) {
                            Profiles.Profile resumed=settings.profiles.find(trip.profileId);
                            if (trip.vehicle.equals(resumed.vehicle)) profile=resumed;
                        }
                        state.profileName=profile.name; state.profileId=profile.id; state.vehicle=profile.vehicle;
                        ignition.resumeTrip(trip.saved,trip.motion,trip.drive>0 || trip.idle>0);
                        resumedUntil=now+RESUME_GRACE; updateTrip(); publish();
                    }
                    Maintenance.schedule(this);
                }
                catch (Exception error) { journalError(error); }
                if (!active) return;
                RoadMapData.load(this);
                try {
                    location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,fixes,log.getLooper());
                    state.gpsProvider=location.isProviderEnabled(LocationManager.GPS_PROVIDER);
                    location.registerGnssStatusCallback(satellites,log);
                }
                catch (IllegalArgumentException | SecurityException e) { gpsAt=-1; }
                tick.run();
                obd.execute(this::readObd);
            });
        }
        return START_STICKY;
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if (!active) return;
            long now=SystemClock.elapsedRealtime();
            state.armed=true; state.gps=fresh(gpsAt,now); state.status=adapterStatus;
            if (!state.gps) state.altitude=Double.NaN;
            if (!fresh(ecuAt,now) && !state.gps) state.speed=Double.NaN;
            if (!fresh(ecuAt,now)) { state.rpm=Double.NaN; state.rate=Double.NaN; state.fuel=Double.NaN; }
            if (now-coolantAt>TEMP_FRESH) state.coolant=Double.NaN;
            if (now-oilAt>TEMP_FRESH) state.oil=Double.NaN;
            if (now-odometerAt>ODOMETER_FRESH) state.odometer=Double.NaN;
            if (now-fuelLevelAt>FUEL_LEVEL_FRESH) state.fuel=Double.NaN;
            if (now-tankAt>150000) state.tankLiters=Double.NaN;
            if (now-voltageAt>45000) state.voltage=null;
            if (now-barometerAt>BAROMETER_FRESH) state.barometricKpa=Double.NaN;
            if (EngineHours.kiaProfile(profile.vehicle) && now>=nextAmbientRequest) {
                nextAmbientRequest=now+CAN_AMBIENT_PERIOD;
                main.post(() -> { if (active && getSystemService(android.os.PowerManager.class).isInteractive()) ambient.request(); });
            }
            ClearCodes pending=clearRequest.get();
            if (pending!=null && now>=pending.expires && clearRequest.compareAndSet(pending,null)) {
                state.dtcBusy=false; state.dtcStatus="Upłynął czas na wykonanie polecenia";
            }
            if (!fresh(gearAt,now)) state.gear="";
            if (now-proxyAt>=3000) {
                proxyAt=now; state.proxyStatus=lan.update(settings.proxyEnabled(),profile); state.proxyName=lan.publishedName();
            }
            try {
                if (trip!=null && (!settings.recordingEnabled() || now-trip.start>=Trips.MAX_TRIP_MS ||
                    now>=resumedUntil && !ignition.continuesTrip(now,stationaryTimeout()))) finish(now);
                if (trip!=null && now-trip.saved>=5000) checkpoint(now,null);
                if (now-maintenanceAt>=30000) { maintenanceAt=now; Maintenance.schedule(Recorder.this); }
            } catch (Exception error) { journalError(error); }
            measurement.tick(now); updateTrip(); publish(); log.postDelayed(this,1000);
        }
    };
    private void begin(long at) throws Exception {
        if (!settings.recordingEnabled()) return;
        ignition.beginTrip(at);
        trip=new Trips.Trip(profile.vehicle,System.currentTimeMillis()/1000,at);
        Trips.Point first=lastLocation!=null && fresh(gpsAt,at) ? trip.location(lastLocation,gpsAt) : null;
        checkpoint(at,first);
    }
    private void gps(Location l) {
        if (!active || l==null || !l.hasAccuracy() || l.getAccuracy()>30 || Math.abs(l.getLatitude())>90
            || Math.abs(l.getLongitude())>180) return;
        long at=l.getElapsedRealtimeNanos()/1000000;
        if (!fresh(at,SystemClock.elapsedRealtime()) || at<=gpsAt) return;
        Location previous=lastLocation;
        gpsAt=at; lastLocation=l;
        state.altitude=l.hasAltitude() ? l.getAltitude() : Double.NaN;
        boolean gpsOnly=!fresh(ecuAt,at);
        double kmh=l.hasSpeed() ? l.getSpeed()*3.6 : Double.NaN;
        roadAlert(l,previous,Double.isFinite(kmh) ? kmh : gpsOnly ? Double.NaN : state.speed,at);
        try {
            if (gpsOnly) {
                boolean moving=ignition.gpsMotion(kmh,at);
                if (Double.isFinite(kmh)) state.speed=kmh;
                if (moving && trip==null) begin(at);
                if (trip!=null && settings.recordingEnabled() && kmh>3) trip.max=Math.max(trip.max,kmh);
            }
            if (trip!=null && settings.recordingEnabled()) {
                Trips.Point point=trip.location(l,at);
                if (point!=null) checkpoint(at,point);
            }
        } catch (Exception error) { journalError(error); }
    }
    private void roadAlert(Location fix,Location previous,double kmh,long at) {
        if (!settings.roadAlerts()) return;
        RoadMapData.Snapshot map=RoadMapData.snapshot();
        if (map==null || map.sites.isEmpty()) return;
        double bearing=fix.hasBearing() ? fix.getBearing() : Double.NaN;
        if (!Double.isFinite(bearing) && previous!=null && fresh(previous.getElapsedRealtimeNanos()/1000000,at) && previous.distanceTo(fix)>=12)
            bearing=(previous.bearingTo(fix)+360)%360;
        RoadAlert.Approach alert=roadAlerts.observe(map.sites,fix.getLatitude(),fix.getLongitude(),bearing,kmh,at);
        if (alert==null) return;
        try {
            notifications.notify(ROAD_NOTIFICATION,new Notification.Builder(this,ROAD_CHANNEL)
                .setSmallIcon(getApplicationInfo().icon).setContentTitle(UiLanguage.tr(alert.title())).setContentText(UiLanguage.roadDistance(alert.detail()))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setTimeoutAfter(12_000L).setAutoCancel(true)
                .setContentIntent(openDashboard).build());
        } catch (SecurityException unavailable) { Log.d("CarOBD","Android zablokował powiadomienie o pomiarze",unavailable); }
    }
    private void recordInstrument(InstrumentHistory.Metric metric,double value) {
        if (!settings.recordingEnabled() || !metric.valid(value)) return;
        String vehicle=profile.vehicle;
        if (!vehicle.equals(instrumentVehicle)) {
            instrumentVehicle=vehicle; Arrays.fill(instrumentSlots,-1);
        }
        long time=System.currentTimeMillis(),slot=time/InstrumentHistory.BUCKET;
        if (instrumentSlots[metric.id]==slot) return;
        instrumentSlots[metric.id]=slot;
        long now=SystemClock.elapsedRealtime();
        boolean prune=instrumentPrunedAt<0 || now-instrumentPrunedAt>=3600000;
        try {
            store.instrumentSample(vehicle,metric,slot,time,value,prune);
            if (prune) instrumentPrunedAt=now;
        } catch (RuntimeException error) { Log.w("CarOBD","Nie można zapisać odczytu wykresu",error); }
    }
    private void speed(double value,long at) {
        if (!active || !Double.isFinite(value)) return;
        ecuAt=at; state.speed=value; state.obdLink=LINK_ADAPTER;
        if (!state.obd) showObd("OBD połączone",true,0);
        try {
            boolean moving=ignition.obdMotion(value,at);
            if (moving && trip==null) begin(at);
            boolean recording=trip!=null && settings.recordingEnabled();
            if (recording) trip.speed(value,at);
            boolean[] splitSaved={false};
            TripMath.SplitListener sink=recording ? (band,launch,seconds) -> {
                trip.accelerationSplit(band,launch,seconds); splitSaved[0]=true;
            } : null;
            if (settings.autoAcceleration()) {
                automaticRun.sample(value,at,sink); measurement.sample(value,at);
            } else {
                automaticRun.resetMotion(); measurement.sample(value,at,sink);
            }
            if (splitSaved[0]) checkpoint(at,null);
            updateTrip(); publish();
        } catch (Exception error) { journalError(error); }
    }
    private void engine(Elm.Sample sample,long at) {
        if (!active) return;
        state.rpm=sample.rpm; state.rate=sample.rate; state.fuelSource=sample.source;
        double v=fresh(ecuAt,at) ? state.speed : Double.NaN;
        boolean parked=v<=2 && Double.isFinite(sample.rpm) && sample.rpm<=100;
        if (parked && refuel!=null && refuelVehicle.equals(profile.vehicle)) refuel.parked(at);
        if (Refuel.valid(sample.fuel)) {
            state.fuel=sample.fuel; fuelLevelAt=at;
            if (state.obd) observeRefuel(sample.fuel,parked,at);
        }
        ignition.observe(sample.rpm,v,at);
        try {
            if (trip!=null && settings.recordingEnabled()) trip.fuel(sample.rate,sample.source,at);
        } catch (Exception error) { journalError(error); }
    }
    private void observeRefuel(double level,boolean parkedWithEngineOff,long at) {
        String vehicle=profile.vehicle;
        long now=System.currentTimeMillis();
        if (refuel==null || !refuelVehicle.equals(vehicle)) {
            refuelVehicle=vehicle;
            refuel=new Refuel(settings.refuelBaseline(vehicle,now));
        }
        Refuel.Reading reading=refuel.sample(level,parkedWithEngineOff,at);
        if (reading==null) return;
        settings.saveRefuelBaseline(vehicle,reading.after,now);
        if (!reading.refueled) return;
        String detail=Dashboard.fmt(reading.before,1)+"% → "+Dashboard.fmt(reading.after,1)+"%";
        notifications.notify(REFUEL_NOTIFICATION,new Notification.Builder(this,REFUEL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(UiLanguage.tr("Wykryto tankowanie"))
            .setContentText(detail).setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openDashboard).setAutoCancel(true).build());
    }
    private void updateTrip() {
        state.started=trip==null ? 0 : trip.start;
        state.autoStopAt=trip==null ? 0 : ignition.autoStopAt(SystemClock.elapsedRealtime(),stationaryTimeout());
        state.km=trip==null ? 0 : trip.distance();
        state.tripLiters=trip==null || trip.fuelCover<=0 ? Double.NaN : trip.liters;
        state.mean=trip==null || state.km<0.1 || trip.fuelCover<Math.max(2,(SystemClock.elapsedRealtime()-trip.start)*0.00060)
            ? Double.NaN : 100*trip.liters/state.km;
    }
    private long stationaryTimeout() { return settings.autoStopMinutes()*60_000L; }
    private static boolean fresh(long at,long now) { return at>=0 && now>=at-1000 && now-at<=FRESH; }
    private void receiveCanAmbient(double value) {
        long at=SystemClock.elapsedRealtime();
        log.post(() -> {
            if (active) { canAmbientC=value; canAmbientAt=at; publish(); }
        });
    }
    // Źródło radia dotyczy tylko Sorento. Pozostałe profile korzystają z własnego adaptera.
    private boolean useCanAmbient(long at,long since) {
        return EngineHours.kiaProfile(profile.vehicle) && Double.isFinite(canAmbientC) &&
            canAmbientAt>=since && at-canAmbientAt<=CAN_AMBIENT_FRESH;
    }
    private double temperature(long at,long since,boolean fromCan) {
        if (fromCan) return canAmbientC;
        return ambientAt>=since && at-ambientAt<=BAROMETER_FRESH ? obdAmbientC : Double.NaN;
    }
    private void checkpoint(long at,Trips.Point p) throws Exception {
        double temperature=temperature(at,trip.start,useCanAmbient(at,trip.start));
        double pressure=barometerAt>=trip.start && at-barometerAt<=BAROMETER_FRESH ? state.barometricKpa*10 : Double.NaN;
        trip.recordConditions(temperature,pressure);
        store.save(trip,at,p,bootCount,profile.id); trip.saved=at; state.journalError=false;
    }
    private void finish(long now) throws Exception {
        if (trip==null) return;
        checkpoint(now,null);
        store.closeTrip(trip.summary(now),false);
        trip=null; resumedUntil=0; Maintenance.schedule(this);
    }
    private void journalError(Exception error) {
        Log.w("CarOBD","Nie można zapisać trasy",error); state.journalError=true; publish();
    }
    private void publish() {
        long now=SystemClock.elapsedRealtime();
        state.ambientCan=profile!=null && useCanAmbient(now,0);
        state.ambientC=temperature(now,0,state.ambientCan);
        state.measurement=measurement.state(); state.times=measurement.times();
        live=new Live(state);
    }
    private boolean differentProfile(Profiles.Profile current,Profiles.Profile next) {
        return !current.id.equals(next.id) || !current.adapter.equals(next.adapter) ||
            !current.transport.equals(next.transport) || !current.vehicle.equals(next.vehicle) ||
            !current.fuel.equals(next.fuel) || !current.name.equals(next.name);
    }

    private Notification notification(String status) {
        String text=UiLanguage.adapterStatus(status);
        return new Notification.Builder(this,"drive").setSmallIcon(getApplicationInfo().icon)
            .setContentTitle(UiLanguage.tr("CarOBD • rejestrator tras")).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true)
            .setContentIntent(openDashboard).setOngoing(true).build();
    }
    private void queueService(Intent intent) {
        if (!active || state.serviceBusy || state.dtcBusy) return;
        boolean write=WRITE_SERVICE.equals(intent.getAction());
        Elm.ServiceInterval target=write ? new Elm.ServiceInterval(intent.getIntExtra(SERVICE_KM,-1),
            intent.getIntExtra(SERVICE_MONTHS,-1),intent.getIntExtra(SERVICE_MODE,-1)) : null;
        if (!state.obd || state.speed!=0 || !fresh(ecuAt,SystemClock.elapsedRealtime()) ||
            !EngineHours.kiaProfile(profile.vehicle) || !profile.id.equals(intent.getStringExtra(PROFILE))) {
            state.serviceStatus="Zatrzymaj auto i sprawdź połączenie OBD dla tego profilu";
        } else if (state.measurement==Measurement.WAITING || state.measurement==Measurement.RUNNING) {
            state.serviceStatus="Zakończ pomiar przyspieszenia";
        } else if (write && (state.service==null || !target.validChangeFrom(state.service))) {
            state.serviceStatus="Odczytaj ustawienia z auta i sprawdź wpisane wartości";
        } else if (write && !state.service.same(new Elm.ServiceInterval(intent.getIntExtra("before_km",-1),
            intent.getIntExtra("before_months",-1),intent.getIntExtra("before_mode",-1)))) {
            state.serviceStatus="Ustawienia zostały odświeżone. Sprawdź odczyt przed zapisem";
        } else {
            state.serviceBusy=true; serviceWriting=write;
            state.serviceStatus=write ? "Zapisuję do zegarów i sprawdzam odczyt…" : "Odczytuję ustawienia z zegarów…";
            serviceRequest.set(new ServiceRequest(profile,reconnect,state.service,target));
        }
        publish();
    }
    private void showObd(String value,boolean connected,long after) {
        boolean changed=!value.equals(adapterStatus);
        if (!connected && state.obd && state.speed<=2 && refuel!=null && refuelVehicle.equals(profile.vehicle))
            refuel.parked(SystemClock.elapsedRealtime());
        adapterStatus=value; state.status=value; state.obd=connected; state.retryAt=after;
        if (!connected) {
            automaticRun.resetMotion();
            serviceRequest.set(null);
            clearRequest.set(null);
            if (state.dtcBusy) state.dtcStatus="Przerwano połączenie · wynik kasowania niepotwierdzony";
            state.dtcBusy=false; state.dtcs=null;
            if (state.serviceBusy) {
                state.serviceStatus=serviceWriting ? "Przerwano OBD. Wynik zapisu nie jest potwierdzony"
                    : "Przerwano odczyt ustawień OBD";
                state.serviceBusy=false;
            } else if (state.service!=null) state.serviceStatus="";
            state.service=null;
            state.coolant=Double.NaN; state.oil=Double.NaN; state.faults=Collections.emptyList();
            state.hours=null; state.odometer=Double.NaN; state.tankLiters=Double.NaN; state.fuel=Double.NaN;
            state.gear=""; gearAt=-1; state.voltage=null; voltageAt=-1;
            state.barometricKpa=Double.NaN; barometerAt=-1;
            obdAmbientC=Double.NaN; ambientAt=-1;
        }
        if (changed && active) notifications.notify(11,notification(value));
        publish();
    }
    private void status(Profiles.Profile owner,String value,int linkStage,long after) {
        log.post(() -> {
            if (!active || profile!=owner) return;
            state.obdLink=linkStage; showObd(value,false,after);
        });
    }
    private int wakeState() { synchronized (retryLock) { return wake; } }
    private void wakeRetry() { synchronized (retryLock) { wake++; retryLock.notifyAll(); } }
    private void waitUntil(long deadline,int snapshot) throws InterruptedException {
        synchronized (retryLock) {
            long remaining;
            while (active && wake==snapshot && (remaining=deadline-SystemClock.elapsedRealtime())>0)
                retryLock.wait(remaining);
        }
    }

    private void readObd() {
        ObdRetry retry=new ObdRetry();
        Profiles.Profile last=null;
        long advertisedAt=-1, probedAt=-1, nextTank=0, nextOdometer=0;
        while (active && !Thread.currentThread().isInterrupted()) {
            Profiles.Profile current=profile;
            if (last!=current) { retry.reset(); advertisedAt=-1; probedAt=-1; nextTank=0; nextOdometer=0; last=current; }
            int wakeBefore=wakeState(), request=reconnect;
            String address=current.adapter;
            BluetoothAdapter adapter=null;
            boolean ready=false,ble="BLE".equals(current.transport),wifi=current.isWifi();
            try {
                if (address.isEmpty()) {
                    status(current,"Wybierz adapter OBD w konfiguracji",LINK_OFF,0);
                    waitUntil(SystemClock.elapsedRealtime()+4000,wakeBefore); continue;
                }
                if (wifi) {
                    status(current,"Łączenie Wi-Fi · "+address,LINK_SEARCH,0);
                    link=lan.connect(TcpElm.Address.parse(address));
                } else {
                    BluetoothManager manager=getSystemService(BluetoothManager.class);
                    adapter=manager==null ? null : manager.getAdapter();
                    if (adapter==null || !adapter.isEnabled()) {
                        status(current,"Bluetooth wyłączony lub niedostępny",LINK_OFF,0);
                        retry.reset(); waitUntil(SystemClock.elapsedRealtime()+4000,wakeBefore); continue;
                    }
                    BluetoothDevice device=adapter.getRemoteDevice(address);
                    if (!ble && device.getBondState()!=BluetoothDevice.BOND_BONDED) {
                        status(current,"Sparuj adapter Classic w Androidzie",LINK_OFF,0);
                        waitUntil(SystemClock.elapsedRealtime()+7000,wakeBefore); continue;
                    }
                    status(current,"Łączenie z adapterem "+current.transport,LINK_SEARCH,0);
                    connectBluetooth(device,ble);
                }
                ready=true;
                status(current,"Adapter połączony • inicjalizacja OBD",LINK_ADAPTER,0);
                Elm session=new Elm(link,wifi ? null : exchange);
                session.init(); retry.reset();
                Dtc.Monitor faults=new Dtc.Monitor();
                EngineHours.Monitor engineHours=new EngineHours.Monitor();
                boolean kiaProfile=EngineHours.kiaProfile(current.vehicle),gearSupported=false;
                long nextGear=0, nextProtocolCheck=0, nextVoltage=0, nextBarometer=0, nextAmbient=0;
                if (!wifi) exchange.connected(true);
                status(current,"Adapter połączony • oczekiwanie na ECU",LINK_ADAPTER,0);
                for (int n=0,missed=0;active && profile==current && request==reconnect;n++) {
                    long begin=SystemClock.elapsedRealtime();
                    double v=session.speed(); long at=SystemClock.elapsedRealtime();
                    if (Double.isFinite(v)) {
                        if (missed>=ECU_MISSES) nextGear=0;
                        missed=0; nextProtocolCheck=0;
                        log.post(() -> { if (profile==current) speed(v,at); });
                    } else {
                        if (++missed==ECU_MISSES) { faults=new Dtc.Monitor(); engineHours=new EngineHours.Monitor(); }
                        if (missed>=ECU_MISSES && at>=nextProtocolCheck) {
                            status(current,"Adapter połączony • ponawiam wykrywanie ECU",LINK_ADAPTER,0);
                            boolean ecu=session.rediscoverProtocol();
                            nextProtocolCheck=SystemClock.elapsedRealtime()+ECU_RECHECK_MS;
                            status(current,ecu ? "ECU odpowiada • brak odczytu prędkości" :
                                "Adapter połączony • ECU nie odpowiada",LINK_ADAPTER,0);
                            continue;
                        }
                    }
                    ServiceRequest service=serviceRequest.get();
                    if (service!=null && service.profile==current && service.connection==request &&
                        serviceRequest.compareAndSet(service,null)) {
                        if (service.target==null && v==0) engineHours.requestRefresh(at);
                        serviceOperation(session,service,v);
                        continue;
                    }
                    ClearCodes clear=clearRequest.get();
                    if (clear!=null && clearRequest.compareAndSet(clear,null)) {
                        String result=Dtc.clear(session,() -> active && profile==clear.profile && link==clear.link &&
                            request==reconnect && SystemClock.elapsedRealtime()<clear.expires);
                        log.post(() -> {
                            if (!active || profile!=clear.profile || link!=clear.link || !state.dtcBusy) return;
                            state.dtcs=null; state.dtcStatus=result; state.dtcBusy=false; publish();
                        });
                        faults=new Dtc.Monitor();
                        continue;
                    }
                    if (n%3==0 && missed==0) {
                        Elm.Sample data=session.read(current.fuel,n/3);
                        long sampled=SystemClock.elapsedRealtime();
                        log.post(() -> { if (profile==current) engine(data,sampled); });
                    }
                    // Każdy PID temperatury odczytujemy osobno, między kolejnymi pomiarami prędkości.
                    int phase=n%18;
                    if (missed==0 && (phase==3 || phase==12)) {
                        boolean coolant=phase==3;
                        double value=session.temperature(coolant);
                        long sampled=SystemClock.elapsedRealtime();
                        log.post(() -> {
                            if (!active || profile!=current || request!=reconnect) return;
                            if (coolant) { state.coolant=value; coolantAt=sampled; }
                            else { state.oil=value; oilAt=sampled; }
                            recordInstrument(coolant ? InstrumentHistory.Metric.COOLANT : InstrumentHistory.Metric.OIL,value);
                            publish();
                        });
                    }
                    boolean measuring=live.measurement==Measurement.RUNNING || live.measurement==Measurement.WAITING;
                    // Wybierak odczytujemy po prędkości, przed żądaniami proxy i wolniejszą diagnostyką.
                    if (missed==0 && n%3!=0 && !measuring && kiaProfile) {
                        long gearStarted=SystemClock.elapsedRealtime();
                        if (gearStarted>=nextGear) {
                            KiaGear reading=session.kiaGear();
                            long sampled=SystemClock.elapsedRealtime();
                            if (reading!=null) {
                                gearSupported=true;
                                String gear=reading.label(v);
                                log.post(() -> {
                                    if (active && profile==current && request==reconnect && state.obd) {
                                        state.gear=gear; gearAt=sampled; publish();
                                    }
                                });
                            }
                            nextGear=KiaGear.nextPoll(gearStarted,sampled,reading!=null,gearSupported);
                        }
                    }
                    if (!wifi && missed<ECU_MISSES && n%3!=0) session.serveProxy();
                    if (missed==0 && n%3==1 && !measuring) {
                        Dtc.Report update=faults.poll(session,begin);
                        if (update!=null) log.post(() -> {
                            if (active && profile==current && request==reconnect && state.obd) {
                                state.dtcs=update; state.faults=update.lines(); publish();
                            }
                        });
                    }
                    if (missed==0 && n%3==2 && !measuring) {
                        if (engineHours.due(begin)) {
                            EngineHours.Reading hours=engineHours.poll(session,current.vehicle,begin);
                            if (hours!=null) log.post(() -> {
                                if (active && profile==current && request==reconnect) { state.hours=hours; publish(); }
                            });
                        } else if (begin>=nextVoltage) {
                            nextVoltage=begin+15000;
                            Elm.Voltage voltage=session.voltage();
                            log.post(() -> {
                                if (active && profile==current && request==reconnect && state.obd) {
                                    state.voltage=voltage; voltageAt=begin;
                                    if (voltage!=null) recordInstrument(InstrumentHistory.Metric.VOLTAGE,voltage.volts);
                                    publish();
                                }
                            });
                        } else if (begin>=nextOdometer) {
                            nextOdometer=begin+ODOMETER_PERIOD;
                            boolean kia=EngineHours.kiaProfile(current.vehicle) && session.confirmKia();
                            double modelOdo=kia ? session.kiaOdometer() : Double.NaN;
                            double reading=Double.isFinite(modelOdo) ? modelOdo : session.odometer();
                            if (Double.isFinite(reading)) log.post(() -> {
                                if (active && profile==current && request==reconnect) {
                                    state.odometer=reading; odometerAt=begin; publish();
                                }
                            });
                        } else if (begin>=nextAmbient) {
                            // W Sorento 0146 silnika różni się od temperatury wskazywanej przez zegary.
                            double temperature=EngineHours.kiaProfile(current.vehicle) ?
                                (session.confirmKia() ? session.kiaAmbientC() : Double.NaN) : session.ambientC();
                            nextAmbient=begin+(Double.isFinite(temperature) ? 30_000 : 120_000);
                            log.post(() -> {
                                if (active && profile==current && request==reconnect && state.obd) {
                                    obdAmbientC=temperature; ambientAt=begin; publish();
                                }
                            });
                        } else if (begin>=nextBarometer) {
                            double pressure=session.barometricKpa();
                            nextBarometer=begin+(Double.isFinite(pressure) ? BAROMETER_PERIOD : BAROMETER_RETRY);
                            log.post(() -> {
                                if (active && profile==current && request==reconnect && state.obd) {
                                    state.barometricKpa=pressure; barometerAt=begin; publish();
                                }
                            });
                        } else if (begin>=nextTank && EngineHours.kiaProfile(current.vehicle)) {
                            nextTank=begin+120000;
                            double liters=session.confirmKia() ? session.kiaTankLiters() : Double.NaN;
                            if (Double.isFinite(liters)) nextTank=begin+60000;
                            if (Double.isFinite(liters)) log.post(() -> {
                                if (active && profile==current && request==reconnect && state.obd) {
                                    state.tankLiters=liters; tankAt=begin; publish();
                                }
                            });
                        }
                    }
                    Thread.sleep(Math.max(50,(missed>=ECU_MISSES ? 2300 : 600)-(SystemClock.elapsedRealtime()-begin)));
                }
                continue;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (SecurityException denied) {
                status(current,wifi ? "Brak uprawnienia do sieci Wi-Fi" : "Brak uprawnienia do Bluetooth",LINK_OFF,0);
                closeLink(); retry.reset();
                try { waitUntil(SystemClock.elapsedRealtime()+7000,wakeBefore); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                continue;
            } catch (Exception disconnected) {
                if (!active || profile!=current || request!=reconnect) continue;
                boolean gattConnected=link instanceof BluetoothElm.Ble && ((BluetoothElm.Ble)link).connected();
                closeLink();
                long delay=retry.failed(),now=SystemClock.elapsedRealtime();
                String reason=ready ? "Utracono łączność OBD" : gattConnected ?
                    "Adapter BLE połączony • brak kanału ELM" : "Brak połączenia z adapterem";
                if (wifi && disconnected instanceof TcpElm.Busy) reason="Proxy Wi-Fi jest zajęte";
                else if (wifi && !ready && disconnected.getMessage()!=null) reason=disconnected.getMessage();
                if (!ready && !gattConnected && ble && adapter!=null && retry.failures()>=2) {
                    if (probedAt<0 || now-probedAt>15000) {
                        probedAt=now;
                        try { if (BluetoothElm.visible(adapter,address)) advertisedAt=SystemClock.elapsedRealtime(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                    if (retry.possibleBusy(advertisedAt>=0 && SystemClock.elapsedRealtime()-advertisedAt<30000))
                        reason="Adapter widoczny • możliwe zajęcie";
                }
                long next=SystemClock.elapsedRealtime()+delay;
                status(current,reason,LINK_SEARCH,next);
                try { waitUntil(next,wakeBefore); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            } finally { closeLink(); }
        }
    }
    private void serviceOperation(Elm session,ServiceRequest service,double speed) throws Exception {
        KiaService.Result result;
        if (speed!=0) result=new KiaService.Result(service.before,false,"Auto musi stać i odpowiadać na odczyt prędkości");
        else if (service.target!=null) result=session.saveKiaService(service.before,service.target,
            () -> active && profile==service.profile && reconnect==service.connection);
        else {
            Elm.ServiceInterval read=session.kiaService();
            result=new KiaService.Result(read,read!=null,read==null ? "Zegary nie zwróciły ustawień"
                : "Odczytano bezpośrednio z zegarów");
        }
        log.post(() -> {
            if (!active || profile!=service.profile || reconnect!=service.connection || !state.serviceBusy) return;
            state.service=result.values; state.serviceStatus=result.message; state.serviceBusy=false;
            publish();
        });
    }
    private void connectBluetooth(BluetoothDevice device,boolean ble) throws Exception {
        if (ble) {
            BluetoothElm.Ble gatt=new BluetoothElm.Ble(device); link=gatt; gatt.connect(this);
        } else {
            BluetoothElm.Classic spp=new BluetoothElm.Classic(device); link=spp;
            Future<?> timeout=linkDeadline.schedule(spp::close,12,TimeUnit.SECONDS);
            try { spp.connect(); } finally { timeout.cancel(false); }
        }
    }
    private void closeLink() {
        exchange.connected(false);
        Elm.Link current=link; link=null;
        if (current!=null) current.close();
    }

    private void stopRecording() {
        active=false; wakeRetry();
        log.post(() -> {
            try { finish(SystemClock.elapsedRealtime()); }
            catch (Exception e) { journalError(e); }
            main.post(() -> { stopForeground(true); stopSelf(); });
        });
    }
    @Override public void onDestroy() {
        active=false; wakeRetry(); tpms.close(); ambient.close();
        if (receiverRegistered) unregisterReceiver(wakeReceiver);
        if (location!=null) { location.removeUpdates(fixes); location.unregisterGnssStatusCallback(satellites); }
        lan.close();
        obd.shutdownNow(); linkDeadline.shutdownNow();
        closeLink();
        log.removeCallbacksAndMessages(null);
        log.post(store::close); logThread.quitSafely(); live=new Live("Rejestrator zatrzymany");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
