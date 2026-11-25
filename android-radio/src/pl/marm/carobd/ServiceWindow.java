package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

final class ServiceWindow {
    private final Dashboard activity;
    private final Profiles.Profile car;
    private final Settings settings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean readOnOpen;

    ServiceWindow(Dashboard activity,Profiles.Profile car) {
        this.activity=activity; this.car=car; settings=new Settings(activity);
    }

    void show() {
        boolean kia=EngineHours.kiaProfile(car.vehicle);
        readOnOpen=kia;
        LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(10),dp(24),dp(6));
        ScrollView scroll=new ScrollView(activity); scroll.addView(content);
        LinearLayout panel=new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(panel,22); heading.setText(tr("Motogodziny i serwis · ")+car.name);
        heading.setTypeface(null,Typeface.BOLD); heading.setPadding(dp(24),dp(15),dp(24),dp(6));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout counters=new LinearLayout(activity); content.addView(counters,-1,-2);
        TextView engineHours=hoursCard(counters,tr("Praca silnika"));
        counters.addView(new View(activity),dp(8),1);
        TextView idleHours=hoursCard(counters,tr("Bieg jałowy"));
        TextView service=label(content,15); service.setPadding(0,dp(10),0,dp(4));
        Editor editor=kia ? new Editor(content) : null;
        TextView reminder=kia ? reminderRow(content) : null;
        LinearLayout network=new LinearLayout(activity); content.addView(network,-1,-2);
        TextView dns=label(network,13), connection=label(network,13);
        dns.setTextColor(Dashboard.MUTE); connection.setTextColor(Dashboard.MUTE);
        dns.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        connection.setLayoutParams(new LinearLayout.LayoutParams(0,-2,2));
        connection.setGravity(Gravity.END);
        if (!kia) service.setText(tr("Odczyt ustawień przeglądu jest dostępny po wybraniu profilu Kia."));

        AlertDialog dialog=new AlertDialog(activity) {
            @Override public void cancel() {
                if (!isShowing()) return;
                if (editor==null || !editor.dirty()) { super.cancel(); return; }
                AlertDialog.Builder confirm=new AlertDialog.Builder(activity);
                if (Recorder.live.serviceBusy) {
                    confirm.setTitle(tr("Trwa zapis do zegarów")).setPositiveButton("OK",null).show();
                } else {
                    confirm.setTitle(tr("Odrzucić niezapisane zmiany?")).setNegativeButton(tr("Wróć"),null)
                        .setPositiveButton(tr("Odrzuć"),(d,i) -> dismiss()).show();
                }
            }
        };
        dialog.setView(panel);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout actions=new LinearLayout(activity); actions.setPadding(dp(24),0,dp(24),dp(12));
        actions.setGravity(Gravity.CENTER_VERTICAL); panel.addView(actions,-1,dp(64));
        actions.addView(activity.button(tr("Zamknij"),false,dialog::cancel),-2,dp(48));
        actions.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        Button save=kia ? activity.button(tr("Zapisz do auta"),true,editor::save) : null;
        if (save!=null) { enabled(save,false); actions.addView(save,-2,dp(48)); }
        Runnable update=new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || activity.isDestroyed()) return;
                Recorder.Live live=Recorder.live;
                boolean own=car.vehicle.equals(live.vehicle) || kia && EngineHours.kiaProfile(live.vehicle);
                EngineHours.Reading hours=live.hours;
                boolean haveHours=own && live.obd && hours!=null && hours.fresh(SystemClock.elapsedRealtime());
                changed(engineHours,haveHours ? UiLanguage.duration(hours.total) : "—");
                changed(idleHours,haveHours ? UiLanguage.duration(hours.idle) : "—");
                String ip=ObdLan.radioAddress(activity);
                String addresses=ip.isEmpty() ? tr("Radio bez adresu Wi-Fi") : tr("Adres radia: ")+ip;
                if (car.isWifi()) addresses+=tr("\nProxy OBD: ")+car.adapter;
                else if (own && live.armed && !live.proxyStatus.isEmpty()) addresses+="\n"+UiLanguage.proxyStatus(live.proxyStatus);
                changed(dns,own && !car.isWifi() ? live.proxyName : "");
                changed(connection,addresses);
                if (kia) {
                    boolean current=car.id.equals(live.profileId);
                    boolean connected=current && live.armed && live.obd;
                    boolean measuring=live.measurement==Measurement.WAITING || live.measurement==Measurement.RUNNING;
                    Elm.ServiceInterval value=connected ? live.service : null;
                    String status=!connected ? tr("Połącz OBD z tym profilem Kia") :
                        live.serviceBusy ? UiLanguage.tr(live.serviceStatus) : live.speed!=0 ? tr("Odczyt i zapis dostępne na postoju. OBD musi pokazywać 0 km/h.") :
                        measuring ? tr("Zakończ pomiar przyspieszenia") : !live.serviceStatus.isEmpty() ? UiLanguage.tr(live.serviceStatus) :
                        tr("Odczytuję ustawienia z zegarów…");
                    changed(service,status);
                    service.setTextColor(status.startsWith(tr("Odczytano")) ? Dashboard.MINT : Dashboard.MUTE);
                    boolean ready=connected && live.speed==0 && !live.serviceBusy && !live.dtcBusy && !measuring;
                    if (ready && readOnOpen) { requestRead(); ready=false; }
                    editor.update(value,ready && !readOnOpen);
                    changed(reminder,reminder(value,own && live.obd ? live.odometer : Double.NaN));
                    enabled(save,ready && editor.dirty());
                }
                main.postDelayed(this,700);
            }
        };
        dialog.setOnDismissListener(view -> main.removeCallbacks(update));
        dialog.setOnShowListener(view -> {
            dialog.getWindow().setBackgroundDrawable(activity.box(Dashboard.PANEL,16));
            dialog.getWindow().setLayout(Math.min(dp(650),activity.getResources().getDisplayMetrics().widthPixels-dp(24)),-2);
            main.post(update);
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    private TextView hoursCard(LinearLayout row,String title) {
        LinearLayout card=new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(activity.box(Dashboard.EDGE,10)); card.setPadding(dp(12),dp(4),dp(12),dp(5));
        row.addView(card,new LinearLayout.LayoutParams(0,-2,1));
        TextView caption=label(card,13); caption.setText(title); caption.setTextColor(Dashboard.MUTE);
        caption.setPadding(0,dp(3),0,0);
        TextView value=label(card,20); value.setTypeface(null,Typeface.BOLD); value.setTextColor(Dashboard.MINT);
        value.setPadding(0,0,0,dp(3)); value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        return value;
    }
    private TextView reminderRow(LinearLayout parent) {
        LinearLayout row=new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(5),0,dp(4)); parent.addView(row,-1,-2);
        TextView reminder=label(row,14); reminder.setTextColor(Dashboard.MUTE);
        reminder.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        row.addView(new View(activity),dp(8),1);
        Button reset=activity.button(tr("Ostatni reset"),false,this::editBaseline);
        reset.setTooltipText(tr("Ustaw datę ostatniego resetu przypomnienia"));
        row.addView(reset,-2,dp(48));
        return reminder;
    }
    private void requestRead() {
        readOnOpen=false;
        activity.startService(command(Recorder.READ_SERVICE));
    }
    private Intent command(String action) {
        return new Intent(activity,Recorder.class).setAction(action).putExtra(Recorder.PROFILE,car.id);
    }
    private String key(String field) { return "service_reset_"+field+":"+car.vehicle; }
    private String reminder(Elm.ServiceInterval interval,double odometer) {
        long day=settings.prefs.getLong(key("day"),-1);
        if (day<0) return tr("Brak daty ostatniego resetu");
        LocalDate start=LocalDate.ofEpochDay(day),today=LocalDate.now();
        if (start.isAfter(today)) return tr("Sprawdź datę ostatniego resetu");
        long passed=today.toEpochDay()-day;
        int startKm=settings.prefs.getInt(key("km"),-1);
        long km=startKm>=0 && odometer>=startKm-.5 ? Math.max(0,Math.round(odometer)-startKm) : -1;
        String summary=tr("Od wpisanego resetu  ")+days(passed)+(km<0 ? "" : " · "+Dashboard.fmt(km,0)+" km");
        if (interval==null || interval.months<=0 || interval.km<=0) return summary;
        long due=start.plusMonths(interval.months).toEpochDay()-today.toEpochDay();
        long kmLeft=interval.km-km;
        return summary+tr("\nDo przeglądu (szacunek)  ")+days(Math.abs(due))+(due<0 ? tr(" po terminie") : "")+
            (km<0 ? "" : " · "+Dashboard.fmt(Math.abs(kmLeft),0)+" km"+(kmLeft<0 ? tr(" po terminie") : ""));
    }
    private static String days(long count) { return count+ (count==1 ? tr(" dzień") : tr(" dni")); }
    private void editBaseline() {
        LocalDate today=LocalDate.now();
        long stored=settings.prefs.getLong(key("day"),today.toEpochDay());
        LocalDate initial=LocalDate.ofEpochDay(Math.min(stored,today.toEpochDay()));
        DatePickerDialog date=new DatePickerDialog(activity,(view,year,month,day) -> {
            LocalDate selected=LocalDate.of(year,month+1,day);
            Recorder.Live live=Recorder.live;
            double current=car.vehicle.equals(live.vehicle) && live.obd ? live.odometer : Double.NaN;
            int max=Double.isFinite(current) ? (int)Math.round(current) : 9_999_999;
            int saved=settings.prefs.getInt(key("km"),-1);
            int suggestion=selected.equals(today) && Double.isFinite(current) ? max : saved;
            numberPad(tr("Przebieg przy resecie (km)"),suggestion,7,tr("Wpisz stan licznika z dnia resetu"),n -> n>=0 && n<=max,
                n -> settings.prefs.edit().putLong(key("day"),selected.toEpochDay()).putInt(key("km"),n).apply(),true);
        },initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());
        date.setTitle(tr("Data ostatniego resetu przeglądu")); date.getDatePicker().setMaxDate(System.currentTimeMillis()); date.setCanceledOnTouchOutside(true); date.show();
    }
    private final class Editor {
        final Button km, months;
        final RadioGroup modes;
        final RadioButton user, service;
        int distance, period;
        Elm.ServiceInterval shown;

        Editor(LinearLayout content) {
            LinearLayout numbers=new LinearLayout(activity);
            numbers.setOrientation(LinearLayout.HORIZONTAL); content.addView(numbers);
            km=number(numbers,tr("Interwał (km)"),KiaService.Field.KM);
            numbers.addView(new View(activity),dp(8),1);
            months=number(numbers,tr("Interwał (mies.)"),KiaService.Field.MONTHS);
            modes=new RadioGroup(activity); modes.setOrientation(RadioGroup.HORIZONTAL); content.addView(modes);
            user=mode("User"); service=mode("Service");
            update(null,false);
        }
        private Button number(LinearLayout row,String hint,KiaService.Field field) {
            LinearLayout column=new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
            row.addView(column,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=label(column,14); title.setText(hint);
            Button value=activity.button(tr("Po odczycie"),false,() -> {}); value.setTextSize(18);
            value.setId(View.generateViewId()); title.setLabelFor(value.getId());
            value.setOnClickListener(v -> editNumber(hint,field,value));
            column.addView(value,new LinearLayout.LayoutParams(-1,-2));
            return value;
        }
        private RadioButton mode(String title) {
            RadioButton button=new RadioButton(activity);
            button.setId(View.generateViewId()); button.setText(title); button.setTextColor(Dashboard.WHITE);
            button.setMinHeight(dp(44)); button.setPadding(dp(6),0,dp(17),0); modes.addView(button);
            return button;
        }
        void update(Elm.ServiceInterval actual,boolean ready) {
            if (actual!=null && actual!=shown) {
                shown=actual;
                distance=actual.km; period=actual.months;
                km.setText(Integer.toString(distance)); months.setText(Integer.toString(period));
                modes.check(actual.mode==0 ? user.getId() : service.getId());
            }
            boolean edit=ready && actual!=null;
            enabled(km,edit); enabled(months,edit); user.setEnabled(edit); service.setEnabled(edit);
        }
        private void editNumber(String title,KiaService.Field field,Button target) {
            boolean mileage=field==KiaService.Field.KM;
            int original=mileage ? distance : period;
            String limits=mileage ? tr("5 000–60 000 km, co 500 km") : tr("Od 1 do 36 miesięcy");
            numberPad(title,original,mileage ? 5 : 2,limits,n -> n==original || field.accepts(n),n -> {
                if (!target.isEnabled()) return;
                if (mileage) distance=n; else period=n;
                target.setText(Integer.toString(n));
            },false);
        }
        private Elm.ServiceInterval draft() {
            return new Elm.ServiceInterval(distance,period,modes.getCheckedRadioButtonId()==user.getId() ? 0 : 1);
        }
        boolean dirty() { return shown!=null && !shown.same(draft()); }
        void save() {
            if (!km.isEnabled() || !dirty()) return;
            Elm.ServiceInterval value=draft();
            Intent write=command(Recorder.WRITE_SERVICE)
                .putExtra(Recorder.SERVICE_KM,value.km).putExtra(Recorder.SERVICE_MONTHS,value.months)
                .putExtra(Recorder.SERVICE_MODE,value.mode).putExtra("before_km",shown.km)
                .putExtra("before_months",shown.months).putExtra("before_mode",shown.mode);
            new AlertDialog.Builder(activity).setTitle(tr("Zapisać ustawienia w zegarach?"))
                .setMessage(tr("Zapis może skasować bieżące odliczanie przeglądu i przywrócić wartości domyślne w zegarach."))
                .setNegativeButton(tr("Anuluj"),null).setPositiveButton(tr("Zapisz"),(dialog,button) -> activity.startService(write))
                .show();
        }
    }

    private void numberPad(String title,int original,int length,String limits,IntPredicate valid,IntConsumer apply,boolean optional) {
            LinearLayout pad=new LinearLayout(activity); pad.setOrientation(LinearLayout.VERTICAL);
            pad.setPadding(dp(18),0,dp(18),dp(6));
            TextView digits=label(pad,28); digits.setGravity(Gravity.CENTER); digits.setText(original>=0 ? Integer.toString(original) : "");
            TextView range=label(pad,14); range.setGravity(Gravity.CENTER); range.setText(limits);
            // Klawiatura w oknie zachowuje przyciski dialogu widoczne na radiu w poziomie.
            boolean[] replace={true};
            String[] keys={"1","2","3","4","5","6","7","8","9","Wyczyść","0","⌫"};
            for (int i=0;i<keys.length;i+=3) {
                LinearLayout row=new LinearLayout(activity); pad.addView(row);
                for (int j=i;j<i+3;j++) {
                    String key=keys[j];
                    Button press=new Button(activity); press.setText(tr(key)); press.setAllCaps(false);
                    if ("⌫".equals(key)) press.setContentDescription(tr("Usuń ostatnią cyfrę"));
                    row.addView(press,new LinearLayout.LayoutParams(0,dp(46),1));
                    press.setOnClickListener(v -> {
                        String input=digits.getText().toString();
                        if ("Wyczyść".equals(key)) input="";
                        else if ("⌫".equals(key)) input=input.isEmpty() ? "" : input.substring(0,input.length()-1);
                        else if (replace[0]) input=key;
                        else if (input.length()<length) input+=key;
                        replace[0]=false; digits.setText(input); range.setText(limits);
                    });
                }
            }
            ScrollView scroll=new ScrollView(activity); scroll.addView(pad);
            AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
                .setNegativeButton(tr("Anuluj"),null).setPositiveButton(tr("Ustaw"),null);
            if (optional) builder.setNeutralButton(tr("Pomiń km"),(d,i) -> apply.accept(-1));
            AlertDialog keypad=builder.create();
            keypad.setOnShowListener(v -> keypad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String input=digits.getText().toString();
                int number=input.isEmpty() ? -1 : Integer.parseInt(input);
                if (!valid.test(number)) { range.setText(tr("Wpisz poprawną wartość. ")+limits); return; }
                apply.accept(number);
                keypad.dismiss();
            }));
            keypad.show();
    }

    private static void enabled(Button button,boolean active) {
        button.setEnabled(active); button.setAlpha(active ? 1f : .52f);
    }
    private int dp(float value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent,int size) {
        TextView label=new TextView(activity);
        label.setTextSize(size); label.setTextColor(Dashboard.WHITE);
        label.setPadding(0,dp(5),0,dp(6)); parent.addView(label);
        return label;
    }
    private static void changed(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(Dashboard.withUnits(value));
    }
}
