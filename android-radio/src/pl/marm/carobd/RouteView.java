package pl.marm.carobd;

import android.content.Context;
import android.graphics.*;
import android.net.http.HttpResponseCache;
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
    private static final Map<String,Long> BACKOFF=new LinkedHashMap<String,Long>(64,0.75F,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> entry) { return size()>128; }
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final List<Trips.Point> points;
    private final GestureDetector pan;
    private final ScaleGestureDetector pinch;
    private boolean ready;
    private volatile boolean background,attached;
    private double cx,cy;
    private int zoom=12;

    RouteView(Context context,List<Trips.Point> points) {
        super(context); this.points=points;
        setContentDescription("Mapa zapisanego przejazdu");
        pan=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                if (pinch.isInProgress() || points.isEmpty()) return false;
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
    private static double x(int longitude) { return (longitude/1e5+180)/360; }
    private static double y(int latitude) {
        double sine=Math.sin(Math.toRadians(Math.max(-85,Math.min(85,latitude/1e5))));
        return 0.5-Math.log((1+sine)/(1-sine))/(4*Math.PI);
    }
    private double world() { return (double)TILE*(1<<zoom); }
    private double wrap(double value) { return value-Math.rint(value); }
    void fit() { ready=false; invalidate(); }
    private void bounds() {
        double base=x(points.get(0).lo), minX=base,maxX=base,minY=1,maxY=0;
        for (Trips.Point p : points) {
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
        if (points.isEmpty()) { paint.setColor(Color.WHITE); paint.setTextSize(24); canvas.drawText("Brak punktów GPS",24,42,paint); return; }
        if (!ready) bounds();
        int w=getWidth(), h=getHeight();
        paint.setColor(0xff294359); paint.setStrokeWidth(1);
        for (int i=0;i<w;i+=40) canvas.drawLine(i,0,i,h,paint);
        for (int i=0;i<h;i+=40) canvas.drawLine(0,i,w,i,paint);
        if (background) drawBackground(canvas,w,h);
        double world=world();
        Path track=new Path();
        float firstX=0, firstY=0, px=0, py=0;
        int previous=-1;
        for (Trips.Point point : points) {
            px=(float)(w/2.0+wrap(x(point.lo)-cx)*world); py=(float)(h/2.0+(y(point.la)-cy)*world);
            if (previous<0) { firstX=px; firstY=py; }
            if (previous<0 || point.s-previous>30) track.moveTo(px,py); else track.lineTo(px,py);
            previous=point.s;
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xff071524); paint.setStrokeWidth(10); canvas.drawPath(track,paint);
        paint.setColor(0xff53eab4); paint.setStrokeWidth(5); canvas.drawPath(track,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(firstX,firstY,7,paint); paint.setColor(0xfffee0a1); canvas.drawCircle(px,py,7,paint);
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
                    http.setRequestProperty("User-Agent","CarOBD Radio/0.1 (https://github.com/rysiulg/carobd-radio)");
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
