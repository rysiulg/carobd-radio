package pl.radiokia.preview;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mały uchwyt tylko na ekranie Home, pełnym FM i pełnym CarOBD. */
public final class ThemeSwipeService extends AccessibilityService {
    private static final String CAROBD="pl.marm.carobd";
    private static final Uri CAROBD_STATUS=Uri.parse("content://pl.marm.carobd.dashboard");
    private static final String[] APPS={"pl.radiokia.fm",CAROBD};
    private static final String[] LABELS={"Ekran główny","RadioKIA FM","CarOBD"};
    private static final int HOME=0;
    private final ExecutorService background=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver screenWake=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) wakeCarObd();
        }
    };
    private long nextWake;
    private boolean wakeRegistered;
    private WindowManager windows;
    private TextView handle;
    private int active=-1;
    private float startX,startY;
    private boolean tracking;

    @Override protected void onServiceConnected() {
        windows=getSystemService(WindowManager.class);
        if (!wakeRegistered) {
            IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT>=33) registerReceiver(screenWake,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenWake,filter);
            wakeRegistered=true;
        }
        wakeCarObd();
    }

    /** Wybudzenie TS7 nie musi rozsyłać BOOT_COMPLETED. Podpisany dostawca wznawia rejestrator. */
    private void wakeCarObd() {
        if (Build.VERSION.SDK_INT>28) return;
        long now=SystemClock.elapsedRealtime();
        if (now<nextWake) return;
        nextWake=now+5000;
        background.execute(() -> {
            try {
                ProviderInfo provider=getPackageManager().resolveContentProvider(CAROBD_STATUS.getAuthority(),0);
                if (provider!=null && CAROBD.equals(provider.packageName))
                    getContentResolver().call(CAROBD_STATUS,"snapshot",null,null);
            } catch (RuntimeException unavailable) {
                // Brak CarOBD albo brak zgodnego podpisu nie blokuje gestów motywu.
            }
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (windows==null || event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg=event.getPackageName(), name=event.getClassName();
        if (pkg==null || name==null) return;
        // Okno uchwytu i jego elementy nie są zmianą aktywnej aplikacji.
        if (getPackageName().contentEquals(pkg) && !Dashboard.class.getName().contentEquals(name)) return;
        int page=pageOf(pkg,name);
        if (page<0) hide();
        else {
            if (page==HOME) wakeCarObd();
            show(page);
        }
    }

    private ComponentName home() {
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved=getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved==null || resolved.activityInfo==null) return null;
        String pkg=resolved.activityInfo.packageName;
        if (getPackageName().equals(pkg) || "android".equals(pkg)) return null;
        return new ComponentName(pkg,resolved.activityInfo.name);
    }
    private int pageOf(CharSequence pkg,CharSequence cls) {
        ComponentName home=home();
        if (home==null) return -1;
        if (matches(home,pkg,cls)) return HOME;
        for (int i=0;i<APPS.length;i++) {
            if (!APPS[i].contentEquals(pkg)) continue;
            Intent launch=getPackageManager().getLaunchIntentForPackage(APPS[i]);
            if (launch!=null && matches(launch.getComponent(),pkg,cls)) return i+1;
        }
        return -1;
    }
    private boolean matches(ComponentName component,CharSequence pkg,CharSequence cls) {
        return component!=null && component.getPackageName().contentEquals(pkg) &&
            component.getClassName().contentEquals(cls);
    }

    private void show(int page) {
        if (active==page && handle!=null) return;
        active=page;
        if (handle==null) {
            handle=new TextView(this);
            handle.setTextColor(Color.WHITE); handle.setTextSize(15);
            handle.setGravity(Gravity.CENTER); handle.setIncludeFontPadding(false);
            GradientDrawable shape=new GradientDrawable();
            shape.setColor(0xe621303e); shape.setCornerRadius(dp(16));
            handle.setBackground(shape);
            handle.setOnTouchListener((view,event) -> gesture(event));
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(dp(112),dp(34),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
            params.y=dp(7);
            params.setTitle("RadioKIA swipe");
            try { windows.addView(handle,params); }
            catch (WindowManager.BadTokenException | SecurityException unavailable) { handle=null; active=-1; return; }
        }
        String[] marks={"● ○ ○","○ ● ○","○ ○ ●"};
        handle.setText((page>0 ? "‹   " : "    ")+marks[page]+(page<LABELS.length-1 ? "   ›" : "    "));
        handle.setContentDescription(LABELS[page]+". Przesuń uchwyt w bok, aby zmienić ekran.");
    }

    private boolean gesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX=event.getRawX(); startY=event.getRawY(); tracking=true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                tracking=false;
                break;
            case MotionEvent.ACTION_UP:
                float dx=event.getRawX()-startX, dy=event.getRawY()-startY;
                if (tracking && Math.abs(dx)>=dp(44) && Math.abs(dx)>=1.5f*Math.abs(dy))
                    move(dx<0 ? 1 : -1);
                tracking=false;
                break;
        }
        return true;
    }
    private void move(int step) {
        int next=active+step;
        if (active<0 || next<0 || next>=LABELS.length) return;
        if (next==HOME) {
            if (home()!=null && performGlobalAction(GLOBAL_ACTION_HOME)) hide();
            return;
        }
        Intent launch=getPackageManager().getLaunchIntentForPackage(APPS[next-1]);
        if (launch!=null) {
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                hide(); return;
            } catch (ActivityNotFoundException | SecurityException unavailable) { /* Aplikację można uruchomić też z menu. */ }
        }
        Toast.makeText(this,"Nie można otworzyć "+LABELS[next],Toast.LENGTH_SHORT).show();
    }
    private void hide() {
        active=-1; tracking=false;
        if (handle==null) return;
        windows.removeViewImmediate(handle); handle=null;
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    @Override public void onInterrupt() { hide(); }
    @Override public boolean onUnbind(Intent intent) {
        hide();
        if (wakeRegistered) { unregisterReceiver(screenWake); wakeRegistered=false; }
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }
}
