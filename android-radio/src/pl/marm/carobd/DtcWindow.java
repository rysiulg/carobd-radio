package pl.marm.carobd;

import static pl.marm.carobd.UiLanguage.tr;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Kody i status monitorów EOBD. Kasowanie wymaga oddzielnego potwierdzenia. */
final class DtcWindow {
    private final Dashboard activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> expanded=new HashSet<>();
    private Dtc.Report shown;

    DtcWindow(Dashboard activity) { this.activity=activity; }

    void show() { show(""); }

    void show(String codeToExpand) {
        if (codeToExpand!=null && !codeToExpand.isEmpty()) expanded.add(codeToExpand);
        LinearLayout content=new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView status=label(content,16), operation=label(content,14);
        LinearLayout rows=new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL); content.addView(rows);
        LinearLayout monitors=new LinearLayout(activity); monitors.setOrientation(LinearLayout.VERTICAL); content.addView(monitors);
        ScrollView scroll=new ScrollView(activity); scroll.addView(content);
        LinearLayout panel=panel(tr("Diagnostyka EOBD"));
        LinearLayout tabs=new LinearLayout(activity); tabs.setPadding(dp(20),dp(5),dp(20),0);
        panel.addView(tabs,-1,-2);
        Button codesTab=activity.button(tr("Kody usterek"),true,() -> {});
        Button monitorsTab=activity.button(tr("Monitory emisji"),false,() -> {});
        tabs.addView(codesTab,new LinearLayout.LayoutParams(0,dp(48),1));
        tabs.addView(new View(activity),dp(8),1);
        tabs.addView(monitorsTab,new LinearLayout.LayoutParams(0,dp(48),1));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        AlertDialog dialog=new AlertDialog.Builder(activity).setView(panel).create();
        Button clear=actions(panel,tr("Zamknij"),dialog::dismiss,tr("Skasuj kody"),this::confirm);
        clear.setEnabled(false);
        TextView monitorSubtitle=label(monitors,14); monitorSubtitle.setText(tr("Gotowość od ostatniego skasowania kodów"));
        monitorSubtitle.setTextColor(Dashboard.MUTE);
        LinearLayout monitorRows=new LinearLayout(activity); monitorRows.setOrientation(LinearLayout.VERTICAL); monitors.addView(monitorRows);
        renderMonitors(monitorRows,null);
        monitors.setVisibility(View.GONE);
        codesTab.setSelected(true);
        View.OnClickListener switchTab=v -> {
            boolean showMonitors=v==monitorsTab;
            rows.setVisibility(showMonitors ? View.GONE : View.VISIBLE);
            monitors.setVisibility(showMonitors ? View.VISIBLE : View.GONE);
            clear.setVisibility(showMonitors ? View.GONE : View.VISIBLE);
            styleTab(codesTab,!showMonitors); styleTab(monitorsTab,showMonitors);
            scroll.scrollTo(0,0);
        };
        codesTab.setOnClickListener(switchTab); monitorsTab.setOnClickListener(switchTab);
        Runnable update=new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || activity.isDestroyed()) return;
                Recorder.Live live=Recorder.live;
                Dtc.Report report=live.dtcs;
                changed(status,!live.obd ? tr("Brak połączenia z OBD") : report==null ? tr("Odczytuję status diagnostyki…") :
                    report.flags<0 ? tr("Brak odczytu stanu kontrolki Check Engine") :
                    (report.mil() ? tr("Check Engine włączony") : tr("Check Engine wyłączony"))+tr(" · zapisane: ")+report.storedCount());
                changed(operation,!live.dtcStatus.isEmpty() ? UiLanguage.tr(live.dtcStatus) : report!=null && report.clearable() && !live.canClearDtc() ?
                    tr("Kasowanie jest dostępne na postoju, z włączonym zapłonem i zgaszonym silnikiem.") : "");
                operation.setVisibility(operation.length()==0 ? View.GONE : View.VISIBLE);
                if (report!=shown && (report==null || shown==null || report.flags!=shown.flags || !report.results.equals(shown.results)))
                    render(rows,report);
                if (report!=shown && (report==null || shown==null || report.flags!=shown.flags || !report.monitors.equals(shown.monitors)))
                    renderMonitors(monitorRows,report);
                shown=report;
                clear.setEnabled(live.canClearDtc());
                main.postDelayed(this,500);
            }
        };
        dialog.setOnDismissListener(v -> main.removeCallbacks(update));
        dialog.setOnShowListener(v -> {
            style(dialog);
            main.post(update);
        });
        dialog.show();
    }
    private void styleTab(Button button,boolean active) {
        button.setSelected(active);
        button.setBackground(activity.box(active ? Dashboard.MINT : Dashboard.EDGE,12));
        button.setTextColor(active ? Dashboard.DARK : Dashboard.WHITE);
    }
    private void renderMonitors(LinearLayout rows,Dtc.Report report) {
        rows.removeAllViews();
        if (report==null || report.flags<0) {
            label(rows,15).setText(tr("Brak odczytu monitorów z ECU"));
            return;
        }
        if (report.monitors.isEmpty()) {
            label(rows,15).setText(tr("ECU nie zgłosiło obsługiwanych monitorów"));
            return;
        }
        for (Map.Entry<String,Boolean> monitor:report.monitors.entrySet()) {
            LinearLayout line=new LinearLayout(activity);
            line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(dp(10),dp(4),dp(10),dp(4));
            line.setBackground(activity.box(Dashboard.EDGE,9));
            LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(-1,-2);
            item.topMargin=dp(5); rows.addView(line,item);
            TextView name=label(line,15); name.setText(UiLanguage.tr(monitor.getKey()));
            name.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            TextView state=label(line,14);
            state.setText(monitor.getValue() ? tr("Gotowy") : tr("Nieukończony"));
            state.setTextColor(monitor.getValue() ? Dashboard.MINT : Dashboard.WARNING);
        }
    }
    private void render(LinearLayout rows,Dtc.Report report) {
        rows.removeAllViews();
        if (report==null) return;
        Map<String,Integer> codes=report.codes();
        for (Map.Entry<String,Integer> entry:codes.entrySet()) {
            String code=entry.getKey(); List<String> tags=new ArrayList<>();
            for (int i=0;i<Dtc.STATUS.length;i++) if ((entry.getValue()&(1<<i))!=0) tags.add(UiLanguage.tr(Dtc.STATUS[i]));
            addCode(rows,code,String.join(" / ",tags));
        }
        List<String> missing=new ArrayList<>();
        for (int i=0;i<Dtc.STATUS.length;i++) if (report.results.get(i)==null) missing.add(UiLanguage.tr(Dtc.STATUS[i]));
        if (codes.isEmpty() && missing.size()<Dtc.STATUS.length)
            label(rows,14).setText(missing.isEmpty() ? tr("Brak kodów DTC") : tr("Brak kodów w trybach, które odpowiedziały"));
        if (!missing.isEmpty()) label(rows,14).setText(tr("Bez odpowiedzi ECU · ")+String.join(", ",missing));
    }
    private void addCode(LinearLayout rows,String code,String status) {
        LinearLayout card=new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(activity.box(Dashboard.EDGE,12));
        LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(-1,-2);
        item.topMargin=dp(6); rows.addView(card,item);
        Button toggle=activity.button("",false,() -> {});
        toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        card.addView(toggle,-1,dp(48));

        LinearLayout details=new LinearLayout(activity); details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(14),0,dp(14),dp(8)); card.addView(details,-1,-2);
        TextView category=label(details,13); category.setText(UiLanguage.tr(Dtc.category(code))); category.setTextColor(Dashboard.MUTE);
        label(details,16).setText(UiLanguage.dtc(Dtc.description(code)));
        Runnable refresh=() -> {
            boolean open=expanded.contains(code);
            SpannableString title=new SpannableString((open ? "▴" : "▾")+"  "+code+"   ·   "+status);
            title.setSpan(new StyleSpan(Typeface.BOLD),3,3+code.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            toggle.setText(title);
            toggle.setContentDescription(code+" · "+status+" · "+(open ? tr("Zwiń opis") : tr("Rozwiń opis")));
            toggle.setTextColor(open ? Dashboard.MINT : Dashboard.WHITE);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
        };
        refresh.run();
        toggle.setOnClickListener(v -> { if (!expanded.add(code)) expanded.remove(code); refresh.run(); });
    }
    private void confirm() {
        Recorder.Live live=Recorder.live;
        if (!live.canClearDtc()) return;
        LinearLayout panel=panel(tr("Skasować kody emisji EOBD?"));
        TextView message=label(panel,16); message.setPadding(dp(20),dp(8),dp(20),dp(10));
        message.setText(tr("Sterownik może usunąć zapisane kody i ramki zamrożone oraz wyzerować gotowość monitorów emisji. Kody trwałe znikają po potwierdzeniu naprawy przez ECU."));
        AlertDialog dialog=new AlertDialog.Builder(activity).setView(panel).create();
        actions(panel,tr("Anuluj"),dialog::dismiss,tr("Skasuj"),() -> {
            dialog.dismiss();
            if (Recorder.live.canClearDtc() && live.profileId.equals(Recorder.live.profileId)) activity.startService(
                new Intent(activity,Recorder.class).setAction(Recorder.CLEAR_DTC).putExtra(Recorder.PROFILE,live.profileId));
        });
        dialog.show();
        style(dialog);
    }
    private LinearLayout panel(String title) {
        LinearLayout panel=new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(panel,22); heading.setText(title); heading.setTypeface(null,Typeface.BOLD);
        heading.setPadding(dp(20),dp(15),dp(20),dp(4));
        return panel;
    }
    private Button actions(LinearLayout panel,String close,Runnable dismiss,String danger,Runnable confirm) {
        LinearLayout row=new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20),dp(4),dp(20),dp(12)); panel.addView(row,-1,-2);
        row.addView(activity.button(close,false,dismiss),-2,dp(48));
        row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        Button clear=activity.button(danger,false,confirm);
        int[] disabled={-android.R.attr.state_enabled};
        StateListDrawable background=new StateListDrawable();
        background.addState(disabled,activity.box(0xff502f3e,12));
        background.addState(new int[0],activity.box(0xffa8293b,12));
        clear.setBackground(background);
        clear.setTextColor(new ColorStateList(new int[][]{disabled,new int[0]},new int[]{0xffe5a2aa,Dashboard.WHITE}));
        row.addView(clear,-2,dp(48));
        return clear;
    }
    private void style(AlertDialog dialog) {
        dialog.getWindow().setBackgroundDrawable(activity.box(Dashboard.PANEL,16));
        dialog.getWindow().setLayout(Math.min(dp(620),activity.getResources().getDisplayMetrics().widthPixels-dp(24)),-2);
    }
    private TextView label(LinearLayout parent,int sp) {
        TextView text=new TextView(activity); text.setTextSize(sp); text.setTextColor(Dashboard.WHITE);
        text.setPadding(0,dp(4),0,dp(4)); parent.addView(text);
        return text;
    }
    private int dp(int size) { return Math.round(size*activity.getResources().getDisplayMetrics().density); }
    private static void changed(TextView view,String text) { if (!text.contentEquals(view.getText())) view.setText(text); }
}
