package pl.marm.carobd;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Jedna lista profili oraz dokładnie jeden domyślny, zapisywane w tej samej transakcji preferencji. */
final class Profiles {
    private static final String LIST="adapter_profiles", DEFAULT="default_profile", FIRST="legacy-default";
    private static final Object LOCK=new Object();
    static final class Profile {
        static final String WIFI="Wi-Fi";
        final String id,name,vehicle,adapter,adapterName,transport,fuel;
        Profile(String id,String name,String vehicle,String adapter,String adapterName,String transport,String fuel) {
            this.id=id; this.name=name; this.vehicle=vehicle; this.adapter=adapter; this.adapterName=adapterName;
            this.transport=transport; this.fuel=fuel;
        }
        Profile(JSONObject row) throws JSONException {
            this(row.getString("id"),row.getString("name"),row.getString("vehicle"),row.optString("adapter"),
                row.optString("adapterName"),row.optString("transport","BLE"),row.optString("fuel","diesel"));
        }
        Profile withAdapter(String address,String label,String mode) {
            return new Profile(id,name,vehicle,address,label,mode,fuel);
        }
        boolean isWifi() { return WIFI.equals(transport); }
        JSONObject encode() throws JSONException {
            return new JSONObject().put("id",id).put("name",name).put("vehicle",vehicle).put("adapter",adapter)
                .put("adapterName",adapterName).put("transport",transport).put("fuel",fuel);
        }
    }

    private final SharedPreferences prefs;
    Profiles(SharedPreferences prefs) { this.prefs=prefs; }

    private List<Profile> read() {
        ArrayList<Profile> rows=new ArrayList<>();
        String data=prefs.getString(LIST,"");
        if (!data.isEmpty()) {
            try {
                JSONArray array=new JSONArray(data);
                for (int i=0;i<array.length();i++) rows.add(new Profile(array.getJSONObject(i)));
            } catch (JSONException bad) { throw new IllegalStateException("Nie można odczytać zapisanych profili",bad); }
        }
        if (rows.isEmpty()) {
            rows.add(new Profile(FIRST,"Kia",prefs.getString("vehicle","kia-radio"),prefs.getString("adapter",""),
                prefs.getString("adapterName",""),prefs.getString("transport","BLE"),prefs.getString("fuel","diesel")));
            write(rows,FIRST);
        }
        return rows;
    }

    private void write(List<Profile> rows,String defaultId) {
        profileEdit(rows,defaultId).apply();
    }
    private SharedPreferences.Editor profileEdit(List<Profile> rows,String defaultId) {
        JSONArray data=new JSONArray();
        try { for (Profile row:rows) data.put(row.encode()); }
        catch (JSONException bad) { throw new IllegalArgumentException(bad); }
        return prefs.edit().putString(LIST,data.toString()).putString(DEFAULT,defaultId);
    }

    private int index(List<Profile> rows,String id) {
        for (int i=0;i<rows.size();i++) if (rows.get(i).id.equals(id)) return i;
        return -1;
    }
    private String defaultId(List<Profile> rows) {
        String id=prefs.getString(DEFAULT,FIRST);
        return index(rows,id)<0 ? rows.get(0).id : id;
    }
    List<Profile> list() { synchronized (LOCK) { return read(); } }
    Profile find(String id) {
        synchronized (LOCK) {
            List<Profile> rows=read(); int i=index(rows,id);
            return i<0 ? rows.get(index(rows,defaultId(rows))) : rows.get(i);
        }
    }
    Profile defaultProfile() { return find(null); }

    Profile create() {
        synchronized (LOCK) {
            List<Profile> rows=read();
            String id=UUID.randomUUID().toString();
            Profile row=new Profile(id,uniqueName(rows,"Nowy profil"),"auto-"+id.substring(0,8),"","","BLE","diesel");
            rows.add(row); write(rows,defaultId(rows)); return row;
        }
    }
    Profile duplicate(Profile source) {
        synchronized (LOCK) {
            List<Profile> rows=read();
            Profile row=new Profile(UUID.randomUUID().toString(),uniqueName(rows,source.name+" (kopia)"),
                source.vehicle,source.adapter,source.adapterName,source.transport,source.fuel);
            rows.add(row); write(rows,defaultId(rows)); return row;
        }
    }
    private static String uniqueName(List<Profile> rows,String base) {
        String name=base; int n=2;
        while (containsName(rows,name)) name=base+" "+n++;
        return name;
    }
    private static boolean containsName(List<Profile> rows,String name) {
        for (Profile row:rows) if (row.name.equalsIgnoreCase(name)) return true;
        return false;
    }
    boolean save(Profile edit,boolean makeDefault) {
        synchronized (LOCK) {
            List<Profile> rows=read(); int pos=index(rows,edit.id);
            if (pos<0) throw new IllegalArgumentException("Nieznany profil");
            rows.set(pos,edit);
            return profileEdit(rows,makeDefault ? edit.id : defaultId(rows)).commit();
        }
    }
}
