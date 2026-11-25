package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Wysokość GPS w funkcji dystansu. Luki w pomiarze rozdzielają linię wykresu. */
final class ElevationProfile {
    private final List<Sample> samples=new ArrayList<>();
    private double distance,lowest=Double.POSITIVE_INFINITY,highest=Double.NEGATIVE_INFINITY;

    private static final class Sample {
        final double distance,altitude;
        final boolean join;
        Sample(double distance,double altitude,boolean join) {
            this.distance=distance; this.altitude=altitude; this.join=join;
        }
    }
    static ElevationProfile fromTracks(List<List<Trips.Point>> tracks) {
        ElevationProfile profile=new ElevationProfile();
        float[] meters=new float[1];
        for (List<Trips.Point> part:tracks) {
            Trips.Point previous=null;
            for (Trips.Point point:part) {
                boolean continuous=previous!=null && point.s-previous.s<=30;
                if (continuous) {
                    Location.distanceBetween(previous.la/1e5,previous.lo/1e5,point.la/1e5,point.lo/1e5,meters);
                    profile.distance+=meters[0];
                }
                if (Double.isFinite(point.altitude)) {
                    profile.samples.add(new Sample(profile.distance,point.altitude,continuous && Double.isFinite(previous.altitude)));
                    profile.lowest=Math.min(profile.lowest,point.altitude);
                    profile.highest=Math.max(profile.highest,point.altitude);
                }
                previous=point;
            }
        }
        return profile;
    }
    void show(Context context) {
        LinearLayout content=new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        float density=context.getResources().getDisplayMetrics().density;
        int pad=Math.round(24*density);
        content.setPadding(pad,0,pad,Math.round(8*density));
        TextView summary=new TextView(context);
        summary.setTextSize(15);
        summary.setTextColor(Dashboard.MUTE);
        content.addView(summary);
        if (samples.isEmpty()) {
            summary.setText(tr("W punktach tej trasy nie zapisano wysokości GPS."));
        } else {
            summary.setText(String.format(UiLanguage.locale(),tr("Min. %.0f m   ·   Maks. %.0f m"),lowest,highest));
            Plot chart=new Plot(context);
            content.addView(chart,new LinearLayout.LayoutParams(-1,Math.round(205*density)));
        }
        new AlertDialog.Builder(context).setTitle(tr("Profil wysokości")).setView(content)
            .setPositiveButton(tr("Zamknij"),null).show();
    }
    private final class Plot extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line=new Path();
        Plot(Context context) {
            super(context); setContentDescription(tr("Wykres wysokości GPS względem dystansu"));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d=getResources().getDisplayMetrics().density;
            float left=58*d, right=getWidth()-12*d, top=15*d, bottom=getHeight()-28*d;
            if (right<=left || bottom<=top) return;
            double margin=Math.max(4,(highest-lowest)*.08), low=lowest-margin, high=highest+margin;
            double extent=Math.max(1,distance);
            paint.setTextSize(11*d); paint.setStrokeWidth(1*d);
            paint.setTextAlign(Paint.Align.RIGHT);
            for (int tick=0;tick<=4;tick++) {
                float y=bottom-(bottom-top)*tick/4;
                paint.setColor(0xff375572); canvas.drawLine(left,y,right,y,paint);
                paint.setColor(Dashboard.MUTE);
                canvas.drawText(String.format(UiLanguage.locale(),"%.0f m",low+(high-low)*tick/4),left-6*d,y+4*d,paint);
            }
            canvas.drawText(String.format(UiLanguage.locale(),"%.2f km",distance/1000),right,bottom+19*d,paint);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("0 km",left,bottom+19*d,paint);
            line.rewind();
            paint.setColor(Dashboard.MINT);
            for (Sample sample:samples) {
                float x=left+(float)(sample.distance/extent)*(right-left);
                float y=bottom-(float)((sample.altitude-low)/(high-low))*(bottom-top);
                if (sample.join) line.lineTo(x,y);
                else { line.moveTo(x,y); canvas.drawCircle(x,y,2*d,paint); }
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f*d);
            canvas.drawPath(line,paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
