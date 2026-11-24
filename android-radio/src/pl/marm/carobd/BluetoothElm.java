package pl.marm.carobd;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** ELM przez sparowany RFCOMM albo usługi GATT: WRITE i NOTIFY. */
final class BluetoothElm {
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Krótki pomiar obecności konkretnego adaptera, bez łączenia z innymi urządzeniami. */
    static boolean visible(BluetoothAdapter adapter,String address) throws InterruptedException {
        BluetoothLeScanner scanner;
        try { scanner=adapter.getBluetoothLeScanner(); }
        catch (RuntimeException unavailable) { return false; }
        if (scanner==null) return false;
        CountDownLatch nearby=new CountDownLatch(1);
        ScanCallback callback=new ScanCallback() {
            @Override public void onScanResult(int type,ScanResult result) {
                if (address.equalsIgnoreCase(result.getDevice().getAddress())) nearby.countDown();
            }
        };
        try {
            ScanFilter filter=new ScanFilter.Builder().setDeviceAddress(address).build();
            ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filter),settings,callback);
            return nearby.await(1800,TimeUnit.MILLISECONDS);
        } catch (RuntimeException unavailable) { return false; }
        finally { try { scanner.stopScan(callback); } catch (RuntimeException ignored) {} }
    }

    static class Classic implements Elm.Link {
        private final BluetoothSocket socket;
        Classic(BluetoothDevice device) throws IOException { socket=device.createRfcommSocketToServiceRecord(SPP); }
        void connect() throws IOException { socket.connect(); }
        @Override public String query(String command,int timeoutMs) throws Exception {
            InputStream in=socket.getInputStream();
            OutputStream out=socket.getOutputStream();
            out.write((command+"\r").getBytes(StandardCharsets.US_ASCII)); out.flush();
            StringBuilder response=new StringBuilder();
            long deadline=SystemClock.elapsedRealtime()+timeoutMs;
            byte[] data=new byte[256];
            while (SystemClock.elapsedRealtime()<deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (in.available()==0) { Thread.sleep(16); continue; }
                int n=in.read(data);
                if (n<0) throw new EOFException();
                response.append(new String(data,0,n,StandardCharsets.US_ASCII));
                if (response.indexOf(">")>=0) return response.toString();
                if (response.length()>8192) throw new IOException("Za długa odpowiedź ELM");
            }
            throw new IOException("Przekroczono czas odpowiedzi ELM");
        }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }

    static class Ble extends BluetoothGattCallback implements Elm.Link {
        private final BluetoothDevice device;
        private final LinkedBlockingQueue<String> responses=new LinkedBlockingQueue<>(64);
        private final LinkedBlockingQueue<Boolean> setup=new LinkedBlockingQueue<>(2);
        private final LinkedBlockingQueue<Boolean> writes=new LinkedBlockingQueue<>(2);
        private volatile BluetoothGatt gatt;
        private volatile BluetoothGattCharacteristic tx,rx;
        private volatile boolean closed,connected;
        Ble(BluetoothDevice device) { this.device=device; }
        void connect(Context context) throws Exception {
            gatt=device.connectGatt(context,false,this,BluetoothDevice.TRANSPORT_LE);
            if (gatt==null || !Boolean.TRUE.equals(setup.poll(12,TimeUnit.SECONDS))) throw new IOException("BLE: brak kanału ELM");
        }
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            if (closed) return;
            if (status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
                connected=true;
                if (!g.discoverServices()) setup.offer(false);
            } else {
                connected=false; setup.offer(false); writes.offer(false); responses.offer("");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
            if (closed || status!=BluetoothGatt.GATT_SUCCESS) { setup.offer(false); return; }
            int best=-1;
            for (BluetoothGattService service : g.getServices()) for (BluetoothGattCharacteristic w : service.getCharacteristics()) {
                if ((w.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))==0) continue;
                for (BluetoothGattCharacteristic n : service.getCharacteristics()) {
                    if ((n.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))==0 ||
                        n.getDescriptor(CCC)==null) continue;
                    String id=service.getUuid().toString().toLowerCase(Locale.ROOT);
                    int score=id.startsWith("0000fff0") || id.startsWith("0000ffe0") || id.startsWith("000018f0") ||
                        id.startsWith("6e400001") ? 10 : 1;
                    if (w.getUuid().toString().startsWith("0000fff2")) score+=2;
                    if (n.getUuid().toString().startsWith("0000fff1")) score+=2;
                    if (score>best) { best=score; tx=w; rx=n; }
                }
            }
            if (rx==null || !g.setCharacteristicNotification(rx,true)) { setup.offer(false); return; }
            BluetoothGattDescriptor descriptor=rx.getDescriptor(CCC);
            descriptor.setValue((rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0 ?
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            if (!g.writeDescriptor(descriptor)) setup.offer(false);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {
            if (CCC.equals(d.getUuid())) setup.offer(status==BluetoothGatt.GATT_SUCCESS);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {
            writes.offer(status==BluetoothGatt.GATT_SUCCESS);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            if (c==rx) responses.offer(new String(c.getValue(),StandardCharsets.US_ASCII));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            if (c==rx) responses.offer(new String(value,StandardCharsets.US_ASCII));
        }
        @Override public String query(String command,int timeoutMs) throws Exception {
            BluetoothGatt g=gatt; BluetoothGattCharacteristic w=tx;
            if (closed || !connected || g==null || w==null) throw new IOException("Adapter BLE odłączony");
            responses.clear(); writes.clear();
            if (!connected) throw new IOException("Adapter BLE odłączony");
            boolean confirmed=(w.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)!=0;
            w.setWriteType(confirmed ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            w.setValue((command+"\r").getBytes(StandardCharsets.US_ASCII));
            if (!g.writeCharacteristic(w) || (confirmed && !Boolean.TRUE.equals(writes.poll(1500,TimeUnit.MILLISECONDS))))
                throw new IOException("BLE: błąd wysłania polecenia");
            long deadline=SystemClock.elapsedRealtime()+timeoutMs;
            StringBuilder data=new StringBuilder();
            while (connected && deadline>SystemClock.elapsedRealtime()) {
                String line=responses.poll(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
                if (line==null || line.isEmpty()) break;
                data.append(line);
                if (data.indexOf(">")>=0) return data.toString();
                if (data.length()>8192) break;
            }
            throw new IOException("BLE: brak pełnej odpowiedzi ELM");
        }
        boolean connected() { return connected; }
        @Override public void close() {
            closed=true; connected=false; setup.offer(false); writes.offer(false); responses.offer("");
            BluetoothGatt current=gatt;
            if (current!=null) {
                try { current.disconnect(); } catch (RuntimeException ignored) {}
                try { current.close(); } catch (RuntimeException ignored) {}
            }
        }
    }
}
