package pl.radiokia.fm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

final class ScanStations {
    private static final int STOCK_SLOTS = 18;

    private ScanStations() {}

    /** Zamienia treść wiersza fabrycznego FM na dziesiąte MHz. Zero oznacza brak wartości. */
    static int parse(String text) {
        try {
            int frequency = new BigDecimal(text.trim().replace(',', '.')).movePointRight(1).intValueExact();
            return frequency >= Config.FM_MIN && frequency <= Config.FM_MAX ? frequency : 0;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return 0;
        }
    }

    /** Usuwa dopełnienie pustych slotów 87,5 MHz oraz dokładne duplikaty. */
    static List<Integer> clean(NavigableMap<Integer, Integer> raw, boolean complete) {
        NavigableMap<Integer, Integer> stations = new TreeMap<>(raw);
        if (complete && stations.size() == STOCK_SLOTS && stations.lastKey() == STOCK_SLOTS) {
            while (!stations.isEmpty() && stations.lastEntry().getValue() == Config.FM_MIN)
                stations.pollLastEntry();
        }
        TreeSet<Integer> unique = new TreeSet<>();
        for (int frequency : stations.values()) if (frequency != 0) unique.add(frequency);
        return new ArrayList<>(unique);
    }

    /** Bliskość jest wskazówką do odsłuchu. Obie częstotliwości pozostają dostępne. */
    static boolean nearby(List<Integer> stations, int index) {
        int current = stations.get(index);
        return (index > 0 && current - stations.get(index - 1) <= 2)
            || (index + 1 < stations.size() && stations.get(index + 1) - current <= 2);
    }
}
