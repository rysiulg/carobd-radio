package pl.marm.carobd;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Grupuje podsumowania z Historii bez zmiany zapisanych przejazdów. */
final class TripGrouping {
    static final int PAGE_SIZE=60, MAX_GAP_MINUTES=20;
    private static final long MAX_GAP_SECONDS=MAX_GAP_MINUTES*60L;

    static final class Entry {
        final List<JSONObject> routes=new ArrayList<>();
        final LocalDate day;
        Entry(JSONObject newest,ZoneId zone) { routes.add(newest); day=TripGrouping.day(start(newest),zone); }
        String id() { return routes.get(0).optString("id"); }
        long start() { return start(routes.get(routes.size()-1)); }
        long end() { return routes.get(0).optLong("t_end",0); }
        boolean contains(String id) {
            for (JSONObject trip:routes) if (id.equals(trip.optString("id"))) return true;
            return false;
        }
        private static long start(JSONObject trip) { return trip.optLong("t_start",0); }
    }
    static final class Page {
        final List<JSONObject> rows;
        final boolean more;
        Page(List<JSONObject> rows,boolean more) { this.rows=rows; this.more=more; }
    }

    private static LocalDate day(long time,ZoneId zone) { return Instant.ofEpochSecond(time).atZone(zone).toLocalDate(); }

    static boolean canJoin(JSONObject older,JSONObject newer,ZoneId zone) {
        long start=older.optLong("t_start",0), end=older.optLong("t_end",0), next=newer.optLong("t_start",0);
        return start>0 && end>=start && next>=end && next-end<=MAX_GAP_SECONDS &&
            day(start,zone).equals(day(next,zone));
    }
    static List<Entry> entries(List<JSONObject> newestFirst,boolean join,ZoneId zone) {
        List<Entry> result=new ArrayList<>();
        JSONObject newer=null;
        for (JSONObject trip:newestFirst) {
            if (join && newer!=null && canJoin(trip,newer,zone)) result.get(result.size()-1).routes.add(trip);
            else result.add(new Entry(trip,zone));
            newer=trip;
        }
        return result;
    }
}
