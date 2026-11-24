package pl.marm.carobd;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import org.json.*;
import java.util.*;

/** SQLite jest dziennikiem awaryjnym i historią. Zamykanie przejazdu oraz kolejka są atomowe. */
final class Trips extends SQLiteOpenHelper {
    static final int CHUNK=100;
    private static final int CLOSED=1, EMPTY=2;
    static final long RESUME_WINDOW_MS=120_000L, MAX_TRIP_MS=12*60*60*1000L;
    private static final String RESUME="_resume";
    static final class Point {
        final int s, la, lo, kmh;
        Point(int s,int la,int lo,int kmh) { this.s=s; this.la=la; this.lo=lo; this.kmh=kmh; }
    }
    static final class Trip {
        final String id;
        final String vehicle;
        final long startUnix, start, previousMotionAt;
        final TripMath.Run acceleration=new TripMath.Run();
        final List<Point> pendingPoints=new ArrayList<>();
        String profileId="";
        long speedAt=-1, gpsAt=-1, fuelAt=-1, motion, saved=-1;
        double speed, rate, km, gpsKm, liters, fuelCover, drive, idle, max;
        int fuelSource, count, lastOff=-1;
        Location lastFix;
        Trip(String vehicle,long unix,long at,long previousMotionAt) {
            this("radio-"+UUID.randomUUID(),vehicle,unix,at,previousMotionAt);
        }
        private Trip(String id,String vehicle,long unix,long at,long previousMotionAt) {
            this.id=id;
            this.vehicle=vehicle; startUnix=unix; start=at; motion=at; this.previousMotionAt=previousMotionAt;
            if (previousMotionAt>=0 && at-previousMotionAt<=2500) acceleration.sample(0,previousMotionAt);
        }
        void speed(double v,long at) {
            if (!Double.isFinite(v) || v<0 || v>255) return;
            if (speedAt>=0 && at>speedAt && at-speedAt<=6000) {
                long gap=at-speedAt;
                km+=TripMath.trapezoid(speed,v,gap);
                if (speed>2 || v>2) drive+=gap/1000.0; else idle+=gap/1000.0;
            }
            if (v>2) motion=at;
            max=Math.max(max,v); speed=v; speedAt=at;
            acceleration.sample(v,at);
        }
        void fuel(double v,int source,long at) {
            if (!Double.isFinite(v)) { fuelAt=-1; return; }
            if (fuelAt>=0 && at>fuelAt && at-fuelAt<=6500) {
                long gap=at-fuelAt; liters+=TripMath.trapezoid(rate,v,gap); fuelCover+=gap/1000.0;
            }
            rate=v; fuelAt=at; fuelSource=source;
        }
        Point location(Location fix,long at) {
            int off=(int)Math.max(0,(at-start)/1000);
            if (lastFix!=null) {
                long gap=at-gpsAt; float meters=lastFix.distanceTo(fix);
                if (gap<1000 || (gap<=30000 && meters>50+gap*0.08)) return null;
                if (gap<=30000 && (meters>Math.max(5,fix.getAccuracy()*0.45) || (fix.hasSpeed() && fix.getSpeed()>2)))
                    gpsKm+=meters/1000.0;
            }
            lastFix=fix; gpsAt=at;
            if (off<=lastOff || (lastOff>=0 && off-lastOff<3)) return null;
            lastOff=off; count++;
            double v=fix.hasSpeed() ? fix.getSpeed()*3.6 : at-speedAt<6500 && speedAt>=0 ? speed : 0;
            return new Point(off,(int)Math.round(fix.getLatitude()*1e5),(int)Math.round(fix.getLongitude()*1e5),
                (int)Math.round(Math.max(0,Math.min(400,v))));
        }
        JSONObject summary(long at) throws JSONException {
            double duration=Math.max(1,(at-start)/1000.0), distance=distance();
            JSONArray thresholds=new JSONArray();
            for (double v : acceleration.times) thresholds.put(Double.isFinite(v) ? v : JSONObject.NULL);
            JSONObject data=new JSONObject().put("id",id).put("vehicle",vehicle).put("type","trip").put("t_start",startUnix)
                .put("t_end",startUnix+Math.round(duration)).put("dur_s",duration).put("drive_s",drive).put("idle_s",idle)
                .put("km",distance).put("gps_km",gpsKm).put("track_pts",count).put("max_kmh",max)
                .put("avg_kmh",distance*3600/duration).put("fuel_cover",Math.min(1,fuelCover/duration))
                .put("accel_s",thresholds).put("final",1);
            if (fuelCover>0) data.put("fuel_l",liters).put("fuel_src",fuelSource);
            if (distance>=0.1 && fuelCover>=Math.max(2,duration*0.60)) data.put("l100",Math.min(100,100*liters/distance));
            return data;
        }
        double distance() { return drive>0 && km>=gpsKm*0.8 ? km : gpsKm; }
    }
    Trips(Context c) { super(c,"trips.db",null,3); setWriteAheadLoggingEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips(id TEXT PRIMARY KEY, started INTEGER NOT NULL, info TEXT NOT NULL, closed INTEGER NOT NULL, exported TEXT NOT NULL DEFAULT '', vehicle TEXT NOT NULL DEFAULT '', remote INTEGER NOT NULL DEFAULT 0, expected INTEGER NOT NULL DEFAULT 0, follow INTEGER NOT NULL DEFAULT -1, retry_after INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE points(trip TEXT, off INTEGER, la INTEGER, lo INTEGER, kmh INTEGER, PRIMARY KEY(trip,off)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE outbox(n INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,payload TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if (oldVersion<2) upgradeProfiles(db);
        if (oldVersion<3) archiveEmptyTrips(db);
    }
    private void upgradeProfiles(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE trips ADD COLUMN vehicle TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE trips ADD COLUMN remote INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE trips ADD COLUMN expected INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE trips ADD COLUMN follow INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE trips ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0");
        try (Cursor c=db.rawQuery("SELECT id,info FROM trips",null)) {
            while (c.moveToNext()) {
                try {
                    String vehicle=new JSONObject(c.getString(1)).optString("vehicle","kia-radio");
                    db.execSQL("UPDATE trips SET vehicle=? WHERE id=?",new Object[]{vehicle,c.getString(0)});
                } catch (JSONException bad) { throw new IllegalStateException("Nie można odczytać poprzedniej historii",bad); }
            }
        }
    }

    private static boolean emptyTrip(JSONObject trip) {
        return trip.optDouble("km")==0 && !(trip.optDouble("gps_km")>0);
    }
    /** Zamknięte zapisy 0 km zachowuje w lokalnej bazie jako archiwum poza historią i eksportem. */
    private void archiveEmptyTrips(SQLiteDatabase db) {
        List<String> ids=new ArrayList<>();
        try (Cursor c=db.rawQuery("SELECT id,info FROM trips WHERE closed=1",null)) {
            while (c.moveToNext()) {
                try { if (emptyTrip(new JSONObject(c.getString(1)))) ids.add(c.getString(0)); }
                catch (JSONException ignored) { /* Nie zmieniaj rekordu bez poprawnego podsumowania. */ }
            }
        }
        for (String id:ids) {
            db.execSQL("UPDATE trips SET closed=? WHERE id=? AND closed=1",new Object[]{EMPTY,id});
            if (Settings.identifier(id)) db.delete("outbox","id=? OR id GLOB ?",new String[]{id,id+"-g[0-9]*"});
            else db.delete("outbox","id=?",new String[]{id});
        }
        android.util.Log.i("CarOBD","Historia: zarchiwizowano "+ids.size()+" wpisów 0 km");
    }

    void save(Trip trip,long at,Point point,int boot,String profileId) throws JSONException {
        if (point!=null) trip.pendingPoints.add(point);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject resume=new JSONObject().put("boot",boot).put("start",trip.start).put("at",at)
                .put("profile",profileId).put("obd_km",trip.km).put("fuel_s",trip.fuelCover).put("motion",trip.motion);
            JSONObject json=trip.summary(at).put(RESUME,resume);
            db.execSQL("INSERT OR IGNORE INTO trips(id,started,info,closed,vehicle) VALUES(?,?,?,0,?)",
                new Object[]{trip.id,trip.startUnix,json.toString(),trip.vehicle});
            db.execSQL("UPDATE trips SET info=? WHERE id=? AND closed=0",new Object[]{json.toString(),trip.id});
            for (Point pending:trip.pendingPoints) db.execSQL("INSERT OR IGNORE INTO points VALUES(?,?,?,?,?)",
                new Object[]{trip.id,pending.s,pending.la,pending.lo,pending.kmh});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Wyjątek pozostawia bufor do następnej próby, a klucz (trip, off) zapobiega duplikatom.
        trip.pendingPoints.clear();
    }
    void closeTrip(JSONObject info,boolean recovered) throws JSONException {
        String id=info.getString("id");
        info.remove(RESUME);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor=db.rawQuery("SELECT closed FROM trips WHERE id=?",new String[]{id})) {
                if (!cursor.moveToFirst() || cursor.getInt(0)!=0) { db.setTransactionSuccessful(); return; }
            }
            List<Point> points=points(id);
            info.put("track_pts",points.size()).put("recovered",recovered ? 1 : 0);
            boolean empty=emptyTrip(info);
            if (!empty) {
                enqueue(db,info);
                for (JSONObject chunk : trackRecords(info,points)) enqueue(db,chunk);
            }
            db.execSQL("UPDATE trips SET closed=?,info=? WHERE id=?",new Object[]{empty ? EMPTY : CLOSED,info.toString(),id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    Trip recover(Profiles.Profile requested,List<Profiles.Profile> profiles,boolean explicitProfile,boolean allowResume,
                 long now,long wall,int boot) throws JSONException {
        List<JSONObject> unfinished=new ArrayList<>();
        try (Cursor rows=getReadableDatabase().rawQuery("SELECT info FROM trips WHERE closed=0 ORDER BY started DESC,id DESC",null)) {
            while (rows.moveToNext()) unfinished.add(new JSONObject(rows.getString(0)));
        }
        Trip resumed=null;
        for (JSONObject item:unfinished) {
            String vehicle=item.getString("vehicle");
            JSONObject saved=item.optJSONObject(RESUME);
            boolean matches=requested.vehicle.equals(vehicle);
            if (!matches && !explicitProfile && saved!=null) for (Profiles.Profile candidate:profiles) {
                if (candidate.id.equals(saved.optString("profile")) && candidate.vehicle.equals(vehicle)) {
                    matches=true; break;
                }
            }
            if (allowResume && resumed==null && matches) {
                int lastOff=-1;
                try (Cursor row=getReadableDatabase().rawQuery("SELECT MAX(off) FROM points WHERE trip=?",
                        new String[]{item.getString("id")})) {
                    if (row.moveToFirst() && !row.isNull(0)) lastOff=row.getInt(0);
                }
                resumed=restore(item,now,wall,boot,lastOff);
                if (resumed!=null) continue;
            }
            closeTrip(item,true);
        }
        return resumed;
    }
    static Trip restore(JSONObject item,long now,long wall,int boot,int lastOff) throws JSONException {
        long unix=item.getLong("t_start"), end=item.getLong("t_end");
        JSONObject resume=item.optJSONObject(RESUME);
        long start,savedAt;
        if (resume!=null && boot>=0 && resume.optInt("boot",-1)>=0) {
            if (boot!=resume.optInt("boot")) return null;
            start=resume.optLong("start",-1); savedAt=resume.optLong("at",-1);
        } else {
            // Aktualizacja ze starszego APK ma tylko czasy UTC. Początek musi przypadać po rozruchu urządzenia.
            start=now-(wall-unix*1000L); savedAt=now-(wall-end*1000L);
        }
        if (start<=0 || savedAt<start || savedAt>now+1000 || now-savedAt>RESUME_WINDOW_MS || now-start>=MAX_TRIP_MS)
            return null;
        Trip trip=new Trip(item.getString("id"),item.getString("vehicle"),unix,start,-1);
        double duration=item.optDouble("dur_s",0);
        trip.saved=Math.min(now,savedAt);
        trip.motion=resume==null ? trip.saved : resume.optLong("motion",trip.saved);
        trip.profileId=resume==null ? "" : resume.optString("profile");
        trip.gpsKm=item.optDouble("gps_km",0);
        trip.drive=item.optDouble("drive_s",0); trip.idle=item.optDouble("idle_s",0);
        double selected=item.optDouble("km",0);
        trip.km=resume==null ? (trip.drive>0 && selected!=trip.gpsKm ? selected : 0) : resume.optDouble("obd_km",0);
        trip.liters=item.optDouble("fuel_l",0); trip.fuelSource=item.optInt("fuel_src",0);
        trip.fuelCover=resume==null ? item.optDouble("fuel_cover",0)*duration : resume.optDouble("fuel_s",0);
        trip.max=item.optDouble("max_kmh",0); trip.count=item.optInt("track_pts",0); trip.lastOff=lastOff;
        JSONArray thresholds=item.optJSONArray("accel_s");
        if (thresholds!=null) for (int i=0;i<trip.acceleration.times.length;i++)
            trip.acceleration.times[i]=thresholds.optDouble(i,Double.NaN);
        return trip;
    }
    static List<JSONObject> trackRecords(JSONObject info,List<Point> points) throws JSONException {
        ArrayList<JSONObject> records=new ArrayList<>();
        String trip=info.getString("id"), vehicle=info.getString("vehicle");
        for (int i=0;i<points.size();i+=CHUNK) {
            int seq=i/CHUNK+1, lat=0, lon=0;
            StringBuilder encoded=new StringBuilder();
            for (int j=i;j<Math.min(points.size(),i+CHUNK);j++) {
                Point p=points.get(j);
                if (j>i) encoded.append(';');
                encoded.append(p.la-lat).append(',').append(p.lo-lon).append(',').append(p.s).append(',').append(p.kmh);
                lat=p.la; lon=p.lo;
            }
            records.add(new JSONObject().put("type","track").put("id",trip+"-g"+seq).put("vehicle",vehicle)
                .put("trip",trip).put("seq",seq).put("p",encoded.toString()));
        }
        return records;
    }
    private void enqueue(SQLiteDatabase db,JSONObject item) throws JSONException {
        db.execSQL("INSERT OR IGNORE INTO outbox(id,payload) VALUES(?,?)",new Object[]{item.getString("id"),item.toString()});
    }
    // Profile Kii współdzielą historię, także dla rekordów zapisanych przed zmianą nazwy profilu.
    private static String historyScope(String vehicle) {
        return EngineHours.kiaProfile(vehicle)
            ? "vehicle COLLATE NOCASE IN (?, 'kia-radio', '"+EngineHours.KIA_ID+"')" : "vehicle=?";
    }
    List<JSONObject> history(String vehicle,int offset) {
        List<JSONObject> rows=new ArrayList<>();
        String sql="SELECT info FROM trips WHERE closed=1 AND "+historyScope(vehicle)+" ORDER BY started DESC,id DESC LIMIT 60 OFFSET "+Math.max(0,offset);
        try (Cursor c=getReadableDatabase().rawQuery(sql,new String[]{vehicle})) {
            while (c.moveToNext()) rows.add(new JSONObject(c.getString(0)));
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return rows;
    }
    static final class RouteJob {
        final String id;
        final int after;
        RouteJob(String id,int after) { this.id=id; this.after=after; }
    }
    void importHistory(String vehicle,JSONArray items) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();
        String scope=historyScope(vehicle);
        db.beginTransaction();
        try {
            for (int i=0;i<items.length();i++) {
                JSONObject row=items.getJSONObject(i);
                String id=row.getString("id");
                if (!Settings.identifier(id) || !vehicle.equals(row.getString("vehicle"))) throw new JSONException("Niezgodna historia auta");
                int expected=Math.max(0,row.optInt("track_pts"));
                int closed=emptyTrip(row) ? EMPTY : CLOSED;
                db.execSQL("INSERT OR IGNORE INTO trips(id,started,info,closed,vehicle,remote,expected) VALUES(?,?,?,?,?,1,?)",
                    new Object[]{id,row.optLong("t_start"),row.toString(),closed,vehicle,expected});
                db.execSQL("UPDATE trips SET info=?,expected=MAX(expected,?),closed=? WHERE id=? AND "+scope+" AND remote=1",
                    new Object[]{row.toString(),expected,closed,id,vehicle});
                mergeNasMetrics(db,vehicle,scope,id,row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void mergeNasMetrics(SQLiteDatabase db,String vehicle,String scope,String id,JSONObject server) throws JSONException {
        if (!server.has("cost_pln") && !server.has("odo_end")) return;
        JSONObject local;
        try (Cursor row=db.rawQuery("SELECT info FROM trips WHERE id=? AND "+scope+" AND closed=1 AND remote=0",new String[]{id,vehicle})) {
            if (!row.moveToFirst()) return;
            local=new JSONObject(row.getString(0));
        }
        for (String field:new String[]{"cost_pln","odo_end","odo_src"}) if (server.has(field)) local.put(field,server.get(field));
        db.execSQL("UPDATE trips SET info=? WHERE id=? AND "+scope+" AND closed=1 AND remote=0",
            new Object[]{local.toString(),id,vehicle});
    }
    RouteJob routeToDownload(String vehicle,long now) {
        String sql="SELECT id,follow FROM trips t WHERE "+historyScope(vehicle)+" AND t.remote=1 AND t.closed=1 AND t.retry_after<=? AND t.expected>"
            +"(SELECT count(*) FROM points p WHERE p.trip=t.id) ORDER BY t.started DESC LIMIT 1";
        try (Cursor c=getReadableDatabase().rawQuery(sql,new String[]{vehicle,Long.toString(now)})) {
            return c.moveToFirst() ? new RouteJob(c.getString(0),c.getInt(1)) : null;
        }
    }
    void importPoints(RouteJob trip,JSONArray items,boolean more,long retryAt) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            int last=trip.after;
            for (int i=0;i<items.length();i++) {
                JSONArray p=items.getJSONArray(i);
                int off=p.getInt(0),la=p.getInt(1),lo=p.getInt(2),kmh=p.optInt(3,-1);
                if (off<=last || Math.abs((long)la)>9000000 || Math.abs((long)lo)>18000000)
                    throw new JSONException("Nieprawidłowy punkt trasy");
                db.execSQL("INSERT OR IGNORE INTO points VALUES(?,?,?,?,?)",new Object[]{trip.id,off,la,lo,kmh});
                last=off;
            }
            if (more && last==trip.after) throw new JSONException("Nie przewinięto pozycji trasy");
            db.execSQL("UPDATE trips SET follow=?,retry_after=? WHERE id=? AND remote=1",
                new Object[]{more ? last : -1,more ? 0 : retryAt,trip.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void delayRoute(String id,long until) {
        getWritableDatabase().execSQL("UPDATE trips SET retry_after=?,follow=-1 WHERE id=? AND remote=1",new Object[]{until,id});
    }
    JSONObject unexported(String uri) throws JSONException {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT info FROM trips WHERE closed=1 AND remote=0 AND exported<>? ORDER BY started LIMIT 1",new String[]{uri})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }
    void exported(String id,String uri) { getWritableDatabase().execSQL("UPDATE trips SET exported=? WHERE id=?",new Object[]{uri,id}); }
    List<Point> points(String id) {
        ArrayList<Point> result=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT off,la,lo,kmh FROM points WHERE trip=? ORDER BY off",new String[]{id})) {
            while (c.moveToNext()) result.add(new Point(c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3)));
        }
        return result;
    }
    JSONObject next() throws JSONException {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM outbox ORDER BY n LIMIT 1",null)) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }
    long oldestPendingAt() throws JSONException {
        JSONObject head=next();
        if (head==null) return 0;
        long time=head.optLong("t_end",0);
        if (time>0) return time;
        // Gdy NAS przyjął podsumowanie, wiek oczekującego fragmentu śladu wynika z jego przejazdu.
        String trip=head.optString("trip");
        if (trip.isEmpty()) return 0;
        try (Cursor c=getReadableDatabase().rawQuery("SELECT info FROM trips WHERE id=?",new String[]{trip})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)).optLong("t_end",0) : 0;
        }
    }
    int pending() {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM outbox",null)) { c.moveToFirst(); return c.getInt(0); }
    }
    void ack(String id) { getWritableDatabase().delete("outbox","id=?",new String[]{id}); }
}
