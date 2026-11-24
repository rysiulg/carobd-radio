package pl.marm.carobd;

import java.util.Locale;

/** PID 017F podaje łączny czas pracy i bieg jałowy w sekundach. */
final class EngineHours {
    static final String KIA_ID="";
    static final long FRESH_MS=180000;
    private static final long FIRST_REPEAT_MS=3000, PERIOD_MS=30000, UNSUPPORTED_MS=60000;

    static boolean kiaProfile(String vehicle) {
        return "kia-radio".equalsIgnoreCase(vehicle) || (!KIA_ID.isEmpty() && KIA_ID.equalsIgnoreCase(vehicle));
    }
    // OBD zwraca czas trwania, więc rok to 365 dni.
    static String duration(long seconds) {
        if (seconds<0) return "—";
        long minutes=seconds/60, hours=minutes/60, days=hours/24, years=days/365;
        StringBuilder text=new StringBuilder();
        if (years>0) {
            long digit=years%10, lastTwo=years%100;
            String unit=years==1 ? " rok" : digit>=2 && digit<=4 && (lastTwo<12 || lastTwo>14) ? " lata" : " lat";
            text.append(years).append(unit);
        }
        days%=365; hours%=24; minutes%=60;
        if (days>0) text.append(' ').append(days).append(days==1 ? " dzień" : " dni");
        if (hours>0) text.append(' ').append(hours).append(" godz.");
        if (minutes>0 || text.length()==0) text.append(' ').append(minutes).append(" min");
        return text.toString().trim();
    }
    static final class Reading {
        final long total,idle,at;
        Reading(long total,long idle,long at) { this.total=total; this.idle=idle; this.at=at; }
        boolean fresh(long now) { return now-at<FRESH_MS; }
    }

    static final class Monitor {
        private long due;
        private Reading previous;
        private boolean verifyVehicle, kiaVerified, confirmed;

        boolean due(long now) { return now>=due; }

        Reading poll(Elm elm,String vehicle,long now) throws Exception {
            if (verifyVehicle) {
                verifyVehicle=false;
                kiaVerified=elm.confirmKia();
                due=now+(kiaVerified ? FIRST_REPEAT_MS : UNSUPPORTED_MS);
                return null;
            }
            String data=payload(elm.diagnostic("017F"),"417F",15);
            due=now+UNSUPPORTED_MS;
            if (data==null) return null;
            boolean maskedOff=data.startsWith("00");
            if (maskedOff && !kiaVerified && kiaProfile(vehicle)) {
                verifyVehicle=true; due=now+FIRST_REPEAT_MS; return null;
            }
            Reading next=decode(data,kiaVerified,now);
            if (next==null) return null;
            Reading last=previous;
            due=now+FIRST_REPEAT_MS;
            if (last==null || !plausible(last,next)) {
                // Po potwierdzeniu próbki nie używaj błędnej odpowiedzi jako nowej podstawy.
                if (!confirmed) previous=next;
                return null;
            }
            previous=next;
            if (!last.fresh(now)) return null;
            confirmed=true;
            due=now+PERIOD_MS; return next;
        }
    }

    static Reading decode(String data,boolean kiaVerified,long now) {
        int flags=Integer.parseInt(data.substring(0,2),16);
        boolean exception=flags==0 && kiaVerified;
        if ((flags&1)==0 && !exception) return null;
        long total=Long.parseLong(data.substring(2,10),16);
        long idle=(flags&2)!=0 || exception ? Long.parseLong(data.substring(10,18),16) : -1;
        return total==0xffffffffL || (exception && total==0) || idle>total ? null : new Reading(total,idle,now);
    }
    private static boolean plausible(Reading last,Reading next) {
        long elapsed=next.at-last.at, limit=elapsed/1000+3;
        long total=next.total-last.total, idle=next.idle-last.idle;
        return elapsed>=1000 && total>=0 && total<=limit &&
            (last.idle<0 || next.idle<0 || (idle>=0 && idle<=limit));
    }
    static boolean matchesKia(String reply) {
        if (KIA_ID.isEmpty()) return false;
        String data=payload(reply,"490201",20);
        if (data==null) return false;
        long hash=0xcbf29ce484222325L;
        for (int i=0;i<data.length();i+=2) {
            int value=Integer.parseInt(data.substring(i,i+2),16);
            if (!(value>='0' && value<='9') && !(value>='A' && value<='Z' && "IOQ".indexOf(value)<0)) return false;
            hash=(hash^value)*0x100000001b3L;
        }
        return KIA_ID.equalsIgnoreCase(Long.toHexString(hash));
    }

    // ELM z ATH0 zwraca cały ładunek albo ponumerowane ramki ISO-TP bez nagłówków CAN.
    static String payload(String raw,String prefix,int bytes) {
        return payload(raw,prefix,bytes,bytes);
    }
    static String payload(String raw,String prefix,int minBytes,int maxBytes) {
        String found=null;
        StringBuilder block=new StringBuilder();
        int declared=0, expected=0;
        for (String line:(raw+"\rEND").toUpperCase(Locale.ROOT).split("[\r\n>]+")) {
            String row=line.replace(" ","").replace("\t","");
            boolean frame=row.matches("[0-9A-F]:[0-9A-F]{2,64}");
            int index=frame ? Character.digit(row.charAt(0),16) : -1;
            if (block.length()>0 && (index<=0 || index!=expected)) {
                String candidate=complete(block.toString(),prefix,minBytes,maxBytes,declared);
                if (candidate!=null) {
                    if (found!=null && !found.equals(candidate)) return null;
                    found=candidate;
                }
                block.setLength(0); declared=0;
            }
            if (row.matches("[0-9A-F]{3}")) declared=Integer.parseInt(row,16);
            else if (index==0 || (index>0 && block.length()>0 && index==expected)) {
                block.append(row.substring(2)); expected=index+1;
            } else if (row.startsWith(prefix)) {
                String candidate=complete(row,prefix,minBytes,maxBytes,0);
                if (candidate!=null) {
                    if (found!=null && !found.equals(candidate)) return null;
                    found=candidate;
                }
            }
        }
        return found;
    }
    private static String complete(String row,String prefix,int minBytes,int maxBytes,int declared) {
        int bytes=declared==0 ? row.length()/2 : declared;
        int len=bytes*2;
        if (!row.startsWith(prefix) || row.length()<len || bytes<minBytes || bytes>maxBytes ||
            !row.substring(0,len).matches("[0-9A-F]+") || (row.length()>len && declared==0)) return null;
        return row.substring(prefix.length(),len);
    }
}
