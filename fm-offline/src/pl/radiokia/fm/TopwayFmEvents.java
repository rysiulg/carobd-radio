package pl.radiokia.fm;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.math.BigDecimal;

final class TopwayFmEvents {
    interface Listener {
        void onTunerChannel(int frequency);
        void onTunerSteering(int direction, boolean seek);
    }

    private static final int RADIO_KEY = 0x9E0A, RADIO_STATE = 0x9F00, FM_SOURCE = 10;
    private static final String NAME = "RadioKIA FM";
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper(), this::receive);
    private Class<?> api;
    private Object client;

    private TopwayFmEvents(Listener listener) { this.listener = listener; }

    /** Obserwuje wyłącznie komunikaty FM z interfejsu Topway używanego przez TS7 z Androidem 8.1. */
    static TopwayFmEvents open(Listener listener) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) return null;
        TopwayFmEvents events = new TopwayFmEvents(listener);
        try {
            events.api = Class.forName("android.tw.john.TWUtil");
            short[] topics = {(short) RADIO_KEY, (short) RADIO_STATE};
            events.client = events.api.getMethod("open", int.class, short[].class, int.class).invoke(null, 0, topics, 0);
            if (events.client == null) return null;
            events.api.getMethod("addHandler", String.class, Handler.class).invoke(events.client, NAME, events.handler);
            events.api.getMethod("start").invoke(events.client);
            return events;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            events.close();
            return null;
        }
    }

    private boolean receive(Message message) {
        if (client == null) return true;
        switch (message.what & 0xffff) {
            case RADIO_STATE:
                if (message.arg1 == FM_SOURCE && message.arg2 == 0 && message.obj instanceof String) {
                    int frequency = decodeFrequency((String) message.obj);
                    if (frequency != 0) listener.onTunerChannel(frequency);
                }
                break;
            case RADIO_KEY:
                if (message.arg2 != 0) break;
                switch (message.arg1) {
                    case 3: listener.onTunerSteering(1, false); break;
                    case 4: listener.onTunerSteering(-1, false); break;
                    case 9: listener.onTunerSteering(1, true); break;
                    case 10: listener.onTunerSteering(-1, true); break;
                }
                break;
        }
        return true;
    }

    static int decodeFrequency(String value) {
        try {
            int frequency = new BigDecimal(value.trim()).movePointRight(1).intValueExact();
            return frequency >= Config.FM_MIN && frequency <= Config.FM_MAX ? frequency : 0;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return 0;
        }
    }

    /** Zwalnia subskrypcję w procesie RadioKIA. */
    void close() {
        Object owned = client;
        client = null;
        handler.removeCallbacksAndMessages(null);
        if (owned == null) return;
        try {
            api.getMethod("removeHandler", String.class).invoke(owned, NAME);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Zamknięcie nadal jest możliwe. */ }
        try {
            api.getMethod("stop").invoke(owned);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Zamknięcie nadal jest możliwe. */ }
        try {
            api.getMethod("close").invoke(owned);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* System zwolni uchwyt procesu. */ }
    }
}
