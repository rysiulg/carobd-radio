package pl.marm.carobd;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.function.DoubleConsumer;

/** Odczyt temperatury zewnętrznej z MCU używany przez fabryczny pasek TS7. */
final class TopwayAmbient implements AutoCloseable {
    private static final short TOPIC=0x0508;
    private static final String CLIENT="CarOBD ambient";
    private final DoubleConsumer onReading;
    private final Handler handler=new Handler(Looper.getMainLooper(),this::receive);
    private Class<?> api;
    private Object client;

    TopwayAmbient(DoubleConsumer onReading) { this.onReading=onReading; }

    void start() {
        if (client!=null || Build.VERSION.SDK_INT!=Build.VERSION_CODES.O_MR1) return;
        try {
            api=Class.forName("android.tw.john.TWUtil");
            client=api.getConstructor().newInstance();
            if ((Integer)api.getMethod("open",short[].class).invoke(client,(Object)new short[]{TOPIC})!=0) {
                close(); return;
            }
            api.getMethod("addHandler",String.class,Handler.class).invoke(client,CLIENT,handler);
            api.getMethod("start").invoke(client);
            request();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { close(); }
    }

    void request() {
        if (client==null) return;
        try {
            // Te same parametry żądania statusu co w fabrycznym SystemUI.
            api.getMethod("write",int.class,int.class,int.class).invoke(client,(int)TOPIC,0xff,0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
    }

    static double decode(byte[] data) {
        if (data==null || data.length<2) return Double.NaN;
        int celsius=data[1];
        return celsius>-40 && celsius<80 ? celsius : Double.NaN;
    }

    private boolean receive(Message message) {
        if (client!=null && (message.what&0xffff)==TOPIC && message.obj instanceof byte[])
            onReading.accept(decode((byte[])message.obj));
        return true;
    }

    @Override public void close() {
        Object owned=client;
        client=null; handler.removeCallbacksAndMessages(null);
        if (owned==null) return;
        try { api.getMethod("removeHandler",String.class).invoke(owned,CLIENT); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
        try { api.getMethod("stop").invoke(owned); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
        try { api.getMethod("close").invoke(owned); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
    }
}
