package pl.radiokia.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.function.Consumer;

final class XtRadio {
    private XtRadio() {}

    /** Wysyła jedno polecenie FM i zgłasza wynik rozmowy z XTService na wątku ekranu. */
    static void send(Context origin, int transaction, int frequency, Consumer<String> completed) {
        Context context = origin.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        class Request implements ServiceConnection, Runnable {
            private boolean finished;

            void finish(String error) {
                if (finished) return;
                finished = true;
                main.removeCallbacks(this);
                try {
                    context.unbindService(this);
                } catch (IllegalArgumentException ignored) { /* Brak zarejestrowanego połączenia. */ }
                completed.accept(error);
            }

            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (finished) return;
                main.removeCallbacks(this);
                new Thread(() -> {
                    String error = transact(binder, transaction, frequency);
                    main.post(() -> finish(error));
                }, "radiokia-fm").start();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                finish("Utracono połączenie z XTService.");
            }

            @Override public void onBindingDied(ComponentName name) {
                finish("Połączenie z XTService wygasło.");
            }

            @Override public void onNullBinding(ComponentName name) {
                finish("XTService nie udostępnił interfejsu FM.");
            }

            @Override public void run() {
                finish("Upłynął czas połączenia z XTService.");
            }
        }
        Request request = new Request();
        Intent intent = new Intent(Config.XT_SERVICE + ".Bind").setClassName(Config.XT_PACKAGE, Config.XT_SERVICE);
        try {
            if (context.bindService(intent, request, Context.BIND_AUTO_CREATE)) main.postDelayed(request, Config.XT_TIMEOUT_MS);
            else request.finish("XTService jest niedostępny.");
        } catch (SecurityException denied) {
            request.finish("Android odmówił połączenia z XTService.");
        }
    }

    /** Fabryczne FM przyjmuje częstotliwość w setnych MHz, a wyszukiwanie bez argumentu. */
    private static String transact(IBinder binder, int transaction, int frequency) {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (!Config.XT_INTERFACE.equals(binder.getInterfaceDescriptor())) return "Niezgodny interfejs XTService.";
            request.writeInterfaceToken(Config.XT_INTERFACE);
            if (transaction == Config.XT_TUNE) request.writeInt(frequency * 10);
            if (!binder.transact(transaction, request, reply, 0)) return "XTService nie obsługuje polecenia FM.";
            reply.readException();
            return null;
        } catch (RemoteException | RuntimeException failure) {
            return "Błąd XTService: " + failure.getClass().getSimpleName() + ".";
        } finally {
            request.recycle();
            reply.recycle();
        }
    }
}
