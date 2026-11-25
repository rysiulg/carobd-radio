package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Tygodniowy wykres rzeczywistych próbek i osobno oznaczonych podsumowań NAS. */
final class InstrumentHistory {
    static final long BUCKET=5*60_000L, WEEK=7*24*60*60_000L;

    enum Metric {
        COOLANT(0,"Temperatura cieczy chłodzącej","°C",0,"coolant_max","Maksimum z przejazdu (NAS)"),
        OIL(1,"Temperatura oleju","°C",0,null,null),
        VOLTAGE(2,"Napięcie akumulatora","V",1,"volt",null);

        final int id,decimals;
        final String title,unit,archivedField,legend;
        Metric(int id,String title,String unit,int decimals,String archivedField,String legend) {
            this.id=id; this.title=title; this.unit=unit; this.decimals=decimals;
            this.archivedField=archivedField; this.legend=legend;
        }
        boolean valid(double value) {
            return this==VOLTAGE ? value>=1 && value<=40 : value>=-40 && value<=215;
        }
        String label(double value) { return String.format(UiLanguage.locale(),"%."+decimals+"f %s",value,unit); }
    }

    static final class Sample {
        final long at;
        final double value;
        final boolean archived;
        Sample(long at,double value,boolean archived) { this.at=at; this.value=value; this.archived=archived; }
    }

    static final class Chart extends View {
        private static final int LINE=0xff64eabd, ARCHIVED=0xffffbe82, LABEL=0xffd4e3ec, GRID=0xff34536b;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Metric metric;
        private final long now;
        private final SimpleDateFormat day=new SimpleDateFormat("dd.MM",UiLanguage.locale());
        private List<Sample> points=Collections.emptyList();
        private double min,max;

        Chart(Context context,Metric metric,long now) {
            super(context);
            this.metric=metric; this.now=now;
            setBackgroundColor(0xff122438);
        }
        void show(List<Sample> samples) {
            points=samples;
            if (points.isEmpty()) return;
            min=max=samples.get(0).value;
            for (Sample p:samples) { min=Math.min(min,p.value); max=Math.max(max,p.value); }
            double span=Math.max(max-min,metric==Metric.VOLTAGE ? .6 : 10);
            double middle=(min+max)/2;
            min=middle-span*.58; max=middle+span*.58;
            setContentDescription(tr(metric.title)+tr(" · wykres z 7 dni · ")+samples.size()+tr(" próbek"));
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (points.isEmpty()) return;
            float d=getResources().getDisplayMetrics().density;
            float left=66*d, top=16*d, right=getWidth()-26*d, bottom=getHeight()-31*d;
            if (left>=right || top>=bottom) return;
            paint.setTextSize(13*d); paint.setStrokeWidth(d);
            for (int i=0;i<=4;i++) {
                float y=bottom-(bottom-top)*i/4;
                paint.setColor(GRID); canvas.drawLine(left,y,right,y,paint);
                paint.setColor(LABEL); paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(metric.label(min+(max-min)*i/4),left-7*d,y+4*d,paint);
            }
            for (int i=0;i<=4;i++) {
                float x=left+(right-left)*i/4;
                paint.setColor(GRID); canvas.drawLine(x,top,x,bottom,paint);
                paint.setColor(LABEL); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(i==4 ? tr("Teraz") : day.format(new Date(now-WEEK+WEEK*i/4)),x,bottom+20*d,paint);
            }
            Sample previous=null;
            paint.setStrokeWidth(2.5f*d);
            for (Sample p:points) {
                float x=x(p,left,right), y=y(p,top,bottom);
                paint.setColor(p.archived ? ARCHIVED : LINE);
                if (previous!=null && !p.archived && !previous.archived && p.at-previous.at<=BUCKET*2)
                    canvas.drawLine(x(previous,left,right),y(previous,top,bottom),x,y,paint);
                canvas.drawCircle(x,y,(p.archived ? 3.4f : 2.4f)*d,paint);
                previous=p;
            }
        }
        private float x(Sample p,float left,float right) { return left+(right-left)*(WEEK+p.at-now)/WEEK; }
        private float y(Sample p,float top,float bottom) { return (float)(bottom-(bottom-top)*(p.value-min)/(max-min)); }
    }
}
