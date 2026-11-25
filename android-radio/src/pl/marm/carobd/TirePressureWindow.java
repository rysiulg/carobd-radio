package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.text.NumberFormat;

/** Widok TPMS z ciśnieniem obok każdego koła. */
final class TirePressureWindow {
    private static final int BACK=0xff102236, TILE=0xff17344e, EDGE=0xff467599;
    private static final int TEXT=0xfff0f7fd, MUTED=0xffaec7dc, ACCENT=0xff5ce2d1, WARNING=0xffffab76;
    private static final String[] UNITS={"bar","kPa","psi"};
    private final Context context;
    private final Settings settings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NumberFormat number=NumberFormat.getNumberInstance(UiLanguage.locale());
    private final TextView[] readings=new TextView[4];
    private final LinearLayout[] cards=new LinearLayout[4];
    private TextView status;
    private CarShape car;
    private int renderedMask=-1,unitIndex;
    private AlertDialog dialog;
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if (dialog==null || !dialog.isShowing()) return;
            render();
            main.postDelayed(this,850);
        }
    };

    TirePressureWindow(Context context) {
        this.context=context;
        settings=new Settings(context);
        String preferred=settings.get("tpms_unit","bar");
        if ("kPa".equals(preferred)) unitIndex=1;
        else if ("psi".equals(preferred)) unitIndex=2;
    }
    void show() {
        LinearLayout body=column();
        body.setPadding(dp(18),dp(14),dp(18),dp(12));
        TextView title=text(tr("Ciśnienie opon"),22,TEXT,true);
        title.setGravity(Gravity.CENTER); body.addView(title);
        TextView model=text("Kia Sorento",14,ACCENT,true);
        model.setGravity(Gravity.CENTER); body.addView(model);
        status=text("",14,MUTED,false);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        body.addView(unitPicker());
        LinearLayout diagram=new LinearLayout(context);
        diagram.setGravity(Gravity.CENTER);
        body.addView(diagram,new LinearLayout.LayoutParams(-1,dp(188)));
        diagram.addView(side(0,2),new LinearLayout.LayoutParams(0,-1,1));
        car=new CarShape(context);
        diagram.addView(car,new LinearLayout.LayoutParams(dp(114),-1));
        diagram.addView(side(1,3),new LinearLayout.LayoutParams(0,-1,1));

        LinearLayout actions=new LinearLayout(context);
        actions.setGravity(Gravity.CENTER);
        body.addView(actions);
        Button close=action(tr("Zamknij"),false,() -> dialog.dismiss());
        actions.addView(close,new LinearLayout.LayoutParams(-2,dp(40)));
        actions.addView(new View(context),dp(10),1);
        Button reset=action(tr("Odśwież"),true,() -> {
            context.startService(new Intent(context,Recorder.class).setAction(Recorder.RESET_TPMS));
        });
        actions.addView(reset,new LinearLayout.LayoutParams(-2,dp(40)));
        dialog=new AlertDialog.Builder(context).setView(body).create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> main.removeCallbacks(refresh));
        dialog.show();
        Window window=dialog.getWindow();
        if (window!=null) {
            window.setBackgroundDrawable(frame(BACK,EDGE,18));
            int available=context.getResources().getDisplayMetrics().widthPixels-dp(24);
            window.setLayout(Math.min(dp(500),available),-2);
        }
        refresh.run();
    }
    private LinearLayout unitPicker() {
        LinearLayout units=new LinearLayout(context);
        units.setGravity(Gravity.CENTER);
        units.addView(text(tr("Jednostka"),12,MUTED,true));
        units.addView(new View(context),dp(8),1);
        Spinner picker=new Spinner(context,Spinner.MODE_DROPDOWN);
        picker.setContentDescription(tr("Jednostka ciśnienia"));
        picker.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        picker.setPopupBackgroundDrawable(frame(BACK,EDGE,9));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,UNITS) {
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                TextView item=(TextView)super.getView(position,recycled,parent);
                item.setTextColor(ACCENT); item.setTextSize(13);
                return item;
            }
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent) {
                TextView item=(TextView)super.getDropDownView(position,recycled,parent);
                item.setTextColor(TEXT);
                return item;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter); picker.setSelection(unitIndex);
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if (unitIndex==position) return;
                unitIndex=position;
                settings.prefs.edit().putString("tpms_unit",UNITS[position]).apply();
                if (car!=null) render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        units.addView(picker,dp(79),dp(34));
        return units;
    }
    private String pressureText(double bars) {
        if (!Double.isFinite(bars)) return "—";
        int decimals=2;
        if (unitIndex==1) { bars*=100; decimals=0; }
        if (unitIndex==2) { bars/=0.0689475729; decimals=1; }
        number.setMinimumFractionDigits(decimals); number.setMaximumFractionDigits(decimals);
        return number.format(bars)+" "+UNITS[unitIndex];
    }
    private LinearLayout side(int front,int rear) {
        LinearLayout side=column();
        side.setGravity(Gravity.CENTER);
        for (int wheel:new int[]{front,rear}) {
            LinearLayout card=column();
            card.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(-1,dp(67));
            cell.setMargins(dp(2),dp(6),dp(2),dp(6)); side.addView(card,cell);
            TextView label=text(tr(TpmsMonitor.wheelName(wheel)),12,MUTED,true);
            TextView pressure=text("—",24,TEXT,true);
            label.setGravity(Gravity.CENTER); pressure.setGravity(Gravity.CENTER);
            pressure.setTypeface(context.getResources().getFont(R.font.oxanium_semibold));
            card.addView(label); card.addView(pressure);
            readings[wheel]=pressure; cards[wheel]=card;
        }
        return side;
    }
    private void render() {
        TpmsMonitor.Snapshot state=TpmsMonitor.snapshot();
        String message=state.lowMask!=0 ? (state.hasValues ? tr("Niskie ciśnienie") : tr("Ostatnie ostrzeżenie: niskie ciśnienie")) :
            state.hasValues ? "" : state.connected ? tr("Oczekiwanie na dane z dekodera CAN") : tr("Brak połączenia z dekoderem CAN");
        if (!message.contentEquals(status.getText())) status.setText(message);
        status.setVisibility(message.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        status.setTextColor(state.lowMask!=0 ? WARNING : MUTED);
        for (int i=0;i<readings.length;i++) {
            String formatted=pressureText(state.at(i));
            if (!formatted.contentEquals(readings[i].getText())) readings[i].setText(formatted);
            if (renderedMask!=state.lowMask) {
                boolean low=state.low(i);
                cards[i].setBackground(frame(low ? 0xff442b2b : TILE,low ? WARNING : EDGE,11));
                readings[i].setTextColor(low ? WARNING : TEXT);
            }
        }
        if (renderedMask!=state.lowMask) {
            renderedMask=state.lowMask;
            car.lowMask=renderedMask;
            car.invalidate();
        }
    }
    private Button action(String label,boolean primary,Runnable run) {
        Button button=new Button(context);
        button.setAllCaps(false); button.setText(label); button.setTextSize(13);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(17),0,dp(17),0);
        button.setTextColor(primary ? 0xff07242c : TEXT);
        button.setBackground(frame(primary ? ACCENT : TILE,primary ? 0xffabfff0 : EDGE,10));
        button.setOnClickListener(view -> run.run());
        return button;
    }
    private GradientDrawable frame(int background,int stroke,int radius) {
        GradientDrawable frame=new GradientDrawable();
        frame.setColor(background); frame.setCornerRadius(dp(radius)); frame.setStroke(dp(1),stroke);
        return frame;
    }
    private LinearLayout column() {
        LinearLayout view=new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private TextView text(String label,int size,int color,boolean bold) {
        TextView view=new TextView(context);
        view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif",bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }

    private static final class CarShape extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path profile=new Path();
        int lowMask;
        CarShape(Context context) {
            super(context);
            profile.moveTo(30,19); profile.cubicTo(39,11,75,11,84,19);
            profile.quadTo(92,29,92,49); profile.lineTo(91,144);
            profile.quadTo(91,172,79,175); profile.quadTo(57,180,34,175);
            profile.quadTo(23,172,23,144); profile.lineTo(22,49); profile.quadTo(22,29,30,19);
            profile.close();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth()/114f,getHeight()/188f);
            for (int i=0;i<4;i++) {
                float x=i%2==0 ? 16 : 86, y=i<2 ? 39 : 118;
                paint.setColor((lowMask & (1<<i))!=0 ? WARNING : 0xff69aac4);
                canvas.drawRoundRect(x,y,x+12,y+31,4,4,paint);
            }
            paint.setColor(0xff224966); canvas.drawPath(profile,paint);
            paint.setColor(0xff8fd4ee); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f);
            canvas.drawPath(profile,paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff0c263d);
            canvas.drawRoundRect(32,49,82,76,8,8,paint);
            canvas.drawRoundRect(32,128,82,153,6,6,paint);
            canvas.drawRoundRect(16,54,25,63,3,3,paint);
            canvas.drawRoundRect(89,54,98,63,3,3,paint);
            paint.setColor(0xff315d79);
            canvas.drawRoundRect(32,78,82,125,6,6,paint);
            paint.setColor(0xff27435b);
            canvas.drawRoundRect(38,81,76,106,4,4,paint);
            paint.setColor(0xff66a3be); paint.setStrokeWidth(1.3f);
            canvas.drawLine(31,77,31,138,paint); canvas.drawLine(83,77,83,138,paint);
            paint.setColor(0xff0c263d);
            canvas.drawRoundRect(46,17,68,21,2,2,paint);
            paint.setColor(ACCENT);
            canvas.drawRoundRect(29,25,44,29,2,2,paint);
            canvas.drawRoundRect(70,25,85,29,2,2,paint);
            paint.setColor(0xffd76d69);
            canvas.drawRoundRect(28,157,35,167,2,2,paint);
            canvas.drawRoundRect(79,157,86,167,2,2,paint);
            canvas.restore();
        }
    }
}
