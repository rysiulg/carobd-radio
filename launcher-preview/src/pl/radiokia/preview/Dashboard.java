package pl.radiokia.preview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.location.Location;
import android.text.InputType;
import android.widget.EditText;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pl.radiokia.fm.DashboardTuner;
import pl.radiokia.fm.FmScale;
import pl.radiokia.fm.WheelPresets;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;
import android.widget.VideoView;

public final class Dashboard extends Activity {
    private static final int BACK = 0xff131924, PANEL = 0xff202a39, CONTROL = 0xff2c394d;
    private static final int INK = 0xfff2f4fa, MUTED = 0xffb1bfd3, ROUTE = 0xff9ebcf7;
    private static final int CLIP_REQUEST = 41, LOCATION_REQUEST = 42;
    private static final int PRESET_COUNT = 8, FM_ACCENT = 0xff78ddd0;
    private static final String PHONE="com.zjinnova.zlink", VLC="org.videolan.vlc", DS="com.synology.dsvideo", MAPS="com.google.android.apps.maps";
    private static final String PHONE_APP_NAME="TLink5";
    private static final Uri CAROBD=Uri.parse("content://pl.marm.carobd.dashboard");
    private static final String CAROBD_APP="pl.marm.carobd";
    private static final String RADIO_FM="pl.radiokia.fm";
    private static final String RADIO_SERVICE="pl.radiokia.serwis";
    private static final Uri FM_PRESETS=Uri.parse("content://"+RADIO_FM+".presets");
    private static final Locale PL=new Locale("pl","PL");
    private enum Panel {
        MAP("Mapa"), FM("Radio FM"), OBD("Dane auta"), VIDEO("Wideo"), AUTO("Android Auto"), CARPLAY("CarPlay");
        final String title;
        Panel(String title) { this.title = title; }
    }
    private enum Page {
        LAYOUT("Układ"), CAR("CarOBD"), RADIO("RadioKIA FM");
        final String title;
        Page(String title) { this.title=title; }
    }
    // Kolejność stron jest niezależna od indeksów Page zapisanych w ustawieniach.
    private static final List<Page> PAGE_ORDER=Arrays.asList(Page.LAYOUT,Page.RADIO,Page.CAR);
    private static final class CarFields {
        TextView speed,duration,distance,fuel,mean,status;
    }
    private static final class FmFields {
        TextView frequency,status;
        FmScale scale;
        final TextView[] presets=new TextView[PRESET_COUNT];
    }
    private static final Panel[] DEFAULT_PANELS = {Panel.MAP, Panel.FM, Panel.VIDEO};
    private final Panel[] panels = DEFAULT_PANELS.clone();
    private final FrameLayout[] slots = new FrameLayout[DEFAULT_PANELS.length];
    private SharedPreferences preferences;
    private boolean twoPanels, editing, floating, playClip, wantsGps;
    private float horizontal, vertical, floatX = 1f, floatY = 1f;
    private int clipPosition, realFrequency, requestedFrequency, floatWidth, floatHeight;
    private boolean running,telemetryBusy;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private DashboardTuner tuner;
    private int fmSending;
    private final WheelPresets wheel=new WheelPresets(main,() -> fmFocused() && fmSending==0,
        value -> sendFm(value,false,true), this::tunerChannel);
    private String fmNote="Czekam na odczyt tunera";
    private TextView mapAltitude;
    private TextView faultNotice;
    private List<String> faults=Collections.emptyList();
    private final List<CarFields> cars=new ArrayList<>();
    private final List<FmFields> radios=new ArrayList<>();
    private final TextView[] pageTabs=new TextView[Page.values().length];
    private Page page=Page.LAYOUT;
    private SwipePages swipe;
    private ViewAnimator pages;
    private View layoutTools;
    private int[] fmChannels=new int[0];
    private LiveMap liveMap;
    private Uri clip;
    private VideoView video;
    private GpsRecorder gps;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(BACK);
        preferences = getSharedPreferences("pulpit", MODE_PRIVATE);
        int savedPage=preferences.getInt("page",0);
        if (savedPage>=0 && savedPage<Page.values().length) page=Page.values()[savedPage];
        twoPanels = preferences.getBoolean("twoPanels", false);
        horizontal = preferences.getFloat("horizontal", 0.5f);
        vertical = preferences.getFloat("vertical", 0.57f);
        for (int i = 0; i < panels.length; i++) {
            int ordinal = preferences.getInt("panel" + i, DEFAULT_PANELS[i].ordinal());
            if (ordinal >= 0 && ordinal < Panel.values().length) panels[i] = Panel.values()[ordinal];
        }
        floatWidth=preferences.getInt("floatW",420); floatHeight=preferences.getInt("floatH",265);
        gps = new GpsRecorder(this, this::updateGpsStatus);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        running=true;
        // Fabryczne FM publikuje stan również wtedy, gdy samo zasłania pulpit.
        if (tuner==null) tuner=new DashboardTuner(this::tunerChannel,this::tunerSteering);
        if (wantsGps || (liveMap!=null && locationAllowed())) startGps(wantsGps);
        telemetry.run();
    }

    @Override protected void onStop() {
        running=false;
        wheel.clear();
        main.removeCallbacks(telemetry);
        fmSequence++; realFrequency=requestedFrequency=0; fmNote="Czekam na odczyt tunera"; showFm();
        gps.stop(); updateGpsStatus();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        reader.execute(() -> {
            int[] channels=null;
            try {
                android.content.pm.ProviderInfo provider=getPackageManager().resolveContentProvider(FM_PRESETS.getAuthority(),0);
                if (provider!=null && RADIO_FM.equals(provider.packageName)) {
                    Bundle result=getContentResolver().call(FM_PRESETS,"presets",null,null);
                    if (result!=null) channels=result.getIntArray("channels");
                }
            } catch (IllegalArgumentException | SecurityException unavailable) { }
            int[] saved=channels==null ? new int[0] : channels;
            main.post(() -> { if (running) { fmChannels=saved; showFm(); } });
        });
    }

    @Override protected void onDestroy() {
        if (video!=null) video.stopPlayback();
        if (tuner!=null) { tuner.close(); tuner=null; }
        main.removeCallbacksAndMessages(null);
        reader.shutdownNow(); gps.close();
        super.onDestroy();
    }

    private final Runnable telemetry=new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateAltitude();
            if (!telemetryBusy) {
                telemetryBusy=true;
                reader.execute(() -> {
                    Bundle state=null;
                    try { state=getContentResolver().call(CAROBD,"snapshot",null,null); }
                    catch (RuntimeException unavailable) { }
                    Bundle result=state;
                    main.post(() -> {
                        telemetryBusy=false;
                        if (running) showCar(result);
                    });
                });
            }
            main.postDelayed(this,1200);
        }
    };
    private static void text(TextView view,String text) { if (view!=null && !text.contentEquals(view.getText())) view.setText(text); }

    private void render() {
        if (video != null) {
            playClip = video.isPlaying();
            clipPosition = video.getCurrentPosition();
            video.stopPlayback();
            video = null;
        }
        swipe=new SwipePages(this, this::stepPage);
        cars.clear(); radios.clear();
        LinearLayout root = column();
        root.setBackgroundColor(BACK);
        root.setPadding(dp(22), dp(12), dp(22), dp(14));
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = column();
        brand.addView(label("RadioKIA Pulpit", 25, INK, true));
        brand.addView(label("Pulpit samochodowy", 13, MUTED, false));
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout tools=row(); layoutTools=tools;
        addRight(tools, action("3 panele", !twoPanels, () -> setLayout(false)));
        addRight(tools, action("2 panele", twoPanels, () -> setLayout(true)));
        addRight(tools, action(editing ? "Gotowe" : "Edytuj", editing, () -> { editing = !editing; render(); }));
        addRight(bar,tools);
        if (getPackageManager().getLaunchIntentForPackage(RADIO_SERVICE)!=null)
            addRight(bar,action("Serwer SSH",false,() -> launchApp(RADIO_SERVICE,null,false)));
        LinearLayout status = row();
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        status.setPadding(dp(11), 0, 0, 0);
        TextView temperature = label("— °C", 19, MUTED, true);
        temperature.setContentDescription("Temperatura zewnętrzna. Brak pomiaru");
        status.addView(temperature);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(INK);
        clock.setTextSize(29);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        addRight(status, clock);
        addRight(bar, status);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(62)));
        LinearLayout navigation=row();
        for (Page item:PAGE_ORDER) {
            TextView tab=action(item.title,false,() -> changePage(item));
            pageTabs[item.ordinal()]=tab; addRight(navigation,tab);
        }
        navigation.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        addRight(navigation,action("Aplikacje",false,this::openApps));
        root.addView(navigation,new LinearLayout.LayoutParams(-1,dp(44)));
        faultNotice=action("",false,() -> {
            if (!faults.isEmpty()) new AlertDialog.Builder(this).setTitle("Usterki OBD")
                .setMessage(TextUtils.join("\n\n",faults)).setPositiveButton("Zamknij",null).show();
        });
        faultNotice.setTextColor(0xffffbe82);
        root.addView(faultNotice,new LinearLayout.LayoutParams(-1,dp(48))); showFaultNotice();

        pages=new ViewAnimator(this); pages.setAnimateFirstView(false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(12);
        root.addView(pages,bodyParams);
        LinearLayout body=row(); pages.addView(body,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout left = column();
        liveMap=null; mapAltitude=null;
        slots[0] = new FrameLayout(this);
        slots[1] = new FrameLayout(this);
        slots[2] = new FrameLayout(this);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, horizontal));
        left.addView(slots[0], new LinearLayout.LayoutParams(-1, 0, twoPanels ? 1 : vertical));
        if (!twoPanels) {
            left.addView(divider(left, slots[0], slots[1], true), new LinearLayout.LayoutParams(-1, dp(14)));
            left.addView(slots[1], new LinearLayout.LayoutParams(-1, 0, 1 - vertical));
            fillSlot(1);
        }
        body.addView(divider(body, left, slots[2], false), new LinearLayout.LayoutParams(dp(16), -1));
        body.addView(slots[2], new LinearLayout.LayoutParams(0, -1, 1 - horizontal));
        fillSlot(0);
        fillSlot(2);
        pages.addView(vehiclePanel(Panel.OBD,-1),new FrameLayout.LayoutParams(-1,-1));
        pages.addView(vehiclePanel(Panel.FM,-1),new FrameLayout.LayoutParams(-1,-1));
        pages.setDisplayedChild(page.ordinal());
        showPageTabs();
        swipe.addView(root,new FrameLayout.LayoutParams(-1,-1));
        if (floating) addFloatingVideo(swipe);
        setContentView(swipe);
        if (running && liveMap!=null && locationAllowed()) startGps(wantsGps);
    }

    private void stepPage(int direction) {
        int next=PAGE_ORDER.indexOf(page)+direction;
        if (next>=0 && next<PAGE_ORDER.size()) changePage(PAGE_ORDER.get(next));
    }
    private void changePage(Page next) {
        if (next==page) return;
        if (page==Page.LAYOUT && !floating && video!=null && video.getDuration()>0) {
            clipPosition=video.getCurrentPosition(); playClip=video.isPlaying();
        }
        int direction=PAGE_ORDER.indexOf(next)>PAGE_ORDER.indexOf(page) ? 1 : -1;
        page=next;
        if (!fmFocused()) wheel.clear();
        preferences.edit().putInt("page",page.ordinal()).apply();
        pages.setInAnimation(slide(direction,0));
        pages.setOutAnimation(slide(0,-direction));
        pages.setDisplayedChild(page.ordinal());
        showPageTabs();
    }
    private Animation slide(float from,float to) {
        Animation animation=new TranslateAnimation(Animation.RELATIVE_TO_PARENT,from,Animation.RELATIVE_TO_PARENT,to,
            Animation.ABSOLUTE,0,Animation.ABSOLUTE,0);
        animation.setDuration(210);
        return animation;
    }
    private void showPageTabs() {
        layoutTools.setVisibility(page==Page.LAYOUT ? View.VISIBLE : View.GONE);
        for (Page item:Page.values()) styleAction(pageTabs[item.ordinal()],item==page);
    }
    @Override public void onBackPressed() {
        if (page==Page.LAYOUT) super.onBackPressed();
        else changePage(Page.LAYOUT);
    }

    private void setLayout(boolean useTwoPanels) {
        twoPanels = useTwoPanels;
        preferences.edit().putBoolean("twoPanels", useTwoPanels).apply();
        render();
    }

    private void fillSlot(int slot) {
        FrameLayout holder = slots[slot];
        holder.removeAllViews();
        Panel type = panels[slot];
        View content;
        switch (type) {
            case MAP: content = map(); break;
            case FM:
            case OBD: content = vehiclePanel(type,slot); break;
            case VIDEO: content = videoPanel(false); break;
            default: content = projection(type);
        }
        if (editing) {
            LinearLayout editor = column();
            TextView change = action("Zmień panel · " + type.title, false, () -> choosePanel(slot));
            editor.addView(change, new LinearLayout.LayoutParams(-1, dp(44)));
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, 0, 1);
            below.topMargin = dp(6);
            editor.addView(content, below);
            content = editor;
        }
        holder.addView(content, new FrameLayout.LayoutParams(-1, -1));
    }

    private void choosePanel(int slot) {
        Panel[] types = Panel.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].title;
        new AlertDialog.Builder(this).setTitle("Wybierz zawartość panelu")
            .setSingleChoiceItems(names, panels[slot].ordinal(), (dialog, index) -> {
                dialog.dismiss();
                assignPanel(slot, types[index]);
                render();
            }).setNegativeButton("Anuluj", null).show();
    }

    private void assignPanel(int slot, Panel type) {
        for (int i = 0; i < panels.length; i++) {
            if (i != slot && panels[i] == type) panels[i] = panels[slot];
        }
        panels[slot] = type;
        SharedPreferences.Editor saved = preferences.edit();
        for (int i = 0; i < panels.length; i++) saved.putInt("panel" + i, panels[i].ordinal());
        saved.apply();
    }

    /** Zmienia wagi istniejących paneli, aby przeciąganie nie otwierało ponownie wideo. */
    private View divider(LinearLayout split, View first, View second, boolean stacked) {
        TextView grip = label(stacked ? "⋯" : "⋮", 20, ROUTE, true);
        swipe.keepGesture(grip);
        grip.setGravity(Gravity.CENTER);
        grip.setContentDescription(stacked ? "Podział góra dół" : "Podział lewo prawo");
        int[] origin = new int[2];
        grip.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return true;
            split.getLocationOnScreen(origin);
            float point = stacked ? event.getRawY() - origin[1] : event.getRawX() - origin[0];
            float available = stacked ? split.getHeight() : split.getWidth();
            if (available <= 0) return true;
            float fraction = clamp(point / available, stacked ? .44f : .32f, stacked ? .62f : .68f);
            if (stacked) vertical = fraction;
            else horizontal = fraction;
            LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) first.getLayoutParams();
            LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) second.getLayoutParams();
            a.weight = fraction;
            b.weight = 1 - fraction;
            split.requestLayout();
            if (action == MotionEvent.ACTION_UP) preferences.edit()
                .putFloat(stacked ? "vertical" : "horizontal", fraction).apply();
            return true;
        });
        return grip;
    }

    private View projection(Panel type) {
        LinearLayout pane=card(); pane.setGravity(Gravity.CENTER);
        pane.addView(label(type.title,24,INK,true));
        pane.addView(action("Otwórz "+PHONE_APP_NAME,true,() -> launchApp(PHONE,null,false)),new LinearLayout.LayoutParams(-2,dp(48)));
        return pane;
    }

    private View map() {
        FrameLayout panel=new FrameLayout(this);
        panel.setBackground(round(0xff293b43,18)); panel.setClipToOutline(true);
        liveMap=new LiveMap(this);
        swipe.keepGesture(liveMap);
        panel.addView(liveMap,new FrameLayout.LayoutParams(-1,-1));
        liveMap.position(gps.position());
        LinearLayout toolbar=row(); toolbar.setPadding(dp(8),dp(7),dp(8),dp(7));
        addRight(toolbar,action("Pozycja",true,this::centerMap));
        addRight(toolbar,action("−",false,() -> liveMap.zoomBy(-1)));
        addRight(toolbar,action("+",false,() -> liveMap.zoomBy(1)));
        addRight(toolbar,action("Mapy",false,this::openMaps));
        panel.addView(toolbar,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        TextView gpsEntry=action(wantsGps ? "Stop zapisu GPS" : "Zapis GPS",false,() -> {
            wantsGps=!wantsGps;
            if (!locationAllowed()) centerMap(); else startGps(wantsGps);
            render();
        });
        FrameLayout.LayoutParams anchor=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.LEFT);
        anchor.setMargins(dp(8),0,0,dp(8));
        panel.addView(gpsEntry,anchor);
        mapAltitude=label("",14,INK,true);
        mapAltitude.setBackground(round(BACK,8)); mapAltitude.setPadding(dp(10),dp(6),dp(10),dp(6));
        FrameLayout.LayoutParams elevation=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);
        elevation.setMargins(0,0,dp(8),dp(24));
        panel.addView(mapAltitude,elevation); updateAltitude();
        return panel;
    }
    private boolean locationAllowed() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    private void centerMap() {
        if (!locationAllowed()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);
        else { startGps(wantsGps); if (liveMap!=null) liveMap.center(); }
    }
    private void openMaps() {
        Location loc=gps.position();
        launchApp(MAPS,loc==null ? null : Uri.parse("geo:"+loc.getLatitude()+","+loc.getLongitude()+"?z=15"),false);
    }

    private View vehiclePanel(Panel type,int slot) {
        boolean obd=type==Panel.OBD, wholePage=slot<0;
        LinearLayout panel=card(), header=row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(obd ? "Dane auta" : "Tuner FM",wholePage ? 25 : 19,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        if (!wholePage) {
            addRight(header,action("FM",!obd,() -> changeVehiclePanel(slot,Panel.FM)));
            addRight(header,action("OBD",obd,() -> changeVehiclePanel(slot,Panel.OBD)));
        }
        if (!obd) addRight(header,action("RadioKIA FM",false,() -> launchApp(RADIO_FM,null,false)));
        panel.addView(header);
        if (obd) buildCarFields(panel,wholePage);
        else buildCompactFm(panel,wholePage);
        return panel;
    }
    private void buildCarFields(LinearLayout panel,boolean wholePage) {
        LinearLayout content=scrollPane(panel);
        CarFields fields=new CarFields(); cars.add(fields);
        int digits=wholePage ? 30 : 19, rowHeight=wholePage ? 94 : 50;
        LinearLayout metrics=row(); metrics.setGravity(Gravity.CENTER_VERTICAL);
        fields.speed=metric(metrics,"Prędkość",digits); fields.duration=metric(metrics,"Czas jazdy",digits);
        fields.distance=metric(metrics,"Dystans",digits); fields.fuel=metric(metrics,"Zużyte paliwo",digits);
        content.addView(metrics,new LinearLayout.LayoutParams(-1,dp(rowHeight)));
        fields.mean=label("",wholePage ? 20 : 14,MUTED,true); content.addView(fields.mean);
        LinearLayout note=row(); note.setGravity(Gravity.CENTER_VERTICAL);
        fields.status=label("",13,MUTED,false);
        note.addView(fields.status,new LinearLayout.LayoutParams(0,-2,1));
        addRight(note,action(wholePage ? "Otwórz CarOBD" : "CarOBD",false,() -> launchApp(CAROBD_APP,null,false)));
        addRight(note,action("Serwis",false,this::openCarService)); content.addView(note);
    }
    private LinearLayout scrollPane(LinearLayout panel) {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout content=column(); content.setPadding(0,dp(6),dp(6),dp(4));
        scroll.addView(content); panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return content;
    }
    private void buildCompactFm(LinearLayout panel,boolean wholePage) {
        LinearLayout content=scrollPane(panel);
        FmFields fields=new FmFields(); radios.add(fields);
        LinearLayout current=row(); current.setGravity(Gravity.CENTER_VERTICAL);
        TextView lower=action("‹",false,() -> command(0,false));
        TextView upper=action("›",false,() -> command(0,true));
        lower.setContentDescription("Szukaj niższej częstotliwości"); upper.setContentDescription("Szukaj wyższej częstotliwości");
        current.addView(lower,new LinearLayout.LayoutParams(dp(52),dp(44)));
        fields.frequency=label("",wholePage ? 61 : 39,INK,true);
        fields.frequency.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
        fields.frequency.setGravity(Gravity.CENTER); fields.frequency.setSingleLine(true);
        current.addView(fields.frequency,new LinearLayout.LayoutParams(0,dp(wholePage ? 91 : 53),1));
        current.addView(upper,new LinearLayout.LayoutParams(dp(52),dp(44))); content.addView(current);
        fields.status=label("",wholePage ? 17 : 13,MUTED,false); fields.status.setGravity(Gravity.CENTER); content.addView(fields.status);
        fields.scale=new FmScale(this,value -> command(value,false));
        swipe.keepGesture(fields.scale);
        content.addView(fields.scale,new LinearLayout.LayoutParams(-1,dp(wholePage ? 112 : 76)));
        final int columns=4;
        for (int first=0;first<PRESET_COUNT;first+=columns) {
            LinearLayout strip=row(); content.addView(strip,new LinearLayout.LayoutParams(-1,dp(wholePage ? 62 : 47)));
            for (int column=0;column<columns;column++) {
                final int index=first+column;
                TextView button=action("",false,() -> { if (index<fmChannels.length) command(fmChannels[index],false); });
                button.setPadding(dp(2),0,dp(2),0); button.setTextSize(wholePage ? 19 : 14); button.setSingleLine(true);
                LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-1,1);
                size.setMargins(column==0 ? 0 : dp(6),0,0,dp(5));
                strip.addView(button,size); fields.presets[index]=button;
            }
        }
        showFm();
    }
    private void changeVehiclePanel(int slot,Panel type) {
        if (panels[slot]==type) return;
        assignPanel(slot,type); render();
    }
    private String frequency(int value) { return String.format(PL,"%.1f",value/10.0); }
    private void showFm() {
        String mhz=realFrequency>0 ? frequency(realFrequency)+" MHz" : "— MHz";
        for (FmFields fields:radios) {
            text(fields.frequency,mhz); text(fields.status,fmNote);
            fields.scale.setFrequency(realFrequency);
            for (int i=0;i<fields.presets.length;i++) {
                TextView button=fields.presets[i];
                if (button==null) continue;
                int channel=i<fmChannels.length ? fmChannels[i] : 0;
                boolean active=channel>0 && channel==realFrequency;
                String slot="P"+(i+1);
                text(button,slot+(channel>0 ? "  "+frequency(channel) : "  —"));
                button.setContentDescription(slot+(channel>0 ? " "+frequency(channel)+" MHz" : " pusty"));
                button.setEnabled(channel>0); button.setSelected(active);
                button.setTextColor(active ? FM_ACCENT : channel>0 ? INK : MUTED);
            }
        }
    }
    private boolean fmFocused() {
        return tuner!=null && running && hasWindowFocus() &&
            (page==Page.RADIO || page==Page.LAYOUT &&
                (panels[0]==Panel.FM || (!twoPanels && panels[1]==Panel.FM) || panels[2]==Panel.FM));
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) wheel.clear();
    }
    private void tunerSteering(int direction,boolean seek) {
        if (!fmFocused()) return;
        if (seek) {
            wheel.clear(); fmSequence++;
            requestedFrequency=realFrequency=0; fmNote="Wyszukiwanie stacji…"; showFm();
        } else if (!wheel.step(direction,realFrequency,fmChannels)) {
            fmNote="Zapisz preset w RadioKIA FM"; showFm();
        }
    }
    private void tunerChannel(int value) {
        if (!wheel.acceptChannel(value)) return;
        realFrequency=value;
        if (requestedFrequency==0 || requestedFrequency==value) { requestedFrequency=0; fmNote="Odczyt z tunera"; }
        showFm();
    }
    private int fmSequence;
    private void command(int value,boolean up) {
        if (tuner==null) return;
        wheel.clear();
        sendFm(value,up,false);
    }
    private void sendFm(int value,boolean up,boolean preset) {
        int token=++fmSequence;
        fmSending++;
        requestedFrequency=value;
        realFrequency=0;
        fmNote=value==0 ? "Wyszukiwanie stacji…" : "Strojenie "+frequency(value)+" MHz…";
        showFm();
        java.util.function.Consumer<String> done=error -> {
            fmSending--;
            if (token==fmSequence && running) {
                if (error!=null && (!preset || wheel.awaiting(value))) {
                    if (preset) wheel.finish(value);
                    fmNote=error; showFm();
                } else if (error==null && (value==0 || realFrequency!=value)) {
                    main.postDelayed(() -> {
                        if (token!=fmSequence || "Odczyt z tunera".equals(fmNote) ||
                            preset && wheel.pending() && !wheel.awaiting(value)) return;
                        if (preset) wheel.finish(value);
                        fmNote=value>0 ? "Wysłano "+frequency(value)+" MHz · brak odczytu tunera" : "Wysłano szukanie · brak odczytu tunera";
                        showFm();
                    },4500);
                }
            }
            wheel.flush();
        };
        if (value>0) tuner.tune(this,value,done); else tuner.seek(this,up,done);
    }
    private void showCar(Bundle state) {
        boolean available=state!=null && state.getBoolean("armed");
        boolean obd=available && state.getBoolean("obd_online");
        ArrayList<String> codes=obd ? state.getStringArrayList("obd_faults") : null;
        faults=codes==null ? Collections.emptyList() : codes; showFaultNotice();
        if (cars.isEmpty()) return;
        long seconds=available ? state.getLong("seconds",-1) : -1;
        String status;
        if (available && state.getBoolean("journal_error")) {
            status="Błąd zapisu trasy";
        } else if (available && !obd) {
            long retry=state.getLong("obd_retry_s");
            status=(seconds>=0 ? "Trwa rejestracja trasy · " : "")+state.getString("obd_status","Brak połączenia OBD")+
                (retry>0 ? " · ponowienie za "+retry+" s" : "");
        } else status=available ? (seconds>=0 ? "Trwa rejestracja trasy" : "") :
            state==null ? "Brak odpowiedzi CarOBD" : "CarOBD się uruchamia lub potrzebuje dostępu do lokalizacji";
        String speed=available ? number(state.getDouble("kmh",Double.NaN),0," km/h") : "—";
        String duration=seconds<0 ? "—" : String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
        String distance=available ? number(state.getDouble("distance",Double.NaN),2," km") : "—";
        String fuel=available ? number(state.getDouble("fuel_l",Double.NaN),2," l") : "—";
        String mean="Średnie spalanie  "+(available ? number(state.getDouble("fuel_mean",Double.NaN),1," l/100 km") : "—");
        for (CarFields fields:cars) {
            text(fields.speed,speed); text(fields.duration,duration); text(fields.distance,distance); text(fields.fuel,fuel);
            text(fields.mean,mean);
            text(fields.status,status); fields.status.setVisibility(status.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
    }
    private void showFaultNotice() {
        faultNotice.setVisibility(faults.isEmpty() ? View.GONE : View.VISIBLE);
        if (!faults.isEmpty()) text(faultNotice,"OBD · "+faults.get(0)+(faults.size()>1 ? "  +"+(faults.size()-1) : ""));
    }
    private static String number(double value,int digits,String unit) {
        return Double.isFinite(value) ? String.format(PL,"%."+digits+"f",value)+unit : "—";
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(request,permissions,result);
        if (request==LOCATION_REQUEST && locationAllowed()) startGps(wantsGps);
    }
    private void startGps(boolean record) {
        if (!gps.start(record)) Toast.makeText(this,"Włącz lokalizację GPS.",Toast.LENGTH_SHORT).show();
        updateGpsStatus();
    }
    private void updateGpsStatus() {
        if (liveMap!=null) liveMap.position(gps.position());
        updateAltitude();
    }
    private void updateAltitude() {
        if (mapAltitude!=null) text(mapAltitude,"Wysokość GPS  "+number(gps.altitude(),0," m"));
    }

    private View videoPanel(boolean isFloating) {
        LinearLayout panel = card();
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label(isFloating ? "↔  Wideo" : "Wideo", 19, INK, true);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        if (isFloating) heading.setContentDescription("Przeciągnij okno wideo");
        addRight(header, action(isFloating || floating ? "Przypnij" : "Pływające", false, () -> {
            floating = !floating;
            render();
        }));
        if (isFloating || !floating) addRight(header,action("Źródło",true,this::chooseVideoSource));
        panel.addView(header);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(round(0xff131b27, 13));
        screen.setClipToOutline(true);
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(-1, 0, 1);
        screenParams.topMargin = dp(12);
        panel.addView(screen, screenParams);
        if (clip == null || (floating && !isFloating)) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            TextView prompt = label("▶", 34, ROUTE, false);
            empty.addView(prompt, new LinearLayout.LayoutParams(-2, -2));
            String message = floating && !isFloating ? "Film w pływającym oknie" : "Wybierz telefon, aplikację lub strumień";
            empty.addView(label(message, 16, MUTED, false), new LinearLayout.LayoutParams(-2, -2));
            screen.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        } else {
            VideoView playerView = new VideoView(this);
            swipe.keepGesture(playerView);
            video = playerView;
            FrameLayout.LayoutParams fit = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
            screen.addView(video, fit);
            MediaController controls = new MediaController(this);
            controls.setAnchorView(video);
            video.setMediaController(controls);
            video.setOnPreparedListener(player -> {
                if (video != playerView) return;
                if (clipPosition > 0 && player.getDuration() > clipPosition) playerView.seekTo(clipPosition);
                if (playClip) playerView.start();
            });
            video.setOnErrorListener((player, reason, extra) -> {
                if (video == playerView) {
                    clip = null;
                    render();
                    Toast.makeText(this, "Nie można odtworzyć tego filmu.", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            video.setVideoURI(clip);
        }
        if (isFloating) panel.setTag(heading);
        return panel;
    }

    /** Przesuwa warstwę wideo w granicach pulpitu bez zmiany powierzchni odtwarzacza. */
    private void addFloatingVideo(FrameLayout root) {
        View floatingVideo = videoPanel(true);
        TextView handle = (TextView) floatingVideo.getTag();
        FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(dp(floatWidth),dp(floatHeight));
        float[] offset=new float[2];
        FrameLayout wrapper=new FrameLayout(this);
        swipe.keepGesture(wrapper);
        wrapper.setElevation(dp(14)); floatingVideo.setElevation(0);
        root.addView(wrapper,size);
        wrapper.addView(floatingVideo,new FrameLayout.LayoutParams(-1,-1));
        root.post(() -> {
            resizeFloating(root,wrapper,dp(floatWidth),dp(floatHeight));
            wrapper.setTranslationX(Math.max(0,root.getWidth()-size.width-dp(16))*floatX);
            wrapper.setTranslationY(dp(75)+Math.max(0,root.getHeight()-size.height-dp(91))*floatY);
        });
        TextView corner=action("⤢",false,() -> {
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams) wrapper.getLayoutParams();
            resizeFloating(root,wrapper,params.width+dp(60),params.height+dp(36));
        });
        corner.setContentDescription("Powiększ lub przeciągnij, aby zmienić rozmiar wideo");
        FrameLayout.LayoutParams grip=new FrameLayout.LayoutParams(dp(48),dp(46),Gravity.RIGHT|Gravity.BOTTOM);
        wrapper.addView(corner,grip);
        int[] dimensions=new int[2]; float[] down=new float[2];
        corner.setOnTouchListener((v,e) -> {
            int what=e.getActionMasked();
            if (what==MotionEvent.ACTION_DOWN) {
                down[0]=e.getRawX(); down[1]=e.getRawY();
                dimensions[0]=wrapper.getWidth(); dimensions[1]=wrapper.getHeight(); return true;
            }
            if (what==MotionEvent.ACTION_MOVE) {
                resizeFloating(root,wrapper,dimensions[0]+Math.round(e.getRawX()-down[0]),
                    dimensions[1]+Math.round(e.getRawY()-down[1]));
            }
            if (what==MotionEvent.ACTION_UP && Math.abs(e.getRawX()-down[0])+Math.abs(e.getRawY()-down[1])<dp(6)) v.performClick();
            return true;
        });
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                offset[0] = event.getRawX() - wrapper.getTranslationX();
                offset[1] = event.getRawY() - wrapper.getTranslationY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float limitX = maxFloatX(root, wrapper), limitY = maxFloatY(root, wrapper);
                float x = clamp(event.getRawX() - offset[0], 0, limitX);
                float y = clamp(event.getRawY() - offset[1] - dp(75), 0, limitY);
                wrapper.setTranslationX(x);
                wrapper.setTranslationY(dp(75) + y);
                floatX = limitX > 0 ? x / limitX : 0;
                floatY = limitY > 0 ? y / limitY : 0;
            }
            return true;
        });
    }

    private void resizeFloating(FrameLayout root,View wrapper,int width,int height) {
        FrameLayout.LayoutParams size=(FrameLayout.LayoutParams) wrapper.getLayoutParams();
        size.width=Math.round(clamp(width,dp(335),Math.max(dp(335),root.getWidth()-dp(20))));
        size.height=Math.round(clamp(height,dp(175),Math.max(dp(175),root.getHeight()-dp(94))));
        wrapper.setTranslationX(clamp(wrapper.getTranslationX(),0,Math.max(0,root.getWidth()-size.width-dp(16))));
        wrapper.setTranslationY(clamp(wrapper.getTranslationY(),dp(75),Math.max(dp(75),root.getHeight()-size.height-dp(16))));
        wrapper.setLayoutParams(size);
        float density=getResources().getDisplayMetrics().density;
        floatWidth=Math.round(size.width/density); floatHeight=Math.round(size.height/density);
        preferences.edit().putInt("floatW",floatWidth).putInt("floatH",floatHeight).apply();
    }

    private int maxFloatX(FrameLayout root, View child) { return Math.max(0, root.getWidth() - child.getWidth() - dp(16)); }
    private int maxFloatY(FrameLayout root, View child) { return Math.max(0, root.getHeight() - child.getHeight() - dp(91)); }
    private static float clamp(float value, float min, float max) { return Math.min(Math.max(value, min), max); }

    private void chooseVideoSource() {
        new AlertDialog.Builder(this).setTitle("Źródło wideo").setItems(
            new String[]{"Z telefonu · "+PHONE_APP_NAME,"DS video","VLC","Adres strumienia HTTPS","Plik lokalny"},
            (d,i) -> {
                if (i==0) launchApp(PHONE,null,true);
                else if (i==1) launchApp(DS,null,true);
                else if (i==2) launchApp(VLC,null,true);
                else if (i==3) chooseStream();
                else pickClip();
            }).setNegativeButton("Anuluj",null).show();
    }
    private void openApps() {
        PackageManager packages=getPackageManager();
        Intent launchers=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps=new ArrayList<>(packages.queryIntentActivities(launchers,0));
        apps.removeIf(app -> app.activityInfo==null || !app.activityInfo.exported || getPackageName().equals(app.activityInfo.packageName));
        Collections.sort(apps,new ResolveInfo.DisplayNameComparator(packages));
        CharSequence[] names=new CharSequence[apps.size()];
        for (int i=0;i<apps.size();i++) names[i]=apps.get(i).loadLabel(packages);
        new AlertDialog.Builder(this).setTitle("Aplikacje").setItems(names,(dialog,index) -> {
            ResolveInfo app=apps.get(index);
            Intent open=Intent.makeMainActivity(new ComponentName(app.activityInfo.packageName,app.activityInfo.name));
            try { startActivity(open); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(this,"Nie można otworzyć aplikacji",Toast.LENGTH_SHORT).show();
            }
        }).setNeutralButton("Ekran główny",(dialog,button) -> openHomeSettings())
            .setPositiveButton("Gesty motywu",(dialog,button) -> showThemeGestures())
            .setNegativeButton("Zamknij",null).show();
    }
    private void openHomeSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); }
        catch (ActivityNotFoundException unavailable) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
    }
    private void showThemeGestures() {
        new AlertDialog.Builder(this).setTitle("Gesty motywu")
            .setMessage("Wybierz fabryczny ekran główny. W Ułatwieniach dostępu włącz „RadioKIA – gesty motywu”.\n\n"+
                "Przesuń mały uchwyt u dołu w lewo, aby przejść do pełnego FM, a następnie do CarOBD. W prawo wracasz.")
            .setNeutralButton("Ekran główny",(dialog,which) -> openHomeSettings())
            .setPositiveButton("Ułatwienia dostępu",(dialog,which) ->
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Zamknij",null).show();
    }
    private void launchApp(String packageName,Uri target,boolean adjacent) {
        Intent intent=target==null ? getPackageManager().getLaunchIntentForPackage(packageName) :
            new Intent(Intent.ACTION_VIEW,target).setPackage(packageName);
        launch(intent,adjacent);
    }
    private void openCarService() {
        Intent intent=getPackageManager().getLaunchIntentForPackage(CAROBD_APP);
        if (intent!=null) intent.putExtra("pl.marm.carobd.SHOW_SERVICE",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch(intent,false);
    }
    private void launch(Intent intent,boolean adjacent) {
        if (intent==null) { Toast.makeText(this,"Tej aplikacji nie ma w menu radia.",Toast.LENGTH_SHORT).show(); return; }
        if (adjacent) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException failure) {
            Toast.makeText(this,"Nie można otworzyć aplikacji.",Toast.LENGTH_SHORT).show();
        }
    }
    private void chooseStream() {
        EditText input=new EditText(this);
        input.setSingleLine(true); input.setHint("https://…");
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Strumień wideo").setView(input)
            .setPositiveButton("Odtwarzaj",null).setNegativeButton("Anuluj",null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                URI source=URI.create(input.getText().toString().trim());
                if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost()==null || source.getUserInfo()!=null)
                    throw new IllegalArgumentException();
                play(Uri.parse(source.toString())); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input.setError("Podaj adres HTTPS strumienia"); }
        }));
        dialog.show();
    }
    private void play(Uri source) {
        if (video!=null) { video.stopPlayback(); video=null; }
        clip=source; clipPosition=0; playClip=true; render();
    }

    private void pickClip() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, CLIP_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CLIP_REQUEST && result == RESULT_OK && data != null && data.getData() != null) {
            play(data.getData());
        }
    }

    private TextView metric(LinearLayout parent,String name,int size) {
        LinearLayout cell=column();
        TextView value=label("—",size,INK,true); value.setSingleLine(true); cell.addView(value);
        cell.addView(label(name,13,MUTED,false));
        parent.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
        return value;
    }

    private LinearLayout card() {
        LinearLayout panel = column();
        panel.setPadding(dp(15), dp(12), dp(15), dp(13));
        panel.setBackground(round(PANEL, 18));
        return panel;
    }

    private void addRight(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (parent.getChildCount() != 0) params.leftMargin = dp(8);
        parent.addView(child, params);
    }

    private TextView action(String text, boolean selected, Runnable click) {
        TextView button = label(text, 15, selected ? BACK : INK, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinimumHeight(dp(44));
        styleAction(button,selected);
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void styleAction(TextView button,boolean selected) {
        button.setSelected(selected); button.setTextColor(selected ? BACK : INK);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),round(selected ? ROUTE : CONTROL,10),null));
    }

    private TextView label(String text, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { return new LinearLayout(this); }

}
