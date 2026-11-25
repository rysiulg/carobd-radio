package pl.marm.carobd;

import android.content.Context;
import android.util.AtomicFile;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Publiczny wykaz lokalizacji CANARD. Nie wysyła pozycji ani historii auta. */
final class RoadMapData {
    static final String CATALOG="https://api.dane.gov.pl/1.4/datasets/4364/resources?per_page=100&sort=-data_date";
    static final long CHECK_INTERVAL=24*60*60_000L;
    private static final String CHECKED="road_map_checked";
    private static final String SEED_DATE="2026-06-30", SEED_VERSION="3020728|2026-11-19T13:42:06Z";
    private static final int MAX_BYTES=1_000_000;
    private static volatile Snapshot current;

    static final class Snapshot {
        final String date, version;
        final List<RoadAlert.Site> sites;
        final double[] markers;
        Snapshot(String date,String version,List<RoadAlert.Site> sites) {
            this.date=date; this.version=version; this.sites=Collections.unmodifiableList(sites);
            Map<String,double[]> unique=new LinkedHashMap<>();
            for (RoadAlert.Site site:sites) {
                addMarker(unique,site.section()?1:0,site.lat,site.lon);
                if (site.section()) addMarker(unique,1,site.endLat,site.endLon);
            }
            markers=new double[3*unique.size()];
            int at=0;
            for (double[] marker:unique.values()) { System.arraycopy(marker,0,markers,at,3); at+=3; }
        }
        private void addMarker(Map<String,double[]> unique,int type,double lat,double lon) {
            unique.putIfAbsent(type+"/"+lat+"/"+lon,new double[]{type,lat,lon});
        }
    }

    static boolean due(Settings settings) {
        long since=System.currentTimeMillis()-settings.prefs.getLong(CHECKED,0);
        return since<0 || since>=CHECK_INTERVAL;
    }

    static Snapshot snapshot() { return current; }

    static synchronized Snapshot load(Context context) {
        if (current!=null) return current;
        AtomicFile file=cache(context);
        try (InputStream in=file.openRead()) {
            JSONObject data=new JSONObject(new String(read(in),StandardCharsets.UTF_8));
            current=parse(data.getString("csv"),data.getString("date"),data.getString("version"));
        } catch (IOException | JSONException invalid) {
            try (InputStream in=context.getResources().openRawResource(R.raw.canard_poland)) {
                current=parse(decode(read(in)),SEED_DATE,SEED_VERSION);
            } catch (IOException | JSONException unavailable) {
                current=new Snapshot("","",Collections.emptyList());
            }
        }
        return current;
    }

    static void refresh(Context context,Settings settings) throws IOException,JSONException {
        Snapshot previous=load(context);
        JSONObject source=new JSONObject(new String(fetch(CATALOG),StandardCharsets.UTF_8));
        Resource resource=latest(source.getJSONArray("data"));
        if (resource==null) throw new IOException("CANARD: brak wykazu lokalizacji");
        if (resource.date.compareTo(previous.date)>=0 && !resource.version.equals(previous.version)) {
            String csv=decode(fetch(resource.url));
            Snapshot replacement=parse(csv,resource.date,resource.version);
            store(context,new JSONObject().put("csv",csv).put("date",resource.date).put("version",resource.version));
            current=replacement;
        }
        settings.prefs.edit().putLong(CHECKED,System.currentTimeMillis()).apply();
    }

    static final class Resource {
        final String url, date, version;
        Resource(String url,String date,String version) { this.url=url; this.date=date; this.version=version; }
    }
    static Resource latest(JSONArray items) throws JSONException {
        Resource latest=null;
        for (int i=0;i<items.length();i++) {
            JSONObject row=items.getJSONObject(i), attributes=row.getJSONObject("attributes");
            String title=attributes.optString("title").toLowerCase(Locale.ROOT);
            String date=attributes.optString("data_date"), url=attributes.optString("file_url");
            String version=row.optString("id")+"|"+attributes.optString("modified");
            if (!"csv".equalsIgnoreCase(attributes.optString("format")) || !title.contains("urządzeń rejestrujących") ||
                !title.contains("lokalizacji") || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || url.isEmpty()) continue;
            if (latest==null || date.compareTo(latest.date)>0 ||
                date.equals(latest.date) && version.substring(version.indexOf('|')+1)
                    .compareTo(latest.version.substring(latest.version.indexOf('|')+1))>0)
                latest=new Resource(url,date,version);
        }
        return latest;
    }

    static Snapshot parse(String csv,String date,String version) throws IOException, JSONException {
        Map<String,RoadAlert.Site> sites=new LinkedHashMap<>();
        try (BufferedReader lines=new BufferedReader(new StringReader(csv))) {
            String headline=lines.readLine();
            if (headline==null) throw new IOException("CANARD: pusty CSV");
            List<String> header=columns(headline.replace("\ufeff","").toLowerCase(Locale.ROOT));
            int type=header.indexOf("rodzaj urządzenia"), id=header.indexOf("kod lokalizacji"),
                city=header.indexOf("miejscowość"), road=header.indexOf("nr drogi"), category=header.indexOf("kategoria drogi"),
                startLat=header.indexOf("szerokość geograficzna - początek"), startLon=header.indexOf("długość geograficzna - początek"),
                endLat=header.indexOf("szerokość geograficzna - koniec"), endLon=header.indexOf("długość geograficzna - koniec"),
                forward=prefix(header,"długość odcinka kierunek p-t"), reverse=prefix(header,"długość odcinka kierunek t-p");
            int[] required={type,id,city,road,category,startLat,startLon,endLat,endLon,forward,reverse};
            if (Arrays.stream(required).anyMatch(c -> c<0)) throw new IOException("CANARD: zmiana kolumn");
            for (String line;(line=lines.readLine())!=null;) {
                List<String> row=columns(line);
                if (row.size()!=header.size()) continue;
                String kind=row.get(type).toLowerCase(Locale.ROOT), code=row.get(id);
                double lat=number(row.get(startLat)), lon=number(row.get(startLon));
                if (code.isEmpty() || !inPoland(lat,lon)) continue;
                String location=(row.get(category)+row.get(road)+" "+row.get(city)).trim();
                String key=code+"/"+lat+"/"+lon;
                if ("punktowe".equals(kind)) sites.putIfAbsent(key,new RoadAlert.Site(key,location,lat,lon,Double.NaN,Double.NaN));
                else if ("odcinkowy pomiar prędkości".equals(kind)) {
                    double lastLat=number(row.get(endLat)), lastLon=number(row.get(endLon));
                    if (!inPoland(lastLat,lastLon) || RoadAlert.distance(lat,lon,lastLat,lastLon)<30) continue;
                    if (number(row.get(forward))>0) sites.putIfAbsent(key,new RoadAlert.Site(key,location,lat,lon,lastLat,lastLon));
                    if (number(row.get(reverse))>0) {
                        String back=code+"/"+lastLat+"/"+lastLon;
                        sites.putIfAbsent(back,new RoadAlert.Site(back,location,lastLat,lastLon,lat,lon));
                    }
                }
            }
        }
        if (sites.size()<100 || sites.values().stream().noneMatch(RoadAlert.Site::section))
            throw new IOException("CANARD: niekompletny wykaz");
        return new Snapshot(date,version,new ArrayList<>(sites.values()));
    }

    private static List<String> columns(String line) {
        List<String> fields=new ArrayList<>();
        StringBuilder current=new StringBuilder();
        boolean quoted=false;
        for (int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if (ch=='"') {
                if (quoted && i+1<line.length() && line.charAt(i+1)=='"') { current.append(ch); i++; }
                else quoted=!quoted;
            } else if (ch==';' && !quoted) { fields.add(current.toString().trim()); current.setLength(0); }
            else current.append(ch);
        }
        fields.add(current.toString().trim());
        return fields;
    }
    private static int prefix(List<String> columns,String match) {
        for (int i=0;i<columns.size();i++) if (columns.get(i).startsWith(match)) return i;
        return -1;
    }
    private static double number(String text) {
        try { return Double.parseDouble(text.replace(',','.')); }
        catch (NumberFormatException absent) { return Double.NaN; }
    }
    private static boolean inPoland(double lat,double lon) { return lat>=48.9 && lat<=55.1 && lon>=14 && lon<=24.3; }
    private static String decode(byte[] csv) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(csv)).toString(); }
        catch (CharacterCodingException legacy) { return new String(csv,Charset.forName("windows-1250")); }
    }
    private static AtomicFile cache(Context context) {
        return new AtomicFile(new File(context.getFilesDir(),"canard-locations.json"));
    }
    private static void store(Context context,JSONObject data) throws IOException {
        AtomicFile file=cache(context);
        FileOutputStream out=file.startWrite();
        try { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (IOException failure) { file.failWrite(out); throw failure; }
    }
    private static byte[] fetch(String link) throws IOException {
        URL url=new URL(link);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"api.dane.gov.pl".equalsIgnoreCase(url.getHost()) ||
            url.getUserInfo()!=null || url.getPort()!=-1 || !url.getPath().startsWith("/media/resources/") && !CATALOG.equals(link))
            throw new IOException("Adres spoza API Otwarte Dane");
        HttpURLConnection http=(HttpURLConnection)url.openConnection();
        http.setInstanceFollowRedirects(false); http.setConnectTimeout(8000); http.setReadTimeout(10000);
        http.setRequestProperty("User-Agent","CarOBD Radio/0.3 (https://github.com/rysiulg/carobd-radio)");
        try {
            if (http.getResponseCode()!=200) throw new IOException("CANARD: HTTP "+http.getResponseCode());
            try (InputStream in=http.getInputStream()) { return read(in); }
        } finally { http.disconnect(); }
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        for (int n;(n=in.read(buffer))!=-1;) {
            if (out.size()+n>MAX_BYTES) throw new IOException("CANARD: przekroczony rozmiar");
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
}
