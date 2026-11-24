package pl.radiokia.fm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.LinkedHashSet;

/** Udostępnia zapisane presety wyłącznie zaufanemu pulpitowi RadioKIA. */
public final class PresetData extends ContentProvider {
    private static final String DASHBOARD = "pl.radiokia.preview";

    static LinkedHashSet<Integer> read(Context context) {
        LinkedHashSet<Integer> presets = new LinkedHashSet<>();
        String saved = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).getString(Config.PRESETS, "");
        for (String value : saved.split(",")) {
            try {
                int frequency = Integer.parseInt(value);
                if (frequency >= Config.FM_MIN && frequency <= Config.FM_MAX) presets.add(frequency);
            } catch (NumberFormatException ignored) { /* Pusty lub uszkodzony wpis. */ }
        }
        return presets;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"presets".equals(method) || !trusted()) throw new SecurityException("Tylko pulpit RadioKIA");
        LinkedHashSet<Integer> presets = read(getContext());
        int[] channels = new int[Math.min(presets.size(), Config.MAX_PRESETS)];
        int index = 0;
        for (int channel : presets) {
            if (index == channels.length) break;
            channels[index++] = channel;
        }
        Bundle result = new Bundle();
        result.putIntArray("channels", channels);
        return result;
    }

    private boolean trusted() {
        return DASHBOARD.equals(getCallingPackage())
            && getContext().getPackageManager().checkSignatures(getContext().getPackageName(), DASHBOARD)
                == PackageManager.SIGNATURE_MATCH;
    }

    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
