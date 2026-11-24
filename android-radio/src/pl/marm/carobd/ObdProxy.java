package pl.marm.carobd;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Lokalne proxy ELM. Klienci mają własne ustawienia wyjścia, bez dostępu do konfiguracji adaptera. */
final class ObdProxy implements AutoCloseable {
    private static final int MAX_CLIENTS=4, MAX_COMMAND=64;
    private static final Pattern READ=Pattern.compile("(?:01|09)[0-9A-F]{2}|03|07|0A|ATRV|ATDP|ATDPN");
    private final ServerSocket listener=new ServerSocket();
    private final Set<Socket> clients=new HashSet<>();
    private final byte[] localIp;
    private final int prefix;
    private final ObdExchange exchange;
    private volatile boolean closed;

    ObdProxy(InetAddress ip,int port,int prefix,ObdExchange exchange) throws IOException {
        localIp=ip.getAddress();
        if (localIp.length!=4 || prefix<1 || prefix>32) { listener.close(); throw new IOException("Brak lokalnej sieci IPv4"); }
        this.prefix=prefix; this.exchange=exchange;
        try {
            listener.setReuseAddress(true); listener.bind(new InetSocketAddress(ip,port),MAX_CLIENTS);
        } catch (IOException e) { listener.close(); throw e; }
        start("OBD Wi-Fi listen",this::accept);
    }
    int port() { return listener.getLocalPort(); }
    boolean open() { return !closed; }
    synchronized int clientCount() { return clients.size(); }
    static boolean readOnly(String cmd) { return READ.matcher(cmd).matches(); }

    private static void start(String name,Runnable task) {
        Thread thread=new Thread(task,name); thread.setDaemon(true); thread.start();
    }
    private boolean local(Socket socket) {
        byte[] remote=socket.getInetAddress().getAddress();
        if (remote.length!=localIp.length) return false;
        for (int i=0,m=prefix;m>0;i++,m-=8) {
            int mask=0xff<<(8-Math.min(8,m));
            if (((localIp[i]^remote[i])&0xff&mask)!=0) return false;
        }
        return true;
    }
    private void accept() {
        try {
            while (!closed) {
                Socket socket=listener.accept();
                if (!local(socket)) { closeSocket(socket); continue; }
                synchronized (this) {
                    if (closed || clients.size()>=MAX_CLIENTS) {
                        try { send(socket.getOutputStream(),"BUSY\r>"); } catch (IOException ignored) {}
                        closeSocket(socket); continue;
                    }
                    clients.add(socket);
                }
                start("OBD Wi-Fi client",() -> serve(socket));
            }
        } catch (IOException unavailable) { close(); }
    }
    private void serve(Socket socket) {
        try {
            socket.setKeepAlive(true); socket.setTcpNoDelay(true);
            InputStream in=new BufferedInputStream(socket.getInputStream());
            OutputStream out=socket.getOutputStream();
            Client client=new Client();
            StringBuilder line=new StringBuilder();
            boolean oversized=false, lastCr=false;
            for (int value;(value=in.read())>=0;) {
                if (value=='\n' && lastCr) { lastCr=false; continue; }
                lastCr=value=='\r';
                if (lastCr || value=='\n') {
                    send(out,oversized ? "?\r>" : client.answer(line.toString(),exchange));
                    line.setLength(0); oversized=false;
                } else if (line.length()<MAX_COMMAND && value>=32 && value<=126) line.append((char)value);
                else oversized=true;
            }
        } catch (IOException ignored) {
            // Rozłączenie jest lokalne dla tego klienta.
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            closeSocket(socket);
            synchronized (this) { clients.remove(socket); }
        }
    }
    private static void send(OutputStream out,String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    static final class Client {
        private boolean echo=true, spaces=true, lineFeeds;
        private String last="";
        String answer(String line,ObdExchange exchange) throws InterruptedException {
            String cmd=line.replace(" ","").toUpperCase(Locale.ROOT);
            if (cmd.isEmpty()) cmd=last;
            else last=cmd;
            String prefix=echo && !line.isEmpty() ? line+"\r" : "";
            String reply;
            switch (cmd) {
                case "ATI": reply="ELM327 v1.5"; break;
                case "ATZ": case "ATWS": reset(); reply="ELM327 v1.5"; break;
                case "ATD": reset(); reply="OK"; break;
                case "ATE0": case "ATE1": echo=cmd.endsWith("1"); reply="OK"; break;
                case "ATS0": case "ATS1": spaces=cmd.endsWith("1"); reply="OK"; break;
                case "ATL0": case "ATL1": lineFeeds=cmd.endsWith("1"); reply="OK"; break;
                case "ATH0": case "ATSP0": case "ATAT0": case "ATAT1": case "ATAT2":
                    reply="OK"; break;
                case "AT@1": reply="CarOBD Wi-Fi"; break;
                default:
                    if (cmd.matches("(?:01|09)[0-9A-F]{2}[1-9]")) cmd=cmd.substring(0,4);
                    reply=readOnly(cmd) ? format(exchange.request(cmd)) : "?";
            }
            String frame=prefix+reply+"\r>";
            return lineFeeds ? frame.replace("\r","\r\n") : frame;
        }
        private void reset() { echo=spaces=true; lineFeeds=false; last=""; }
        private String format(String raw) {
            String[] lines=raw.replace(">","").trim().split("[\r\n]+");
            for (int i=0;i<lines.length;i++) {
                String data=lines[i].replace(" ","");
                if (data.matches("(?:[0-9A-Fa-f]{2})+")) lines[i]=spaces ? data.replaceAll("..(?!$)","$0 ") : data;
            }
            String result=String.join("\r",lines);
            return result.isEmpty() ? ObdExchange.NO_DATA : result;
        }
    }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) {} }
    @Override public synchronized void close() {
        if (closed) return;
        closed=true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket client:clients) closeSocket(client);
    }
}
