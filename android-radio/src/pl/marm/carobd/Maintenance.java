package pl.marm.carobd;

import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Eksportuje GPX, ponawia synchronizację i aktualizuje wykaz CANARD w tle. */
public final class Maintenance extends JobService {
    private static final int EXPORT=140, SYNC=141, PERIODIC=142, ROAD_MAP=143;
    private static final Object NETWORK=new Object();
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Map<Integer,Future<?>> jobs=new HashMap<>();
    static volatile String failedExportFolder="";

    static void schedule(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        ComponentName service=new ComponentName(context,Maintenance.class);
        Settings s=new Settings(context);
        if (RoadMapData.due(s) && scheduler.getPendingJob(ROAD_MAP)==null)
            scheduler.schedule(new JobInfo.Builder(ROAD_MAP,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60*60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build());
        if (!s.get("folder","").isEmpty() && scheduler.getPendingJob(EXPORT)==null)
            scheduler.schedule(new JobInfo.Builder(EXPORT,service).setMinimumLatency(250).setPersisted(true).build());
        if (s.hasNasCredential()) {
            if (scheduler.getPendingJob(SYNC)==null)
                scheduler.schedule(new JobInfo.Builder(SYNC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
            if (scheduler.getPendingJob(PERIODIC)==null)
                scheduler.schedule(new JobInfo.Builder(PERIODIC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15*60*1000L).setPersisted(true).build());
        }
    }
    @Override public boolean onStartJob(JobParameters params) {
        int id=params.getJobId();
        jobs.put(id,workers.submit(() -> {
            boolean retry;
            Settings settings=new Settings(this);
            if (id==ROAD_MAP) {
                try { RoadMapData.refresh(this,settings); retry=false; }
                catch (IOException | JSONException e) {
                    Log.w("CarOBD", "Nie udało się zaktualizować wykazu CANARD", e);
                    retry=true;
                }
            } else {
                Trips db=new Trips(this);
                String folder=settings.get("folder","");
                try {
                    if (id==EXPORT) retry=export(db,folder);
                    else synchronized (NETWORK) { retry=sync(db,settings); }
                } catch (Exception e) {
                    retry=true;
                    if (id==EXPORT) failedExportFolder=folder;
                } finally { db.close(); }
            }
            final boolean again=retry;
            new android.os.Handler(getMainLooper()).post(() -> {
                Future<?> current=jobs.remove(id);
                if (current!=null && !current.isCancelled()) jobFinished(params,again);
            });
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Future<?> f=jobs.remove(params.getJobId()); if (f!=null) f.cancel(true);
        return true;
    }
    @Override public void onDestroy() { workers.shutdownNow(); super.onDestroy(); }

    private boolean sync(Trips db,Settings settings) throws Exception {
        Settings.Credential credential=settings.credential();
        if (!Settings.identifier(credential.device) || credential.token.length()<24) return false;
        URL endpoint=new URL(Settings.DEFAULT_ENDPOINT);
        for (int i=0;i<25 && !Thread.currentThread().isInterrupted();i++) {
            JSONObject record=settings.nasUploadEnabled() ? db.next() : null;
            if (record==null) {
                Recorder.Live live=Recorder.live;
                String vehicle=live.armed ? live.vehicle : settings.profiles.defaultProfile().vehicle;
                if (Settings.identifier(vehicle) && !EngineHours.kiaProfile(vehicle)) try { NasHistory.pullTelemetry(settings,credential,vehicle); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return true; }
                catch (Exception unavailable) { /* Telemetria nie blokuje synchronizacji historii. */ }
                return NasHistory.pull(db,settings,credential);
            }
            String id=record.getString("id");
            byte[] data=new JSONObject().put("device",credential.device).put("sent_unix",System.currentTimeMillis()/1000)
                .put("records",new JSONArray().put(record)).toString().getBytes(StandardCharsets.UTF_8);
            if (data.length>16384) throw new IOException("Rekord przekracza limit NAS");
            HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
            try {
                if (!settings.nasUploadEnabled()) return false;
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(6500); connection.setReadTimeout(7500);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                connection.setRequestProperty("X-API-Key",credential.token);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream body=connection.getOutputStream()) { body.write(data); }
                int code=connection.getResponseCode();
                if (code!=200) throw new IOException("HTTP "+code);
                if (!acknowledged(readJson(connection,32768),id)) throw new IOException("Brak ACK rekordu");
                if (Thread.currentThread().isInterrupted()) return true;
                db.ack(id);
                settings.markNasUpload();
            } finally { connection.disconnect(); }
        }
        return settings.nasUploadEnabled() && db.pending()>0;
    }

    static JSONObject readJson(HttpURLConnection connection,int limit) throws IOException,JSONException {
        StringBuilder response=new StringBuilder();
        try (Reader in=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)) {
            char[] buffer=new char[512]; int n;
            while ((n=in.read(buffer))!=-1) {
                response.append(buffer,0,n);
                if (response.length()>limit) throw new IOException("Nieprawidłowa wielkość odpowiedzi NAS");
            }
        }
        return new JSONObject(response.toString());
    }
    static boolean acknowledged(JSONObject response,String id) {
        JSONArray accepted=response.optJSONArray("accepted");
        return Boolean.TRUE.equals(response.opt("ok")) && accepted!=null && accepted.length()==1 && id.equals(accepted.optString(0));
    }

    private boolean export(Trips db,String destination) throws Exception {
        if (destination.isEmpty()) return false;
        Uri tree=Uri.parse(destination);
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        for (int i=0;i<20 && !Thread.currentThread().isInterrupted();i++) {
            JSONObject trip=db.unexported(destination);
            if (trip==null) { failedExportFolder=""; return false; }
            String id=trip.getString("id");
            String name=id+".gpx";
            Uri document=find(tree,name);
            if (document==null) document=DocumentsContract.createDocument(getContentResolver(),parent,"application/gpx+xml",name);
            if (document==null) throw new IOException("Nie można utworzyć GPX");
            try (OutputStream stream=getContentResolver().openOutputStream(document,"rwt")) {
                if (stream==null) throw new IOException("Nie można zapisać GPX");
                writeGpx(stream,trip,db.points(id));
            }
            db.exported(id,destination);
            failedExportFolder="";
        }
        return true;
    }
    private Uri find(Uri tree,String name) {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor=getContentResolver().query(children,columns,null,null,null)) {
            while (cursor!=null && cursor.moveToNext()) if (name.equals(cursor.getString(1)))
                return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
        }
        return null;
    }

    static void writeGpx(OutputStream stream,JSONObject trip,List<Trips.Point> points) throws IOException,JSONException {
        Writer out=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));
        SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long start=trip.getLong("t_start");
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"CarOBD\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>");
        out.write(xml(trip.getString("id"))); out.write("</name><desc>");
        out.write(xml(trip.toString())); out.write("</desc><trkseg>");
        int previous=-1;
        for (Trips.Point p : points) {
            if (previous>=0 && p.s-previous>30) out.write("</trkseg><trkseg>");
            out.write("<trkpt lat=\""+(p.la/1e5)+"\" lon=\""+(p.lo/1e5)+"\">");
            if (Double.isFinite(p.altitude)) out.write("<ele>"+p.altitude+"</ele>");
            out.write("<time>"+iso.format(new Date((start+p.s)*1000))+"</time></trkpt>");
            previous=p.s;
        }
        out.write("</trkseg></trk></gpx>"); out.flush();
    }
    private static String xml(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}
