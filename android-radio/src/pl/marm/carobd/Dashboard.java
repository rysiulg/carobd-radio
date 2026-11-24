package pl.marm.carobd;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Dashboard extends Activity {
    private static final int BG=0xff091625, PANEL=0xff122438, EDGE=0xff254057, WHITE=0xfff0f7fa,
        MUTE=0xffa5b8c9, MINT=0xff64eabd, DARK=0xff0b251f, OFFLINE=0xffff8790, RECORDING=0xffff434f,
        WARNING=0xffffbe82;
    private static final Locale PL=new Locale("pl","PL");
    private static final String KIA_PANEL="pl.radiokia.preview";
    private static final String SHOW_SERVICE="pl.marm.carobd.SHOW_SERVICE";
    private static final int LOCATION=1, BLUETOOTH=2, FOLDER=3;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService healthWorker=Executors.newSingleThreadExecutor();
    private final Map<String,BluetoothDevice> devices=new LinkedHashMap<>();
    private Settings settings;
    private Trips store;
    private Typeface instrumentsFont;
    private LinearLayout root,body;
    private View recordingDot;
    private ImageView storageIcon;
    private TextView pill, speedSource, distance, consumption, tank, rangeValue, activeTime, syncReminder, historyStatus;
    private TextView coolantTemp, oilTemp, altitude, tripMean, odometer, engineHours, gpsStatus, obdStatus;
    private Button faultNotice;
    private Instruments.Dial speedDial,rpmDial;
    private Instruments.Fuel fuelChart;
    private String historyProfileId;
    private long historyRevision;
    private Button measure, retryObd;
    private Button arm,profileChoice,autoStopSetting;
    private String nextProfileId,editProfileId;
    private Profiles.Profile nextProfile,editing;
    private EditText profileName,vehicleId;
    private Spinner fuelType;
    private TextView adapterInfo,proxyInfo;
    private int page,offset;
    private boolean demo;
    private String folderLabel;
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private AlertDialog deviceDialog;
    private ArrayAdapter<String> choices;
    private String mode;
    private JSONObject selected;
    private DashboardHealth health;
    private String healthFolder;
    private long healthDue;
    private boolean healthBusy;

    private final Runnable ticker=new Runnable() {
        @Override public void run() { refresh(); main.postDelayed(this,850); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        instrumentsFont=getResources().getFont(R.font.oxanium_semibold);
        settings=new Settings(this); store=new Trips(this);
        show(0); Maintenance.schedule(this); checkServiceShortcut(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); checkServiceShortcut(intent); }
    private void checkServiceShortcut(Intent intent) {
        if (intent!=null && intent.getBooleanExtra(SHOW_SERVICE,false)) main.post(this::openService);
    }
    private void openService() {
        Recorder.Live live=Recorder.live;
        new ServiceWindow(this,live.armed ? settings.profiles.find(live.profileId) : nextProfile).show();
    }
    @Override public void onStart() { super.onStart(); healthDue=0; main.post(ticker); startMonitorIfReady(); }
    private void startMonitorIfReady() {
        if (!demo && !Recorder.live.armed &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startRecorder();
    }
    @Override public void onStop() {
        main.removeCallbacks(ticker); showRecordingDot(false); pulse(storageIcon,false); stopScan(); super.onStop();
    }
    @Override public void onDestroy() { healthWorker.shutdownNow(); store.close(); super.onDestroy(); }

    private int dp(float size) { return (int)(size*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable box(int bg,int radius) {
        GradientDrawable result=new GradientDrawable(); result.setColor(bg); result.setCornerRadius(dp(radius));
        if (bg==PANEL) result.setStroke(dp(1),EDGE);
        return result;
    }
    private LinearLayout group(boolean vertical) {
        LinearLayout r=new LinearLayout(this); r.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); return r;
    }
    private void padding(View v,int horizontal,int vertical) { v.setPadding(dp(horizontal),dp(vertical),dp(horizontal),dp(vertical)); }
    private TextView txt(String text,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("tnum"); v.setTypeface(Typeface.create("sans-serif",bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }
    private void gap(LinearLayout parent,int d) {
        boolean row=parent.getOrientation()==LinearLayout.HORIZONTAL;
        parent.addView(new View(this),row ? dp(d) : 1,row ? 1 : dp(d));
    }
    private void weight(LinearLayout parent,View view,float value) {
        boolean row=parent.getOrientation()==LinearLayout.HORIZONTAL;
        parent.addView(view,new LinearLayout.LayoutParams(row ? 0 : -1,row ? -1 : 0,value));
    }
    private void width(LinearLayout parent,View view,float w) {
        parent.addView(view,new LinearLayout.LayoutParams(0,-2,w));
    }
    private Button button(String title,boolean primary,Runnable action) {
        Button view=new Button(this); view.setText(title); view.setTextSize(14); view.setAllCaps(false);
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setTextColor(primary ? DARK : WHITE); view.setBackground(box(primary ? MINT : EDGE,12)); padding(view,13,6);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private LinearLayout panel(boolean vertical) {
        LinearLayout view=group(vertical); view.setBackground(box(PANEL,16)); padding(view,18,15); return view;
    }
    private void show(int target) {
        showRecordingDot(false); recordingDot=null;
        pulse(storageIcon,false); storageIcon=null; syncReminder=null;
        page=target; speedDial=null; arm=null; profileChoice=null; historyStatus=null; proxyInfo=null;
        nextProfile=settings.profiles.find(nextProfileId);
        root=group(true); root.setBackgroundColor(BG); padding(root,20,13);
        setContentView(root);
        LinearLayout row=group(false); row.setGravity(Gravity.CENTER_VERTICAL); root.addView(row,-1,dp(62));
        TextView brand=txt("CarOBD",28,WHITE,true); row.addView(brand);
        gap(row,12);
        row.addView(button("Wróć do panelu Kia",false,this::openKiaPanel),-2,dp(48));
        gap(row,12);
        row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        pill=txt("",13,MUTE,true); row.addView(pill);
        gap(row,12);
        gpsStatus=txt("",13,MUTE,true); row.addView(gpsStatus);
        gap(row,10);
        obdStatus=txt("",13,MUTE,true); row.addView(obdStatus);
        if (page==0) {
            gap(row,12);
            profileChoice=button("",false,this::chooseDrivingProfile);
            profileChoice.setSingleLine(true); profileChoice.setMaxWidth(dp(156));
            profileChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(profileChoice,-2,dp(48));
        }
        gap(row,12);
        for (int i=0;i<3;i++) {
            final int tab=i; Button b=button(new String[]{"Pulpit","Historia","Konfiguracja"}[i],page==i,() -> show(tab));
            row.addView(b,-2,dp(48)); if (i<2) gap(row,8);
        }
        faultNotice=button("",false,() -> {
            List<String> faults=Recorder.live.faults;
            if (!faults.isEmpty()) new AlertDialog.Builder(this).setTitle("Usterki OBD")
                .setMessage(android.text.TextUtils.join("\n\n",faults)).setPositiveButton("Zamknij",null).show();
        });
        faultNotice.setTextColor(0xffffbe82);
        faultNotice.setVisibility(View.GONE); root.addView(faultNotice,-1,-2);
        gap(root,12);
        body=group(true); weight(root,body,1);
        if (target==0) dashboard(); if (target==1) history(); if (target==2) config();
        refresh();
    }
    private void openKiaPanel() {
        Intent panel=getPackageManager().getLaunchIntentForPackage(KIA_PANEL);
        if (panel==null) { message("Zainstaluj aplikację RadioKIA Pulpit"); return; }
        try { startActivity(panel); }
        catch (ActivityNotFoundException | SecurityException error) { message("Nie można otworzyć panelu Kia"); }
    }
    private void dashboard() {
        LinearLayout columns=group(false);
        columns.setMinimumHeight(dp(480)); scrolling(body,columns);
        LinearLayout instruments=panel(true); weight(columns,instruments,1.62f); gap(columns,12);
        LinearLayout readings=group(false); instruments.addView(readings,-1,dp(47));
        coolantTemp=readout(readings,"Temperatura cieczy chłodzącej",R.drawable.metric_coolant,22);
        gap(readings,8);
        oilTemp=readout(readings,"Temperatura oleju",R.drawable.metric_oil,22);
        gap(readings,8);
        altitude=readout(readings,"Wysokość GPS",R.drawable.metric_altitude,22);
        gap(instruments,3);
        FrameLayout clockArea=new FrameLayout(this); weight(instruments,clockArea,1);
        LinearLayout clocks=group(false);
        clockArea.addView(clocks,new FrameLayout.LayoutParams(-1,-1));
        speedDial=new Instruments.Dial(this,instrumentsFont,"Prędkość","km/h",Instruments.SPEED_LIMIT,20,Instruments.SPEED_COLOR,.49f);
        rpmDial=new Instruments.Dial(this,instrumentsFont,"Obroty","obr./min",Instruments.RPM_LIMIT,1000,Instruments.RPM_COLOR,.445f);
        weight(clocks,rpmDial,.92f);
        gap(clocks,10);
        weight(clocks,speedDial,1.08f);
        recordingDot=new View(this); recordingDot.setBackground(box(RECORDING,6));
        recordingDot.setContentDescription("Trwa zapis przejazdu");
        recordingDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        recordingDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dotPosition=new FrameLayout.LayoutParams(dp(12),dp(12),Gravity.TOP|Gravity.END);
        dotPosition.topMargin=dp(10); dotPosition.setMarginEnd(dp(9));
        clockArea.addView(recordingDot,dotPosition);
        // Liczniki mają własną przestrzeń poza płótnem, więc tarcze nie zasłaniają cyfr.
        gap(instruments,3);
        odometer=centeredCounter(instruments,"—",32,WHITE,40);
        odometer.setTypeface(instrumentsFont); odometer.setIncludeFontPadding(false);
        engineHours=centeredCounter(instruments,"",16,WHITE,27);
        speedSource=txt("",14,MUTE,false); instruments.addView(speedSource);
        gap(instruments,3);
        arm=button("Włącz automat",false,this::armRecording); instruments.addView(arm,-1,-2);
        retryObd=button("Ponów OBD",false,() -> {
            if (Recorder.live.armed && !Recorder.live.obd) startService(new Intent(this,Recorder.class).setAction(Recorder.RETRY));
        }); instruments.addView(retryObd,-1,-2);
        LinearLayout stats=panel(true); weight(columns,stats,1);
        LinearLayout fuelReadings=group(false); stats.addView(fuelReadings,-1,dp(64));
        consumption=readout(fuelReadings,"Spalanie chwilowe",R.drawable.metric_flow,22); gap(fuelReadings,8);
        tripMean=readout(fuelReadings,"Średnie spalanie przejazdu",R.drawable.metric_average,22);
        LinearLayout fuelStatus=group(false); stats.addView(fuelStatus,-1,dp(38));
        tank=readout(fuelStatus,"Poziom paliwa",R.drawable.metric_fuel,15);
        gap(fuelStatus,8);
        rangeValue=readout(fuelStatus,"Zasięg wg samochodu",R.drawable.metric_range,15);
        fuelChart=new Instruments.Fuel(this); weight(stats,fuelChart,1);
        gap(stats,10);
        LinearLayout tripLine=group(false); tripLine.setGravity(Gravity.CENTER_VERTICAL); stats.addView(tripLine);
        distance=txt("",21,WHITE,true); distance.setTypeface(instrumentsFont); width(tripLine,distance,1);
        activeTime=txt("",21,WHITE,true); activeTime.setTypeface(instrumentsFont);
        activeTime.setGravity(Gravity.END); tripLine.addView(activeTime);
        gap(stats,6);
        LinearLayout statusLine=group(false); statusLine.setGravity(Gravity.CENTER_VERTICAL); stats.addView(statusLine);
        syncReminder=txt("",13,WARNING,true); width(statusLine,syncReminder,1); gap(statusLine,8);
        storageIcon=new ImageView(this); storageIcon.setPadding(dp(6),dp(6),dp(6),dp(6));
        storageIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        statusLine.addView(storageIcon,dp(37),dp(37));
        gap(stats,7);
        LinearLayout actions=group(false); stats.addView(actions,-1,dp(48));
        measure=button("Pomiar przyspieszenia",true,this::measure);
        Button service=button("Motogodziny i serwis",false,this::openService);
        for (Button action:new Button[]{measure,service}) {
            padding(action,7,4); action.setMaxLines(2);
            action.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        weight(actions,measure,1); gap(actions,8); weight(actions,service,1);
    }
    private TextView centeredCounter(LinearLayout column,String label,int size,int color,int height) {
        TextView view=txt(label,size,color,size>13);
        view.setGravity(Gravity.CENTER); view.setSingleLine(true);
        view.setAutoSizeTextTypeUniformWithConfiguration(10,size,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        column.addView(view,-1,dp(height));
        return view;
    }
    private TextView readout(LinearLayout row,String caption,int icon,int size) {
        LinearLayout cell=group(false);
        cell.setGravity(Gravity.CENTER); cell.setBackground(box(0xff0d1c2d,9));
        ImageView mark=new ImageView(this);
        mark.setImageResource(icon); mark.setColorFilter(MINT);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(mark,dp(23),dp(23)); gap(cell,8);
        TextView value=txt("",size,WHITE,true);
        value.setTypeface(instrumentsFont); value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false); value.setTag(caption);
        cell.setTooltipText(caption); cell.addView(value);
        weight(row,cell,1);
        return value;
    }
    private void reading(TextView view,String value) {
        changed(view,value);
        String description=view.getTag()+" "+value;
        if (!description.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription()))
            view.setContentDescription(description);
    }
    private static void changed(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    private void showRecordingDot(boolean recording) {
        if (recordingDot==null) return;
        int visibility=recording ? View.VISIBLE : View.GONE;
        if (recordingDot.getVisibility()==visibility) return;
        recordingDot.setVisibility(visibility); pulse(recordingDot,recording);
    }
    private static void pulse(View view,boolean active) {
        if (view==null || active==(view.getAnimation()!=null)) return;
        if (!active) { view.clearAnimation(); return; }
        AlphaAnimation blink=new AlphaAnimation(1f,.18f);
        blink.setDuration(620); blink.setRepeatMode(Animation.REVERSE); blink.setRepeatCount(Animation.INFINITE);
        view.startAnimation(blink);
    }
    private void refreshHealth(long now) {
        String folder=settings.get("folder","");
        if (!folder.equals(healthFolder)) { healthFolder=folder; health=null; healthDue=0; }
        if (!demo && !healthBusy && now>=healthDue) {
            healthDue=now+30_000; healthBusy=true;
            healthWorker.execute(() -> {
                DashboardHealth checked=DashboardHealth.read(getApplicationContext(),settings,folder);
                main.post(() -> {
                    healthBusy=false;
                    if (!isDestroyed() && folder.equals(healthFolder)) health=checked;
                });
            });
        }
        String medium=settings.get("medium","Nośnik");
        int icon=folder.isEmpty() ? R.drawable.storage_radio : "USB".equals(medium) ? R.drawable.storage_usb :
            "Karta SD".equals(medium) ? R.drawable.storage_sd : R.drawable.storage_radio;
        if (!Integer.valueOf(icon).equals(storageIcon.getTag())) {
            storageIcon.setImageResource(icon); storageIcon.setTag(icon);
        }
        boolean lowRadio=!demo && health!=null && health.lowRadio;
        boolean lowGpx=!demo && health!=null && health.lowGpx;
        boolean gpxError=!demo && !folder.isEmpty() && folder.equals(Maintenance.failedExportFolder);
        String location=folder.isEmpty() ? "Pamięć radia" : medium+" · kopie GPX";
        String warning=lowRadio && lowGpx ? "Mało miejsca w radiu i na nośniku GPX" :
            lowRadio ? "Mało miejsca w pamięci radia" : lowGpx ? "Mało miejsca na nośniku GPX" :
            gpxError ? "Sprawdź nośnik GPX" : "";
        String description=location+(warning.isEmpty() ? "" : " · "+warning);
        if (!description.equals(storageIcon.getContentDescription())) {
            storageIcon.setContentDescription(description); storageIcon.setTooltipText(description);
            storageIcon.setColorFilter(lowRadio || lowGpx ? WARNING : gpxError ? OFFLINE : MINT);
        }
        pulse(storageIcon,lowRadio || lowGpx);
        changed(syncReminder,!demo && health!=null && settings.nasUploadEnabled() ? health.reminder : "");
    }
    private void connectionStatus(TextView view,String label,boolean online,String description) {
        String value=label+(online ? " ✓" : " ×");
        if (value.contentEquals(view.getText())) return;
        view.setText(value); view.setTextColor(online ? MINT : OFFLINE);
        view.setContentDescription(description);
    }
    private void refresh() {
        Recorder.Live live=Recorder.live;
        if (page==1 && !demo && NasHistory.revision!=historyRevision) { show(1); return; }
        if (historyStatus!=null) changed(historyStatus,NasHistory.status);
        if (proxyInfo!=null) changed(proxyInfo,live.armed && !live.proxyStatus.isEmpty() ? live.proxyStatus :
            settings.proxyEnabled() ? "Uruchom automat, aby udostępnić odczyt OBD" : "Proxy Wi-Fi wyłączone");
        List<String> faults=live.faults;
        boolean showFault=!demo && live.obd && !faults.isEmpty();
        faultNotice.setVisibility(showFault ? View.VISIBLE : View.GONE);
        if (showFault) changed(faultNotice,"OBD · "+faults.get(0)+(faults.size()>1 ? "  +"+(faults.size()-1) : ""));
        String banner=demo ? "DANE DEMONSTRACYJNE" : live.journalError ? "Błąd zapisu trasy" : "";
        changed(pill,banner); pill.setVisibility(banner.isEmpty() ? View.GONE : View.VISIBLE);
        pill.setTextColor(!demo && live.journalError ? 0xffffbe82 : MUTE);
        connectionStatus(gpsStatus,"GPS",live.gps,live.gps ? "Sygnał GPS" : "Brak sygnału GPS");
        connectionStatus(obdStatus,"OBD",live.obd,live.obd ? "OBD połączone" : "Brak danych OBD");
        if (speedDial==null) return;
        String profileName=live.armed ? live.profileName : nextProfile.name;
        changed(profileChoice,profileName);
        String profileDescription="Profil jazdy: "+profileName;
        if (!profileDescription.equals(profileChoice.getContentDescription())) profileChoice.setContentDescription(profileDescription);
        profileChoice.setEnabled(live.started==0 && !demo);
        speedDial.value(demo ? 74 : live.speed);
        rpmDial.value(demo ? 1850 : live.rpm);
        reading(coolantTemp,fmt(demo ? 90 : live.coolant,0)+" °C");
        reading(oilTemp,fmt(demo ? 105 : live.oil,0)+" °C");
        reading(altitude,fmt(demo ? 247 : live.altitude,0)+" m");
        long now=SystemClock.elapsedRealtime();
        String vehicle=live.armed ? live.vehicle : nextProfile.vehicle;
        boolean kia=EngineHours.kiaProfile(vehicle);
        rpmDial.kiaScale(demo || kia);
        speedDial.gear(demo ? "D4" : kia ?
            (live.gear.isEmpty() ? "—" : live.gear) : "");
        double reading=demo ? 197452 : live.obd ? live.odometer : Double.NaN;
        changed(odometer,Double.isFinite(reading) ? String.format(PL,"%,.0f km",reading) : "—");
        boolean radioHours=live.obd && live.hours!=null && live.hours.fresh(now);
        long engineSeconds=demo ? Math.round(6984.9*3600) : radioHours ? live.hours.total : -1;
        long idleSeconds=demo ? Math.round(1644.4*3600) : radioHours ? live.hours.idle : -1;
        changed(engineHours,EngineHours.duration(engineSeconds)+"  ·  Postój "+EngineHours.duration(idleSeconds));
        long remaining=live.retryAt==0 ? 0 : Math.max(0,(live.retryAt-now+999)/1000);
        String obdMessage=live.status+(remaining>0 ? " · ponowienie za "+remaining+" s" : "");
        boolean recording=!demo && live.started>0;
        showRecordingDot(recording);
        String countdown=recording && live.autoStopAt>now ? "Auto-stop za "+elapsed((live.autoStopAt-now+999)/1000) : "";
        String notice=demo || !live.armed ? "" : live.obd ? countdown :
            (countdown.isEmpty() ? "" : countdown+" · ")+obdMessage;
        changed(speedSource,notice); speedSource.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        retryObd.setVisibility(!demo && live.armed && !live.obd ? View.VISIBLE : View.GONE);
        changed(distance,(demo ? "8,42" : fmt(live.km,2))+" km");
        long tripSeconds=demo ? 9*60+32 : live.started>0 ? (now-live.started)/1000 : -1;
        changed(activeTime,elapsed(tripSeconds));
        reading(consumption,(live.fuelSource==2 && !demo && Double.isFinite(live.rate) ? "~" : "")+
            (demo ? "5,8" : fmt(live.rate,1))+" l/h");
        reading(tripMean,(demo ? "6,7" : fmt(live.mean,1))+" l/100 km");
        boolean estimatedFuel=!demo && !Double.isFinite(live.fuel) && Double.isFinite(live.tankLiters);
        double fuel=demo ? 58 : estimatedFuel ? 100*live.tankLiters/Elm.KIA_TANK_L : live.fuel;
        tank.setTag(estimatedFuel ? "Poziom paliwa szacowany z litrów OBD" : "Poziom paliwa");
        JSONObject range=kia ? null : NasHistory.telemetry(settings,vehicle).optJSONObject("range");
        long age=range==null ? Long.MAX_VALUE : System.currentTimeMillis()/1000-range.optLong("t",0);
        double nasRange=age>=0 && age<=6*3600 ? range.optDouble("range_km") : Double.NaN;
        reading(tank,Double.isFinite(fuel) ? (estimatedFuel ? "~" : "")+fmt(fuel,0)+"%" : "—");
        reading(rangeValue,demo ? "520 km" : Double.isFinite(nasRange) ? fmt(nasRange,0)+" km" : "—");
        fuelChart.sample(live.rate,live.rpm,live.speed,now,demo);
        changed(arm,demo ? "Zakończ podgląd" : "Włącz lokalizację");
        arm.setVisibility(demo || !live.armed ? View.VISIBLE : View.GONE);
        String measurement=live.measurement==Measurement.WAITING ? "Anuluj · czeka na ruszenie" :
            live.measurement==Measurement.RUNNING ? "Pomiar w toku · anuluj" :
            live.measurement==Measurement.RESULT ? "Wynik pomiaru" : "Pomiar przyspieszenia";
        changed(measure,measurement);
        measure.setEnabled(!demo && live.armed && (live.measurement!=Measurement.NONE ||
            (Double.isFinite(live.speed) && live.speed<=2 && live.rpm>300)));
        refreshHealth(now);
    }
    private void measure() {
        Recorder.Live live=Recorder.live;
        if (live.measurement==Measurement.RESULT) {
            StringBuilder results=new StringBuilder();
            for (int i=0;i<live.times.length;i++) results.append("0–").append((i+1)*20).append(" km/h   ")
                .append(Double.isFinite(live.times[i]) ? fmt(live.times[i],2)+" s" : "—").append('\n');
            AlertDialog.Builder result=new AlertDialog.Builder(this).setTitle("Wynik przyspieszenia")
                .setMessage(results).setNegativeButton("Zamknij",null);
            if (Double.isFinite(live.speed) && live.speed<=2 && live.rpm>300)
                result.setPositiveButton("Nowy pomiar",(d,i) -> startMeasurement());
            result.show();
        } else startMeasurement();
    }
    private void startMeasurement() { startService(new Intent(this,Recorder.class).setAction(Recorder.MEASURE)); }
    private void armRecording() {
        if (demo) { demo=false; selected=null; refresh(); return; }
        if (Recorder.live.armed) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION);
        } else startRecorder();
    }
    private void startRecorder() {
        startForegroundService(new Intent(this,Recorder.class).putExtra(Recorder.PROFILE,nextProfileId));
        nextProfileId=null; nextProfile=settings.profiles.defaultProfile();
    }
    private void chooseDrivingProfile() {
        List<Profiles.Profile> profiles=settings.profiles.list();
        String preferred=settings.profiles.defaultProfile().id;
        String[] choices=new String[profiles.size()];
        for (int i=0;i<profiles.size();i++) {
            Profiles.Profile item=profiles.get(i);
            choices[i]=item.name+(item.id.equals(preferred) ? " · domyślny" : "");
        }
        new AlertDialog.Builder(this).setTitle("Profil dla tej rejestracji").setItems(choices,(d,i) -> {
            nextProfile=profiles.get(i);
            nextProfileId=nextProfile.id.equals(preferred) ? null : nextProfile.id;
            if (Recorder.live.armed) startService(new Intent(this,Recorder.class).putExtra(Recorder.PROFILE,nextProfile.id));
            refresh();
        }).setNegativeButton("Anuluj",null).show();
    }

    private ScrollView scrolling(LinearLayout parent,LinearLayout content) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        weight(parent,scroll,1); return scroll;
    }
    private String tripDistance(JSONObject trip) {
        double km=trip.optDouble("km"), gps=trip.optDouble("gps_km");
        if (!(km>0) && gps>0) return fmt(gps,2)+" km GPS";
        return Double.isFinite(km) ? fmt(km,2)+" km" : "—";
    }
    private String tripFuel(JSONObject trip) {
        double liters=trip.optDouble("fuel_l");
        return Double.isFinite(liters) ? fmt(liters,2)+" l" : "—";
    }
    private View tripCard(String title,String metrics,boolean selected,Runnable open) {
        LinearLayout card=group(true);
        card.setGravity(Gravity.CENTER); card.setMinimumHeight(dp(55)); padding(card,8,5);
        card.setBackground(box(selected ? MINT : EDGE,12));
        int ink=selected ? DARK : WHITE;
        TextView date=txt(title,14,ink,true);
        TextView details=txt(metrics,11,ink,true);
        details.setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        for (TextView line:new TextView[]{date,details}) {
            line.setMaxLines(1); line.setGravity(Gravity.CENTER);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(line,-1,-2);
        }
        card.setContentDescription(title+", "+metrics);
        card.setFocusable(true); card.setSelected(selected); card.setOnClickListener(v -> open.run());
        return card;
    }
    private void history() {
        LinearLayout columns=group(false); weight(body,columns,1);
        LinearLayout list=panel(true); weight(columns,list,0.48f); gap(columns,12);
        LinearLayout listTitle=group(false); listTitle.setGravity(Gravity.CENTER_VERTICAL);
        width(listTitle,txt("Przejazdy",21,WHITE,true),1); list.addView(listTitle); gap(list,9);
        Profiles.Profile car=settings.profiles.find(historyProfileId);
        if (!demo) {
            list.addView(button(car.name,false,this::chooseHistoryProfile),-1,dp(48)); gap(list,7);
        }
        historyRevision=NasHistory.revision;
        LinearLayout entries=group(true); scrolling(list,entries);
        List<JSONObject> items=demo ? Demo.rows() : store.history(car.vehicle,offset);
        if (items.isEmpty()) entries.addView(txt("Brak zapisanych tras",15,MUTE,false));
        String selectedId=selected==null ? "" : selected.optString("id");
        selected=items.isEmpty() ? null : items.get(0);
        for (JSONObject item:items) if (selectedId.equals(item.optString("id"))) { selected=item; break; }
        for (JSONObject info:items) {
            double cost=info.optDouble("cost_pln");
            String price=Double.isFinite(cost) ? " · "+fmt(cost,2)+"\u00a0zł" : "";
            View pick=tripCard(date(info.optLong("t_start")),tripDistance(info)+" · "+tripFuel(info)+price,
                info==selected || info.optString("id").equals(selected.optString("id")),() -> { selected=info; show(1); });
            entries.addView(pick,-1,-2); gap(entries,7);
        }
        if (!demo && (offset>0 || items.size()==60)) {
            LinearLayout nav=group(false); list.addView(nav);
            if (offset>0) width(nav,button("Nowsze",false,() -> { offset-=60; selected=null; show(1); }),1);
            if (items.size()==60) width(nav,button("Starsze",false,() -> { offset+=60; selected=null; show(1); }),1);
        }
        if (!demo) {
            historyStatus=txt(NasHistory.status,13,MUTE,false); list.addView(historyStatus);
            gap(list,7); list.addView(button("Pobierz z NAS",false,() -> {
                if (!settings.hasNasCredential()) { message("Radio oczekuje na przypisanie do NAS."); return; }
                NasHistory.request(settings,car.vehicle);
                NasHistory.refreshHistory(settings,car.vehicle);
                NasHistory.status="NAS • oczekiwanie na połączenie"; Maintenance.schedule(this); refresh();
            }),-1,dp(48));
        }
        LinearLayout view=panel(true); weight(columns,view,1.52f);
        if (selected==null) { view.addView(txt("Mapa i statystyki trasy",21,WHITE,true)); return; }
        JSONObject info=selected;
        LinearLayout title=group(false); title.setGravity(Gravity.CENTER_VERTICAL); view.addView(title);
        width(title,txt(date(info.optLong("t_start")),19,WHITE,true),1);
        title.addView(txt(tripDistance(info)+"   •   "+elapsed(info.optLong("dur_s",-1))+
            "   •   "+fmt(info.optDouble("l100"),1)+" l/100 km",14,MINT,true));
        view.addView(txt("Zużyte paliwo  "+tripFuel(info)+"   •   Szacowany koszt paliwa (NAS)  "+(Double.isFinite(info.optDouble("cost_pln")) ?
            fmt(info.optDouble("cost_pln"),2)+" zł" : "—"),14,MUTE,true));
        gap(view,9);
        List<Trips.Point> points=demo ? Demo.points(info.optString("id")) : store.points(info.optString("id"));
        RouteView map=new RouteView(this,points); weight(view,map,1);
        LinearLayout controls=group(false); controls.setGravity(Gravity.CENTER_VERTICAL); view.addView(controls);
        Switch tiles=new Switch(this); tiles.setText("Podkład mapy"); tiles.setTextColor(MUTE);
        tiles.setChecked(!demo && settings.enabled("tiles"));
        if (tiles.isChecked()) map.background(true);
        tiles.setOnCheckedChangeListener((b,on) -> {
            map.background(on);
            if (!demo) settings.prefs.edit().putBoolean("tiles",on).apply();
        });
        controls.addView(tiles);
        controls.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        for (String type:new String[]{"−","Dopasuj","+"}) {
            controls.addView(button(type,false,() -> { if (type.equals("Dopasuj")) map.fit(); else map.zoomBy(type.equals("+") ? 1 : -1); }),-2,dp(44));
            gap(controls,5);
        }
        view.addView(txt("Przyspieszenie od ruszenia • najlepszy czas w trasie",15,WHITE,true));
        JSONArray times=info.optJSONArray("accel_s");
        gap(view,6); LinearLayout marks=group(false); view.addView(marks);
        for (int i=0;i<7;i++) {
            LinearLayout cell=group(true); cell.setGravity(Gravity.CENTER);
            cell.addView(txt("0–"+((i+1)*20)+" km/h",13,MUTE,false));
            cell.addView(txt(times!=null && !times.isNull(i) ? fmt(times.optDouble(i),2)+" s" : "—",19,MINT,true));
            width(marks,cell,1);
        }
    }
    private void chooseHistoryProfile() {
        List<Profiles.Profile> profiles=settings.profiles.list();
        String[] names=new String[profiles.size()];
        for (int i=0;i<profiles.size();i++) names[i]=profiles.get(i).name;
        new AlertDialog.Builder(this).setTitle("Historia samochodu").setItems(names,(d,i) -> {
            Profiles.Profile chosen=profiles.get(i); historyProfileId=chosen.id; offset=0; selected=null;
            NasHistory.request(settings,chosen.vehicle); Maintenance.schedule(this); show(1);
        }).setNegativeButton("Anuluj",null).show();
    }

    private void config() {
        LinearLayout columns=group(false); weight(body,columns,1);
        LinearLayout index=panel(true); weight(columns,index,1); gap(columns,12);
        LinearLayout editor=panel(true); weight(columns,editor,1.45f);
        LinearLayout left=group(true),right=group(true);
        scrolling(index,left); scrolling(editor,right);
        Switch recording=new Switch(this);
        recording.setText("Rejestruj przejazdy"); recording.setTextColor(WHITE);
        recording.setChecked(settings.recordingEnabled());
        recording.setOnCheckedChangeListener((view,enabled) -> settings.setRecordingEnabled(enabled));
        left.addView(recording,-1,dp(44));
        Switch upload=new Switch(this);
        upload.setText("Wysyłaj przejazdy do NAS"); upload.setTextColor(WHITE);
        upload.setChecked(settings.nasUploadEnabled());
        upload.setOnCheckedChangeListener((view,enabled) -> {
            settings.setNasUploadEnabled(enabled); healthDue=0;
            if (enabled) Maintenance.schedule(this);
        });
        left.addView(upload,-1,dp(44)); gap(left,8);
        autoStopSetting=button(autoStopLabel(),false,this::chooseAutoStop);
        left.addView(autoStopSetting,-1,dp(48)); gap(left,14);
        Switch sharing=new Switch(this);
        sharing.setText("Udostępnij OBD przez Wi-Fi"); sharing.setTextColor(WHITE);
        sharing.setChecked(settings.proxyEnabled());
        sharing.setOnCheckedChangeListener((view,enabled) -> { settings.setProxyEnabled(enabled); refresh(); });
        left.addView(sharing,-1,dp(44));
        proxyInfo=txt("",14,MUTE,false); left.addView(proxyInfo); gap(left,14);
        editing=settings.profiles.find(editProfileId);
        editProfileId=editing.id;
        String preferred=settings.profiles.defaultProfile().id;
        left.addView(txt("Profile adapterów",22,WHITE,true)); gap(left,10);
        for (Profiles.Profile item:settings.profiles.list()) {
            String title=item.name+(item.id.equals(preferred) ? " · domyślny" : "");
            left.addView(button(title,item.id.equals(editing.id),() -> edit(item)),-1,dp(48)); gap(left,6);
        }
        LinearLayout profileActions=group(false); left.addView(profileActions);
        width(profileActions,button("Dodaj profil",false,() -> edit(settings.profiles.create())),1); gap(profileActions,7);
        width(profileActions,button("Duplikuj",false,() -> {
            Profiles.Profile copy=editorValue(); if (copy!=null) edit(settings.profiles.duplicate(copy));
        }),1);
        gap(left,19);
        left.addView(txt("Zapis GPX",19,WHITE,true)); gap(left,5);
        left.addView(txt(settings.get("medium","Tylko pamięć radia"),15,MINT,true)); gap(left,7);
        LinearLayout storage=group(false); left.addView(storage);
        width(storage,button("Wybierz kartę SD",false,() -> folder("Karta SD")),1); gap(storage,7);
        width(storage,button("Wybierz USB",false,() -> folder("USB")),1); gap(left,8);
        if (!settings.get("folder","").isEmpty())
            left.addView(button("Tylko pamięć radia",false,() -> {
                settings.prefs.edit().remove("folder").remove("medium").apply(); show(2);
            }),-1,dp(48));
        right.setFocusableInTouchMode(true); right.requestFocus();
        right.addView(txt("Edycja profilu",22,WHITE,true)); gap(right,8);
        profileName=field(right,"Nazwa profilu",editing.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        profileName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(72)});
        vehicleId=field(right,"Identyfikator auta",editing.vehicle,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        gap(right,7); adapterInfo=txt(adapterLabel(),15,MINT,true); right.addView(adapterInfo); gap(right,7);
        LinearLayout adapter=group(false); right.addView(adapter);
        width(adapter,button("Skanuj BLE",false,() -> selectAdapter("BLE")),1); gap(adapter,7);
        width(adapter,button("Sparowane Classic",false,() -> selectAdapter("Classic")),1); gap(adapter,7);
        width(adapter,button("Wpisz MAC",false,this::chooseManualTransport),1); gap(adapter,7);
        width(adapter,button("Wi-Fi TCP",false,this::enterWifi),1); gap(right,6);
        right.addView(button("Parowanie w Androidzie",false,() -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (ActivityNotFoundException e) { message("Ten panel fabryczny nie udostępnia tego ekranu."); }
        }),-1,dp(48));
        gap(right,7); right.addView(txt("Paliwo",14,MUTE,true));
        fuelType=new Spinner(this);
        fuelType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Diesel","Benzyna"}));
        fuelType.setSelection("diesel".equals(editing.fuel) ? 0 : 1);
        right.addView(fuelType,-1,dp(47)); gap(right,8);
        LinearLayout save=group(false); right.addView(save);
        width(save,button("Zapisz profil",true,() -> saveProfile(false)),1); gap(save,7);
        if (!editing.id.equals(preferred)) width(save,button("Ustaw jako domyślny",false,() -> saveProfile(true)),1);
        gap(right,7); right.addView(button("Motogodziny i serwis",false,() -> new ServiceWindow(this,editing).show()));
        gap(right,10);
        right.addView(button(demo ? "Wyłącz podgląd demonstracyjny" : "Podgląd demonstracyjny",false,() -> {
            if (Recorder.live.started>0) { message("Podgląd jest dostępny poza przejazdem."); return; }
            demo=!demo; selected=null; offset=0; show(demo ? 0 : 2);
        }),-1,dp(48));
    }
    private String autoStopLabel() {
        int minutes=settings.autoStopMinutes();
        return "Auto-stop · "+(minutes==0 ? "wyłączony" : minutes+" min");
    }
    private void chooseAutoStop() {
        String[] choices=new String[Settings.AUTO_STOP_MAX+1];
        choices[0]="0 — wyłączony";
        for (int i=1;i<choices.length;i++) choices[i]=i+" min";
        new AlertDialog.Builder(this).setTitle("Auto-stop po zatrzymaniu")
            .setSingleChoiceItems(choices,settings.autoStopMinutes(),(dialog,minutes) -> {
                settings.setAutoStopMinutes(minutes);
                changed(autoStopSetting,autoStopLabel());
                dialog.dismiss();
            }).setNegativeButton("Anuluj",null).show();
    }
    private String adapterLabel() {
        return editing.adapter.isEmpty() ? "Brak wybranego adaptera" :
            editing.isWifi() ? "Wi-Fi TCP · "+editing.adapter : editing.adapterName+" · "+editing.transport+" · "+editing.adapter;
    }
    private void edit(Profiles.Profile item) { editProfileId=item.id; show(2); }
    private Profiles.Profile editorValue() {
        String name=profileName.getText().toString().trim(), vehicle=vehicleId.getText().toString().trim();
        if (name.isEmpty()) { profileName.setError("Podaj nazwę profilu"); return null; }
        if (!Settings.identifier(vehicle)) { vehicleId.setError("Użyj liter bez polskich znaków, cyfr lub ._-"); return null; }
        return new Profiles.Profile(editing.id,name,vehicle,editing.adapter,editing.adapterName,editing.transport,
            fuelType.getSelectedItemPosition()==0 ? "diesel" : "benzyna");
    }
    private void saveProfile(boolean makeDefault) {
        Profiles.Profile value=editorValue(); if (value==null) return;
        if (!settings.profiles.save(value,makeDefault)) {
            message("Nie udało się zapisać profilu. Spróbuj ponownie.");
            return;
        }
        if (makeDefault) nextProfileId=null;
        Recorder.Live live=Recorder.live;
        if (live.armed && live.started==0 && (makeDefault || value.id.equals(live.profileId)))
            startService(new Intent(this,Recorder.class).putExtra(Recorder.PROFILE,value.id));
        edit(value);
        startMonitorIfReady();
        savePopup("Zapisano profil",makeDefault ? "„"+value.name+"” jest teraz profilem domyślnym." : "„"+value.name+"”");
    }
    private void chooseManualTransport() {
        new AlertDialog.Builder(this).setTitle("Tryb połączenia").setItems(new String[]{"BLE","Classic"},(d,i) -> {
            mode=i==0 ? "BLE" : "Classic"; enterMac();
        }).setNegativeButton("Anuluj",null).show();
    }
    private void enterWifi() {
        EditText address=new EditText(this);
        address.setSingleLine(true); address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("adapter.marm:"+TcpElm.PORT);
        if (editing.isWifi()) address.setText(editing.adapter);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Host i port OBD Wi-Fi").setView(address)
            .setPositiveButton("Zapisz",null).setNegativeButton("Anuluj",null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                TcpElm.Address endpoint=TcpElm.Address.parse(address.getText().toString());
                confirmEditedAddress(dialog,endpoint.toString(),endpoint.host,Profiles.Profile.WIFI);
            } catch (IllegalArgumentException bad) { address.setError(bad.getMessage()); }
        }));
        dialog.show();
    }
    private void setEditedAdapter(String address,String name,String transport) {
        editing=editing.withAdapter(address,name,transport);
        adapterInfo.setText(adapterLabel());
    }
    private void confirmEditedAddress(AlertDialog dialog,String address,String name,String transport) {
        setEditedAdapter(address,name,transport);
        dialog.dismiss();
        savePopup("Ustawiono adres adaptera","Aby zachować zmianę, kliknij „Zapisz profil”.");
    }
    private EditText field(LinearLayout group,String label,String value,int type) {
        group.addView(txt(label,13,MUTE,true));
        EditText field=new EditText(this); field.setTextSize(15); field.setTextColor(WHITE);
        field.setInputType(type); field.setSingleLine(true); field.setText(value);
        field.setContentDescription(label); field.setBackgroundTintList(ColorStateList.valueOf(EDGE));
        group.addView(field,-1,dp(42)); return field;
    }
    private void folder(String label) {
        folderLabel=label;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker,FOLDER); }
        catch (ActivityNotFoundException e) { message("Radio nie udostępnia systemowego wyboru katalogu."); }
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==FOLDER && result==RESULT_OK && data!=null && data.getData()!=null) {
            Uri uri=data.getData();
            try {
                int flags=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0) throw new SecurityException();
                getContentResolver().takePersistableUriPermission(uri,flags);
                settings.prefs.edit().putString("folder",uri.toString()).putString("medium",folderLabel==null ? "Nośnik" : folderLabel).apply();
                Maintenance.schedule(this); show(2);
            } catch (SecurityException e) { message("Wybrany katalog nie pozwala zapisywać GPX."); }
        }
    }
    private static String[] btPermissions() {
        return new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT};
    }
    private boolean hasBt() {
        return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
    }
    private void selectAdapter(String transport) {
        mode=transport;
        if (!hasBt()) { requestPermissions(btPermissions(),BLUETOOTH); return; }
        if (Build.VERSION.SDK_INT<=30 && "BLE".equals(mode) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},BLUETOOTH); return;
        }
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        BluetoothAdapter bt=manager==null ? null : manager.getAdapter();
        if (bt==null || !bt.isEnabled()) { message("Włącz Bluetooth w Androidzie radia."); return; }
        stopScan(); devices.clear();
        choices=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1);
        deviceDialog=new AlertDialog.Builder(this).setTitle("BLE".equals(mode) ? "Wybierz adapter BLE" : "Wybierz sparowane urządzenie")
            .setAdapter(choices,(dialog,index) -> {
                BluetoothDevice chosen=new ArrayList<>(devices.values()).get(index);
                setEditedAdapter(chosen.getAddress(),deviceName(chosen),mode);
            }).setNeutralButton("Wpisz MAC",(d,i) -> enterMac()).setNegativeButton("Anuluj",null).create();
        deviceDialog.setOnDismissListener(dialog -> stopScan()); deviceDialog.show();
        for (BluetoothDevice d:bt.getBondedDevices()) if (!"BLE".equals(mode) || d.getType()!=BluetoothDevice.DEVICE_TYPE_CLASSIC) addDevice(d);
        if ("BLE".equals(mode)) {
            scanner=bt.getBluetoothLeScanner();
            if (scanner==null) { message("Radio nie udostępnia skanera BLE."); return; }
            scan=new ScanCallback() {
                @Override public void onScanResult(int type,ScanResult item) { main.post(() -> addDevice(item.getDevice())); }
                @Override public void onScanFailed(int code) { main.post(() -> message("Skan BLE niedostępny ("+code+").")); }
            };
            try { scanner.startScan(scan); main.postDelayed(this::stopScan,12000); }
            catch (RuntimeException error) { message("Skan BLE niedostępny."); stopScan(); }
        }
    }
    private void enterMac() {
        final EditText field=new EditText(this);
        field.setSingleLine(true); field.setHint("00:11:22:33:44:55");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Adres adaptera "+mode).setView(field)
            .setPositiveButton("Zapisz",null).setNegativeButton("Anuluj",null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address=field.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!BluetoothAdapter.checkBluetoothAddress(address)) { field.setError("Nieprawidłowy adres MAC"); return; }
            confirmEditedAddress(dialog,address,"Adapter "+mode,mode);
        }));
        dialog.show();
    }
    private void addDevice(BluetoothDevice device) {
        if (deviceDialog==null || !deviceDialog.isShowing() || devices.containsKey(device.getAddress())) return;
        devices.put(device.getAddress(),device);
        choices.add(deviceName(device)+"  ·  "+device.getAddress());
    }
    private String deviceName(BluetoothDevice d) {
        String name=d.getName(); return name==null || name.isEmpty() ? "Urządzenie Bluetooth" : name;
    }
    private void stopScan() {
        if (scanner!=null && scan!=null) try { scanner.stopScan(scan); } catch (RuntimeException ignored) {}
        scanner=null; scan=null;
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grant) {
        super.onRequestPermissionsResult(request,permissions,grant);
        if (request==LOCATION && grant.length>0 && grant[0]==PackageManager.PERMISSION_GRANTED) armRecording();
        if (request==BLUETOOTH && grant.length>0 && grant[0]==PackageManager.PERMISSION_GRANTED && hasBt()) selectAdapter(mode);
    }
    private void message(String why) { Toast.makeText(this,why,Toast.LENGTH_LONG).show(); }
    private void savePopup(String title,String detail) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("OK",null).show();
    }
    static String fmt(double n,int d) { return Double.isFinite(n) ? String.format(PL,"%."+d+"f",n) : "—"; }
    private static String date(long sec) { return new SimpleDateFormat("dd.MM.yyyy  HH:mm",PL).format(new Date(sec*1000)); }
    private static String elapsed(long sec) {
        if (sec<0) return "—";
        if (sec==0) return "0s";
        long hours=sec/3600,minutes=sec/60%60,seconds=sec%60;
        return ((hours>0 ? hours+"g " : "")+(minutes>0 ? minutes+"m " : "")+
            (seconds>0 ? seconds+"s" : "")).trim();
    }
}
