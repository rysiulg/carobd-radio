package pl.marm.carobd;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.ArrayList;

/** Bieżący stan CarOBD oraz publiczna mapa pomiarów dla podpisanego pulpitu RadioKIA. */
public final class DashboardData extends ContentProvider {
    private static final String DASHBOARD="pl.radiokia.preview";
    private long nextWake;

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if (!trusted()) throw new SecurityException("Tylko pulpit RadioKIA");
        if ("roadMap".equals(method)) {
            RoadMapData.Snapshot map=RoadMapData.load(getContext());
            Bundle result=new Bundle();
            result.putString("version",map.version); result.putString("date",map.date);
            if (!map.version.equals(arg)) result.putDoubleArray("markers",map.markers);
            return result;
        }
        if (!"snapshot".equals(method)) throw new IllegalArgumentException("Nieznane żądanie pulpitu");
        Recorder.Live live=Recorder.live;
        if (!live.armed) wakeRecorder();
        Bundle current=new Bundle();
        current.putBoolean("armed",live.armed);
        current.putBoolean("obd_online",live.obd);
        current.putBoolean("journal_error",live.journalError);
        current.putString("obd_status",live.status);
        current.putLong("obd_retry_s",live.retryAt==0 ? 0 : Math.max(0,(live.retryAt-SystemClock.elapsedRealtime()+999)/1000));
        String vehicle=live.armed ? live.vehicle : new Settings(getContext()).profiles.defaultProfile().vehicle;
        current.putString("vehicle",vehicle);
        current.putBoolean("kia_profile",EngineHours.kiaProfile(vehicle));
        current.putDouble(Trips.AMBIENT_C,live.ambientC);
        current.putDouble("kmh",live.speed);
        current.putLong("seconds",live.started<=0 ? -1 : (SystemClock.elapsedRealtime()-live.started)/1000);
        current.putDouble("distance",live.started<=0 ? Double.NaN : live.km);
        current.putDouble("fuel_l",live.tripLiters);
        current.putDouble("fuel_mean",live.mean);
        current.putStringArrayList("obd_faults",new ArrayList<>(live.faults));
        return current;
    }
    private synchronized void wakeRecorder() {
        long now=SystemClock.elapsedRealtime();
        if (now<nextWake) return;
        nextWake=now+5000;
        long identity=Binder.clearCallingIdentity();
        try { Boot.resumeRecorder(getContext()); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    private boolean trusted() {
        return DASHBOARD.equals(getCallingPackage())
            && getContext().getPackageManager().checkSignatures(getContext().getPackageName(), DASHBOARD)
                == PackageManager.SIGNATURE_MATCH;
    }
    @Override public Cursor query(Uri uri,String[] columns,String where,String[] args,String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String where,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String where,String[] args) { throw new UnsupportedOperationException(); }
}
