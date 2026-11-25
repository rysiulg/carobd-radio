package pl.marm.carobd;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;

import java.util.Arrays;
import java.util.Locale;

/** Odbiera pomiary z Topway niezależnie od połączenia ELM i otwartego pulpitu. */
final class TpmsMonitor {
    private static final String SERVICE="com.tw.carinfoservice.CarService";
    private static final String API=SERVICE+"Aidl", CALLBACK=SERVICE+"CallBack";
    private static final int REGISTER=1, UNREGISTER=2, FIRST_PRESSURE=37, COUNT=4;
    private static final long CHECK_MS=15000, FRESH_MS=180000;
    private static final int NOTIFICATION=31;
    private static final String CHANNEL="tire_pressure";
    static final String SHOW="pl.marm.carobd.SHOW_TPMS";
    private static final double DEFAULT_LOW_KPA=180;
    private static final String[] WHEELS={"Lewy przód","Prawy przód","Lewy tył","Prawy tył"};

    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NotificationManager notifications;
    private final double[] bars={Double.NaN,Double.NaN,Double.NaN,Double.NaN};
    private final long[] sampledAt=new long[COUNT];
    private boolean started,bound,registered;
    private int lowMask;
    private IBinder remote;
    private int generation;
    private final Runnable periodic=this::check;
    private static volatile Snapshot latest=new Snapshot(new double[]{Double.NaN,Double.NaN,Double.NaN,Double.NaN},false,0,false);

    static final class Snapshot {
        private final double[] pressures;
        final boolean connected,hasValues;
        final int lowMask;
        Snapshot(double[] pressures,boolean connected,int lowMask,boolean hasValues) {
            this.pressures=pressures; this.connected=connected; this.lowMask=lowMask; this.hasValues=hasValues;
        }
        double at(int wheel) { return pressures[wheel]; }
        boolean low(int wheel) { return (lowMask & (1<<wheel))!=0; }
    }
    static Snapshot snapshot() { return latest; }
    static String wheelName(int wheel) { return WHEELS[wheel]; }

    private Binder callback(int token) {
        return new Binder() {
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                if (code==INTERFACE_TRANSACTION) { reply.writeString(CALLBACK); return true; }
                if (code<IBinder.FIRST_CALL_TRANSACTION || code>90) return super.onTransact(code,data,reply,flags);
                data.enforceInterface(CALLBACK);
                if (code>=FIRST_PRESSURE && code<FIRST_PRESSURE+COUNT) {
                    int wheel=code-FIRST_PRESSURE;
                    double value=toBars(data.readFloat(),data.readString());
                    long time=SystemClock.elapsedRealtime();
                    main.post(() -> { if (started && token==generation) accept(wheel,value,time); });
                }
                if (reply!=null) reply.writeNoException();
                return true;
            }
        };
    }
    private Binder receiver;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder service) {
            if (!started || !bound) return;
            remote=service; receiver=callback(++generation);
            registered=subscription(REGISTER);
            publish();
        }
        @Override public void onServiceDisconnected(ComponentName name) { lost(); }
        @Override public void onBindingDied(ComponentName name) { lost(); }
        @Override public void onNullBinding(ComponentName name) { lost(); }
    };

    TpmsMonitor(Context context) {
        this.context=context;
        notifications=context.getSystemService(NotificationManager.class);
    }
    void start() {
        if (started) return;
        started=true;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,UiLanguage.tr("Ciśnienie opon"),NotificationManager.IMPORTANCE_HIGH));
        check();
    }
    void close() {
        started=false; main.removeCallbacks(periodic);
        if (registered) subscription(UNREGISTER);
        lost();
        lowMask=0; publish(); notifications.cancel(NOTIFICATION);
    }
    void refresh() {
        if (!started) return;
        Arrays.fill(sampledAt,0);
        publish();
        check();
    }
    private void lost() {
        generation++; remote=null; receiver=null; registered=false;
        if (bound) { bound=false; context.unbindService(connection); }
        publish();
    }
    private void check() {
        if (!started) return;
        main.removeCallbacks(periodic);
        if (bound && (!registered || remote==null || !remote.isBinderAlive())) lost();
        if (!bound) {
            Intent intent=new Intent(SERVICE+".Bind").setClassName("com.tw.carinfoservice",SERVICE);
            try { bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE); }
            catch (SecurityException ignored) { bound=false; }
        }
        publish();
        main.postDelayed(periodic,CHECK_MS);
    }
    private boolean subscription(int code) {
        if (remote==null || receiver==null) return false;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(API); data.writeStrongBinder(receiver);
            if (!remote.transact(code,data,reply,0)) return false;
            reply.readException(); return true;
        } catch (RemoteException | RuntimeException ignored) { return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    private void accept(int wheel,double value,long time) {
        bars[wheel]=value; sampledAt[wheel]=time;
        int oldMask=lowMask;
        // Używamy progu tej samej usługi Topway, w kPa.
        int configured=Settings.System.getInt(context.getContentResolver(),"TPMS_LOW",(int)DEFAULT_LOW_KPA);
        double limit=(configured>=80 && configured<=400 ? configured : DEFAULT_LOW_KPA)/100;
        lowMask=alertMask(lowMask,wheel,value,limit);
        publish();
        if (oldMask!=lowMask) notifyLow();
    }
    static int alertMask(int previous,int wheel,double value,double limit) {
        if (!Double.isFinite(value)) return previous;
        if (value<limit) return previous | (1<<wheel);
        if (value>=limit+0.08) return previous & ~(1<<wheel);
        return previous;
    }
    private void publish() {
        long now=SystemClock.elapsedRealtime();
        double[] fresh=bars.clone();
        boolean hasValues=false;
        for (int i=0;i<COUNT;i++) {
            if (sampledAt[i]==0 || now-sampledAt[i]>=FRESH_MS) fresh[i]=Double.NaN;
            hasValues|=Double.isFinite(fresh[i]);
        }
        latest=new Snapshot(fresh,registered,lowMask,hasValues);
    }
    private void notifyLow() {
        if (lowMask==0) { notifications.cancel(NOTIFICATION); return; }
        Intent open=new Intent(context,Dashboard.class).setAction(SHOW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent details=PendingIntent.getActivity(context,NOTIFICATION,open,PendingIntent.FLAG_IMMUTABLE);
        try {
            notifications.notify(NOTIFICATION,new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_tpms)
                .setContentTitle(UiLanguage.tr("Niskie ciśnienie w oponach"))
                .setContentText(UiLanguage.tr("Sprawdź wskazanie TPMS na zegarach samochodu"))
                .setCategory(Notification.CATEGORY_STATUS).setOnlyAlertOnce(true).setContentIntent(details).build());
        } catch (SecurityException ignored) { /* Ostrzeżenie pozostaje widoczne na pulpicie. */ }
    }
    static double toBars(double value,String unit) {
        if (unit==null || !Double.isFinite(value) || value<=0) return Double.NaN;
        switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "bar": break;
            case "psi": value*=0.0689475729; break;
            case "kpa": value/=100; break;
            default: return Double.NaN;
        }
        return value<=10 ? value : Double.NaN;
    }
}
