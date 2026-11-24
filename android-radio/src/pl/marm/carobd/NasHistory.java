package pl.marm.carobd;

import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class NasHistory {
    static volatile String status="";
    static volatile long revision;
    private static final long INTERVAL=300000;
    private static final int HISTORY_CONTRACT=4;

    private static String telemetryKey(String vehicle) {
        return "nas_telemetry_"+(EngineHours.kiaProfile(vehicle) ? "kia-radio" : vehicle);
    }
    static JSONObject telemetry(Settings settings,String vehicle) {
        try { return new JSONObject(settings.get(telemetryKey(vehicle),"{}")); }
        catch (JSONException invalid) { return new JSONObject(); }
    }
    static void pullTelemetry(Settings settings,Settings.Credential auth,String vehicle) throws Exception {
        String key=telemetryKey(vehicle);
        long now=System.currentTimeMillis(), last=settings.prefs.getLong(key+"_at",0);
        if (last<=now && now-last<INTERVAL) return;
        settings.prefs.edit().putLong(key+"_at",now).apply();
        JSONObject reply=get(auth,vehicle,"&view=telemetry");
        if (reply!=null && vehicle.equals(reply.optString("vehicle")) && reply.has("engine_hours") && reply.has("range"))
            settings.set(key,reply.toString());
    }

    static void request(Settings settings,String vehicle) {
        settings.prefs.edit().putString("history_vehicle",vehicle).remove("history_at_"+vehicle).apply();
    }
    static void refreshHistory(Settings settings,String vehicle) {
        String key="history_"+vehicle+"_requested";
        settings.prefs.edit().putLong(key,settings.prefs.getLong(key,0)+1).apply();
    }
    static synchronized boolean pull(Trips db,Settings settings,Settings.Credential auth) throws Exception {
        String vehicle=settings.get("history_vehicle",settings.profiles.defaultProfile().vehicle);
        SharedPreferences prefs=settings.prefs;
        String key="history_"+vehicle, checked="history_at_"+vehicle;
        long now=System.currentTimeMillis();
        long requested=prefs.getLong(key+"_requested",0);
        boolean replay=prefs.getInt(key+"_contract",0)!=HISTORY_CONTRACT || prefs.getLong(key+"_applied",-1)!=requested;
        if (!replay && now-prefs.getLong(checked,0)<INTERVAL) return false;
        long since=replay ? 0 : prefs.getLong(key+"_from",0);
        String last=replay ? "" : prefs.getString(key+"_last","");
        long newest=since;
        boolean more=true;
        try {
            for (int page=0;page<5 && more;page++) {
                JSONObject reply=get(auth,vehicle,"&received="+since+"&last="+escape(last));
                if (reply==null) { status="NAS • historia auta nieprzypisana"; return false; }
                if (!vehicle.equals(reply.getString("vehicle"))) throw new IOException("Inny samochód w historii");
                JSONArray records=reply.getJSONArray("trips"); more=reply.getBoolean("more");
                if (more && records.length()==0) throw new IOException("Pusta strona historii");
                db.importHistory(vehicle,records);
                if (records.length()>0) {
                    JSONObject row=records.getJSONObject(records.length()-1);
                    since=row.getLong("received"); last=row.getString("id"); newest=Math.max(newest,since);
                    prefs.edit().putLong(key+"_from",since).putString(key+"_last",last)
                        .putInt(key+"_contract",HISTORY_CONTRACT).putLong(key+"_applied",requested).apply();
                    revision++;
                }
            }
            int pages=0;
            for (;pages<20;pages++) {
                Trips.RouteJob route=db.routeToDownload(vehicle,now); if (route==null) break;
                JSONObject reply;
                try { reply=get(auth,vehicle,"&trip="+escape(route.id)+"&after="+route.after); }
                catch (FileNotFoundException gone) { db.delayRoute(route.id,now+INTERVAL); continue; }
                if (reply==null || !route.id.equals(reply.getString("trip"))) throw new IOException("Niezgodna trasa");
                db.importPoints(route,reply.getJSONArray("points"),reply.getBoolean("more"),now+INTERVAL);
                revision++;
            }
            boolean again=more || pages==20;
            if (!again) prefs.edit().putLong(checked,now).putLong(key+"_from",Math.max(0,newest-2)).remove(key+"_last")
                .putInt(key+"_contract",HISTORY_CONTRACT).putLong(key+"_applied",requested).apply();
            status=again ? "NAS • pobieranie historii" : "NAS • historia aktualna";
            return again;
        } catch (Exception error) {
            status="NAS • historia czeka na połączenie";
            throw error;
        }
    }
    static JSONObject lastService(Settings.Credential auth,String vehicle) throws Exception {
        JSONObject reply=get(auth,vehicle,"&view=service");
        if (reply==null) throw new IOException("Brak przypisania auta w NAS");
        if (!vehicle.equals(reply.optString("vehicle")) || !reply.has("service"))
            throw new IOException("Niepełna odpowiedź serwisu NAS");
        return reply.optJSONObject("service");
    }
    private static JSONObject get(Settings.Credential auth,String vehicle,String query) throws Exception {
        URL base=new URL(Settings.DEFAULT_ENDPOINT);
        URL url=new URL(base,"radio_history.php?vehicle="+escape(vehicle)+query);
        HttpURLConnection request=(HttpURLConnection)url.openConnection();
        try {
            request.setInstanceFollowRedirects(false); request.setConnectTimeout(6500); request.setReadTimeout(7500);
            request.setRequestProperty("X-API-Key",auth.token);
            int code=request.getResponseCode();
            if (code==403) return null;
            if (code==404 && query.contains("&trip=")) throw new FileNotFoundException("Usunięta trasa");
            if (code!=200) throw new IOException("HTTP "+code);
            JSONObject response=Maintenance.readJson(request,196608);
            if (!Boolean.TRUE.equals(response.opt("ok"))) throw new IOException("Niepełna odpowiedź NAS");
            return response;
        } finally { request.disconnect(); }
    }
    private static String escape(String value) throws Exception { return URLEncoder.encode(value,StandardCharsets.UTF_8.name()); }
}
