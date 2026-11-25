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
    static final int ACCEL_HISTORY_LIMIT=10;
    private static final int CLOSED=1, EMPTY=2;
    static final long MAX_TRIP_MS=12*60*60*1000L;
    private static final String RESUME="_resume";
    static final class Point {
        final int s, la, lo, kmh;
        final double altitude;
        Point(int s,int la,int lo,int kmh,double altitude) {
            this.s=s; this.la=la; this.lo=lo; this.kmh=kmh;
            this.altitude=altitude>=-1500 && altitude<=11000 ? altitude : Double.NaN;
        }
    }
    static final class Acceleration {
        final long atMs;
        final double seconds;
        final boolean archived;
        Acceleration(long atMs,double seconds,boolean archived) { this.atMs=atMs; this.seconds=seconds; this.archived=archived; }
    }
    static final String AMBIENT_C="ambient_c", PRESSURE_HPA="pressure_hpa";
    static final class Trip {
        final String id;
        final String vehicle;
        final long startUnix, start;
        final double[] acceleration=new double[TripMath.SPLITS];
        final List<List<Acceleration>> runs=new ArrayList<>();
        final List<Point> pendingPoints=new ArrayList<>();
        String profileId="";
        long speedAt=-1, gpsAt=-1, fuelAt=-1, motion, saved=-1;
        double speed, rate, km, gpsKm, liters, fuelCover, drive, idle, max;
        double ambientC=Double.NaN, pressureHpa=Double.NaN;
        int fuelSource, count, lastOff=-1;
        Location lastFix;
        Trip(String vehicle,long unix,long at) {
            this("radio-"+UUID.randomUUID(),vehicle,unix,at);
        }
        private Trip(String id,String vehicle,long unix,long at) {
            this.id=id;
            this.vehicle=vehicle; startUnix=unix; start=at; motion=at;
            Arrays.fill(acceleration,Double.NaN);
            for (int i=0;i<TripMath.SPLITS;i++) runs.add(new ArrayList<>());
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
        }
        void accelerationSplit(int split,long launch,double seconds) {
            List<Acceleration> recent=runs.get(split);
            if (!Double.isFinite(acceleration[split]) || seconds<acceleration[split]) acceleration[split]=seconds;
            if (recent.size()==ACCEL_HISTORY_LIMIT) recent.remove(0);
            recent.add(new Acceleration(startUnix*1000+launch-start,seconds,false));
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
                (int)Math.round(Math.max(0,Math.min(400,v))), fix.hasAltitude() ? fix.getAltitude() : Double.NaN);
        }
        void recordConditions(double temperature,double pressure) {
            if (Double.isFinite(temperature) && temperature>=-50 && temperature<=60) ambientC=temperature;
            if (Double.isFinite(pressure) && pressure>=300 && pressure<=1150) pressureHpa=pressure;
        }
        JSONObject summary(long at) throws JSONException {
            double duration=Math.max(1,(at-start)/1000.0), distance=distance();
            JSONArray thresholds=new JSONArray(), attempts=new JSONArray();
            JSONArray rolling=new JSONArray(), rollingAttempts=new JSONArray();
            boolean hasAttempts=false, hasRollingAttempts=false, hasRollingTime=false;
            for (int i=0;i<TripMath.SPLITS;i++) {
                boolean fromStop=i<TripMath.SERIES_SPLITS;
                double v=acceleration[i]; (fromStop ? thresholds : rolling).put(Double.isFinite(v) ? v : JSONObject.NULL);
                hasRollingTime|=!fromStop && Double.isFinite(v);
                JSONArray split=new JSONArray();
                for (Acceleration run:runs.get(i)) split.put(new JSONArray().put(run.atMs).put(run.seconds));
                if (fromStop) { attempts.put(split); hasAttempts|=split.length()>0; }
                else { rollingAttempts.put(split); hasRollingAttempts|=split.length()>0; }
            }
            JSONObject data=new JSONObject().put("id",id).put("vehicle",vehicle).put("type","trip").put("t_start",startUnix)
                .put("t_end",startUnix+Math.round(duration)).put("dur_s",duration).put("drive_s",drive).put("idle_s",idle)
                .put("km",distance).put("gps_km",gpsKm).put("track_pts",count).put("max_kmh",max)
                .put("avg_kmh",distance*3600/duration).put("fuel_cover",Math.min(1,fuelCover/duration))
                .put("accel_s",thresholds).put("final",1);
            if (Double.isFinite(ambientC)) data.put(AMBIENT_C,ambientC);
            if (Double.isFinite(pressureHpa)) data.put(PRESSURE_HPA,pressureHpa);
            if (hasAttempts) data.put("accel_runs",attempts);
            if (hasRollingTime) data.put("accel_100_s",rolling);
            if (hasRollingAttempts) data.put("accel_100_runs",rollingAttempts);
            if (fuelCover>0) data.put("fuel_l",liters).put("fuel_src",fuelSource);
            if (distance>=0.1 && fuelCover>=Math.max(2,duration*0.60)) data.put("l100",Math.min(100,100*liters/distance));
            return data;
        }
        double distance() { return drive>0 && km>=gpsKm*0.8 ? km : gpsKm; }
    }
    Trips(Context c) { super(c,"trips.db",null,5); setWriteAheadLoggingEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips(id TEXT PRIMARY KEY, started INTEGER NOT NULL, info TEXT NOT NULL, closed INTEGER NOT NULL, exported TEXT NOT NULL DEFAULT '', vehicle TEXT NOT NULL DEFAULT '', remote INTEGER NOT NULL DEFAULT 0, expected INTEGER NOT NULL DEFAULT 0, follow INTEGER NOT NULL DEFAULT -1, retry_after INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE points(trip TEXT, off INTEGER, la INTEGER, lo INTEGER, kmh INTEGER, altitude REAL, PRIMARY KEY(trip,off)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE outbox(n INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,payload TEXT)");
        createInstrumentHistory(db);
    }
    private static void createInstrumentHistory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE instrument_history(vehicle TEXT NOT NULL,metric INTEGER NOT NULL,slot INTEGER NOT NULL,"
            +"sampled INTEGER NOT NULL,value REAL NOT NULL,PRIMARY KEY(vehicle,metric,slot)) WITHOUT ROWID");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if (oldVersion<2) upgradeProfiles(db);
        if (oldVersion<3) archiveEmptyTrips(db);
        if (oldVersion<4) createInstrumentHistory(db);
        if (oldVersion<5) db.execSQL("ALTER TABLE points ADD COLUMN altitude REAL");
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
            for (Point pending:trip.pendingPoints) db.execSQL("INSERT OR IGNORE INTO points(trip,off,la,lo,kmh,altitude) VALUES(?,?,?,?,?,?)",
                new Object[]{trip.id,pending.s,pending.la,pending.lo,pending.kmh,Double.isFinite(pending.altitude) ? pending.altitude : null});
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
                // Indywidualne pomiary zostają lokalnie, NAS nadal otrzymuje dotychczasowe minima trasy.
                JSONObject summary=new JSONObject(info.toString()); summary.remove("accel_runs");
                enqueue(db,summary);
                for (JSONObject chunk : trackRecords(info,points)) enqueue(db,chunk);
            }
            db.execSQL("UPDATE trips SET closed=?,info=? WHERE id=?",new Object[]{empty ? EMPTY : CLOSED,info.toString(),id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    Trip recover(Profiles.Profile requested,List<Profiles.Profile> profiles,boolean explicitProfile,boolean allowResume,
                 long now,long wall,int boot,long stationaryTimeout) throws JSONException {
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
                resumed=restore(item,now,wall,boot,lastOff,stationaryTimeout);
                if (resumed!=null) continue;
            }
            closeTrip(item,true);
        }
        return resumed;
    }
    static Trip restore(JSONObject item,long now,long wall,int boot,int lastOff,long stationaryTimeout) throws JSONException {
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
        if (start<=0 || savedAt<start || savedAt>now+1000 || now-start>=MAX_TRIP_MS ||
            !Engine.recoverableGap(now-savedAt,stationaryTimeout))
            return null;
        Trip trip=new Trip(item.getString("id"),item.getString("vehicle"),unix,start);
        double duration=item.optDouble("dur_s",0);
        trip.saved=Math.min(now,savedAt);
        trip.motion=resume==null ? trip.saved : resume.optLong("motion",trip.saved);
        trip.profileId=resume==null ? "" : resume.optString("profile");
        trip.gpsKm=item.optDouble("gps_km",0);
        trip.recordConditions(item.optDouble(AMBIENT_C),item.optDouble(PRESSURE_HPA));
        trip.drive=item.optDouble("drive_s",0); trip.idle=item.optDouble("idle_s",0);
        double selected=item.optDouble("km",0);
        trip.km=resume==null ? (trip.drive>0 && selected!=trip.gpsKm ? selected : 0) : resume.optDouble("obd_km",0);
        trip.liters=item.optDouble("fuel_l",0); trip.fuelSource=item.optInt("fuel_src",0);
        trip.fuelCover=resume==null ? item.optDouble("fuel_cover",0)*duration : resume.optDouble("fuel_s",0);
        trip.max=item.optDouble("max_kmh",0); trip.count=item.optInt("track_pts",0); trip.lastOff=lastOff;
        for (int i=0;i<TripMath.SPLITS;i++) {
            trip.acceleration[i]=accelerationBest(item,i);
            trip.runs.get(i).addAll(accelerationFrom(item,i,false));
        }
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
    // Każdy przedział zachowuje pierwszy poprawny odczyt i rzeczywisty czas pomiaru.
    void instrumentSample(String vehicle,InstrumentHistory.Metric metric,long slot,long time,double value,boolean prune) {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO instrument_history VALUES(?,?,?,?,?)",new Object[]{vehicle,metric.id,slot,time,value});
        if (prune) db.execSQL("DELETE FROM instrument_history WHERE slot<?",new Object[]{(time-InstrumentHistory.WEEK)/InstrumentHistory.BUCKET});
    }
    List<InstrumentHistory.Sample> instrumentHistory(String vehicle,InstrumentHistory.Metric metric,long now) {
        long from=now-InstrumentHistory.WEEK;
        List<InstrumentHistory.Sample> values=new ArrayList<>();
        SQLiteDatabase db=getReadableDatabase();
        try (Cursor c=db.rawQuery("SELECT sampled,value FROM instrument_history WHERE "+historyScope(vehicle)
                +" AND metric=? AND slot>=? AND sampled BETWEEN ? AND ? ORDER BY sampled",
                new String[]{vehicle,Integer.toString(metric.id),Long.toString(from/InstrumentHistory.BUCKET),Long.toString(from),Long.toString(now)})) {
            while (c.moveToNext()) values.add(new InstrumentHistory.Sample(c.getLong(0),c.getDouble(1),false));
        }
        if (metric.archivedField==null) return values;
        long before=values.isEmpty() ? now+1 : values.get(0).at;
        List<InstrumentHistory.Sample> archived=new ArrayList<>();
        try (Cursor c=db.rawQuery("SELECT info FROM trips WHERE closed=1 AND "+historyScope(vehicle)
                +" AND started BETWEEN ? AND ?",new String[]{vehicle,Long.toString((from-MAX_TRIP_MS)/1000),Long.toString(now/1000)})) {
            while (c.moveToNext()) {
                JSONObject trip=new JSONObject(c.getString(0));
                long at=trip.optLong("t_end")*1000;
                double value=trip.optDouble(metric.archivedField);
                if (at>=from && at<before && metric.valid(value)) archived.add(new InstrumentHistory.Sample(at,value,true));
            }
        } catch (JSONException error) { throw new IllegalStateException(error); }
        archived.sort(Comparator.comparingLong(sample -> sample.at));
        archived.addAll(values);
        return archived;
    }
    TripGrouping.Page history(String vehicle,int offset,boolean join,java.time.ZoneId zone) {
        List<JSONObject> rows=new ArrayList<>();
        String sql="SELECT info FROM trips WHERE closed=1 AND "+historyScope(vehicle)+" ORDER BY started DESC,id DESC LIMIT ? OFFSET ?";
        boolean boundary=false;
        try {
            while (!boundary) {
                int count=0, limit=TripGrouping.PAGE_SIZE+1;
                try (Cursor c=getReadableDatabase().rawQuery(sql,new String[]{vehicle,Integer.toString(limit),Integer.toString(offset+rows.size())})) {
                    while (c.moveToNext()) {
                        JSONObject older=new JSONObject(c.getString(0)); count++;
                        // Granica strony wypada między grupami, również przy bardzo wielu odcinkach.
                        if (rows.size()>=TripGrouping.PAGE_SIZE &&
                                (!join || !TripGrouping.canJoin(older,rows.get(rows.size()-1),zone)))
                            return new TripGrouping.Page(rows,true);
                        rows.add(older);
                    }
                }
                boundary=count<limit;
            }
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return new TripGrouping.Page(rows,false);
    }
    // Starsze pozycje accel_s powyżej 100 km/h były liczone od zera.
    static double accelerationBest(JSONObject trip,int band) {
        boolean rolling=band>=TripMath.SERIES_SPLITS;
        JSONArray best=trip.optJSONArray(rolling ? "accel_100_s" : "accel_s");
        return best==null ? Double.NaN : best.optDouble(rolling ? band-TripMath.SERIES_SPLITS : band,Double.NaN);
    }
    static List<Acceleration> accelerationFrom(JSONObject trip,int band,boolean includeArchived) {
        List<Acceleration> records=new ArrayList<>();
        boolean rolling=band>=TripMath.SERIES_SPLITS;
        JSONArray splits=trip.optJSONArray(rolling ? "accel_100_runs" : "accel_runs");
        JSONArray list=splits==null ? null : splits.optJSONArray(rolling ? band-TripMath.SERIES_SPLITS : band);
        if (list!=null) for (int i=Math.max(0,list.length()-ACCEL_HISTORY_LIMIT);i<list.length();i++) {
            JSONArray row=list.optJSONArray(i);
            if (row==null) continue;
            long at=row.optLong(0,-1); double seconds=row.optDouble(1,Double.NaN);
            if (at>0 && seconds>0 && Double.isFinite(seconds)) records.add(new Acceleration(at,seconds,false));
        }
        if (includeArchived && records.isEmpty()) {
            double seconds=accelerationBest(trip,band);
            long start=trip.optLong("t_start",0);
            if (start>0 && seconds>0 && Double.isFinite(seconds)) records.add(new Acceleration(start*1000,seconds,true));
        }
        return records;
    }
    List<Acceleration> recentAcceleration(String vehicle,int band) {
        List<Acceleration> results=new ArrayList<>();
        String sql="SELECT started,info FROM trips WHERE closed IN (0,1) AND "+historyScope(vehicle)+" ORDER BY started DESC,id DESC";
        try (Cursor c=getReadableDatabase().rawQuery(sql,new String[]{vehicle})) {
            while (c.moveToNext()) {
                if (results.size()==ACCEL_HISTORY_LIMIT && c.getLong(0)*1000+MAX_TRIP_MS<results.get(ACCEL_HISTORY_LIMIT-1).atMs) break;
                List<Acceleration> trip=accelerationFrom(new JSONObject(c.getString(1)),band,true);
                if (trip.isEmpty()) continue;
                results.addAll(trip); results.sort(Comparator.comparingLong((Acceleration a) -> a.atMs).reversed());
                if (results.size()>ACCEL_HISTORY_LIMIT) results.subList(ACCEL_HISTORY_LIMIT,results.size()).clear();
            }
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return results;
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
        for (String field:new String[]{"cost_pln","cost_net_pln","fuel_vat_pct","odo_end","odo_src","volt","coolant_max"}) if (server.has(field)) local.put(field,server.get(field));
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
                double altitude=p.optDouble(4,Double.NaN);
                db.execSQL("INSERT OR IGNORE INTO points(trip,off,la,lo,kmh,altitude) VALUES(?,?,?,?,?,?)",
                    new Object[]{trip.id,off,la,lo,kmh,altitude>=-1500 && altitude<=11000 ? altitude : null});
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
        try (Cursor c=getReadableDatabase().rawQuery("SELECT off,la,lo,kmh,altitude FROM points WHERE trip=? ORDER BY off",new String[]{id})) {
            while (c.moveToNext()) result.add(new Point(c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3),
                c.isNull(4) ? Double.NaN : c.getDouble(4)));
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
