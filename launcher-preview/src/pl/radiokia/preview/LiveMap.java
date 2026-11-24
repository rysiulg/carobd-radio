package pl.radiokia.preview;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.net.http.HttpResponseCache;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Interaktywna mapa pozycji z radia, z kaflami OSM tylko dla oglądanego kadru. */
final class LiveMap extends View {
    private static final int TILE=256;
    private static final ExecutorService HTTP=Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> TILES=new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Set<String> LOADING=new HashSet<>();
    private static final Map<String,Long> BACKOFF=new LinkedHashMap<String,Long>(64,0.75F,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> entry) { return size()>128; }
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private Location position;
    private boolean follow=true;
    private final GestureDetector pan;
    private final ScaleGestureDetector pinch;
    private boolean ready;
    private volatile boolean background,attached;
    private double cx,cy;
    private int zoom=15;

    LiveMap(Context context) {
        super(context);
        setContentDescription("Interaktywna mapa GPS");
        background(true);
        pan=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                if (pinch.isInProgress() || position==null) return false;
                follow=false;
                double world=world(); cx+=dx/world; cy=Math.max(0,Math.min(1,cy+dy/world)); invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { zoomBy(1); return true; }
        });
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float scale=1;
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scale=1; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale*=d.getScaleFactor();
                if (scale>1.55 || scale<0.65) { zoomBy(scale>1 ? 1 : -1); scale=1; }
                return true;
            }
        });
    }
    void background(boolean value) {
        background=value;
        if (value && HttpResponseCache.getInstalled()==null) {
            try { HttpResponseCache.install(new File(getContext().getCacheDir(),"osm"),48L*1024*1024); }
            catch (IOException e) { background=false; }
        }
        invalidate();
    }
    void zoomBy(int delta) { zoom=Math.min(18,Math.max(2,zoom+delta)); invalidate(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached=true; }
    @Override protected void onDetachedFromWindow() { attached=false; super.onDetachedFromWindow(); }
    @Override public boolean onTouchEvent(MotionEvent e) { pinch.onTouchEvent(e); pan.onTouchEvent(e); return true; }
    private static double x(double longitude) { return (longitude+180)/360; }
    private static double y(double latitude) {
        double sine=Math.sin(Math.toRadians(Math.max(-85,Math.min(85,latitude))));
        return 0.5-Math.log((1+sine)/(1-sine))/(4*Math.PI);
    }
    private double world() { return (double)TILE*(1<<zoom); }
    private double wrap(double value) { return value-Math.rint(value); }
    void position(Location fix) {
        if (fix==null) return;
        position=fix;
        if (follow || !ready) center();
        invalidate();
    }
    void center() {
        follow=true;
        if (position==null) return;
        cx=x(position.getLongitude()); cy=y(position.getLatitude());
        ready=true;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff14283a);
        int w=getWidth(), h=getHeight();
        paint.setColor(0xff294359); paint.setStrokeWidth(1);
        for (int i=0;i<w;i+=40) canvas.drawLine(i,0,i,h,paint);
        for (int i=0;i<h;i+=40) canvas.drawLine(0,i,w,i,paint);
        if (position==null) {
            paint.setColor(0xffd8e8f6); paint.setTextSize(17*getResources().getDisplayMetrics().density);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Oczekiwanie na pozycję GPS",w/2f,h/2f,paint);
            paint.setTextAlign(Paint.Align.LEFT);
        } else {
            if (!ready) center();
            if (background) drawBackground(canvas,w,h);
            double world=world();
            float px=(float)(w/2.0+wrap(x(position.getLongitude())-cx)*world);
            float py=(float)(h/2.0+(y(position.getLatitude())-cy)*world);
            paint.setColor(0xff0b1b2d); canvas.drawCircle(px,py,12,paint);
            paint.setColor(0xff75bfff); canvas.drawCircle(px,py,8,paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(px,py,3,paint);
        }
        if (background) {
            String source="© OpenStreetMap contributors";
            paint.setTextSize(15); paint.setColor(0xee112232);
            canvas.drawRect(w-paint.measureText(source)-16,h-28,w,h,paint);
            paint.setColor(Color.WHITE); canvas.drawText(source,w-paint.measureText(source)-8,h-8,paint);
        }
    }
    private void drawBackground(Canvas canvas,int w,int h) {
        double left=cx*world()-w/2.0, top=cy*world()-h/2.0;
        int side=1<<zoom;
        int x0=(int)Math.floor(left/TILE), y0=Math.max(0,(int)Math.floor(top/TILE));
        int x1=(int)Math.floor((left+w)/TILE), y1=Math.min(side-1,(int)Math.floor((top+h)/TILE));
        paint.setColor(Color.WHITE);
        for (int tx=x0;tx<=x1;tx++) for (int ty=y0;ty<=y1;ty++) {
            String key=zoom+"/"+Math.floorMod(tx,side)+"/"+ty;
            Bitmap tile=TILES.get(key);
            if (tile!=null) canvas.drawBitmap(tile,null,new RectF((float)(tx*TILE-left),(float)(ty*TILE-top),
                (float)((tx+1)*TILE-left),(float)((ty+1)*TILE-top)),paint);
            else request(key);
        }
    }
    private void request(String key) {
        synchronized (LOADING) {
            if (LOADING.size()>=24 || System.currentTimeMillis()<BACKOFF.getOrDefault(key,0L) || !LOADING.add(key)) return;
        }
        HTTP.execute(() -> {
            Bitmap tile=null; HttpURLConnection http=null;
            try {
                if (attached && background) {
                    http=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                    http.setInstanceFollowRedirects(false); http.setConnectTimeout(4500); http.setReadTimeout(4500);
                    http.setRequestProperty("User-Agent","RadioKIA Pulpit/0.3 (https://github.com/rysiulg/carobd-radio)");
                    if (http.getResponseCode()==200) try (InputStream in=http.getInputStream()) { tile=BitmapFactory.decodeStream(in); }
                }
            } catch (IOException ignored) {
                // Brak kafla pozostawia czytelny ślad GPS z siatką.
            } finally {
                if (http!=null) http.disconnect();
                synchronized (LOADING) {
                    if (tile!=null) TILES.put(key,tile);
                    else BACKOFF.put(key,System.currentTimeMillis()+45000);
                    LOADING.remove(key);
                }
                if (tile!=null) postInvalidate();
            }
        });
    }
}
