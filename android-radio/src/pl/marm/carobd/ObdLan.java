package pl.marm.carobd;

import android.content.Context;
import android.net.*;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.IOException;
import java.net.*;

/** Wybiera połączenie Wi-Fi bez przełączania sieci dla GPS i synchronizacji NAS. */
final class ObdLan implements AutoCloseable {
    static final String SERVICE_NAME="CarOBD Radio";
    private final ConnectivityManager connectivity;
    private final NsdManager nsd;
    private final ObdExchange exchange;
    private ObdProxy server, loopback;
    private LinkAddress bound;
    private Network boundNetwork;
    private boolean closed;
    private volatile NsdManager.RegistrationListener registration;
    private volatile String serviceName;

    ObdLan(Context context,ObdExchange exchange) {
        connectivity=context.getSystemService(ConnectivityManager.class);
        nsd=context.getSystemService(NsdManager.class); this.exchange=exchange;
    }
    private static Network wifi(ConnectivityManager connectivity) {
        if (connectivity==null) return null;
        Network active=connectivity.getActiveNetwork();
        if (isWifi(connectivity,active)) return active;
        for (Network network:connectivity.getAllNetworks()) if (isWifi(connectivity,network)) return network;
        return null;
    }
    private static boolean isWifi(ConnectivityManager connectivity,Network network) {
        if (network==null) return false;
        NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    private static boolean privateAddress(InetAddress address) {
        return address instanceof Inet4Address && (address.isSiteLocalAddress() || address.isLinkLocalAddress());
    }
    private static LinkAddress localAddress(ConnectivityManager connectivity,Network network) {
        if (network==null) return null;
        LinkProperties links=connectivity.getLinkProperties(network);
        if (links!=null) for (LinkAddress address:links.getLinkAddresses())
            if (privateAddress(address.getAddress()) && address.getPrefixLength()>0) return address;
        return null;
    }
    static String radioAddress(Context context) {
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        LinkAddress address=localAddress(cm,wifi(cm));
        return address==null ? "" : address.getAddress().getHostAddress();
    }
    TcpElm connect(TcpElm.Address endpoint) throws IOException {
        Network network=wifi(connectivity);
        LinkAddress local=localAddress(connectivity,network);
        if (local==null) throw new IOException("Połącz radio z siecią Wi-Fi adaptera OBD");
        IOException failure=new IOException("Adres adaptera nie wskazuje lokalnej sieci IPv4");
        for (InetAddress address:network.getAllByName(endpoint.host)) {
            if (!privateAddress(address) || address.equals(local.getAddress())) continue;
            Socket socket=network.getSocketFactory().createSocket();
            try {
                socket.connect(new InetSocketAddress(address,endpoint.port),3000);
                return new TcpElm(socket);
            } catch (IOException e) { failure=e; try { socket.close(); } catch (IOException ignored) {} }
        }
        throw failure;
    }
    synchronized String update(boolean enabled,Profiles.Profile profile) {
        if (closed) return "";
        if (profile.isWifi() || !enabled) {
            stopSharing(); stopLoopback();
            return profile.isWifi() ? "Klient Wi-Fi · "+profile.adapter : "Proxy Wi-Fi wyłączone";
        }
        if (loopback==null || !loopback.open()) {
            stopLoopback();
            try { loopback=new ObdProxy(InetAddress.getByAddress(new byte[]{127,0,0,1}),TcpElm.PORT,32,exchange); }
            catch (IOException error) { /* Inna usługa może zajmować port lokalny. */ }
        }
        String local=loopback==null ? "Proxy lokalne niedostępne" : "127.0.0.1:"+TcpElm.PORT+" · klienci: "+loopback.clientCount();
        Network network=wifi(connectivity);
        LinkAddress address=localAddress(connectivity,network);
        if (address==null) { stopSharing(); return local; }
        if (server==null || !server.open() || !address.equals(bound) || !network.equals(boundNetwork)) {
            stopSharing();
            try {
                server=new ObdProxy(address.getAddress(),TcpElm.PORT,address.getPrefixLength(),exchange);
                bound=address; boundNetwork=network; announce();
            } catch (IOException error) { return local+"\nProxy Wi-Fi nie może otworzyć portu "+TcpElm.PORT; }
        }
        return bound.getAddress().getHostAddress()+":"+TcpElm.PORT+" · klienci: "+server.clientCount()+"\n"+local;
    }
    String publishedName() { String name=serviceName; return name==null ? "" : name; }
    private void announce() {
        if (nsd==null) return;
        NsdServiceInfo info=new NsdServiceInfo();
        info.setServiceName(SERVICE_NAME); info.setServiceType("_obd._tcp."); info.setPort(TcpElm.PORT);
        NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo service) {
                synchronized (ObdLan.this) { if (registration==this) serviceName=service.getServiceName(); }
            }
            @Override public void onRegistrationFailed(NsdServiceInfo s,int error) { clear(); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) { clear(); }
            @Override public void onUnregistrationFailed(NsdServiceInfo s,int error) { clear(); }
            private void clear() {
                synchronized (ObdLan.this) { if (registration==this) { registration=null; serviceName=null; } }
            }
        };
        registration=listener;
        try { nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener); }
        catch (RuntimeException unavailable) { registration=null; }
    }
    private void stopSharing() {
        if (server!=null) { server.close(); server=null; bound=null; boundNetwork=null; }
        NsdManager.RegistrationListener listener=registration;
        registration=null; serviceName=null;
        if (listener!=null) try { nsd.unregisterService(listener); } catch (RuntimeException ignored) {}
    }
    private void stopLoopback() { if (loopback!=null) { loopback.close(); loopback=null; } }
    @Override public synchronized void close() { closed=true; stopSharing(); stopLoopback(); }
}
