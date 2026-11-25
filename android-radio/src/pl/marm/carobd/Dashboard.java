package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.Manifest;
import android.animation.ValueAnimator;
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
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Dashboard extends Activity {
    static final int BG=0xff091625, PANEL=0xff122438, EDGE=0xff254057, WHITE=0xfff0f7fa,
        MUTE=0xffa5b8c9, MINT=0xff64eabd, DARK=0xff0b251f, OFFLINE=0xffff8790, RECORDING=0xffff434f,
        WARNING=0xffffbe82, LINK_YELLOW=0xffffd451;
    private static final Pattern UNITS=Pattern.compile("(?<=\\d)[\\s\\u00a0\\u202f]*(l/100 km|km/h|l/h|obr\\./min|°C|godz\\.|dzień|dni|min|mies\\.|lata|lat|rok|J\\.|yr|rpm|PLN|km|zł|hPa|[%dghmstlV])(?![\\p{L}/])");
    private static final String KIA_PANEL="pl.radiokia.preview", RADIO_KIA="pl.radiokia.fm";
    private static final String WEATHER="com.dofun.dofunweather.main";
    private static final String SHOW_SERVICE="pl.marm.carobd.SHOW_SERVICE", SHOW_DTC="pl.marm.carobd.SHOW_DTC";
    private static final int LOCATION=1, BLUETOOTH=2, FOLDER=3, NOTIFICATIONS=4;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService healthWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService historyWorker=Executors.newSingleThreadExecutor();
    private final Set<String> deviceAddresses=new HashSet<>();
    private final Map<String,Button> adapterModes=new LinkedHashMap<>();
    private Settings settings;
    private Trips store;
    private Typeface instrumentsFont;
    private LinearLayout root,body;
    private View recordingDot;
    private ImageView storageIcon;
    private TextView pill, speedSource, autoStopCountdown, distance, tripUsedFuel, consumption, tank, rangeValue, activeTime, syncReminder, historyStatus;
    private TextView coolantTemp, oilTemp, altitude, barometer, battery, tripMean, odometer, engineHours, gpsStatus;
    private Button obdStatus, faultNotice;
    private ImageButton tirePressure,engineWarning;
    private TextView ambientTemperature;
    private ImageView iceWarning;
    private boolean tireWarning,icyRoad;
    private ValueAnimator obdFaultBackground;
    private static final double ICE_WARNING_C=4; // Kia Sorento UM pokazuje ostrzeżenie poniżej 4 °C.
    private Instruments.Dial speedDial,rpmDial;
    private Instruments.Fuel fuelChart;
    private String historyProfileId;
    private long historyRevision;
    private Button retryObd;
    private Button arm,profileChoice,autoStopSetting;
    private String nextProfileId,editProfileId;
    private Profiles.Profile nextProfile,editing;
    private EditText profileName,vehicleId;
    private Spinner fuelType;
    private TextView adapterInfo,proxyName,proxyInfo;
    private int page,offset;
    private final Deque<Integer> historyOffsets=new ArrayDeque<>();
    private boolean demo;
    private String folderLabel;
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private AlertDialog deviceDialog;
    private ArrayAdapter<BluetoothDevice> choices;
    private String mode;
    private TripGrouping.Entry selected;
    private final Map<String,JSONObject> markedTrips=new LinkedHashMap<>();
    private Future<?> routeLoad;
    private RouteView historyMap;
    private Dialog mapDialog;
    private float swipeX,swipeY;
    private boolean swipeTracking;
    private DashboardHealth health;
    private String healthFolder;
    private long healthDue;
    private boolean healthBusy;

    private final Runnable ticker=new Runnable() {
        @Override public void run() { refresh(); main.postDelayed(this,850); }
    };
    private final Runnable gearTicker=new Runnable() {
        @Override public void run() { refreshGear(Recorder.live); main.postDelayed(this,200); }
    };
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(UiLanguage.localized(base)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        instrumentsFont=getResources().getFont(R.font.oxanium_semibold);
        settings=new Settings(this); store=new Trips(this);
        if (state!=null) { demo=state.getBoolean("demo"); editProfileId=state.getString("edit_profile"); }
        show(state==null ? 0 : state.getInt("page"));
        if (page==2 && state!=null && state.containsKey("draft_name")) {
            profileName.setText(state.getString("draft_name")); vehicleId.setText(state.getString("draft_plate"));
            fuelType.setSelection(state.getInt("draft_fuel"));
        }
        Maintenance.schedule(this); handleShortcut(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page",page); state.putBoolean("demo",demo); state.putString("edit_profile",editProfileId);
        if (page==2 && profileName!=null) {
            state.putString("draft_name",profileName.getText().toString());
            state.putString("draft_plate",vehicleId.getText().toString()); state.putInt("draft_fuel",fuelType.getSelectedItemPosition());
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handleShortcut(intent); }
    private void handleShortcut(Intent intent) {
        if (intent==null) return;
        if (intent.getBooleanExtra(SHOW_SERVICE,false)) main.post(this::openService);
        if (intent.hasExtra(SHOW_DTC)) {
            String code=intent.getStringExtra(SHOW_DTC);
            intent.removeExtra(SHOW_DTC);
            main.post(() -> new DtcWindow(this).show(code));
        }
        if (TpmsMonitor.SHOW.equals(intent.getAction())) {
            intent.setAction(null);
            main.post(() -> new TirePressureWindow(this).show());
        }
    }
    private void openService() {
        Recorder.Live live=Recorder.live;
        new ServiceWindow(this,live.armed ? settings.profiles.find(live.profileId) : nextProfile).show();
    }
    @Override public void onStart() {
        super.onStart(); healthDue=0; main.post(ticker); main.post(gearTicker); startMonitorIfReady(); requestNotificationAccess();
    }
    private void startMonitorIfReady() {
        if (!demo && !Recorder.live.armed &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startRecorder();
    }
    private void requestNotificationAccess() {
        if (Build.VERSION.SDK_INT<33 || settings.enabled("notification_requested") ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) return;
        settings.prefs.edit().putBoolean("notification_requested",true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
    }
    @Override public void onStop() {
        main.removeCallbacks(ticker); main.removeCallbacks(gearTicker);
        showRecordingDot(false); pulse(storageIcon,false); pulse(gpsStatus,false); pulse(obdStatus,false);
        showObdFaultBackground(false);
        stopScan(); super.onStop();
    }
    @Override public void onDestroy() {
        if (mapDialog!=null) mapDialog.dismiss();
        healthWorker.shutdownNow(); historyWorker.shutdownNow(); store.close(); super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            swipeX=event.getX(); swipeY=event.getY();
            View focus=getCurrentFocus();
            swipeTracking=!touched(historyMap,event) && !(focus instanceof EditText && touched(focus,event));
        } else if (action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_CANCEL) swipeTracking=false;
        else if (swipeTracking && action==MotionEvent.ACTION_MOVE && Math.abs(event.getY()-swipeY)>dp(18) &&
                Math.abs(event.getY()-swipeY)>Math.abs(event.getX()-swipeX)) swipeTracking=false;
        else if (swipeTracking && action==MotionEvent.ACTION_UP) {
            swipeTracking=false;
            float dx=event.getX()-swipeX,dy=event.getY()-swipeY;
            int target=page+(dx<0 ? 1 : -1);
            if (target>=0 && target<=2 && Math.abs(dx)>=dp(82) && Math.abs(dx)>=Math.abs(dy)*1.5f) {
                MotionEvent cancel=MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel); cancel.recycle(); show(target); return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }
    private static boolean touched(View view,MotionEvent event) {
        if (view==null || !view.isShown()) return false;
        int[] origin=new int[2]; view.getLocationOnScreen(origin);
        float x=event.getRawX()-origin[0],y=event.getRawY()-origin[1];
        return x>=0 && y>=0 && x<view.getWidth() && y<view.getHeight();
    }

    private int dp(float size) { return (int)(size*getResources().getDisplayMetrics().density+0.5f); }
    GradientDrawable box(int bg,int radius) {
        GradientDrawable result=new GradientDrawable(); result.setColor(bg); result.setCornerRadius(dp(radius));
        if (bg==PANEL) result.setStroke(dp(1),EDGE);
        return result;
    }
    private LinearLayout group(boolean vertical) {
        LinearLayout r=new LinearLayout(this); r.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); return r;
    }
    private void padding(View v,int horizontal,int vertical) { v.setPadding(dp(horizontal),dp(vertical),dp(horizontal),dp(vertical)); }
    private TextView txt(String text,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(withUnits(text)); v.setTextSize(size); v.setTextColor(color);
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
    Button button(String title,boolean primary,Runnable action) {
        Button view=new Button(this); view.setText(title); view.setTextSize(14); view.setAllCaps(false);
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setTextColor(primary ? DARK : WHITE); view.setBackground(box(primary ? MINT : EDGE,12)); padding(view,13,6);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private ImageButton icon(int drawable,String title,Runnable action) {
        ImageButton view=new ImageButton(this);
        view.setImageResource(drawable); view.setColorFilter(WHITE); view.setBackground(box(EDGE,10));
        view.setScaleType(ImageView.ScaleType.CENTER); view.setContentDescription(title); view.setTooltipText(title);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private LinearLayout panel(boolean vertical) {
        LinearLayout view=group(vertical); view.setBackground(box(PANEL,16)); padding(view,18,15); return view;
    }
    private void show(int target) {
        if (routeLoad!=null) routeLoad.cancel(true);
        showRecordingDot(false); recordingDot=null;
        pulse(storageIcon,false); storageIcon=null; syncReminder=null;
        pulse(gpsStatus,false); pulse(obdStatus,false);
        showObdFaultBackground(false);
        page=target; speedDial=null; arm=null; profileChoice=null; historyStatus=null; proxyName=null; proxyInfo=null; historyMap=null;
        nextProfile=settings.profiles.find(nextProfileId);
        root=group(true); root.setBackgroundColor(BG); padding(root,20,13);
        setContentView(root);
        LinearLayout row=group(false); row.setGravity(Gravity.CENTER_VERTICAL); root.addView(row,-1,dp(62));
        TextView brand=txt("CarOBD",28,WHITE,true); row.addView(brand);
        gap(row,12);
        row.addView(button(tr("Wróć do panelu Kia"),false,() -> openExternalApp(KIA_PANEL,"RadioKIA Pulpit")),-2,dp(48));
        gap(row,8);
        row.addView(button("RadioKIA FM",false,() -> openExternalApp(RADIO_KIA,"RadioKIA FM")),-2,dp(48));
        gap(row,12);
        row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        pill=txt("",13,MUTE,true); row.addView(pill);
        gap(row,12);
        gpsStatus=statusView("GPS",R.drawable.status_gps); row.addView(gpsStatus);
        gap(row,10);
        obdStatus=button("OBD",false,() -> new DtcWindow(this).show());
        obdStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.status_obd,0,0,0);
        obdStatus.setCompoundDrawablePadding(dp(5));
        obdStatus.setTooltipText(tr("Diagnostyka EOBD"));
        row.addView(obdStatus,-2,dp(48));
        if (page==0 || page==1 && !demo) {
            gap(row,12);
            boolean history=page==1;
            profileChoice=button(history ? settings.profiles.find(historyProfileId).name : "",false,
                history ? this::chooseHistoryProfile : this::chooseDrivingProfile);
            if (history) profileChoice.setContentDescription(tr("Profil historii: ")+profileChoice.getText());
            profileChoice.setSingleLine(true); profileChoice.setMaxWidth(dp(156));
            profileChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(profileChoice,-2,dp(48));
        }
        gap(row,12);
        for (int i=0;i<3;i++) {
            final int tab=i; Button b=button(new String[]{tr("Pulpit"),tr("Historia"),tr("Konfiguracja")}[i],page==i,() -> show(tab));
            row.addView(b,-2,dp(48)); if (i<2) gap(row,8);
        }
        faultNotice=button("",false,() -> {
            Dtc.Report report=Recorder.live.dtcs;
            new DtcWindow(this).show(report==null ? "" : report.firstCode());
        });
        faultNotice.setTextColor(0xffffbe82);
        faultNotice.setVisibility(View.GONE); root.addView(faultNotice,-1,-2);
        gap(root,12);
        body=group(true); weight(root,body,1);
        if (target==0) dashboard(); if (target==1) history(); if (target==2) config();
        refresh();
    }
    private void openExternalApp(String packageName,String label) {
        Intent intent=getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent==null) { message(tr("Zainstaluj aplikację ")+label); return; }
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException error) { message(tr("Nie można otworzyć ")+label); }
    }
    private void dashboard() {
        LinearLayout columns=group(false);
        columns.setMinimumHeight(dp(480)); scrolling(body,columns);
        LinearLayout instruments=panel(true); weight(columns,instruments,1.62f); gap(columns,12);
        LinearLayout readings=group(false); instruments.addView(readings,-1,dp(47));
        coolantTemp=instrumentReadout(readings,InstrumentHistory.Metric.COOLANT,R.drawable.metric_coolant);
        gap(readings,8);
        oilTemp=instrumentReadout(readings,InstrumentHistory.Metric.OIL,R.drawable.metric_oil);
        gap(readings,8);
        battery=instrumentReadout(readings,InstrumentHistory.Metric.VOLTAGE,R.drawable.metric_battery);
        gap(readings,8);
        altitude=readout(readings,tr("Wysokość GPS"),R.drawable.metric_altitude,22);
        gap(readings,8);
        barometer=readout(readings,tr("Ciśnienie atmosferyczne z ECU"),R.drawable.metric_barometer,20);
        barometer.setSingleLine(true);
        barometer.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        barometer.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        gap(instruments,3);
        FrameLayout clockArea=new FrameLayout(this); weight(instruments,clockArea,1);
        LinearLayout clocks=group(false);
        clockArea.addView(clocks,new FrameLayout.LayoutParams(-1,-1));
        speedDial=new Instruments.Dial(this,instrumentsFont,tr("Prędkość"),"km/h",Instruments.SPEED_LIMIT,20,Instruments.SPEED_COLOR,.49f);
        rpmDial=new Instruments.Dial(this,instrumentsFont,tr("Obroty"),tr("obr./min"),Instruments.RPM_LIMIT,1000,Instruments.RPM_COLOR,.445f);
        weight(clocks,rpmDial,.92f);
        gap(clocks,10);
        FrameLayout speedArea=new FrameLayout(this);
        speedArea.addView(speedDial,new FrameLayout.LayoutParams(-1,-1));
        weight(clocks,speedArea,1.08f);
        engineWarning=icon(R.drawable.ic_check_engine,tr("Usterka silnika · OBD"),() -> new DtcWindow(this).show());
        engineWarning.setColorFilter(0xffffd058);
        engineWarning.setBackground(dialIndicator(0xff372b18,0xffffd058));
        engineWarning.setVisibility(View.INVISIBLE);
        speedArea.addView(engineWarning,dialPosition(Gravity.START,44));
        tirePressure=icon(R.drawable.ic_tire,tr("Pokaż ciśnienie opon"),() -> new TirePressureWindow(this).show());
        updateTireButton(false);
        speedArea.addView(tirePressure,dialPosition(Gravity.END,62));

        ambientTemperature=txt("— °C",15,WHITE,true);
        ambientTemperature.setGravity(Gravity.CENTER);
        ambientTemperature.setTooltipText(tr("Temperatura zewnętrzna"));
        ambientTemperature.setTypeface(instrumentsFont);
        padding(ambientTemperature,5,0);
        LinearLayout temperatureGroup=group(false);
        temperatureGroup.setGravity(Gravity.CENTER);
        temperatureGroup.setMinimumWidth(dp(132));
        temperatureGroup.setContentDescription(tr("Otwórz pogodę"));
        temperatureGroup.setTooltipText(tr("Pogoda"));
        temperatureGroup.setFocusable(true);
        View.OnClickListener openWeather=view -> openExternalApp(WEATHER,tr("Pogoda"));
        temperatureGroup.setOnClickListener(openWeather);
        ambientTemperature.setOnClickListener(openWeather);
        iceWarning=new ImageView(this);
        iceWarning.setOnClickListener(openWeather);
        iceWarning.setImageResource(R.drawable.ic_ice);
        iceWarning.setVisibility(View.INVISIBLE);
        icyRoad=false;
        temperatureGroup.addView(iceWarning,dp(18),dp(18));
        temperatureGroup.addView(ambientTemperature,-2,-1);
        temperatureGroup.addView(new View(this),dp(18),dp(18));
        FrameLayout.LayoutParams ambientPosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        ambientPosition.topMargin=dp(20);
        clockArea.addView(temperatureGroup,ambientPosition);
        View rpmArea=rpmDial;
        clockArea.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob) -> {
            float rpmCenter=rpmArea.getLeft()+rpmArea.getWidth()/2f;
            float speedCenter=speedArea.getLeft()+speedArea.getWidth()/2f;
            temperatureGroup.setTranslationX((rpmCenter+speedCenter-clockArea.getWidth())/2f);
        });
        recordingDot=new View(this); recordingDot.setBackground(box(RECORDING,6));
        recordingDot.setContentDescription(tr("Trwa zapis przejazdu"));
        recordingDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        recordingDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dotPosition=new FrameLayout.LayoutParams(dp(12),dp(12),Gravity.TOP|Gravity.END);
        dotPosition.topMargin=dp(10); dotPosition.setMarginEnd(dp(9));
        clockArea.addView(recordingDot,dotPosition);
        storageIcon=new ImageView(this); storageIcon.setPadding(dp(4),dp(4),dp(4),dp(4));
        storageIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams storagePosition=new FrameLayout.LayoutParams(dp(27),dp(27),Gravity.TOP|Gravity.END);
        storagePosition.topMargin=dp(3); storagePosition.setMarginEnd(dp(28));
        clockArea.addView(storageIcon,storagePosition);
        // Liczniki mają własną przestrzeń poza płótnem, więc tarcze nie zasłaniają cyfr.
        gap(instruments,3);
        odometer=centeredCounter(instruments,"—",26,WHITE,40);
        odometer.setTypeface(instrumentsFont); odometer.setIncludeFontPadding(false);
        engineHours=centeredCounter(instruments,"",16,WHITE,27);
        LinearLayout recorderStatus=group(false); recorderStatus.setGravity(Gravity.CENTER_VERTICAL); instruments.addView(recorderStatus);
        speedSource=txt("",14,MUTE,false); width(recorderStatus,speedSource,1);
        autoStopCountdown=txt("",14,MUTE,false); autoStopCountdown.setGravity(Gravity.END);
        autoStopCountdown.setPadding(dp(8),0,0,0); recorderStatus.addView(autoStopCountdown,-2,-2);
        gap(instruments,3);
        arm=button(tr("Włącz automat"),false,this::armRecording); instruments.addView(arm,-1,-2);
        retryObd=button(tr("Ponów OBD"),false,() -> {
            if (Recorder.live.armed && !Recorder.live.obd) startService(new Intent(this,Recorder.class).setAction(Recorder.RETRY));
        }); instruments.addView(retryObd,-1,-2);
        LinearLayout stats=panel(true); weight(columns,stats,1);
        LinearLayout fuelReadings=group(false); stats.addView(fuelReadings,-1,dp(64));
        consumption=readout(fuelReadings,tr("Spalanie chwilowe"),R.drawable.metric_flow,22); gap(fuelReadings,8);
        tripMean=readout(fuelReadings,tr("Średnie spalanie przejazdu"),R.drawable.metric_average,22);
        LinearLayout fuelStatus=group(false); stats.addView(fuelStatus,-1,dp(38));
        tank=readout(fuelStatus,tr("Poziom paliwa"),R.drawable.metric_fuel,15);
        tank.setSingleLine(true);
        tank.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        gap(fuelStatus,8);
        rangeValue=readout(fuelStatus,tr("Zasięg wg samochodu"),R.drawable.metric_range,15);
        fuelChart=new Instruments.Fuel(this); weight(stats,fuelChart,1);
        gap(stats,10);
        LinearLayout tripLine=group(false); tripLine.setGravity(Gravity.CENTER_VERTICAL); stats.addView(tripLine);
        distance=tripMetric(tripLine,21,WHITE,Gravity.START,1);
        tripUsedFuel=tripMetric(tripLine,16,MUTE,Gravity.CENTER,.7f);
        tripUsedFuel.setTag(tr("Zużyte paliwo"));
        activeTime=tripMetric(tripLine,21,WHITE,Gravity.END,1);
        gap(stats,6);
        LinearLayout statusLine=group(false); statusLine.setGravity(Gravity.CENTER_VERTICAL); stats.addView(statusLine,-1,dp(37));
        syncReminder=txt("",13,WARNING,true); width(statusLine,syncReminder,1);
        gap(stats,7);
        LinearLayout actions=group(false); stats.addView(actions,-1,dp(48));
        Button service=button(tr("Motogodziny i serwis"),false,this::openService);
        padding(service,7,4); service.setMaxLines(2);
        service.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        weight(actions,new View(this),1); gap(actions,8); weight(actions,service,1);
    }
    private FrameLayout.LayoutParams dialPosition(int side,int size) {
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(size),dp(size),Gravity.BOTTOM|side);
        position.bottomMargin=dp(4);
        return position;
    }
    private GradientDrawable dialIndicator(int fill,int stroke) {
        GradientDrawable surface=box(fill,11);
        surface.setStroke(dp(1),stroke);
        return surface;
    }
    private void updateTireButton(boolean warning) {
        tireWarning=warning;
        String label=warning ? tr("Niskie ciśnienie · pokaż koło") : tr("Pokaż ciśnienie opon");
        tirePressure.setContentDescription(label); tirePressure.setTooltipText(label);
        tirePressure.setImageResource(warning ? R.drawable.ic_tpms : R.drawable.ic_tire);
        int color=warning ? 0xffffa668 : 0xff8ef9e8;
        tirePressure.setColorFilter(color);
        tirePressure.setBackground(new android.graphics.drawable.InsetDrawable(dialIndicator(warning ? 0xff483127 : 0xff103448,color),dp(9)));
    }
    private void updateOutsideTemperature(double value,boolean fromCan) {
        boolean risk=Double.isFinite(value) && value<ICE_WARNING_C;
        if (risk!=icyRoad) {
            icyRoad=risk;
            iceWarning.setVisibility(risk ? View.VISIBLE : View.INVISIBLE);
        }
        String temperature=unit(value,0,"°C");
        changedUnits(ambientTemperature,temperature);
        String tooltip=tr("Temperatura zewnętrzna")+(fromCan ? " · CAN" : "")+(risk ? tr(" · możliwe oblodzenie") : "");
        String description=tooltip+" · "+temperature;
        if (!description.contentEquals(String.valueOf(ambientTemperature.getContentDescription()))) {
            ambientTemperature.setTooltipText(tooltip);
            ambientTemperature.setContentDescription(description);
        }
    }
    private TextView centeredCounter(LinearLayout column,String label,int size,int color,int height) {
        TextView view=txt(label,size,color,size>13);
        view.setGravity(Gravity.CENTER); view.setSingleLine(true);
        view.setAutoSizeTextTypeUniformWithConfiguration(10,size,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        column.addView(view,-1,dp(height));
        return view;
    }
    private TextView tripMetric(LinearLayout row,int size,int color,int gravity,float weight) {
        TextView metric=txt("",size,color,true);
        metric.setTypeface(instrumentsFont); metric.setGravity(gravity); metric.setSingleLine(true);
        metric.setAutoSizeTextTypeUniformWithConfiguration(10,size,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        width(row,metric,weight);
        return metric;
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
    private TextView instrumentReadout(LinearLayout row,InstrumentHistory.Metric metric,int icon) {
        TextView value=readout(row,tr(metric.title),icon,22);
        View cell=(View)value.getParent();
        cell.setFocusable(true);
        cell.setTag(metric);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.setTooltipText(tr(metric.title)+tr(" · wykres z 7 dni"));
        cell.setOnClickListener(v -> openInstrumentHistory(metric));
        return value;
    }
    private void openInstrumentHistory(InstrumentHistory.Metric metric) {
        Recorder.Live live=Recorder.live;
        String vehicle=live.armed ? live.vehicle : nextProfile.vehicle;
        long now=System.currentTimeMillis();
        LinearLayout content=group(true); padding(content,18,4);
        TextView caption=txt(tr("Wczytuję odczyty…"),15,WHITE,false); content.addView(caption);
        InstrumentHistory.Chart chart=new InstrumentHistory.Chart(this,metric,now);
        chart.setVisibility(View.GONE); content.addView(chart,-1,dp(225));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr(metric.title)+tr(" · 7 dni")).setView(content)
            .setPositiveButton(tr("Zamknij"),null).show();
        dialog.getWindow().setLayout(Math.min(dp(730),getResources().getDisplayMetrics().widthPixels-dp(24)),-2);
        boolean preview=demo;
        historyWorker.submit(() -> {
            try {
                List<InstrumentHistory.Sample> points=preview ? Demo.instrumentSamples(metric,now) : store.instrumentHistory(vehicle,metric,now);
                main.post(() -> {
                    if (isDestroyed() || !dialog.isShowing()) return;
                    if (points.isEmpty()) { caption.setText(tr("Brak zapisanych odczytów z ostatnich 7 dni")); return; }
                    boolean archived=points.stream().anyMatch(p -> p.archived);
                    InstrumentHistory.Sample last=points.get(points.size()-1);
                    String lastLabel=last.archived ? tr("Ostatni punkt NAS") : tr("Ostatni odczyt");
                    String when=new SimpleDateFormat("dd.MM  HH:mm",UiLanguage.locale()).format(new Date(last.at));
                    caption.setText(withUnits(lastLabel+"  "+metric.label(last.value)+"   ·   "+when+
                        (archived && metric.legend!=null ? "\n"+tr(metric.legend) : "")));
                    chart.show(points); chart.setVisibility(View.VISIBLE);
                });
            } catch (RuntimeException error) {
                main.post(() -> { if (!isDestroyed() && dialog.isShowing()) caption.setText(tr("Nie udało się odczytać historii")); });
            }
        });
    }
    private void reading(TextView view,String value) {
        changedUnits(view,value);
        String description=view.getTag()+" "+value;
        View target=view;
        if (view.getParent() instanceof View && ((View)view.getParent()).getTag() instanceof InstrumentHistory.Metric) {
            target=(View)view.getParent(); description+=tr(" · wykres z 7 dni");
        }
        if (!description.contentEquals(target.getContentDescription()==null ? "" : target.getContentDescription()))
            target.setContentDescription(description);
    }
    private void showTank(double percent,double liters,double capacity) {
        TripMath.FuelLevel volume=TripMath.fuelLevel(true,percent,liters,capacity);
        TripMath.FuelLevel share=TripMath.fuelLevel(false,percent,liters,capacity);
        String volumeText=fuelReading(volume,1," l"), percentText=fuelReading(share,0,"%");
        String value=volumeText;
        if (volumeText.isEmpty()) value=percentText.isEmpty() ? "—" : percentText;
        else if (!percentText.isEmpty()) value=volumeText+" ("+percentText+")";
        tank.setTag(volume.estimated || share.estimated ? tr("Poziom paliwa · odczyt szacunkowy") : tr("Poziom paliwa"));
        reading(tank,value);
    }
    private static String fuelReading(TripMath.FuelLevel level,int decimals,String unit) {
        return Double.isFinite(level.value) ? (level.estimated ? "~" : "")+fmt(level.value,decimals)+unit : "";
    }
    private static void changed(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    static CharSequence withUnits(String value) {
        Matcher unit=UNITS.matcher(value);
        if (!unit.find()) return value;
        SpannableString sized=new SpannableString(value);
        do { sized.setSpan(new RelativeSizeSpan(.70f),unit.start(1),unit.end(1),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
        while (unit.find());
        return sized;
    }
    private static void changedUnits(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(withUnits(value));
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
        String location=folder.isEmpty() ? tr("Pamięć radia") : tr(medium)+tr(" · kopie GPX");
        String warning=lowRadio && lowGpx ? tr("Mało miejsca w radiu i na nośniku GPX") :
            lowRadio ? tr("Mało miejsca w pamięci radia") : lowGpx ? tr("Mało miejsca na nośniku GPX") :
            gpxError ? tr("Sprawdź nośnik GPX") : "";
        String description=location+(warning.isEmpty() ? "" : " · "+warning);
        if (!description.equals(storageIcon.getContentDescription())) {
            storageIcon.setContentDescription(description); storageIcon.setTooltipText(description);
            storageIcon.setColorFilter(lowRadio || lowGpx ? WARNING : gpxError ? OFFLINE : MINT);
        }
        pulse(storageIcon,lowRadio || lowGpx);
        changed(syncReminder,!demo && health!=null && settings.nasUploadEnabled() ? health.reminder : "");
    }
    private TextView statusView(String name,int icon) {
        TextView view=txt(name,13,MUTE,true);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon,0,0,0); view.setCompoundDrawablePadding(dp(4));
        return view;
    }
    private void connectionStatus(TextView view,int color,boolean trying,String description) {
        if (view.getCurrentTextColor()!=color) {
            view.setTextColor(color); view.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        }
        if (!description.equals(view.getContentDescription())) view.setContentDescription(description);
        pulse(view,trying);
    }
    private void showObdFaultBackground(boolean active) {
        if (obdStatus==null || active==(obdFaultBackground!=null)) return;
        if (!active) {
            obdFaultBackground.cancel(); obdFaultBackground=null;
            obdStatus.setBackground(box(EDGE,12));
            return;
        }
        GradientDrawable background=box(0xffa72b35,12);
        obdStatus.setBackground(background);
        obdFaultBackground=ValueAnimator.ofArgb(0xffa72b35,0xff741b2b);
        obdFaultBackground.setDuration(800);
        obdFaultBackground.setRepeatMode(ValueAnimator.REVERSE);
        obdFaultBackground.setRepeatCount(ValueAnimator.INFINITE);
        obdFaultBackground.addUpdateListener(animator -> background.setColor((int)animator.getAnimatedValue()));
        obdFaultBackground.start();
    }
    private void refreshGear(Recorder.Live live) {
        if (speedDial==null) return;
        boolean kia=EngineHours.kiaProfile(live.armed ? live.vehicle : nextProfile.vehicle);
        speedDial.gear(demo ? "D4" : kia ? (live.gear.isEmpty() ? "—" : live.gear) : "");
    }
    private void refresh() {
        Recorder.Live live=Recorder.live;
        if (page==1 && mapDialog==null && !demo && NasHistory.revision!=historyRevision) { show(1); return; }
        if (historyStatus!=null) changed(historyStatus,tr(NasHistory.status));
        if (proxyInfo!=null) {
            boolean sharing=settings.proxyEnabled();
            String dns=live.armed && sharing ? live.proxyName : "";
            changed(proxyName,dns); proxyName.setVisibility(dns.isEmpty() ? View.GONE : View.VISIBLE);
            changed(proxyInfo,sharing && live.armed && !live.proxyStatus.isEmpty() ? UiLanguage.proxyStatus(live.proxyStatus) :
                sharing ? tr("Uruchom automat, aby udostępnić odczyt OBD") : tr("Proxy Wi-Fi wyłączone"));
        }
        List<String> faults=live.faults;
        boolean engineAlert=!demo && live.obd && live.dtcs!=null && live.dtcs.engineWarning();
        boolean showFault=!demo && live.obd && !faults.isEmpty() && (page!=0 || !engineAlert);
        faultNotice.setVisibility(showFault ? View.VISIBLE : View.GONE);
        if (showFault) {
            String code=live.dtcs==null ? "" : live.dtcs.firstCode();
            changed(faultNotice,"OBD · "+(code.isEmpty() ? faults.get(0) : code)+(faults.size()>1 ? "  +"+(faults.size()-1) : ""));
        }
        String banner=demo ? tr("DANE DEMONSTRACYJNE") : live.journalError ? tr("Błąd zapisu trasy") : "";
        changed(pill,banner); pill.setVisibility(banner.isEmpty() ? View.GONE : View.VISIBLE);
        pill.setTextColor(!demo && live.journalError ? 0xffffbe82 : MUTE);
        boolean reception=live.armed && live.gpsProvider && live.gpsReception;
        boolean seekingGps=live.armed && live.gpsProvider && !live.gps && !reception;
        connectionStatus(gpsStatus,live.gps ? MINT : reception || seekingGps ? LINK_YELLOW : OFFLINE,seekingGps,
            live.gps ? tr("GPS · pozycja ustalona") : reception ? tr("GPS · wykryto satelity, oczekiwanie na pozycję") :
                seekingGps ? tr("GPS · wyszukiwanie sygnału") : tr("GPS · nieaktywny"));
        boolean seekingObd=live.obdLink==Recorder.LINK_SEARCH;
        showObdFaultBackground(engineAlert);
        connectionStatus(obdStatus,engineAlert ? WHITE : live.obd ? MINT :
            seekingObd || live.obdLink==Recorder.LINK_ADAPTER ? LINK_YELLOW : OFFLINE,seekingObd,
            engineAlert ? tr("OBD · usterka silnika") : live.obd ? tr("OBD · samochód odpowiada") : "OBD · "+UiLanguage.adapterStatus(live.status));
        if (speedDial==null) return;
        boolean lowPressure=!demo && TpmsMonitor.snapshot().lowMask!=0;
        if (tireWarning!=lowPressure) updateTireButton(lowPressure);
        engineWarning.setVisibility(engineAlert ? View.VISIBLE : View.INVISIBLE);
        updateOutsideTemperature(demo ? 2 : live.ambientC,!demo && live.ambientCan);
        String profileName=live.armed ? live.profileName : nextProfile.name;
        changed(profileChoice,profileName);
        String profileDescription=tr("Profil jazdy: ")+profileName;
        if (!profileDescription.equals(profileChoice.getContentDescription())) profileChoice.setContentDescription(profileDescription);
        profileChoice.setEnabled(live.started==0 && !demo);
        double speed=demo ? 74 : live.speed;
        speedDial.value(speed);
        rpmDial.value(demo ? 1850 : live.rpm);
        reading(coolantTemp,fmt(demo ? 90 : live.coolant,0)+" °C");
        reading(oilTemp,fmt(demo ? 105 : live.oil,0)+" °C");
        reading(altitude,fmt(demo ? 247 : live.altitude,0)+" m");
        double measuredPressure=live.barometricKpa*10;
        double estimate=live.gps ? TripMath.estimatedPressureHpa(live.altitude) : Double.NaN;
        boolean estimated=!demo && !Double.isFinite(measuredPressure) && Double.isFinite(estimate);
        barometer.setTag(estimated ? tr("Szacowane ciśnienie z wysokości GPS") : tr("Ciśnienie atmosferyczne z ECU"));
        ((View)barometer.getParent()).setTooltipText(barometer.getTag().toString());
        reading(barometer,(estimated ? "~" : "")+unit(demo ? 990 : estimated ? estimate : measuredPressure,0,"hPa"));
        Elm.Voltage volts=live.voltage;
        battery.setTag(volts!=null && volts.adapter && !demo ? tr("Napięcie zmierzone przez adapter ELM") : tr("Napięcie akumulatora"));
        reading(battery,demo ? "14,2 V" : volts==null ? "—" : (volts.adapter ? "~" : "")+unit(volts.volts,1,"V"));
        long now=SystemClock.elapsedRealtime();
        String vehicle=live.armed ? live.vehicle : nextProfile.vehicle;
        boolean kia=EngineHours.kiaProfile(vehicle);
        rpmDial.kiaScale(demo || kia);
        refreshGear(live);
        double reading=demo ? 197452 : live.obd ? live.odometer : Double.NaN;
        changedUnits(odometer,Double.isFinite(reading) ? String.format(UiLanguage.locale(),"%,.2f km",reading) : "—");
        boolean radioHours=live.obd && live.hours!=null && live.hours.fresh(now);
        long engineSeconds=demo ? Math.round(6984.9*3600) : radioHours ? live.hours.total : -1;
        long idleSeconds=demo ? Math.round(1644.4*3600) : radioHours ? live.hours.idle : -1;
        changedUnits(engineHours,UiLanguage.duration(engineSeconds)+tr("  ·  Postój ")+UiLanguage.duration(idleSeconds));
        long remaining=live.retryAt==0 ? 0 : Math.max(0,(live.retryAt-now+999)/1000);
        String obdMessage=UiLanguage.adapterStatus(live.status)+(remaining>0 ? tr(" · ponowienie za ")+remaining+" s" : "");
        boolean recording=!demo && live.started>0;
        showRecordingDot(recording);
        String countdown=recording && live.autoStopAt>now ? tr("Auto-stop za ")+elapsed((live.autoStopAt-now+999)/1000) : "";
        String notice=demo || !live.armed || live.obd ? "" : obdMessage;
        changedUnits(speedSource,notice);
        changedUnits(autoStopCountdown,countdown); autoStopCountdown.setVisibility(countdown.isEmpty() ? View.GONE : View.VISIBLE);
        ((View)speedSource.getParent()).setVisibility(notice.isEmpty() && countdown.isEmpty() ? View.GONE : View.VISIBLE);
        retryObd.setVisibility(!demo && live.armed && !live.obd ? View.VISIBLE : View.GONE);
        changedUnits(distance,unit(demo ? 8.42 : live.km,1,"km"));
        reading(tripUsedFuel,demo ? "0,56 l" : unit(live.tripLiters,2,"l"));
        long tripSeconds=demo ? 9*60+32 : live.started>0 ? (now-live.started)/1000 : -1;
        changedUnits(activeTime,elapsed(tripSeconds));
        boolean byDistance=TripMath.fuelByDistance(speed);
        double instant=demo ? 5.8 : byDistance ? TripMath.fuelPer100Km(live.rate,speed) : live.rate;
        reading(consumption,(live.fuelSource==2 && !demo && Double.isFinite(instant) ? "~" : "")+
            fmt(instant,1)+(byDistance ? " l/100 km" : " l/h"));
        reading(tripMean,(demo ? "6,7" : fmt(live.mean,1))+" l/100 km");
        showTank(demo ? 58 : live.fuel,
            demo ? 0.58*Elm.KIA_TANK_L : live.tankLiters,demo || kia ? Elm.KIA_TANK_L : Double.NaN);
        JSONObject range=kia ? null : NasHistory.telemetry(settings,vehicle).optJSONObject("range");
        long age=range==null ? Long.MAX_VALUE : System.currentTimeMillis()/1000-range.optLong("t",0);
        double nasRange=age>=0 && age<=6*3600 ? range.optDouble("range_km") : Double.NaN;
        reading(rangeValue,demo ? "520 km" : Double.isFinite(nasRange) ? fmt(nasRange,0)+" km" : "—");
        fuelChart.sample(live.rate,live.rpm,live.speed,byDistance,now,demo);
        changed(arm,demo ? tr("Zakończ podgląd") : tr("Włącz lokalizację"));
        arm.setVisibility(demo || !live.armed ? View.VISIBLE : View.GONE);
        refreshHealth(now);
    }
    private void armRecording() {
        if (demo) { demo=false; selected=null; markedTrips.clear(); refresh(); return; }
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
            choices[i]=item.name+(item.id.equals(preferred) ? tr(" · domyślny") : "");
        }
        new AlertDialog.Builder(this).setTitle(tr("Profil dla tej rejestracji")).setItems(choices,(d,i) -> {
            nextProfile=profiles.get(i);
            nextProfileId=nextProfile.id.equals(preferred) ? null : nextProfile.id;
            if (Recorder.live.armed) startService(new Intent(this,Recorder.class).putExtra(Recorder.PROFILE,nextProfile.id));
            refresh();
        }).setNegativeButton(tr("Anuluj"),null).show();
    }

    private ScrollView scrolling(LinearLayout parent,LinearLayout content) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        weight(parent,scroll,1); return scroll;
    }
    private String tripDistance(JSONObject trip) {
        double km=trip.optDouble("km"), gps=trip.optDouble("gps_km");
        if (!(km>0) && gps>0) return fmt(gps,2)+" km GPS";
        return unit(km,2,"km");
    }
    private String tripFuel(JSONObject trip) {
        return unit(trip.optDouble("fuel_l"),2,"l");
    }
    private String fuelCostMode() { return settings.netFuelCost() ? tr("netto") : tr("brutto"); }
    private double tripCost(JSONObject trip) {
        return trip.optDouble(settings.netFuelCost() ? "cost_net_pln" : "cost_pln");
    }
    private static String tripCoverage(int available,int total) {
        return available==total ? "" : " ("+available+"/"+total+tr(" tras)");
    }
    private static String unit(double value,int decimals,String label) {
        return Double.isFinite(value) ? fmt(value,decimals)+" "+label : "—";
    }
    private LinearLayout tripCard(String title,String metrics,boolean selected) {
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
        card.setFocusable(true); card.setSelected(selected);
        return card;
    }
    private void markCard(LinearLayout card,boolean active) {
        card.setSelected(active); card.setBackground(box(active ? MINT : EDGE,12));
        for (int i=0;i<card.getChildCount();i++) ((TextView)card.getChildAt(i)).setTextColor(active ? DARK : WHITE);
    }
    private void history() {
        LinearLayout columns=group(false); weight(body,columns,1);
        LinearLayout list=panel(true); weight(columns,list,0.48f); gap(columns,12);
        LinearLayout view=panel(true); weight(columns,view,1.52f);
        LinearLayout listTitle=group(false); listTitle.setGravity(Gravity.CENTER_VERTICAL);
        Profiles.Profile car=settings.profiles.find(historyProfileId);
        list.addView(listTitle); gap(list,9);
        historyRevision=NasHistory.revision;
        boolean join=settings.joinShortStops();
        Switch shortStops=new Switch(this);
        shortStops.setText(tr("Łącz do ")+TripGrouping.MAX_GAP_MINUTES+" min");
        shortStops.setSingleLine(true); shortStops.setTextColor(WHITE); shortStops.setChecked(join);
        shortStops.setSwitchPadding(dp(4));
        shortStops.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        shortStops.setOnCheckedChangeListener((v,on) -> {
            settings.setJoinShortStops(on);
            offset=0; historyOffsets.clear(); markedTrips.clear(); show(1);
        });
        listTitle.addView(shortStops,new LinearLayout.LayoutParams(0,dp(44),1));
        if (!demo)
            listTitle.addView(icon(R.drawable.action_nas_download,tr("Pobierz z NAS"),() -> syncHistory(car.vehicle)),dp(44),dp(44));
        LinearLayout entries=group(true); scrolling(list,entries);
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        TripGrouping.Page data=demo ? new TripGrouping.Page(Demo.rows(),false) : store.history(car.vehicle,offset,join,zone);
        List<TripGrouping.Entry> items=TripGrouping.entries(data.rows,join,zone);
        if (items.isEmpty()) entries.addView(txt(tr("Brak zapisanych tras"),15,MUTE,false));
        String selectedId=selected==null ? "" : selected.id();
        selected=items.isEmpty() ? null : items.get(0);
        for (TripGrouping.Entry item:items) if (item.contains(selectedId)) { selected=item; break; }
        List<LinearLayout> cards=new ArrayList<>();
        List<CheckBox> boxes=new ArrayList<>();
        Button clear=button(tr("Wyczyść"),false,() -> {});
        clear.setOnClickListener(v -> {
            markedTrips.clear(); for (CheckBox box:boxes) box.setChecked(false);
            historyDetails(view); v.setVisibility(View.GONE);
        });
        clear.setTextSize(11); padding(clear,7,2); listTitle.addView(clear,1,new LinearLayout.LayoutParams(-2,dp(38)));
        clear.setVisibility(markedTrips.isEmpty() ? View.GONE : View.VISIBLE);
        java.time.LocalDate lastDay=null;
        for (TripGrouping.Entry entry:items) {
            if (!entry.day.equals(lastDay)) {
                lastDay=entry.day;
                TextView day=txt(dayLabel(entry.start()),13,MUTE,true);
                if (Build.VERSION.SDK_INT>=28) day.setAccessibilityHeading(true);
                entries.addView(day); gap(entries,4);
            }
            JSONObject first=entry.routes.get(0);
            String stamp=tripTimeRange(entry), details;
            double cost;
            if (entry.routes.size()>1) {
                TripMath.Group totals=tripTotals(entry.routes);
                details=groupDistance(totals)+" · "+unit(totals.recordedLiters(),2,"l");
                cost=totals.estimatedCost();
            } else {
                details=tripDistance(first)+" · "+tripFuel(first);
                cost=tripCost(first);
            }
            if (Double.isFinite(cost)) details+=" · "+fmt(cost,2)+tr("\u00a0zł");
            LinearLayout line=group(false); line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout card=tripCard(stamp,details,entry==selected);
            cards.add(card); width(line,card,1);
            CheckBox check=new CheckBox(this); boxes.add(check);
            check.setContentDescription(tr("Dodaj do porównania · ")+dayLabel(entry.start())+" "+stamp);
            check.setButtonTintList(ColorStateList.valueOf(MINT)); check.setGravity(Gravity.CENTER);
            check.setChecked(markedTrips.containsKey(entry.id()));
            if (check.isChecked()) markTrips(entry,true);
            line.addView(check,dp(42),dp(48));
            check.setOnClickListener(v -> {
                markTrips(entry,check.isChecked());
                clear.setVisibility(markedTrips.isEmpty() ? View.GONE : View.VISIBLE);
                historyDetails(view);
            });
            card.setOnClickListener(v -> {
                if (!markedTrips.isEmpty()) { check.performClick(); return; }
                if (selected==entry) return;
                selected=entry;
                for (LinearLayout pick:cards) markCard(pick,pick==card);
                historyDetails(view);
            });
            entries.addView(line,-1,-2); gap(entries,7);
        }
        if (!demo && (!historyOffsets.isEmpty() || data.more)) {
            LinearLayout nav=group(false); list.addView(nav);
            if (!historyOffsets.isEmpty()) width(nav,button(tr("Nowsze"),false,() -> {
                offset=historyOffsets.pop(); selected=null; show(1);
            }),1);
            if (data.more) width(nav,button(tr("Starsze"),false,() -> {
                historyOffsets.push(offset); offset+=data.rows.size(); selected=null; show(1);
            }),1);
        }
        if (!demo) {
            historyStatus=txt(NasHistory.status,13,MUTE,false); list.addView(historyStatus);
        }
        historyDetails(view);
    }
    private static String dayLabel(long seconds) {
        return new SimpleDateFormat("dd.MM.yyyy",UiLanguage.locale()).format(new Date(seconds*1000));
    }
    private static String tripTimeRange(TripGrouping.Entry entry) {
        SimpleDateFormat time=new SimpleDateFormat("HH:mm",UiLanguage.locale());
        String first=time.format(new Date(entry.start()*1000));
        return entry.routes.size()==1 ? first : first+"–"+time.format(new Date(entry.end()*1000));
    }
    private static String groupDistance(TripMath.Group totals) {
        return totals.distances==0 ? "—" : unit(totals.distance,2,"km");
    }
    private TripMath.Group tripTotals(List<JSONObject> routes) {
        TripMath.Group totals=new TripMath.Group();
        for (JSONObject route:routes) {
            double km=route.optDouble("km"), gps=route.optDouble("gps_km");
            totals.add(km>0 || !(gps>0) ? km : gps,route.optDouble("dur_s"),route.optDouble("l100"),
                route.optDouble("fuel_l"),route.optDouble("fuel_cover"),tripCost(route));
        }
        return totals;
    }
    private void markTrips(TripGrouping.Entry entry,boolean checked) {
        for (JSONObject trip:entry.routes) {
            String id=trip.optString("id");
            if (checked) markedTrips.put(id,trip); else markedTrips.remove(id);
        }
    }
    private void syncHistory(String vehicle) {
        if (!settings.hasNasCredential()) { message(tr("Radio oczekuje na przypisanie do NAS.")); return; }
        NasHistory.request(settings,vehicle); NasHistory.refreshHistory(settings,vehicle);
        NasHistory.status=tr("NAS • oczekiwanie na połączenie"); Maintenance.schedule(this); refresh();
    }
    private void historyDetails(LinearLayout view) {
        if (routeLoad!=null) routeLoad.cancel(true);
        view.removeAllViews();
        boolean compared=!markedTrips.isEmpty();
        List<JSONObject> routes=compared ? new ArrayList<>(markedTrips.values()) :
            selected==null ? Collections.emptyList() : selected.routes;
        if (routes.isEmpty()) { view.addView(txt(tr("Mapa i statystyki trasy"),21,WHITE,true)); return; }
        JSONObject info=routes.get(0);
        LinearLayout title=group(false); title.setGravity(Gravity.CENTER_VERTICAL); view.addView(title);
        if (compared || routes.size()>1) {
            TripMath.Group totals=tripTotals(routes);
            String heading=compared ? tr("Wybrane trasy · ")+routes.size() : dayLabel(selected.start())+"  "+tripTimeRange(selected);
            width(title,txt(heading,19,WHITE,true),1);
            title.addView(txt(groupDistance(totals)+"   •   "+
                elapsed(totals.durations>0 ? Math.round(totals.seconds) : -1),15,MINT,true));
            view.addView(txt((compared ? "" : tr("Połączone odcinki · ")+routes.size()+"   •   ")+
                tr("Średnie spalanie  ")+unit(totals.mean(),1,"l/100 km")+
                tripCoverage(totals.fuelTrips,routes.size())+
                (totals.distances<routes.size() || totals.durations<routes.size() ? tr("   ·   niepełny dystans lub czas") : ""),14,MUTE,true));
            view.addView(txt(tr("Zużyte paliwo  ")+unit(totals.recordedLiters(),2,"l")+
                tripCoverage(totals.fuelMeasurements,routes.size())+
                (totals.partialFuelMeasurements>0 ? tr(" · pomiar częściowy") : "")+
                tr("   •   Szacowany koszt ")+fuelCostMode()+"  "+unit(totals.estimatedCost(),2,tr("zł"))+
                tripCoverage(totals.pricedTrips,routes.size()),14,MUTE,true));
        } else {
            width(title,txt(date(info.optLong("t_start")),19,WHITE,true),1);
            title.addView(txt(tripDistance(info)+"   •   "+elapsed(info.optLong("dur_s",-1))+
                "   •   "+unit(info.optDouble("l100"),1,"l/100 km"),14,MINT,true));
            view.addView(txt(tr("Zużyte paliwo  ")+tripFuel(info)+tr("   •   Szacowany koszt ")+fuelCostMode()+"  "+
                unit(tripCost(info),2,tr("zł")),14,MUTE,true));
        }
        String conditions=tripConditions(routes);
        if (!conditions.isEmpty()) view.addView(txt(conditions,14,MUTE,true));
        gap(view,9);
        RouteView map=new RouteView(this);
        historyMap=map;
        FrameLayout mapFrame=new FrameLayout(this); weight(view,mapFrame,1);
        mapFrame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams fit=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.END);
        fit.topMargin=dp(8); fit.setMarginEnd(dp(60));
        mapFrame.addView(icon(R.drawable.action_fit_route,tr("Pokaż całą trasę"),map::fit),fit);
        FrameLayout.LayoutParams altitudePosition=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.END);
        altitudePosition.topMargin=dp(8); altitudePosition.setMarginEnd(dp(112));
        ImageButton elevation=icon(R.drawable.metric_altitude,tr("Profil wysokości"),() -> {});
        elevation.setEnabled(false);
        mapFrame.addView(elevation,altitudePosition);
        FrameLayout.LayoutParams full=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.END);
        full.topMargin=dp(8); full.setMarginEnd(dp(8));
        ImageButton expand=icon(R.drawable.action_expand_map,tr("Pełny ekran mapy"),() -> {});
        expand.setOnClickListener(v -> {
            if (mapDialog==null) openFullMap(mapFrame,expand); else mapDialog.dismiss();
        });
        mapFrame.addView(expand,full);
        boolean preview=demo;
        routeLoad=historyWorker.submit(() -> {
            List<List<Trips.Point>> tracks=new ArrayList<>();
            for (JSONObject route:routes) {
                if (Thread.currentThread().isInterrupted()) return;
                String id=route.optString("id");
                tracks.add(preview ? Demo.points(id) : store.points(id));
            }
            ElevationProfile profile=ElevationProfile.fromTracks(tracks);
            main.post(() -> {
                if (!isDestroyed() && map==historyMap) {
                    map.setTracks(tracks);
                    elevation.setOnClickListener(button -> profile.show(this));
                    elevation.setEnabled(true);
                }
            });
        });
        gap(view,7);
        if (compared) return;
        view.addView(txt(tr("Przyspieszenie • najlepszy czas w trasie"),15,WHITE,true));
        addAccelerationRows(view,band -> {
            double best=Double.POSITIVE_INFINITY;
            for (JSONObject route:routes) {
                double time=Trips.accelerationBest(route,band);
                if (Double.isFinite(time)) best=Math.min(best,time);
            }
            return best;
        });
    }
    /** Dla kilku odcinków pokazuje najnowszy dostępny pomiar każdej z wartości. */
    private String tripConditions(List<JSONObject> routes) {
        double temperature=Double.NaN,pressure=Double.NaN;
        long temperatureAt=Long.MIN_VALUE,pressureAt=Long.MIN_VALUE;
        for (JSONObject route:routes) {
            long end=route.optLong("t_end",route.optLong("t_start"));
            double c=route.optDouble(Trips.AMBIENT_C), hpa=route.optDouble(Trips.PRESSURE_HPA);
            if (Double.isFinite(c) && end>temperatureAt) { temperature=c; temperatureAt=end; }
            if (Double.isFinite(hpa) && end>pressureAt) { pressure=hpa; pressureAt=end; }
        }
        List<String> conditions=new ArrayList<>(2);
        if (Double.isFinite(temperature)) conditions.add(tr("Temperatura zewn.  ")+unit(temperature,0,"°C"));
        if (Double.isFinite(pressure)) conditions.add(tr("Ciśnienie atm.  ")+unit(pressure,0,"hPa"));
        return conditions.isEmpty() ? "" : tr("Ostatni odczyt · ")+String.join("   •   ",conditions);
    }
    /** Przenosi ten sam widok mapy, aby zachować gesty, trasę i ustawiony kadr. */
    private void openFullMap(FrameLayout mapFrame,ImageButton expand) {
        ViewGroup parent=(ViewGroup)mapFrame.getParent();
        int index=parent.indexOfChild(mapFrame);
        ViewGroup.LayoutParams params=mapFrame.getLayoutParams();
        Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar_Fullscreen);
        FrameLayout full=new FrameLayout(this);
        dialog.setContentView(full);
        dialog.setOnDismissListener(d -> {
            full.removeView(mapFrame);
            if (parent.isAttachedToWindow()) parent.addView(mapFrame,index,params);
            describeMapButton(expand,false);
            mapDialog=null;
        });
        parent.removeView(mapFrame);
        full.addView(mapFrame,new FrameLayout.LayoutParams(-1,-1));
        describeMapButton(expand,true);
        mapDialog=dialog;
        dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        dialog.show();
        dialog.getWindow().setLayout(-1,-1);
    }
    private void describeMapButton(ImageButton button,boolean full) {
        String label=full ? tr("Wróć do historii") : tr("Pełny ekran mapy");
        button.setImageResource(full ? R.drawable.action_collapse_map : R.drawable.action_expand_map);
        button.setContentDescription(label); button.setTooltipText(label);
    }
    private void addAccelerationRows(LinearLayout panel,java.util.function.IntToDoubleFunction seconds) {
        LinearLayout row=null;
        for (int i=0;i<TripMath.SPLITS;i++) {
            int band=i;
            if (i%TripMath.SERIES_SPLITS==0) { gap(panel,5); row=group(false); panel.addView(row); }
            else gap(row,5);
            String range=accelerationRange(i),best=unit(seconds.applyAsDouble(i),2,"s");
            LinearLayout cell=group(true); cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(dp(43)); cell.setBackground(box(EDGE,8));
            cell.addView(txt(range,13,WHITE,false)); cell.addView(txt(best,17,MINT,true));
            cell.setFocusable(true);
            cell.setContentDescription(range+" · "+best+tr(" · ostatnie pomiary"));
            cell.setOnClickListener(v -> openAccelerationHistory(band));
            width(row,cell,1);
        }
    }
    private String accelerationRange(int band) { return TripMath.splitStart(band)+"–"+TripMath.splitEnd(band)+" km/h"; }
    private void openAccelerationHistory(int band) {
        Profiles.Profile profile=settings.profiles.find(historyProfileId);
        LinearLayout content=group(true); padding(content,20,12);
        content.addView(txt(accelerationRange(band)+tr(" · ostatnie pomiary"),22,WHITE,true)); gap(content,10);
        LinearLayout rows=group(true);
        ScrollView scroll=new ScrollView(this); scroll.addView(rows);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(50)));
        rows.addView(txt(tr("Wczytuję wyniki…"),15,MUTE,false));
        AlertDialog panel=new AlertDialog.Builder(this).setView(content).create();
        LinearLayout footer=group(false); footer.setGravity(Gravity.END); content.addView(footer);
        footer.addView(button(tr("Zamknij"),false,panel::dismiss),-2,dp(48));
        panel.show(); panel.getWindow().setBackgroundDrawable(box(PANEL,16));
        panel.getWindow().setLayout(Math.min(dp(640),getResources().getDisplayMetrics().widthPixels-dp(24)),-2);
        boolean sample=demo;
        historyWorker.submit(() -> {
            List<Trips.Acceleration> results=new ArrayList<>();
            String status=tr("Brak zapisanych pomiarów tego zakresu");
            try {
                if (sample) for (JSONObject trip:Demo.rows()) results.addAll(Trips.accelerationFrom(trip,band,true));
                else results=store.recentAcceleration(profile.vehicle,band);
            } catch (RuntimeException e) { status=tr("Nie udało się odczytać pomiarów"); }
            List<Trips.Acceleration> times=results;
            String empty=status;
            main.post(() -> {
                if (isDestroyed() || !panel.isShowing()) return;
                rows.removeAllViews();
                if (times.isEmpty()) { rows.addView(txt(empty,15,MUTE,false)); return; }
                int height=Math.min(dp(350),getResources().getDisplayMetrics().heightPixels-dp(175));
                scroll.getLayoutParams().height=Math.min(dp(55)*times.size(),Math.max(dp(55),height));
                scroll.requestLayout();
                accelerationResults(rows,times);
            });
        });
    }
    /** Zachowuje kolejność ostatnich prób i wyróżnia skrajności w jednostce widocznej w GUI. */
    private void accelerationResults(LinearLayout rows,List<Trips.Acceleration> times) {
        long least=Long.MAX_VALUE,most=Long.MIN_VALUE;
        for (Trips.Acceleration time:times) {
            long centiseconds=Math.round(time.seconds*100);
            least=Math.min(least,centiseconds); most=Math.max(most,centiseconds);
        }
        SimpleDateFormat moment=new SimpleDateFormat("dd.MM.yyyy  HH:mm:ss",UiLanguage.locale());
        for (Trips.Acceleration time:times) {
            long centiseconds=Math.round(time.seconds*100);
            boolean best=centiseconds==least && (least<most || times.size()==1);
            boolean worst=least<most && centiseconds==most;
            int ink=best ? MINT : worst ? OFFLINE : WHITE;
            LinearLayout row=group(false); row.setGravity(Gravity.CENTER_VERTICAL); padding(row,12,5);
            GradientDrawable frame=box(best ? 0xff153a36 : worst ? 0xff3a2732 : EDGE,9);
            if (best || worst) frame.setStroke(dp(1),ink);
            row.setBackground(frame); rows.addView(row,-1,dp(50)); gap(rows,5);
            LinearLayout stamp=group(true); width(row,stamp,1);
            stamp.addView(txt(time.archived ? date(time.atMs/1000) : moment.format(new Date(time.atMs)),14,WHITE,true));
            if (time.archived) stamp.addView(txt(tr("Rekord przejazdu"),11,MUTE,false));
            if (best || worst) {
                TextView rank=txt(best ? tr("Najlepszy") : tr("Najwolniejszy"),12,ink,true);
                rank.setGravity(Gravity.END); row.addView(rank);
            }
            TextView value=txt(unit(time.seconds,2,"s"),21,ink,true); value.setGravity(Gravity.END);
            row.addView(value,dp(80),-2);
        }
    }
    private void chooseHistoryProfile() {
        List<Profiles.Profile> profiles=settings.profiles.list();
        String[] names=new String[profiles.size()];
        for (int i=0;i<profiles.size();i++) names[i]=profiles.get(i).name;
        new AlertDialog.Builder(this).setTitle(tr("Historia samochodu")).setItems(names,(d,i) -> {
            Profiles.Profile chosen=profiles.get(i); historyProfileId=chosen.id; offset=0; historyOffsets.clear(); selected=null; markedTrips.clear();
            NasHistory.request(settings,chosen.vehicle); Maintenance.schedule(this); show(1);
        }).setNegativeButton(tr("Anuluj"),null).show();
    }

    private void config() {
        LinearLayout columns=group(false); weight(body,columns,1);
        LinearLayout index=panel(true); weight(columns,index,1); gap(columns,12);
        LinearLayout editor=panel(true); weight(columns,editor,1.45f);
        LinearLayout left=group(true),right=group(true);
        scrolling(index,left); scrolling(editor,right);
        left.addView(compactButton(UiLanguage.tr("Język")+" · "+UiLanguage.name(),this::chooseLanguage),-1,dp(44)); gap(left,6);
        LinearLayout logging=group(false); left.addView(logging,-1,dp(44));
        Switch recording=new Switch(this);
        recording.setText(tr("Rejestruj")); recording.setTextColor(WHITE); recording.setTooltipText(tr("Rejestruj przejazdy"));
        recording.setChecked(settings.recordingEnabled());
        recording.setOnCheckedChangeListener((view,enabled) -> settings.setRecordingEnabled(enabled));
        logging.addView(recording,new LinearLayout.LayoutParams(0,-1,1)); gap(logging,8);
        Switch upload=new Switch(this);
        upload.setText(tr("Wysyłaj do NAS")); upload.setTextColor(WHITE); upload.setTooltipText(tr("Wysyłaj przejazdy do NAS"));
        upload.setChecked(settings.nasUploadEnabled());
        upload.setOnCheckedChangeListener((view,enabled) -> {
            settings.setNasUploadEnabled(enabled); healthDue=0;
            if (enabled) Maintenance.schedule(this);
        });
        logging.addView(upload,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout tripSettings=group(false); left.addView(tripSettings);
        Button fuelCost=compactButton(fuelCostLabel(),() -> {});
        fuelCost.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(tr("Koszt paliwa"))
            .setSingleChoiceItems(new String[]{tr("Brutto"),tr("Netto")},settings.netFuelCost() ? 1 : 0,(dialog,choice) -> {
                settings.setNetFuelCost(choice==1); fuelCost.setText(fuelCostLabel()); dialog.dismiss();
            }).setNegativeButton(tr("Anuluj"),null).show());
        width(tripSettings,fuelCost,1); gap(tripSettings,6);
        autoStopSetting=compactButton(autoStopLabel(),this::chooseAutoStop);
        width(tripSettings,autoStopSetting,1); gap(left,10);
        Switch automatic=new Switch(this);
        automatic.setText(tr("Automatyczny pomiar przyspieszenia")); automatic.setTextColor(WHITE);
        automatic.setChecked(settings.autoAcceleration());
        automatic.setOnCheckedChangeListener((v,enabled) -> settings.setAutoAcceleration(enabled));
        left.addView(automatic,-1,dp(44));
        Switch roadAlerts=new Switch(this);
        roadAlerts.setText(tr("Ostrzeżenia o pomiarach prędkości")); roadAlerts.setTextColor(WHITE);
        roadAlerts.setChecked(settings.roadAlerts());
        roadAlerts.setOnCheckedChangeListener((view,enabled) -> {
            settings.setRoadAlerts(enabled);
            if (!enabled) getSystemService(NotificationManager.class).cancel(Recorder.ROAD_NOTIFICATION);
        });
        left.addView(roadAlerts,-1,dp(44)); gap(left,8);
        Switch sharing=new Switch(this);
        sharing.setText(tr("Udostępnij OBD przez Wi-Fi")); sharing.setTextColor(WHITE);
        sharing.setChecked(settings.proxyEnabled());
        sharing.setOnCheckedChangeListener((view,enabled) -> { settings.setProxyEnabled(enabled); refresh(); });
        left.addView(sharing,-1,dp(44));
        LinearLayout proxy=group(false); left.addView(proxy,-1,-2);
        proxyName=txt("",14,MUTE,true); proxyName.setTooltipText(tr("Nazwa usługi DNS-SD"));
        proxyInfo=txt("",14,MUTE,false); proxyInfo.setGravity(Gravity.END);
        width(proxy,proxyName,1); width(proxy,proxyInfo,1.55f); gap(left,14);
        editing=settings.profiles.find(editProfileId);
        editProfileId=editing.id;
        String preferred=settings.profiles.defaultProfile().id;
        LinearLayout profileActions=group(false); profileActions.setGravity(Gravity.CENTER_VERTICAL); left.addView(profileActions);
        Button profile=compactButton(profileLabel(editing,preferred)+" ▾",() -> chooseEditingProfile(preferred));
        profile.setContentDescription(tr("Profil do edycji · ")+profileLabel(editing,preferred));
        width(profileActions,profile,1); gap(profileActions,6);
        profileActions.addView(icon(R.drawable.action_add_profile,tr("Dodaj profil"),this::addProfile),dp(44),dp(44));
        gap(profileActions,6);
        profileActions.addView(icon(R.drawable.action_duplicate_profile,tr("Duplikuj profil"),this::duplicateProfile),dp(44),dp(44));
        gap(left,10);
        boolean internal=settings.get("folder","").isEmpty();
        String medium=internal ? tr("Pamięć radia") : settings.get("medium","Nośnik");
        LinearLayout storage=group(false); storage.setGravity(Gravity.CENTER_VERTICAL); left.addView(storage);
        width(storage,txt("GPX · "+UiLanguage.tr(medium),15,WHITE,true),1);
        storageButton(storage,R.drawable.storage_radio,tr("Pamięć radia"),internal,() -> {
            if (!internal) { settings.prefs.edit().remove("folder").remove("medium").apply(); show(2); }
        });
        storageButton(storage,R.drawable.storage_sd,UiLanguage.tr("Karta SD"),!internal && "Karta SD".equals(medium),() -> folder("Karta SD"));
        storageButton(storage,R.drawable.storage_usb,"USB",!internal && "USB".equals(medium),() -> folder("USB"));
        right.setFocusableInTouchMode(true); right.requestFocus();
        right.addView(txt(tr("Edycja profilu"),22,WHITE,true)); gap(right,8);
        LinearLayout nameRow=profileRow(right,tr("Nazwa profilu"));
        profileName=profileField(nameRow,tr("Nazwa profilu"),editing.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            new InputFilter.LengthFilter(Profiles.NAME_LIMIT));
        LinearLayout plateRow=profileRow(right,tr("Nr rej.:"));
        vehicleId=profileField(plateRow,tr("Nr rej."),editing.registration(),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            new InputFilter.AllCaps(),new InputFilter.LengthFilter(Profiles.REGISTRATION_LIMIT));
        vehicleId.setHint(tr("Nr rejestracyjny")); gap(plateRow,8);
        fuelType=new Spinner(this); fuelType.setContentDescription(tr("Typ paliwa"));
        fuelType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Diesel",tr("Benzyna")}));
        fuelType.setSelection("diesel".equals(editing.fuel) ? 0 : 1);
        plateRow.addView(fuelType,dp(128),dp(44));
        gap(right,7); adapterInfo=txt(adapterLabel(),15,MINT,true); right.addView(adapterInfo); gap(right,7);
        LinearLayout adapter=group(false); right.addView(adapter);
        adapterModes.clear();
        adapterModes.put("BLE",adapterButton(adapter,"BT BLE",tr("Wyszukaj adapter OBD Bluetooth Low Energy"),() -> selectAdapter("BLE")));
        gap(adapter,6);
        adapterModes.put("Classic",adapterButton(adapter,"BT Std.",tr("Wybierz sparowany adapter OBD Bluetooth Classic"),() -> selectAdapter("Classic")));
        gap(adapter,6);
        adapterModes.put(Profiles.Profile.WIFI,adapterButton(adapter,"Wi-Fi TCP",tr("Ustaw adres i port adaptera OBD Wi-Fi"),this::enterWifi));
        gap(adapter,6); highlightAdapterMode();
        adapterButton(adapter,"Android",tr("Otwórz ustawienia Bluetooth Androida"),() -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (ActivityNotFoundException e) { message(tr("Ten panel fabryczny nie udostępnia tego ekranu.")); }
        });
        gap(right,8);
        LinearLayout save=group(false); right.addView(save);
        Button preview=button("DEMO",demo,() -> {
            if (Recorder.live.started>0) { message(tr("Podgląd jest dostępny poza przejazdem.")); return; }
            demo=!demo; selected=null; offset=0; historyOffsets.clear(); markedTrips.clear(); show(demo ? 0 : 2);
        });
        preview.setSelected(demo);
        preview.setTooltipText(demo ? tr("Wyłącz podgląd demonstracyjny") : tr("Włącz podgląd demonstracyjny"));
        save.addView(preview,dp(84),dp(48)); gap(save,7);
        if (!editing.id.equals(preferred)) {
            width(save,compactButton(tr("Ustaw jako domyślny"),() -> saveProfile(true)),1); gap(save,7);
        }
        width(save,button(tr("Zapisz profil"),true,() -> saveProfile(false)),1);
    }
    private void chooseLanguage() {
        new AlertDialog.Builder(this).setTitle(UiLanguage.tr("Język"))
            .setSingleChoiceItems(UiLanguage.options(),UiLanguage.selected(),(dialog,index) -> {
                dialog.dismiss();
                if (index==UiLanguage.selected()) return;
                UiLanguage.set(this,index); recreate();
            }).setNegativeButton(UiLanguage.tr("Anuluj"),null).show();
    }
    private static String profileLabel(Profiles.Profile item,String preferred) {
        return item.name+(item.id.equals(preferred) ? tr(" · domyślny") : "");
    }
    private void chooseEditingProfile(String preferred) {
        List<Profiles.Profile> choices=settings.profiles.list();
        ArrayAdapter<Profiles.Profile> rows=new ArrayAdapter<Profiles.Profile>(this,0,choices) {
            @Override public View getView(int position,View reused,ViewGroup parent) {
                FrameLayout row=(FrameLayout)reused;
                if (row==null) {
                    row=new FrameLayout(Dashboard.this); padding(row,14,4);
                    TextView name=txt("",16,WHITE,true); name.setGravity(Gravity.CENTER_VERTICAL); padding(name,12,6);
                    row.addView(name,new FrameLayout.LayoutParams(-1,dp(46)));
                }
                Profiles.Profile item=getItem(position);
                boolean current=item.id.equals(editing.id);
                TextView name=(TextView)row.getChildAt(0);
                GradientDrawable frame=box(PANEL,9);
                if (current) frame.setStroke(dp(1),MINT);
                name.setBackground(frame); name.setTextColor(current ? MINT : WHITE);
                name.setText(profileLabel(item,preferred)); name.setSelected(current);
                return row;
            }
        };
        new AlertDialog.Builder(this).setTitle(tr("Profil do edycji")).setAdapter(rows,(dialog,i) -> {
            if (!choices.get(i).id.equals(editing.id)) edit(choices.get(i));
        }).setNegativeButton(tr("Anuluj"),null).show();
    }
    private void addProfile() {
        new AlertDialog.Builder(this).setTitle(tr("Dodać profil?"))
            .setPositiveButton(tr("Dodaj"),(d,i) -> edit(settings.profiles.create())).setNegativeButton(tr("Anuluj"),null).show();
    }
    private void duplicateProfile() {
        Profiles.Profile copy=editorValue(); if (copy==null) return;
        new AlertDialog.Builder(this).setTitle(tr("Duplikować profil „")+copy.name+tr("”?"))
            .setPositiveButton(tr("Duplikuj"),(d,i) -> edit(settings.profiles.duplicate(copy))).setNegativeButton(tr("Anuluj"),null).show();
    }
    private void storageButton(LinearLayout row,int drawable,String medium,boolean selected,Runnable action) {
        ImageButton choice=icon(drawable,tr("Zapis GPX · ")+medium,action);
        choice.setSelected(selected);
        if (selected) { choice.setBackground(box(MINT,10)); choice.setColorFilter(DARK); }
        gap(row,6); row.addView(choice,dp(44),dp(44));
    }
    private String fuelCostLabel() { return tr("Koszt paliwa · ")+fuelCostMode(); }
    private String autoStopLabel() {
        int minutes=settings.autoStopMinutes();
        return tr("Auto-stop · ")+(minutes==0 ? tr("wyłączony") : minutes+" min");
    }
    private void chooseAutoStop() {
        String[] choices=new String[Settings.AUTO_STOP_MAX+1];
        choices[0]=tr("0 — wyłączony");
        for (int i=1;i<choices.length;i++) choices[i]=i+" min";
        new AlertDialog.Builder(this).setTitle(tr("Auto-stop po zatrzymaniu"))
            .setSingleChoiceItems(choices,settings.autoStopMinutes(),(dialog,minutes) -> {
                settings.setAutoStopMinutes(minutes);
                changed(autoStopSetting,autoStopLabel());
                dialog.dismiss();
            }).setNegativeButton(tr("Anuluj"),null).show();
    }
    private String adapterLabel() {
        return editing.adapter.isEmpty() ? tr("Brak wybranego adaptera") :
            editing.isWifi() ? tr("Wi-Fi TCP · ")+editing.adapter : editing.adapterName+" · "+editing.transport+" · "+editing.adapter;
    }
    private void edit(Profiles.Profile item) { editProfileId=item.id; show(2); }
    private Profiles.Profile editorValue() {
        String name=profileName.getText().toString().trim(), registration=vehicleId.getText().toString().trim();
        if (name.isEmpty() || (name.length()>Profiles.NAME_LIMIT && !name.equals(editing.name))) {
            profileName.setError(tr("Wpisz od 1 do ")+Profiles.NAME_LIMIT+tr(" znaków")); return null;
        }
        String vehicle=editing.vehicle;
        if (!registration.equals(editing.registration())) {
            if (!Profiles.registrationNumber(registration)) {
                vehicleId.setError(tr("Wpisz do ")+Profiles.REGISTRATION_LIMIT+tr(" liter, cyfr lub myślników")); return null;
            }
            vehicle=registration.toUpperCase(Locale.ROOT);
        }
        return new Profiles.Profile(editing.id,name,vehicle,editing.adapter,editing.adapterName,editing.transport,
            fuelType.getSelectedItemPosition()==0 ? "diesel" : "benzyna");
    }
    private void saveProfile(boolean makeDefault) {
        Profiles.Profile value=editorValue(); if (value==null) return;
        if (!settings.profiles.save(value,makeDefault)) {
            message(tr("Nie udało się zapisać profilu. Spróbuj ponownie."));
            return;
        }
        if (makeDefault) nextProfileId=null;
        Recorder.Live live=Recorder.live;
        if (live.armed && live.started==0 && (makeDefault || value.id.equals(live.profileId)))
            startService(new Intent(this,Recorder.class).putExtra(Recorder.PROFILE,value.id));
        edit(value);
        startMonitorIfReady();
        savePopup(tr("Zapisano profil"),makeDefault ? "„"+value.name+tr("” jest teraz profilem domyślnym.") : "„"+value.name+"”");
    }
    private Button compactButton(String name,Runnable action) {
        Button control=button(name,false,action);
        padding(control,4,6); control.setSingleLine(true);
        control.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        return control;
    }
    private Button adapterButton(LinearLayout row,String name,String tooltip,Runnable action) {
        Button control=compactButton(name,action);
        control.setTooltipText(tooltip); width(row,control,1);
        return control;
    }
    private void highlightAdapterMode() {
        for (Map.Entry<String,Button> mode:adapterModes.entrySet()) {
            Button control=mode.getValue();
            boolean chosen=!editing.adapter.isEmpty() && editing.transport.equals(mode.getKey());
            control.setSelected(chosen); control.setTextColor(chosen ? DARK : WHITE);
            control.setBackground(box(chosen ? MINT : EDGE,12)); padding(control,4,6);
        }
    }
    private void enterWifi() {
        EditText address=new EditText(this);
        address.setSingleLine(true); address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("adapter.local:"+TcpElm.PORT);
        if (editing.isWifi()) address.setText(editing.adapter);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("Host i port OBD Wi-Fi")).setView(address)
            .setPositiveButton(tr("Zapisz"),null).setNegativeButton(tr("Anuluj"),null).create();
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
        adapterInfo.setText(adapterLabel()); highlightAdapterMode();
    }
    private void confirmEditedAddress(AlertDialog dialog,String address,String name,String transport) {
        setEditedAdapter(address,name,transport);
        dialog.dismiss();
        savePopup(tr("Ustawiono adres adaptera"),tr("Aby zachować zmianę, kliknij „Zapisz profil”."));
    }
    private LinearLayout profileRow(LinearLayout parent,String label) {
        LinearLayout row=group(false); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(txt(label,13,MUTE,true),dp(107),-2);
        parent.addView(row,-1,dp(44)); return row;
    }
    private EditText profileField(LinearLayout row,String label,String value,int type,InputFilter... filters) {
        EditText field=new EditText(this); field.setTextSize(15); field.setTextColor(WHITE);
        field.setInputType(type); field.setSingleLine(true); field.setText(value); field.setFilters(filters);
        field.setContentDescription(label); field.setBackgroundTintList(ColorStateList.valueOf(EDGE));
        row.addView(field,new LinearLayout.LayoutParams(0,dp(42),1)); return field;
    }
    private void folder(String label) {
        folderLabel=label;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker,FOLDER); }
        catch (ActivityNotFoundException e) { message(tr("Radio nie udostępnia systemowego wyboru katalogu.")); }
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
            } catch (SecurityException e) { message(tr("Wybrany katalog nie pozwala zapisywać GPX.")); }
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
        boolean ble="BLE".equals(transport);
        AlertDialog.Builder picker=new AlertDialog.Builder(this).setTitle(ble ? tr("Wybierz adapter BLE") : tr("Wybierz sparowane urządzenie"))
            .setNeutralButton(tr("Wprowadź MAC"),(d,i) -> enterMac(transport)).setNegativeButton(tr("Anuluj"),null);
        if (!hasBt()) {
            showAdapterPicker(picker.setMessage(tr("Zezwól na Bluetooth, aby wyświetlić urządzenia."))
                .setPositiveButton(tr("Zezwól"),(d,i) -> requestPermissions(btPermissions(),BLUETOOTH)).create());
            return;
        }
        if (Build.VERSION.SDK_INT<=30 && ble &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            showAdapterPicker(picker.setMessage(tr("Zezwól na lokalizację, aby wyszukać urządzenia BLE."))
                .setPositiveButton(tr("Zezwól"),(d,i) -> requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},BLUETOOTH)).create());
            return;
        }
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        BluetoothAdapter bt=manager==null ? null : manager.getAdapter();
        if (bt==null || !bt.isEnabled()) {
            showAdapterPicker(picker.setMessage(tr("Włącz Bluetooth w Androidzie, aby wyświetlić urządzenia.")).create());
            return;
        }
        stopScan(); deviceAddresses.clear();
        choices=new ArrayAdapter<BluetoothDevice>(this,0) {
            @Override public View getView(int position,View reused,ViewGroup parent) {
                FrameLayout row=(FrameLayout)reused;
                if (row==null) {
                    row=new FrameLayout(Dashboard.this); padding(row,14,4);
                    LinearLayout card=group(true); padding(card,12,7);
                    card.addView(txt("",16,WHITE,true),-1,-2); card.addView(txt("",12,MUTE,false),-1,-2);
                    row.addView(card,new FrameLayout.LayoutParams(-1,-2));
                }
                BluetoothDevice device=getItem(position);
                String address=device.getAddress();
                boolean selected=editing.transport.equals(transport) && editing.adapter.equalsIgnoreCase(address);
                LinearLayout card=(LinearLayout)row.getChildAt(0);
                GradientDrawable border=box(PANEL,9);
                if (selected) border.setStroke(dp(1),MINT);
                card.setBackground(border);
                TextView name=(TextView)card.getChildAt(0), mac=(TextView)card.getChildAt(1);
                name.setText(deviceName(device)); name.setTextColor(selected ? MINT : WHITE);
                mac.setText(address);
                return row;
            }
        };
        deviceDialog=picker.setAdapter(choices,(dialog,index) -> {
            BluetoothDevice chosen=choices.getItem(index);
            if (chosen!=null) setEditedAdapter(chosen.getAddress(),deviceName(chosen),transport);
        }).create();
        deviceDialog.setOnDismissListener(dialog -> stopScan()); showAdapterPicker(deviceDialog);
        for (BluetoothDevice d:bt.getBondedDevices()) if (!ble || d.getType()!=BluetoothDevice.DEVICE_TYPE_CLASSIC) addDevice(d);
        if (ble) {
            scanner=bt.getBluetoothLeScanner();
            if (scanner==null) { message(tr("Radio nie udostępnia skanera BLE.")); return; }
            scan=new ScanCallback() {
                @Override public void onScanResult(int type,ScanResult item) { main.post(() -> addDevice(item.getDevice())); }
                @Override public void onScanFailed(int code) { main.post(() -> message(tr("Skan BLE niedostępny (")+code+").")); }
            };
            try { scanner.startScan(scan); main.postDelayed(this::stopScan,12000); }
            catch (RuntimeException error) { message(tr("Skan BLE niedostępny.")); stopScan(); }
        }
    }
    private void showAdapterPicker(AlertDialog dialog) {
        dialog.show();
        Button manual=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        GradientDrawable border=box(PANEL,9); border.setStroke(dp(1),Instruments.SPEED_COLOR);
        manual.setBackground(border); manual.setTextColor(Instruments.SPEED_COLOR);
        manual.setAllCaps(false); padding(manual,12,6);
    }
    private void enterMac(String transport) {
        final EditText field=new EditText(this);
        field.setSingleLine(true); field.setHint("00:11:22:33:44:55");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("Adres adaptera ")+transport).setView(field)
            .setPositiveButton(tr("Zapisz"),null).setNegativeButton(tr("Anuluj"),null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address=field.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!BluetoothAdapter.checkBluetoothAddress(address)) { field.setError(tr("Nieprawidłowy adres MAC")); return; }
            confirmEditedAddress(dialog,address,tr("Adapter ")+transport,transport);
        }));
        dialog.show();
    }
    private void addDevice(BluetoothDevice device) {
        if (deviceDialog==null || !deviceDialog.isShowing() || !deviceAddresses.add(device.getAddress())) return;
        choices.add(device);
    }
    private String deviceName(BluetoothDevice d) {
        String name=d.getName(); return name==null || name.isEmpty() ? tr("Urządzenie Bluetooth") : name;
    }
    private void stopScan() {
        if (scanner!=null && scan!=null) try { scanner.stopScan(scan); } catch (RuntimeException ignored) {}
        scanner=null; scan=null;
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grant) {
        super.onRequestPermissionsResult(request,permissions,grant);
        if (request==LOCATION && grant.length>0 && grant[0]==PackageManager.PERMISSION_GRANTED) {
            armRecording(); requestNotificationAccess();
        }
        if (request==BLUETOOTH && grant.length>0 && grant[0]==PackageManager.PERMISSION_GRANTED && hasBt()) selectAdapter(mode);
    }
    private void message(String why) { Toast.makeText(this,why,Toast.LENGTH_LONG).show(); }
    private void savePopup(String title,String detail) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("OK",null).show();
    }
    static String fmt(double n,int d) { return Double.isFinite(n) ? String.format(UiLanguage.locale(),"%."+d+"f",n) : "—"; }
    private static String date(long sec) { return new SimpleDateFormat("dd.MM.yyyy  HH:mm",UiLanguage.locale()).format(new Date(sec*1000)); }
    private static String elapsed(long sec) {
        if (sec<0) return "—";
        if (sec==0) return "0s";
        long hours=sec/3600,minutes=sec/60%60,seconds=sec%60;
        return ((hours>0 ? hours+(UiLanguage.selected()==2 ? "g " : "h ") : "")+(minutes>0 ? minutes+"m " : "")+
            (seconds>0 ? seconds+"s" : "")).trim();
    }
}
