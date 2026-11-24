package pl.marm.carobd;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class ServiceWindow {
    private final Dashboard activity;
    private final Profiles.Profile car;
    private final Handler main=new Handler(Looper.getMainLooper());

    ServiceWindow(Dashboard activity,Profiles.Profile car) {
        this.activity=activity; this.car=car;
    }

    void show() {
        boolean kia=EngineHours.kiaProfile(car.vehicle);
        LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(10),dp(24),dp(6));
        ScrollView scroll=new ScrollView(activity); scroll.addView(content);
        TextView counters=label(content,18), connection=label(content,15);
        TextView service=label(content,17);
        if (!kia) service.setText("Odczyt ustawień przeglądu jest dostępny dla zweryfikowanego profilu Kia.");

        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("Motogodziny i serwis · "+car.name)
            .setView(scroll).setNegativeButton("Zamknij",null);
        if (kia) builder.setPositiveButton("Odczytaj z auta",null);
        AlertDialog dialog=builder.create();
        Runnable update=new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || activity.isDestroyed()) return;
                Recorder.Live live=Recorder.live;
                boolean own=car.vehicle.equals(live.vehicle) ||
                    (EngineHours.kiaProfile(car.vehicle) && EngineHours.kiaProfile(live.vehicle));
                EngineHours.Reading hours=live.hours;
                String summary="Brak świeżego odczytu motogodzin z OBD";
                if (own && live.obd && hours!=null && hours.fresh(SystemClock.elapsedRealtime())) {
                    summary="Praca silnika  "+EngineHours.duration(hours.total)+
                        "\nPostój na biegu jałowym  "+EngineHours.duration(hours.idle);
                }
                changed(counters,summary);
                String ip=ObdLan.radioAddress(activity);
                String addresses=ip.isEmpty() ? "Radio bez adresu Wi-Fi" : "Adres radia: "+ip;
                if (car.isWifi()) addresses+="\nProxy OBD: "+car.adapter;
                else if (own && live.armed && !live.proxyStatus.isEmpty()) addresses+="\n"+live.proxyStatus;
                changed(connection,addresses);
                if (kia) {
                    boolean connected=own && live.armed && live.obd;
                    String status=connected && !live.serviceStatus.isEmpty() ? live.serviceStatus : "Ustawienia przeglądu";
                    Elm.ServiceInterval value=connected ? live.service : null;
                    changed(service,value==null ? status : status+"\n"+value.km+" km   ·   "+value.months+
                        " mies.   ·   "+(value.mode==0 ? "User" : "Service"));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(connected && live.speed==0);
                }
                main.postDelayed(this,700);
            }
        };
        dialog.setOnDismissListener(view -> main.removeCallbacks(update));
        dialog.setOnShowListener(view -> {
            if (kia) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button ->
                activity.startService(new Intent(activity,Recorder.class).setAction(Recorder.READ_SERVICE)));
            main.post(update);
        });
        dialog.show();
    }

    private int dp(float value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent,int size) {
        TextView label=new TextView(activity);
        label.setTextSize(size); label.setPadding(0,dp(5),0,dp(6)); parent.addView(label);
        return label;
    }
    private static void changed(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
}
