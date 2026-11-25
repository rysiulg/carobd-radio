package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.content.Context;
import android.graphics.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Trasa w Web Mercator. Tło OSM jest pobierane wyłącznie dla oglądanego kadru. */
final class RouteView extends View {
    private static final int TILE=256;
    private static final ExecutorService HTTP=Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> TILES=new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Set<String> LOADING=new HashSet<>();
    private static Network tileNetwork;
    private static final Map<String,Long> BACKOFF=new LinkedHashMap<String,Long>(64,0.75F,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> entry) { return size()>128; }
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path outline=new Path();
    private final Path[] bySpeed=new Path[SpeedBands.UNKNOWN+1];
    private final ConnectivityManager connectivity;
    private Network activeNetwork;
    private long redrawAt=Long.MAX_VALUE;
    private final Runnable redrawTiles=() -> { redrawAt=Long.MAX_VALUE; invalidate(); };
    private final ConnectivityManager.NetworkCallback networkChanges=new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { post(RouteView.this::refreshBackground); }
        @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c) { post(RouteView.this::refreshBackground); }
        @Override public void onLost(Network n) { post(RouteView.this::refreshBackground); }
    };
    private boolean watching;
    private List<List<Trips.Point>> tracks=Collections.emptyList();
    private final GestureDetector pan;
    private boolean pinching;
    private double pinchSpan,pinchX,pinchY;
    private boolean ready,loaded;
    private volatile boolean background,attached;
    private double cx,cy;
    private double zoom=12;

    RouteView(Context context) {
        super(context);
        connectivity=context.getSystemService(ConnectivityManager.class);
        for (int i=0;i<bySpeed.length;i++) bySpeed[i]=new Path();
        setContentDescription(tr("Mapa przejazdów. Kolor śladu zmienia się co 10 km/h."));
        pan=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                if (pinching || tracks.isEmpty()) return false;
                double world=world(); cx+=dx/world; cy=Math.max(0,Math.min(1,cy+dy/world)); invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { zoomBy(1); return true; }
        });
    }
    void setTracks(List<List<Trips.Point>> routes) {
        tracks=new ArrayList<>();
        for (List<Trips.Point> route:routes) if (!route.isEmpty()) tracks.add(route);
        loaded=true; fit();
    }
    private void refreshBackground() {
        if (!attached || connectivity==null) return;
        Network network=connectivity.getActiveNetwork();
        NetworkCapabilities caps=network==null ? null : connectivity.getNetworkCapabilities(network);
        // Sieć VPN może działać bez flag INTERNET i VALIDATED. Kafle weryfikuje HTTPS.
        boolean online=network!=null && (caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
        if (background==online && Objects.equals(activeNetwork,network)) return;
        activeNetwork=network; background=online;
        if (online) synchronized (LOADING) {
            if (!Objects.equals(tileNetwork,network)) { tileNetwork=network; BACKOFF.clear(); }
        }
        if (online && HttpResponseCache.getInstalled()==null) {
            try { HttpResponseCache.install(new File(getContext().getCacheDir(),"osm"),48L*1024*1024); }
            catch (IOException e) { /* Podkład działa też z pamięcią podręczną tylko w RAM. */ }
        }
        invalidate();
    }
    void zoomBy(int delta) { zoomBy(delta,getWidth()/2f,getHeight()/2f); }
    private void zoomBy(int delta,float focusX,float focusY) {
        zoomAround(delta,focusX,focusY,focusX,focusY);
    }
    private void zoomAround(double delta,double fromX,double fromY,double toX,double toY) {
        double prior=world(), midX=getWidth()/2.0, midY=getHeight()/2.0;
        zoom=Math.min(18,Math.max(2,zoom+delta));
        double current=world();
        cx+=(fromX-midX)/prior-(toX-midX)/current;
        cy=Math.max(0,Math.min(1,cy+(fromY-midY)/prior-(toY-midY)/current));
        invalidate();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached=true; refreshBackground();
        if (connectivity!=null) { connectivity.registerDefaultNetworkCallback(networkChanges); watching=true; }
    }
    @Override protected void onDetachedFromWindow() {
        attached=false; background=false; activeNetwork=null;
        removeCallbacks(redrawTiles); redrawAt=Long.MAX_VALUE;
        if (watching) { connectivity.unregisterNetworkCallback(networkChanges); watching=false; }
        super.onDetachedFromWindow();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pinching=false; pinchSpan=0;
                if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!pinching) {
                    MotionEvent cancel=MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    pan.onTouchEvent(cancel); cancel.recycle();
                }
                pinching=true; pinchSpan=0;
                if (event.getPointerCount()==2) pinch(event);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinchSpan=0;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && event.getPointerCount()==2) pinch(event);
                break;
            default: break;
        }
        if (!pinching) pan.onTouchEvent(event);
        return true;
    }
    // Używamy rozstawu od drugiego dotknięcia, także gdy palce zaczynają blisko siebie.
    private void pinch(MotionEvent event) {
        double firstX=event.getX(0), secondX=event.getX(1), firstY=event.getY(0), secondY=event.getY(1);
        double span=Math.max(8*getResources().getDisplayMetrics().density,Math.hypot(firstX-secondX,firstY-secondY));
        double focusX=(firstX+secondX)/2, focusY=(firstY+secondY)/2;
        if (pinchSpan>0 && !tracks.isEmpty()) {
            zoomAround(Math.log(span/pinchSpan)/Math.log(2),pinchX,pinchY,focusX,focusY);
        }
        pinchSpan=span; pinchX=focusX; pinchY=focusY;
    }
    private static double x(int longitude) { return (longitude/1e5+180)/360; }
    private static double y(int latitude) {
        double sine=Math.sin(Math.toRadians(Math.max(-85,Math.min(85,latitude/1e5))));
        return 0.5-Math.log((1+sine)/(1-sine))/(4*Math.PI);
    }
    private double world() { return TILE*Math.pow(2,zoom); }
    private double wrap(double value) { return value-Math.rint(value); }
    void fit() { ready=false; invalidate(); }
    private void bounds() {
        double base=x(tracks.get(0).get(0).lo), minX=base,maxX=base,minY=1,maxY=0;
        for (List<Trips.Point> route:tracks) for (Trips.Point p:route) {
            double px=base+wrap(x(p.lo)-base), py=y(p.la);
            minX=Math.min(px,minX); maxX=Math.max(px,maxX); minY=Math.min(py,minY); maxY=Math.max(py,maxY);
        }
        cx=(minX+maxX)/2; cy=(minY+maxY)/2; zoom=17;
        while (zoom>2 && ((maxX-minX)*world()>getWidth()*0.78 || (maxY-minY)*world()>getHeight()*0.76)) zoom--;
        ready=true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff14283a);
        if (tracks.isEmpty()) {
            paint.setColor(Color.WHITE); paint.setTextSize(24);
            canvas.drawText(loaded ? tr("Brak punktów GPS") : tr("Wczytywanie śladu GPS…"),24,42,paint); return;
        }
        if (!ready) bounds();
        int w=getWidth(), h=getHeight();
        paint.setColor(0xff294359); paint.setStrokeWidth(1);
        for (int i=0;i<w;i+=40) canvas.drawLine(i,0,i,h,paint);
        for (int i=0;i<h;i+=40) canvas.drawLine(0,i,w,i,paint);
        if (background) drawBackground(canvas,w,h);
        outline.rewind(); for (Path path:bySpeed) path.rewind();
        int low=SpeedBands.UNKNOWN, high=-1;
        for (List<Trips.Point> route:tracks) {
            Trips.Point previous=null;
            float lastX=0,lastY=0;
            for (Trips.Point point:route) {
                float px=screenX(point,w), py=screenY(point,h);
                if (previous==null || point.s-previous.s>30) outline.moveTo(px,py);
                else {
                    outline.lineTo(px,py);
                    int bucket=SpeedBands.band(previous.kmh,point.kmh);
                    Path color=bySpeed[bucket]; color.moveTo(lastX,lastY); color.lineTo(px,py);
                    if (bucket<SpeedBands.UNKNOWN) { low=Math.min(low,bucket); high=Math.max(high,bucket); }
                }
                previous=point; lastX=px; lastY=py;
            }
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xff071524); paint.setStrokeWidth(10); canvas.drawPath(outline,paint);
        paint.setStrokeWidth(5);
        // Okrągłe złącza domykają szczeliny tam, gdzie sąsiedni odcinek ma inny kolor.
        for (int i=0;i<bySpeed.length;i++) if (!bySpeed[i].isEmpty()) {
            paint.setColor(i==SpeedBands.UNKNOWN ? SpeedBands.NO_SPEED : SpeedBands.COLORS[i]);
            canvas.drawPath(bySpeed[i],paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (List<Trips.Point> route:tracks) {
            Trips.Point first=route.get(0), last=route.get(route.size()-1);
            paint.setColor(0xff53eab4); canvas.drawCircle(screenX(first,w),screenY(first,h),7,paint);
            paint.setColor(0xfffee0a1); canvas.drawCircle(screenX(last,w),screenY(last,h),7,paint);
        }
        if (high>=0) legend(canvas,w,low,high);
        if (background) {
            String source="© OpenStreetMap contributors";
            paint.setTextSize(15); paint.setColor(0xee112232);
            canvas.drawRect(w-paint.measureText(source)-16,h-28,w,h,paint);
            paint.setColor(Color.WHITE); canvas.drawText(source,w-paint.measureText(source)-8,h-8,paint);
        }
    }
    private float screenX(Trips.Point point,int width) { return (float)(width/2.0+wrap(x(point.lo)-cx)*world()); }
    private float screenY(Trips.Point point,int height) { return (float)(height/2.0+(y(point.la)-cy)*world()); }
    private void legend(Canvas canvas,int width,int low,int high) {
        float d=getResources().getDisplayMetrics().density;
        float x=10*d,y=10*d, size=Math.min(width-20*d,242*d), inset=7*d;
        if (size<96*d) return;
        paint.setColor(0xe6112232); canvas.drawRoundRect(x,y,x+size,y+39*d,5*d,5*d,paint);
        float swatch=(size-2*inset)/(high-low+1);
        for (int i=low;i<=high;i++) {
            paint.setColor(SpeedBands.COLORS[i]); float left=x+inset+(i-low)*swatch;
            canvas.drawRect(left,y+inset,left+swatch,y+14*d,paint);
        }
        paint.setTextSize(12*d); paint.setColor(Color.WHITE);
        String range=high==SpeedBands.UNKNOWN-1 ? (low*SpeedBands.STEP)+"+" :
            (low*SpeedBands.STEP)+"–"+((high+1)*SpeedBands.STEP-1);
        canvas.drawText(range,x+inset,y+30*d,paint);
        float advance=paint.measureText(range);
        paint.setTextSize(8.4f*d); canvas.drawText("km/h",x+inset+advance+4*d,y+30*d,paint);
    }
    private void drawBackground(Canvas canvas,int w,int h) {
        double left=cx*world()-w/2.0, top=cy*world()-h/2.0;
        int tileZoom=(int)Math.floor(zoom), side=1<<tileZoom;
        double tileSize=world()/side;
        int x0=(int)Math.floor(left/tileSize), y0=Math.max(0,(int)Math.floor(top/tileSize));
        int x1=(int)Math.floor((left+w)/tileSize), y1=Math.min(side-1,(int)Math.floor((top+h)/tileSize));
        paint.setColor(Color.WHITE);
        for (int tx=x0;tx<=x1;tx++) for (int ty=y0;ty<=y1;ty++) {
            String key=tileZoom+"/"+Math.floorMod(tx,side)+"/"+ty;
            Bitmap tile=TILES.get(key);
            RectF dest=new RectF((float)(tx*tileSize-left),(float)(ty*tileSize-top),
                (float)((tx+1)*tileSize-left),(float)((ty+1)*tileSize-top));
            if (tile!=null) canvas.drawBitmap(tile,null,dest,paint);
            else {
                // Kafel z poprzedniego poziomu utrzymuje podkład do czasu pobrania nowego.
                if (tileZoom>2) {
                    int parentX=Math.floorMod(tx,side)/2, parentY=ty/2;
                    Bitmap parent=TILES.get((tileZoom-1)+"/"+parentX+"/"+parentY);
                    if (parent!=null) {
                        int halfX=parent.getWidth()/2, halfY=parent.getHeight()/2;
                        int srcX=Math.floorMod(tx,2)*halfX, srcY=(ty%2)*halfY;
                        canvas.drawBitmap(parent,new Rect(srcX,srcY,srcX+halfX,srcY+halfY),dest,paint);
                    }
                }
                request(key);
            }
        }
    }
    private void retryTilesAt(long at) {
        if (at>=redrawAt) return;
        redrawAt=at; removeCallbacks(redrawTiles);
        postDelayed(redrawTiles,Math.max(0,at-SystemClock.uptimeMillis()));
    }
    private void request(String key) {
        synchronized (LOADING) {
            long now=SystemClock.uptimeMillis(), retryAt=BACKOFF.getOrDefault(key,0L);
            if (now<retryAt) { retryTilesAt(retryAt); return; }
            if (LOADING.size()>=24 || !LOADING.add(key)) { retryTilesAt(now+500); return; }
        }
        HTTP.execute(() -> {
            Bitmap tile=null; HttpURLConnection http=null;
            boolean attempted=false;
            try {
                if (attached && background) {
                    attempted=true;
                    http=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                    http.setInstanceFollowRedirects(false); http.setConnectTimeout(4500); http.setReadTimeout(4500);
                    http.setRequestProperty("User-Agent","CarOBD Radio/0.1 (https://github.com/rysiulg/carobd-radio)");
                    if (http.getResponseCode()==200) try (InputStream in=http.getInputStream()) { tile=BitmapFactory.decodeStream(in); }
                }
            } catch (IOException ignored) {
                // Brak kafla pozostawia czytelny ślad GPS z siatką.
            } finally {
                if (http!=null) http.disconnect();
                synchronized (LOADING) {
                    if (tile!=null) TILES.put(key,tile);
                    else if (attempted && attached && background) BACKOFF.put(key,SystemClock.uptimeMillis()+45000);
                    LOADING.remove(key);
                }
                if (attached) postInvalidate();
            }
        });
    }
}
