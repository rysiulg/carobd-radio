package pl.radiokia.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class XtRadioEvents implements ServiceConnection {
    interface Listener {
        void onRadioEvent(int operation, int frequency);
    }

    static final int NEXT = 1, PREVIOUS = 2, SEEK_NEXT = 3, SEEK_PREVIOUS = 4, TUNE = 5;
    private static final int REGISTER = 3, UNREGISTER = 4;
    private static final String DESCRIPTOR = Config.XT_PACKAGE + ".aidl.IRadioCallBack";
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Binder callback = new Binder() {
        { attachInterface(null, DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < NEXT || code > 8) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            int frequency = code == TUNE ? channel(data.readString()) : 0;
            if (code <= TUNE) main.post(() -> {
                if (!closed) listener.onRadioEvent(code, frequency);
            });
            if (reply != null) reply.writeNoException();
            return true;
        }
    };
    private IBinder service;
    private boolean closed;

    XtRadioEvents(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Rejestruje osobnego obserwatora FM obok fabrycznego odbiorcy poleceń. */
    void start() {
        Intent intent = new Intent(Config.XT_SERVICE + ".Bind").setClassName(Config.XT_PACKAGE, Config.XT_SERVICE);
        try {
            if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) close();
        } catch (SecurityException denied) {
            close();
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (closed) return;
        service = binder;
        worker.execute(() -> changeRegistration(binder, REGISTER));
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /** Kończy obserwację poza aktywnym ekranem i zwalnia wyłącznie własny callback. */
    void close() {
        if (closed) return;
        closed = true;
        IBinder binder = service;
        if (binder != null) worker.execute(() -> changeRegistration(binder, UNREGISTER));
        worker.shutdown();
        try {
            context.unbindService(this);
        } catch (IllegalArgumentException ignored) { /* Brak zarejestrowanego połączenia. */ }
    }

    private void changeRegistration(IBinder binder, int operation) {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (!Config.XT_INTERFACE.equals(binder.getInterfaceDescriptor())) return;
            request.writeInterfaceToken(Config.XT_INTERFACE);
            request.writeStrongBinder(callback);
            if (binder.transact(operation, request, reply, 0)) reply.readException();
        } catch (RemoteException | RuntimeException unavailable) {
            // Sterowanie przez Android pozostaje dostępne, gdy XTService nie rejestruje obserwatora.
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private static int channel(String value) {
        try {
            int channel = Integer.parseInt(value);
            return channel >= Config.FM_MIN * 10 && channel <= Config.FM_MAX * 10 ? channel / 10 : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
