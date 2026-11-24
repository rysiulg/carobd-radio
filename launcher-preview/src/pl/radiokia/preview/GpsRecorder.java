package pl.radiokia.preview;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GpsRecorder implements LocationListener {
    private final LocationManager manager;
    private final File journal;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private boolean active, writeError, record;
    private Location position;
    private int saved;

    GpsRecorder(Context context, Runnable changed) {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        journal = new File(context.getFilesDir(), "gps-podglad.jsonl");
        this.changed = changed;
    }

    /** Rejestruje pozycję z anteny radia przez Android GPS po udzieleniu zgody użytkownika. */
    boolean start(boolean save) {
        record=save;
        if (active) return true;
        if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            active = true;
            writeError = false;
            return true;
        } catch (SecurityException | IllegalArgumentException unavailable) { return false; }
    }

    void stop() {
        if (!active) return;
        active = false;
        manager.removeUpdates(this);
    }

    void close() { stop(); writer.shutdown(); }

    String status() {
        if (writeError) return "GPS · błąd zapisu lokalnego";
        if (!active) return "GPS · zatrzymany";
        return record ? "GPS · zapis lokalny / "+saved+" pkt" : position==null ? "GPS · szukanie pozycji" : "GPS · pozycja dostępna";
    }

    Location position() { return position; }
    double altitude() {
        if (!active || position==null || !position.hasAltitude()) return Double.NaN;
        long age=SystemClock.elapsedRealtimeNanos()-position.getElapsedRealtimeNanos();
        return age>=0 && age<=6_500_000_000L ? position.getAltitude() : Double.NaN;
    }

    @Override public void onLocationChanged(Location point) {
        if (!active || point==null || (point.hasAccuracy() && point.getAccuracy()>70)) return;
        position=new Location(point);
        changed.run();
        if (record) {
            Location copy=position;
            writer.execute(() -> append(copy));
        }
    }

    private void append(Location point) {
        try (FileOutputStream output = new FileOutputStream(journal, true)) {
            JSONObject line = new JSONObject();
            line.put("time_ms", point.getTime());
            line.put("latitude", point.getLatitude());
            line.put("longitude", point.getLongitude());
            if (point.hasAccuracy()) line.put("accuracy_m", point.getAccuracy());
            if (point.hasSpeed()) line.put("speed_m_s", point.getSpeed());
            output.write((line.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            main.post(() -> { saved++; writeError = false; changed.run(); });
        } catch (IOException | JSONException failure) {
            main.post(() -> { writeError = true; changed.run(); });
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { changed.run(); }
    @Override public void onProviderDisabled(String provider) { stop(); changed.run(); }
}
