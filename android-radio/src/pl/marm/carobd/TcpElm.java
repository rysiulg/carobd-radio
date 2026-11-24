package pl.marm.carobd;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Adapter TCP i istniejący proxy używają tego samego zakończenia odpowiedzi ELM. */
final class TcpElm implements Elm.Link {
    static final int PORT=35000;
    static final class Busy extends IOException {
        Busy() { super("Proxy OBD jest zajęte"); }
    }
    static final class Address {
        final String host;
        final int port;
        private Address(String host,int port) { this.host=host; this.port=port; }
        static Address parse(String text) {
            String[] fields=text.trim().toLowerCase(Locale.ROOT).split(":",-1);
            if (fields.length>2 || fields[0].length()>253 || !fields[0].matches(
                "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.?"))
                throw new IllegalArgumentException("Podaj nazwę hosta lub adres IPv4");
            int port=PORT;
            if (fields.length==2) {
                if (!fields[1].matches("[0-9]{1,5}")) throw new IllegalArgumentException("Podaj prawidłowy port");
                port=Integer.parseInt(fields[1]);
                if (port<1 || port>65535) throw new IllegalArgumentException("Port musi być od 1 do 65535");
            }
            return new Address(fields[0],port);
        }
        @Override public String toString() { return host+":"+port; }
    }

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    TcpElm(Socket socket) throws IOException {
        this.socket=socket;
        socket.setKeepAlive(true); socket.setTcpNoDelay(true);
        input=new BufferedInputStream(socket.getInputStream()); output=socket.getOutputStream();
    }
    @Override public String query(String command,int timeoutMs) throws IOException {
        output.write((command+"\r").getBytes(StandardCharsets.US_ASCII)); output.flush();
        StringBuilder result=new StringBuilder();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (result.length()<8192) {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) throw new IOException("Przekroczono czas odpowiedzi OBD Wi-Fi");
            socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            int value=input.read();
            if (value<0) throw new EOFException("Adapter Wi-Fi zamknął połączenie");
            if (value=='>' && result.toString().trim().isEmpty()) { result.setLength(0); continue; }
            result.append((char)value);
            if (value=='>') {
                String reply=result.toString();
                if (reply.toUpperCase(Locale.ROOT).contains("BUSY")) throw new Busy();
                return reply;
            }
        }
        throw new IOException("Za długa odpowiedź OBD Wi-Fi");
    }
    @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
}
