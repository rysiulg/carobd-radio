package pl.marm.carobd;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Dzieli odpowiedzi i kolejkę tylko do odczytu. Transport obsługuje wyłącznie wątek rejestratora. */
final class ObdExchange {
    static final String NO_DATA="NO DATA";
    private static final int MAX_PENDING=8;
    private static final long MAX_AGE=TimeUnit.MILLISECONDS.toNanos(2500);
    private final Map<String,Reply> replies=new HashMap<>();
    private final Map<String,Long> pending=new LinkedHashMap<>();
    private boolean online;
    private long generation;

    private static final class Reply {
        final long time=System.nanoTime();
        final String text;
        Reply(String text) { this.text=text; }
    }

    synchronized void connected(boolean online) {
        this.online=online; generation++;
        replies.clear(); pending.clear(); notifyAll();
    }
    synchronized void publish(String command,String response) {
        if (!online || !ObdProxy.readOnly(command)) return;
        replies.put(command,new Reply(response)); pending.remove(command); notifyAll();
    }
    synchronized String take() {
        long now=System.nanoTime();
        Iterator<Map.Entry<String,Long>> rows=pending.entrySet().iterator();
        while (rows.hasNext()) {
            Map.Entry<String,Long> item=rows.next();
            if (now-item.getValue()<=MAX_AGE) return item.getKey();
            rows.remove();
        }
        return null;
    }
    synchronized String request(String command) throws InterruptedException {
        long session=generation, start=System.nanoTime();
        if (!online || !ObdProxy.readOnly(command)) return NO_DATA;
        Reply reply=replies.get(command);
        if (reply!=null && start-reply.time<=MAX_AGE) return reply.text;
        if (!pending.containsKey(command) && pending.size()>=MAX_PENDING) return NO_DATA;
        pending.putIfAbsent(command,start);
        while (online && generation==session) {
            reply=replies.get(command);
            if (reply!=null && reply.time>=start) return reply.text;
            long timeLeft=MAX_AGE-(System.nanoTime()-start);
            if (timeLeft<=0) break;
            TimeUnit.NANOSECONDS.timedWait(this,timeLeft);
        }
        return NO_DATA;
    }
}
