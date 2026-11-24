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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** Rejestruje w tle po uruchomieniu z pulpitu. GPS działa również po utracie ECU. */
public final class Recorder extends Service {
    static final String STOP="pl.marm.carobd.STOP", PROFILE="profile_id", MEASURE="pl.marm.carobd.MEASURE", RETRY="pl.marm.carobd.RETRY_OBD";
    static final String READ_SERVICE="pl.marm.carobd.READ_SERVICE";
    static final class Live {
        boolean armed, gps, obd, journalError;
        int measurement;
        double[] times=new double[0];
        String status,profileName="",profileId="",vehicle="",proxyStatus="",gear="";
        List<String> faults=Collections.emptyList();
        EngineHours.Reading hours;
        Elm.ServiceInterval service;
        String serviceStatus="";
        double speed=Double.NaN,rpm=Double.NaN,rate=Double.NaN,fuel=Double.NaN,km,mean=Double.NaN,tripLiters=Double.NaN;
        double coolant=Double.NaN,oil=Double.NaN,altitude=Double.NaN,odometer=Double.NaN,tankLiters=Double.NaN;
        int fuelSource;
        long started,retryAt,autoStopAt;
        Live(String status) { this.status=status; }
        Live(Live from) {
            this(from.status); armed=from.armed; gps=from.gps; obd=from.obd; retryAt=from.retryAt; profileName=from.profileName; profileId=from.profileId; vehicle=from.vehicle; tripLiters=from.tripLiters;
            measurement=from.measurement; times=from.times; speed=from.speed; rpm=from.rpm; rate=from.rate;
            fuel=from.fuel; km=from.km; mean=from.mean; fuelSource=from.fuelSource; started=from.started;
            coolant=from.coolant; oil=from.oil; altitude=from.altitude; autoStopAt=from.autoStopAt; proxyStatus=from.proxyStatus;
            faults=from.faults; hours=from.hours; journalError=from.journalError; odometer=from.odometer; tankLiters=from.tankLiters;
            service=from.service; serviceStatus=from.serviceStatus; gear=from.gear;
        }
    }
    static volatile Live live=new Live("Gotowy do jazdy");
    private static final long FRESH=6500, TEMP_FRESH=24000, RESUME_GRACE=35_000;
    private static final String SERVICE_READING="Odczytuję ustawienia z zegarów…";
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
    private final Measurement measurement=new Measurement();
    private volatile Elm.Link link;
    private volatile boolean active;
    private LocationManager location;
    private Trips.Trip trip;
    private int bootCount;
    private long resumedUntil;
    private long ecuAt=-1,gpsAt=-1,stoodAt=-1,maintenanceAt=-30000;
    private long coolantAt=-1,oilAt=-1,odometerAt=-1,fuelLevelAt=-1,tankAt=-1,gearAt=-1,proxyAt=-3000;
    private static final long ODOMETER_PERIOD=30000, ODOMETER_FRESH=90000, FUEL_LEVEL_FRESH=45000;
    private static final int ECU_MISSES=3;
    private static final long ECU_RECHECK_MS=15000;
    private Location lastLocation;
    private String adapterStatus="Brak połączenia OBD";
    private NotificationManager notifications;
    private PendingIntent openDashboard;
    private final Object retryLock=new Object();
    private volatile int reconnect;
    private final java.util.concurrent.atomic.AtomicBoolean serviceRead=new java.util.concurrent.atomic.AtomicBoolean();
    private int wake;
    private boolean receiverRegistered;
    private final BroadcastReceiver wakeReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { wakeRetry(); }
    };
    private final LocationListener fixes=new LocationListener() {
        @Override public void onLocationChanged(Location l) { gps(l); }
        @Override public void onProviderDisabled(String p) { gpsAt=-1; }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onStatusChanged(String p,int s,Bundle b) {}
    };

    @Override public void onCreate() {
        super.onCreate();
        settings=new Settings(this);
        bootCount=android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
        lan=new ObdLan(this,exchange);
        store=new Trips(this);
        logThread=new HandlerThread("CarOBD journal"); logThread.start(); log=new Handler(logThread.getLooper());
        location=getSystemService(LocationManager.class);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (intent!=null && STOP.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
        if (intent!=null && READ_SERVICE.equals(intent.getAction()) && active) {
            log.post(() -> {
                if (!active || SERVICE_READING.equals(state.serviceStatus)) return;
                state.service=null;
                if (state.obd && state.speed==0 && EngineHours.kiaProfile(profile.vehicle)) {
                    state.serviceStatus=SERVICE_READING;
                    serviceRead.set(true);
                } else state.serviceStatus="Zatrzymaj auto i sprawdź połączenie OBD";
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
                else if (active && fresh(ecuAt,SystemClock.elapsedRealtime()) && state.speed<=2)
                    measurement.arm(SystemClock.elapsedRealtime());
                publish();
            });
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (active && intent!=null && intent.hasExtra(PROFILE)) {
            Profiles.Profile requested=settings.profiles.find(intent.getStringExtra(PROFILE));
            log.post(() -> {
                if (trip==null && differentProfile(profile,requested)) {
                    profile=requested; ignition=new Engine(); ecuAt=-1; stoodAt=-1; wakeRetry();
                    state.speed=Double.NaN; state.rpm=Double.NaN; state.profileName=requested.name; state.profileId=requested.id; state.vehicle=requested.vehicle;
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
            notifications.createNotificationChannel(new NotificationChannel("drive","Rejestrator tras",NotificationManager.IMPORTANCE_LOW));
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
            active=true; state.armed=true; publish(); log.post(() -> {
                try {
                    long now=SystemClock.elapsedRealtime();
                    trip=store.recover(profile,settings.profiles.list(),requested!=null,settings.recordingEnabled(),
                        now,System.currentTimeMillis(),bootCount);
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
                try { location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,fixes,log.getLooper()); }
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
            if (!fresh(gearAt,now)) state.gear="";
            if (now-proxyAt>=3000) {
                proxyAt=now; state.proxyStatus=lan.update(settings.proxyEnabled(),profile);
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
    private void begin(long at,long previouslyStopped) throws Exception {
        if (!settings.recordingEnabled()) return;
        ignition.beginTrip(at);
        trip=new Trips.Trip(profile.vehicle,System.currentTimeMillis()/1000,at,previouslyStopped);
        Trips.Point first=lastLocation!=null && fresh(gpsAt,at) ? trip.location(lastLocation,gpsAt) : null;
        checkpoint(at,first);
    }
    private void gps(Location l) {
        if (!active || l==null || !l.hasAccuracy() || l.getAccuracy()>30 || Math.abs(l.getLatitude())>90
            || Math.abs(l.getLongitude())>180) return;
        long at=l.getElapsedRealtimeNanos()/1000000;
        if (!fresh(at,SystemClock.elapsedRealtime()) || at<=gpsAt) return;
        gpsAt=at; lastLocation=l;
        state.altitude=l.hasAltitude() ? l.getAltitude() : Double.NaN;
        boolean gpsOnly=!fresh(ecuAt,at);
        double kmh=l.hasSpeed() ? l.getSpeed()*3.6 : Double.NaN;
        try {
            if (gpsOnly) {
                boolean moving=ignition.gpsMotion(kmh,at);
                if (Double.isFinite(kmh)) state.speed=kmh;
                if (moving && trip==null) begin(at,stoodAt);
                if (trip!=null && settings.recordingEnabled() && kmh>3) trip.max=Math.max(trip.max,kmh);
            }
            if (trip!=null && settings.recordingEnabled()) {
                Trips.Point point=trip.location(l,at);
                if (point!=null) checkpoint(at,point);
            }
        } catch (Exception error) { journalError(error); }
    }
    private void speed(double value,long at) {
        if (!active || !Double.isFinite(value)) return;
        ecuAt=at; state.speed=value;
        if (!state.obd) showObd("OBD połączone",true,0);
        try {
            boolean moving=ignition.obdMotion(value,at);
            if (moving && trip==null) begin(at,stoodAt);
            if (trip!=null && settings.recordingEnabled()) trip.speed(value,at);
            measurement.sample(value,at);
            updateTrip(); publish();
        } catch (Exception error) { journalError(error); }
        stoodAt=value<=2 ? at : -1;
    }
    private void engine(Elm.Sample sample,long at) {
        if (!active) return;
        state.rpm=sample.rpm; state.rate=sample.rate; state.fuelSource=sample.source;
        if (Double.isFinite(sample.fuel)) { state.fuel=sample.fuel; fuelLevelAt=at; }
        double v=fresh(ecuAt,at) ? state.speed : Double.NaN;
        ignition.observe(sample.rpm,v,at);
        try {
            if (trip!=null && settings.recordingEnabled()) trip.fuel(sample.rate,sample.source,at);
        } catch (Exception error) { journalError(error); }
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
    private void checkpoint(long at,Trips.Point p) throws Exception {
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
        state.measurement=measurement.state(); state.times=measurement.times();
        live=new Live(state);
    }
    private boolean differentProfile(Profiles.Profile current,Profiles.Profile next) {
        return !current.id.equals(next.id) || !current.adapter.equals(next.adapter) ||
            !current.transport.equals(next.transport) || !current.vehicle.equals(next.vehicle) ||
            !current.fuel.equals(next.fuel) || !current.name.equals(next.name);
    }

    private Notification notification(String status) {
        return new Notification.Builder(this,"drive").setSmallIcon(getApplicationInfo().icon)
            .setContentTitle("CarOBD • rejestrator tras").setContentText(status)
            .setStyle(new Notification.BigTextStyle().bigText(status)).setOnlyAlertOnce(true)
            .setContentIntent(openDashboard).setOngoing(true).build();
    }
    private void showObd(String value,boolean connected,long after) {
        boolean changed=!value.equals(adapterStatus);
        adapterStatus=value; state.status=value; state.obd=connected; state.retryAt=after;
        if (!connected) {
            boolean interrupted=serviceRead.getAndSet(false) || SERVICE_READING.equals(state.serviceStatus);
            state.service=null; state.serviceStatus=interrupted ? "Przerwano odczyt ustawień OBD" : "";
            state.coolant=Double.NaN; state.oil=Double.NaN; state.faults=Collections.emptyList();
            state.hours=null; state.odometer=Double.NaN; state.tankLiters=Double.NaN; state.fuel=Double.NaN;
            state.gear=""; gearAt=-1;
        }
        if (changed && active) notifications.notify(11,notification(value));
        publish();
    }
    private void status(Profiles.Profile owner,String value,boolean connected,long after) {
        log.post(() -> { if (active && profile==owner) showObd(value,connected,after); });
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
                    status(current,"Wybierz adapter OBD w konfiguracji",false,0);
                    waitUntil(SystemClock.elapsedRealtime()+4000,wakeBefore); continue;
                }
                if (wifi) {
                    status(current,"Łączenie Wi-Fi · "+address,false,0);
                    link=lan.connect(TcpElm.Address.parse(address));
                } else {
                    BluetoothManager manager=getSystemService(BluetoothManager.class);
                    adapter=manager==null ? null : manager.getAdapter();
                    if (adapter==null || !adapter.isEnabled()) {
                        status(current,"Bluetooth wyłączony lub niedostępny",false,0);
                        retry.reset(); waitUntil(SystemClock.elapsedRealtime()+4000,wakeBefore); continue;
                    }
                    BluetoothDevice device=adapter.getRemoteDevice(address);
                    if (!ble && device.getBondState()!=BluetoothDevice.BOND_BONDED) {
                        status(current,"Sparuj adapter Classic w Androidzie",false,0);
                        waitUntil(SystemClock.elapsedRealtime()+7000,wakeBefore); continue;
                    }
                    status(current,"Łączenie z adapterem "+current.transport,false,0);
                    connectBluetooth(device,ble);
                }
                ready=true;
                status(current,"Adapter połączony • inicjalizacja OBD",false,0);
                Elm session=new Elm(link,wifi ? null : exchange);
                session.init(); retry.reset();
                Dtc.Monitor faults=new Dtc.Monitor();
                EngineHours.Monitor engineHours=new EngineHours.Monitor();
                boolean kiaProfile=EngineHours.kiaProfile(current.vehicle);
                long nextGear=0, nextProtocolCheck=0;
                if (!wifi) exchange.connected(true);
                status(current,"Adapter połączony • oczekiwanie na ECU",false,0);
                for (int n=0,missed=0;active && profile==current && request==reconnect;n++) {
                    long begin=SystemClock.elapsedRealtime();
                    double v=session.speed(); long at=SystemClock.elapsedRealtime();
                    if (Double.isFinite(v)) {
                        missed=0; nextProtocolCheck=0;
                        log.post(() -> { if (profile==current) speed(v,at); });
                    } else {
                        if (++missed==ECU_MISSES) { faults=new Dtc.Monitor(); engineHours=new EngineHours.Monitor(); }
                        if (missed>=ECU_MISSES && at>=nextProtocolCheck) {
                            status(current,"Adapter połączony • ponawiam wykrywanie ECU",false,0);
                            boolean ecu=session.rediscoverProtocol();
                            nextProtocolCheck=SystemClock.elapsedRealtime()+ECU_RECHECK_MS;
                            status(current,ecu ? "ECU odpowiada • brak odczytu prędkości" :
                                "Adapter połączony • ECU nie odpowiada",false,0);
                            continue;
                        }
                    }
                    if (serviceRead.getAndSet(false)) {
                        Elm.ServiceInterval data=v==0 && EngineHours.kiaProfile(current.vehicle) ? session.kiaService() : null;
                        String outcome=v==0 ? "Zegary nie zwróciły ustawień" : "Odczyt przerwany, auto musi stać";
                        log.post(() -> {
                            if (active && profile==current && request==reconnect) {
                                state.service=data; state.serviceStatus=data==null ? outcome : "Odczytano bezpośrednio z zegarów";
                                publish();
                            }
                        });
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
                            publish();
                        });
                    }
                    if (!wifi && missed<ECU_MISSES && n%3!=0) session.serveProxy();
                    if (missed==0 && n%3==1 && live.measurement!=Measurement.RUNNING && live.measurement!=Measurement.WAITING) {
                        if (kiaProfile && begin>=nextGear) {
                            KiaGear reading=session.kiaGear();
                            long sampled=SystemClock.elapsedRealtime();
                            nextGear=sampled+(reading==null ? 25_000 : 1800);
                            String gear=reading==null ? "" : reading.label(v);
                            log.post(() -> {
                                if (active && profile==current && request==reconnect && state.obd) {
                                    state.gear=gear; gearAt=sampled; publish();
                                }
                            });
                        }
                        List<String> update=faults.poll(session,begin);
                        if (update!=null) log.post(() -> {
                            if (active && profile==current && request==reconnect && !state.faults.equals(update)) {
                                state.faults=update; publish();
                            }
                        });
                    }
                    if (missed==0 && n%3==2 && live.measurement!=Measurement.RUNNING && live.measurement!=Measurement.WAITING) {
                        if (engineHours.due(begin)) {
                            EngineHours.Reading hours=engineHours.poll(session,current.vehicle,begin);
                            if (hours!=null) log.post(() -> {
                                if (active && profile==current && request==reconnect) { state.hours=hours; publish(); }
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
                status(current,wifi ? "Brak uprawnienia do sieci Wi-Fi" : "Brak uprawnienia do Bluetooth",false,0);
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
                status(current,reason,false,next);
                try { waitUntil(next,wakeBefore); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            } finally { closeLink(); }
        }
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
        active=false; wakeRetry();
        if (receiverRegistered) unregisterReceiver(wakeReceiver);
        if (location!=null) location.removeUpdates(fixes);
        lan.close();
        obd.shutdownNow(); linkDeadline.shutdownNow();
        closeLink();
        log.removeCallbacksAndMessages(null);
        log.post(store::close); logThread.quitSafely(); live=new Live("Rejestrator zatrzymany");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
