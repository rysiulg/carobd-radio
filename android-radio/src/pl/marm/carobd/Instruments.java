package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Arrays;

final class Instruments {
    static final int WHITE=0xfff0f7fa, MUTE=0xff9db5c7, TRACK=0xff355065, GRID=0xff29445a,
        MINT=0xff64eabd, RPM_COLOR=0xffff795f, SPEED_COLOR=0xff82beff, SPEED_LIMIT=220, RPM_LIMIT=7000;
    static final class Dial extends View {
        private static final float START=140, SWEEP=260;
        private static final float TICK_OUTER=.96f, TICK_SHORT_INNER=.91f, TICK_LONG_INNER=.88f;
        private static final int FACE=0xff0d1c2d, CENTER=0xff12263a, RED_ZONE=0xffff454e;
        private static final int KIA_RPM_LIMIT=6000, KIA_RED_FROM=4500;
        private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path needle=new Path();
        private final RectF glowBounds=new RectF();
        final String name,unit;
        final int limit,major,accent;
        private final float heightRatio;
        double value=Double.NaN;
        private String digits="—",gear="";
        private boolean kiaScale;
        Dial(Context c,Typeface typeface,String name,String unit,int limit,int major,int accent,float heightRatio) {
            super(c); this.name=name; this.unit=unit; this.limit=limit; this.major=major; this.accent=accent;
            this.heightRatio=heightRatio; ink.setTypeface(typeface);
            setContentDescription(name+tr(" brak odczytu"));
        }
        void value(double next) {
            if (Double.compare(value,next)==0) return;
            value=next;
            digits=Double.isFinite(value) ? Long.toString(Math.round(value)) : "—";
            describe();
            invalidate();
        }
        void kiaScale(boolean enabled) {
            if (kiaScale==enabled) return;
            kiaScale=enabled; invalidate();
        }
        void gear(String next) {
            if (gear.equals(next)) return;
            gear=next; describe(); invalidate();
        }
        private void describe() {
            setContentDescription(name+" "+(Double.isFinite(value) ? digits+" "+unit : tr("brak odczytu"))+
                (gear.isEmpty() ? "" : " · "+("—".equals(gear) ? tr("brak odczytu biegu") : tr("bieg ")+gear)));
        }
        private void text(Canvas c,String text,float x,float y,float size,int color) {
            ink.setStyle(Paint.Style.FILL); ink.setColor(color); ink.setTextSize(size);
            ink.setTextAlign(Paint.Align.CENTER);
            c.drawText(text,x,y,ink);
        }
        @Override protected void onDraw(Canvas c) {
            float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth()*.46f,getHeight()*heightRatio);
            if (r<=1) return;
            ink.setStyle(Paint.Style.FILL); ink.setColor(FACE); c.drawCircle(x,y,r,ink);
            ink.setStyle(Paint.Style.STROKE); ink.setColor(accent); ink.setStrokeWidth(Math.max(1,r*.023f));
            c.drawCircle(x,y,r,ink);
            ink.setStyle(Paint.Style.FILL); ink.setColor(CENTER); c.drawCircle(x,y,r*.445f,ink);
            ink.setStyle(Paint.Style.STROKE); ink.setColor(GRID); ink.setStrokeWidth(Math.max(1,r*.009f));
            c.drawCircle(x,y,r*.445f,ink);
            int divisor=limit>1000 ? 1000 : 1;
            // Układ kresek z zegarów Kia. Pozostałe profile korzystają ze zwykłej podziałki.
            int dialLimit=kiaScale ? KIA_RPM_LIMIT : limit;
            int subdivisions=kiaScale ? 4 : 2;
            int count=dialLimit/major*subdivisions;
            float sweep=Double.isFinite(value) ? (float)(SWEEP*Math.max(0,Math.min(value,dialLimit))/dialLimit) : Float.NaN;
            if (sweep>0) drawGlow(c,x,y,r,sweep);
            for (int i=0;i<=count;i++) {
                double a=Math.toRadians(START+SWEEP*i/count);
                float cos=(float)Math.cos(a),sin=(float)Math.sin(a);
                boolean main=i%subdivisions==0;
                int tick=i*major/subdivisions;
                boolean red=kiaScale && tick>=KIA_RED_FROM;
                ink.setColor(red ? RED_ZONE : main ? WHITE : TRACK);
                ink.setStrokeWidth(r*(main ? .016f : .009f));
                float inner=r*(main ? TICK_LONG_INNER : TICK_SHORT_INNER),outer=r*TICK_OUTER;
                c.drawLine(x+inner*cos,y+inner*sin,x+outer*cos,y+outer*sin,ink);
                if (main) text(c,Integer.toString(tick/divisor),
                    x+r*.704f*cos,y+r*.704f*sin+r*.045f,r*.13f,WHITE);
            }
            if (Float.isFinite(sweep)) {
                // Krótka wskazówka obraca się wokół środka i kończy przy podziałce.
                float base=r*.664f, halfWidth=r*.027f;
                c.save();
                c.translate(x,y); c.rotate(START+sweep);
                needle.rewind(); needle.moveTo(base,-halfWidth);
                needle.lineTo(r*.973f,0); needle.lineTo(base,halfWidth); needle.close();
                ink.setStyle(Paint.Style.FILL); ink.setColor(RED_ZONE); c.drawPath(needle,ink);
                c.restore();
            }
            float digitsSize=r*(digits.length()>3 ? .35f : .52f);
            ink.setTextSize(digitsSize);
            float advance=ink.measureText(digits);
            if (advance>r*.82f) digitsSize*=r*.82f/advance;
            text(c,digits,x,y+r*.101f,digitsSize,WHITE);
            text(c,unit,x,y+r*.262f,r*.0935f,MUTE);
            if (divisor>1) text(c,"×1000",x,y+r*.75f,r*.077f,MUTE);
            if (!gear.isEmpty()) text(c,gear,x,y+r*.78f,r*.24f,"—".equals(gear) ? MUTE : MINT);
        }
        private void drawGlow(Canvas c,float x,float y,float r,float sweep) {
            float middle=r*(TICK_OUTER+TICK_SHORT_INNER)*.5f, width=r*(TICK_OUTER-TICK_SHORT_INNER);
            glowBounds.set(x-middle,y-middle,x+middle,y+middle);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.BUTT);
            // Dwie półprzezroczyste warstwy miękko wypełniają pas pod krótkimi kreskami.
            int color=accent&0x00ffffff;
            ink.setColor(0x28000000|color); ink.setStrokeWidth(width*1.48f);
            c.drawArc(glowBounds,START,sweep,false,ink);
            ink.setColor(0x80000000|color); ink.setStrokeWidth(width);
            c.drawArc(glowBounds,START,sweep,false,ink);
        }
    }

    static final class Fuel extends View {
        private static final int FUEL=0, RPM=1, SPEED=2, SAMPLES=46, PERIOD_MS=2000;
        private static final int[] COLORS={MINT,RPM_COLOR,SPEED_COLOR};
        private static final double[] LIMITS={0,RPM_LIMIT,SPEED_LIMIT}, LOG_UNITS={0,1000,20};
        private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path trace=new Path();
        private final double[][] values=new double[COLORS.length][SAMPLES];
        private long lastSlot=-1;
        private boolean preview,byDistance;

        Fuel(Context c) {
            super(c); clear();
            describe();
        }
        private String fuelUnit() { return byDistance ? "l/100 km" : "l/h"; }
        private void describe() {
            setContentDescription(tr("Spalanie w ")+fuelUnit()+tr(", obroty w obr./min i prędkość w km/h. Ostatnie 90 sekund. ") +
                tr("Obroty i prędkość na skalach logarytmicznych."));
        }
        private void clear() {
            for (double[] series:values) Arrays.fill(series,Double.NaN);
            lastSlot=-1;
        }
        void sample(double rate,double rpm,double speed,boolean distanceUnit,long now,boolean demo) {
            if (preview!=demo) { preview=demo; clear(); invalidate(); }
            if (byDistance!=distanceUnit) { byDistance=distanceUnit; describe(); invalidate(); }
            long slot=now/PERIOD_MS;
            if (slot<=lastSlot) return;
            // Puste próbki zostawiają lukę, gdy odświeżanie ekranu zostało wstrzymane.
            if (lastSlot>=0) for (long missed=Math.max(lastSlot+1,slot-SAMPLES+1);missed<slot;missed++) {
                int i=(int)(missed%SAMPLES);
                for (double[] series:values) series[i]=Double.NaN;
            }
            int i=(int)(slot%SAMPLES);
            values[FUEL][i]=rate; values[RPM][i]=rpm; values[SPEED][i]=speed;
            lastSlot=slot; invalidate();
        }
        private double value(int series,int position) {
            if (preview) {
                double drift=Math.sin(position*.17), slope=Math.sin(position*.61);
                if (series==FUEL) return 5.5+2.5*drift+slope;
                if (series==RPM) return 1800+590*Math.sin(position*.17+.5)+250*slope;
                return 68+31*Math.sin(position*.17-.6)+4*slope;
            }
            int index=(int)Math.floorMod(lastSlot+position+1,SAMPLES);
            double raw=values[series][index];
            return series==FUEL && byDistance ? TripMath.fuelPer100Km(raw,values[SPEED][index]) : raw;
        }
        private double tick(int series,float fraction,double fuelScale) {
            if (series==FUEL) return fuelScale*fraction;
            double unit=LOG_UNITS[series];
            return unit*Math.expm1(fraction*Math.log1p(LIMITS[series]/unit));
        }
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight(),d=getResources().getDisplayMetrics().density;
            float top=32*d,bottom=h-18*d,left=30*d,right=w-76*d;
            if (right-left<64*d || bottom-top<24*d) return;
            double peak=preview ? 15 : 5;
            for (int i=0;i<SAMPLES;i++) {
                double v=value(FUEL,i);
                if (Double.isFinite(v)) peak=Math.max(peak,v);
            }
            double fuelScale=Math.ceil(peak/5)*5;
            ink.setStyle(Paint.Style.FILL); ink.setTextSize(11*d); ink.setTextAlign(Paint.Align.LEFT);
            ink.setColor(COLORS[FUEL]); ink.setStrokeWidth(2*d);
            c.drawLine(left,11*d,left+10*d,11*d,ink);
            c.drawText(tr("Spalanie ")+fuelUnit(),left+14*d,15*d,ink);
            ink.setTextAlign(Paint.Align.RIGHT);
            for (int i=0;i<=3;i++) {
                float fraction=i/3f,y=bottom-(bottom-top)*fraction;
                ink.setColor(GRID); ink.setStrokeWidth(1); c.drawLine(left,y,right,y,ink);
                ink.setColor(MINT); c.drawText(Long.toString(Math.round(tick(FUEL,fraction,fuelScale))),left-6*d,y+4*d,ink);
                ink.setColor(COLORS[RPM]); c.drawText(Long.toString(Math.round(tick(RPM,fraction,fuelScale))),right+34*d,y+4*d,ink);
                ink.setColor(COLORS[SPEED]); c.drawText(Long.toString(Math.round(tick(SPEED,fraction,fuelScale))),w-4*d,y+4*d,ink);
            }
            for (int series=0;series<COLORS.length;series++) drawSeries(c,series,fuelScale,left,top,right,bottom,d);
            ink.setColor(MUTE); ink.setTextAlign(Paint.Align.LEFT); c.drawText(tr("90 s temu"),left,h-2*d,ink);
            ink.setTextAlign(Paint.Align.RIGHT); c.drawText(tr("teraz"),right,h-2*d,ink);
        }
        private void drawSeries(Canvas c,int series,double fuelScale,float left,float top,float right,float bottom,float d) {
            double limit=series==FUEL ? fuelScale : LIMITS[series], unit=LOG_UNITS[series];
            double scale=unit==0 ? limit : Math.log1p(limit/unit);
            trace.rewind(); boolean begun=false;
            float endY=Float.NaN;
            for (int i=0;i<SAMPLES;i++) {
                double sample=value(series,i);
                if (!Double.isFinite(sample) || sample<0) { begun=false; continue; }
                double bounded=Math.min(sample,limit);
                double ratio=(unit==0 ? bounded : Math.log1p(bounded/unit))/scale;
                float x=left+(right-left)*i/(SAMPLES-1),y=bottom-(float)ratio*(bottom-top);
                if (begun) trace.lineTo(x,y); else { trace.moveTo(x,y); begun=true; }
                if (i==SAMPLES-1) endY=y;
            }
            ink.setStrokeWidth(2*d); ink.setColor(COLORS[series]); ink.setStyle(Paint.Style.STROKE); c.drawPath(trace,ink);
            ink.setStyle(Paint.Style.FILL);
            if (Float.isFinite(endY)) c.drawCircle(right,endY,2.5f*d,ink);
        }
    }
}
