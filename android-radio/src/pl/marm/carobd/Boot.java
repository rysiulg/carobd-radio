package pl.marm.carobd;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Wznawia rejestrator GPS/OBD po starcie TS7 i po aktualizacji aplikacji. */
public final class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        String action=intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Maintenance.schedule(context);
        resumeRecorder(context);
    }
    static void resumeRecorder(Context context) {
        if (Build.VERSION.SDK_INT<=28 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
            try { context.startForegroundService(new Intent(context,Recorder.class)); }
            catch (IllegalStateException | SecurityException denied) {
                android.util.Log.w("CarOBD","Nie udało się wznowić rejestratora",denied);
            }
        }
    }
}
