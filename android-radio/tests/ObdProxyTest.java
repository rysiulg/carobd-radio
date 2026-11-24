package pl.marm.carobd;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ObdProxyTest {
    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        ObdExchange exchange=new ObdExchange();
        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        try (ObdProxy proxy=new ObdProxy(loopback,0,32,exchange);
             TcpElm first=client(loopback,proxy.port()); TcpElm second=client(loopback,proxy.port())) {
            check(first.query("ATZ",1000).contains("ELM327"),"Handshake klienta 1");
            check(second.query("ATE0",1000).contains("OK"),"Handshake klienta 2");
            first.query("ATS0",1000); second.query("ATS0",1000);
            check(second.query("04",1000).trim().startsWith("?"),"Kasowanie DTC zablokowane");
            check(second.query("ATSH7E0",1000).trim().startsWith("?"),"Zmiana nagłówka zablokowana");
            check(second.query("2E0072",1000).trim().startsWith("?"),"Zapis UDS zablokowany");
            check(exchange.take()==null,"Polecenia klienta nie dotarły do transportu");
            check(first.query("010D",1000).contains("NO DATA"),"Sesja nadal dostępna bez adaptera");
            exchange.connected(true);
            exchange.publish("010D","410D37\r>");
            check(first.query("010D1",1000).contains("410D37"),"Prędkość z próbkowania");
            check(second.query("010D",1000).contains("410D37"),"Drugi klient korzysta z tej samej próbki");
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<String> pending=worker.submit(() -> second.query("010B",3000));
                long wait=System.nanoTime()+1_000_000_000L;
                String command=null;
                while (command==null && System.nanoTime()<wait) { command=exchange.take(); Thread.sleep(5); }
                check("010B".equals(command),"Kolejka odczytu na żądanie");
                exchange.publish(command,"410B45\r>");
                check(pending.get().contains("410B45"),"Odpowiedź na odczyt na żądanie");
            } finally { worker.shutdownNow(); }
            exchange.connected(false);
            check(first.query("010D",1000).contains("NO DATA"),"Brak starych danych po utracie adaptera");
            exchange.connected(true);
            exchange.publish("010D","410D3C\r>");
            check(first.query("010D",1000).contains("410D3C"),"Ten sam klient po ponownym łączeniu");
            check(second.query("010D",1000).contains("410D3C"),"Drugi klient po ponownym łączeniu");
        }
        TcpElm.Address host=TcpElm.Address.parse("adapter.example.invalid");
        check(host.port==35000 && host.host.equals("adapter.example.invalid"),"DNS i domyślny port");
        check(TcpElm.Address.parse("192.168.0.10:23").port==23,"Adres IP i własny port");
        try { TcpElm.Address.parse("http://10.0.0.1:35000"); throw new AssertionError("URL"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("OK: dwa trwałe połączenia TCP, wspólne próbki, ponowienie, filtrowanie i adresy.");
    }
    private static TcpElm client(InetAddress address,int port) throws IOException {
        Socket socket=new Socket();
        socket.connect(new InetSocketAddress(address,port),1000);
        return new TcpElm(socket);
    }
}
