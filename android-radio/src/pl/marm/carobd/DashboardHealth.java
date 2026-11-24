package pl.marm.carobd;

import android.content.Context;
import android.net.*;
import android.os.*;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import java.io.File;

/** Lekki odczyt miejsca i wieku kolejki poza wątkiem interfejsu. */
final class DashboardHealth {
    static final long SYNC_DELAY_SECONDS=6*3600L;
    private static final long MIB=1024*1024L, RADIO_LIMIT=512*MIB, GPX_LIMIT=256*MIB;
    final boolean lowRadio,lowGpx;
    final String reminder;

    DashboardHealth(boolean lowRadio,boolean lowGpx,String reminder) {
        this.lowRadio=lowRadio; this.lowGpx=lowGpx; this.reminder=reminder;
    }
    static DashboardHealth read(Context context,Settings settings,String folder) {
        boolean lowRadio=lowSpace(freeBytes(context.getFilesDir()),RADIO_LIMIT);
        boolean lowGpx=lowSpace(freeBytes(gpxVolume(context,folder)),GPX_LIMIT);
        String reminder="";
        try (Trips trips=new Trips(context)) {
            if (settings.nasUploadEnabled() && due(System.currentTimeMillis()/1000,trips.oldestPendingAt(),settings.lastNasUpload())) {
                ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
                Network network=connectivity==null ? null : connectivity.getActiveNetwork();
                NetworkCapabilities caps=network==null ? null : connectivity.getNetworkCapabilities(network);
                if (caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    reminder="Połącz z internetem, aby wysłać przejazdy";
                else if (!settings.hasNasCredential()) reminder="Zaległe przejazdy · przypisz radio do NAS";
                else reminder="Zaległe przejazdy · sprawdź połączenie z NAS";
            }
        } catch (Exception unavailable) {
            android.util.Log.w("CarOBD","Nie można odczytać wieku kolejki",unavailable);
        }
        return new DashboardHealth(lowRadio,lowGpx,reminder);
    }
    static boolean due(long now,long pendingAt,long lastUpload) {
        return pendingAt>0 && now-Math.max(pendingAt,lastUpload)>=SYNC_DELAY_SECONDS;
    }
    static boolean lowSpace(long available,long threshold) { return available>=0 && available<threshold; }
    private static long freeBytes(File path) {
        if (path==null) return -1;
        try { return new StatFs(path.getPath()).getAvailableBytes(); }
        catch (RuntimeException unavailable) { return -1; }
    }
    private static File gpxVolume(Context context,String folder) {
        if (folder.isEmpty()) return null;
        Uri tree=Uri.parse(folder);
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        try {
            String root=DocumentsContract.getTreeDocumentId(tree).split(":",2)[0];
            if ("primary".equals(root)) return Environment.getExternalStorageDirectory();
            if (!root.matches("[0-9A-Fa-f-]{4,36}")) return null;
            StorageManager storage=context.getSystemService(StorageManager.class);
            for (StorageVolume volume:storage.getStorageVolumes()) {
                if (root.equalsIgnoreCase(volume.getUuid()) && Environment.MEDIA_MOUNTED.equals(volume.getState()))
                    return Build.VERSION.SDK_INT>=30 ? volume.getDirectory() : new File("/storage",root);
            }
        } catch (RuntimeException unavailable) { /* Niektóre nośniki SAF nie ujawniają pojemności. */ }
        return null;
    }
}
