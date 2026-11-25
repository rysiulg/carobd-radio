package pl.marm.carobd;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Preferencje lokalne. Klucz API jest szyfrowany kluczem instalacji. */
final class Settings {
    static final String DEFAULT_ENDPOINT = "https://nas.example.invalid/carOBD/api.php";
    static final int AUTO_STOP_DEFAULT=5, AUTO_STOP_MAX=30;
    private static final String AUTO_STOP="auto_stop_min";
    private static final String KEY_ALIAS = "carobd-device-api";
    static final class Credential {
        final String device,token;
        Credential(String device,String token) { this.device=device; this.token=token; }
    }
    final SharedPreferences prefs;
    final Profiles profiles;
    Settings(Context context) {
        prefs=context.getSharedPreferences("carobd",Context.MODE_PRIVATE);
        profiles=new Profiles(prefs);
    }
    String get(String name, String fallback) { return prefs.getString(name, fallback); }
    boolean proxyEnabled() { return prefs.getBoolean("obd_wifi_proxy",false); }
    void setProxyEnabled(boolean enabled) { prefs.edit().putBoolean("obd_wifi_proxy",enabled).apply(); }
    boolean recordingEnabled() { return prefs.getBoolean("record_trips",true); }
    void setRecordingEnabled(boolean enable) { prefs.edit().putBoolean("record_trips",enable).apply(); }
    boolean autoAcceleration() { return prefs.getBoolean("auto_acceleration",true); }
    void setAutoAcceleration(boolean enabled) { prefs.edit().putBoolean("auto_acceleration",enabled).apply(); }
    boolean roadAlerts() { return prefs.getBoolean("road_alerts",true); }
    void setRoadAlerts(boolean enabled) { prefs.edit().putBoolean("road_alerts",enabled).apply(); }
    boolean joinShortStops() { return prefs.getBoolean("history_join_short_stops",true); }
    void setJoinShortStops(boolean join) { prefs.edit().putBoolean("history_join_short_stops",join).apply(); }
    boolean netFuelCost() { return prefs.getBoolean("net_fuel_cost",false); }
    void setNetFuelCost(boolean net) { prefs.edit().putBoolean("net_fuel_cost",net).apply(); }
    boolean nasUploadEnabled() { return prefs.getBoolean("upload_to_nas",true); }
    void setNasUploadEnabled(boolean enable) { prefs.edit().putBoolean("upload_to_nas",enable).apply(); }
    int autoStopMinutes() { return Math.max(0,Math.min(AUTO_STOP_MAX,prefs.getInt(AUTO_STOP,AUTO_STOP_DEFAULT))); }
    void setAutoStopMinutes(int minutes) { prefs.edit().putInt(AUTO_STOP,Math.max(0,Math.min(AUTO_STOP_MAX,minutes))).apply(); }
    long lastNasUpload() { return prefs.getLong("last_nas_upload",0); }
    void markNasUpload() { prefs.edit().putLong("last_nas_upload",System.currentTimeMillis()/1000).apply(); }
    double refuelBaseline(String vehicle,long now) {
        long saved=prefs.getLong("fuel_baseline_at:"+vehicle,0);
        return saved>0 && now>=saved && now-saved<=Refuel.BASELINE_MAX_AGE
            ? prefs.getFloat("fuel_baseline:"+vehicle,Float.NaN) : Double.NaN;
    }
    void saveRefuelBaseline(String vehicle,double level,long now) {
        prefs.edit().putFloat("fuel_baseline:"+vehicle,(float)level).putLong("fuel_baseline_at:"+vehicle,now).apply();
    }
    boolean enabled(String name) { return prefs.getBoolean(name, false); }
    void set(String name, String value) { prefs.edit().putString(name, value).apply(); }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    void saveCredential(String device,String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        String sealed=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+
            Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if (!prefs.edit().putString("nas_device",device).putString("nas_token",sealed).commit())
            throw new java.io.IOException("Nie zapisano konfiguracji NAS");
    }

    private String unseal(String secret) {
        String[] parts=secret.split("\\.",2);
        if (parts.length != 2) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
        } catch (Exception error) { return ""; }
    }

    static boolean identifier(String value) { return value.matches("[A-Za-z0-9_.-]{1,80}"); }
    private boolean legacyNas() { return DEFAULT_ENDPOINT.equals(get("endpoint",DEFAULT_ENDPOINT)); }
    boolean hasNasCredential() {
        return !get("nas_token","").isEmpty() || (legacyNas() && !get("device","").isEmpty() && !get("token","").isEmpty());
    }
    Credential credential() {
        String id=get("nas_device",""), sealed=get("nas_token","");
        if (sealed.isEmpty() && legacyNas()) { id=get("device",""); sealed=get("token",""); }
        return new Credential(id,unseal(sealed));
    }
}
