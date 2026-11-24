package pl.radiokia.fm;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class FactoryFmAccess extends AccessibilityService {
    enum Mode {
        RDS(null), LOCAL("LOC"), DISTANT("DX");

        final String stockLabel;
        Mode(String stockLabel) { this.stockLabel = stockLabel; }
    }

    static final class Result {
        final Mode mode;
        final String rds, message;
        final List<Integer> frequencies;
        final boolean returnToRadio;

        Result(Mode mode, String rds, List<Integer> frequencies, String message, boolean returnToRadio) {
            this.mode = mode;
            this.rds = rds;
            this.frequencies = frequencies;
            this.message = message;
            this.returnToRadio = returnToRadio;
        }
    }

    private enum Phase { OPEN, SCANNING, COLLECTING }
    private static final long POLL_MS = 260, WINDOW_LEAVE_MS = 1700;
    private static final long RDS_LIMIT_MS = 10500, SCAN_LIMIT_MS = 90000;
    private static final String RADIO_ID = Config.FACTORY_FM + ":id/";
    private static FactoryFmAccess connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::readRadio;
    private Request current;

    private static final class Request {
        final Object owner;
        final Mode mode;
        final Consumer<Result> callback;
        final long deadline;
        final NavigableMap<Integer, Integer> positions = new TreeMap<>();
        NavigableMap<Integer, Integer> previousPage;
        Phase phase = Phase.OPEN;
        long lastVisible, actAfter, scanStarted;
        int identicalPages;
        boolean triedMode, sawScan, forward;
        String previousPs;

        Request(Object owner, Mode mode, Consumer<Result> callback) {
            this.owner = owner;
            this.mode = mode;
            this.callback = callback;
            deadline = SystemClock.uptimeMillis() + (mode == Mode.RDS ? RDS_LIMIT_MS : SCAN_LIMIT_MS);
        }
    }

    @Override protected void onServiceConnected() {
        connected = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Odczyt tylko podczas żądania użytkownika. */ }

    @Override public void onInterrupt() {
        if (current != null) fail(current, "Przerwano odczyt fabrycznego FM.", false);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        onInterrupt();
        super.onDestroy();
    }

    static boolean available() {
        return connected != null;
    }

    /** Uruchamia odczyt dla jednego ekranu RadioKIA. Usługa pozostaje bezczynna pomiędzy żądaniami. */
    static boolean request(Object owner, Mode mode, Consumer<Result> callback) {
        if (connected == null || connected.current != null) return false;
        connected.current = new Request(owner, mode, callback);
        connected.main.postDelayed(connected.poll, POLL_MS);
        return true;
    }

    static void cancel(Object owner) {
        if (connected == null || connected.current == null || connected.current.owner != owner) return;
        connected.current = null;
        connected.main.removeCallbacks(connected.poll);
    }

    /** Rozpoznaje wyłącznie węzły aktywnego okna fabrycznej aplikacji FM. */
    private void readRadio() {
        Request request = current;
        if (request == null) return;
        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            boolean factoryVisible = root != null && Config.FACTORY_FM.equals(String.valueOf(root.getPackageName()));
            if (factoryVisible) request.lastVisible = now;
            if (now >= request.deadline || (request.lastVisible != 0 && now - request.lastVisible > WINDOW_LEAVE_MS)) {
                String message = request.lastVisible == 0 ? "Nie udało się odczytać ekranu fabrycznego FM."
                    : !factoryVisible ? "Fabryczne FM zostało zamknięte lub schowane."
                    : request.mode == Mode.RDS ? "Fabryczne FM nie pokazało nazwy RDS."
                    : "Fabryczne FM nie zakończyło odczytu listy.";
                fail(request, message, factoryVisible);
            } else if (factoryVisible && now >= request.actAfter) {
                if (request.mode == Mode.RDS) readPs(request, root);
                else readScan(request, root, now);
            }
        } finally {
            if (root != null) root.recycle();
        }
        if (current == request) main.postDelayed(poll, POLL_MS);
    }

    /** Nazwa pochodzi tylko z rzeczywistego pola RDS PS i musi pojawić się w dwóch odczytach. */
    private void readPs(Request request, AccessibilityNodeInfo root) {
        AccessibilityNodeInfo ps = find(root, "rds_psname");
        if (ps == null) return;
        try {
            String label = text(ps).trim();
            if (label.isEmpty() || label.length() > 24 || "RDS".equalsIgnoreCase(label)) return;
            if (label.equals(request.previousPs)) {
                finish(request, new Result(request.mode, label, Collections.emptyList(), "Nazwa odczytana z fabrycznego FM.", true));
            } else request.previousPs = label;
        } finally {
            ps.recycle();
        }
    }

    private void readScan(Request request, AccessibilityNodeInfo root, long now) {
        switch (request.phase) {
            case OPEN:
                startScan(request, root, now);
                break;
            case SCANNING:
                detectCompletion(request, root, now);
                break;
            case COLLECTING:
                readList(request, root, now);
                break;
        }
    }

    /** Ustawia tryb LOC/DX według widocznej etykiety fabrycznego przycisku i uruchamia AS raz. */
    private void startScan(Request request, AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo mode = find(root, "dx"), as = find(root, "as");
        try {
            if (mode == null || as == null || !as.isEnabled()) return;
            String label = text(mode).trim();
            if (!request.mode.stockLabel.equalsIgnoreCase(label)) {
                if (request.triedMode || !("LOC".equalsIgnoreCase(label) || "DX".equalsIgnoreCase(label))
                    || !mode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    fail(request, "Fabryczne FM nie potwierdziło trybu " + request.mode.stockLabel + ".", true);
                    return;
                }
                request.triedMode = true;
                request.actAfter = now + 500;
                return;
            }
            if (!as.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                fail(request, "Fabryczne FM nie uruchomiło AS.", true);
                return;
            }
            request.phase = Phase.SCANNING;
            request.scanStarted = now;
        } finally {
            if (mode != null) mode.recycle();
            if (as != null) as.recycle();
        }
    }

    /** Zbiera listę dopiero po zaobserwowaniu początku i końca operacji AS. */
    private void detectCompletion(Request request, AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo as = find(root, "as"), progress = find(root, "tr_progress");
        try {
            boolean busy = (as != null && !as.isEnabled()) || progress != null;
            if (busy) request.sawScan = true;
            else if (request.sawScan && as != null) {
                request.phase = Phase.COLLECTING;
                request.actAfter = now + 500;
            } else if (now - request.scanStarted > 5000) {
                fail(request, "Nie uzyskano potwierdzenia skanu. Sprawdź fabryczne FM.", true);
            }
        } finally {
            if (as != null) as.recycle();
            if (progress != null) progress.recycle();
        }
    }

    /** Czyta numer slotu i MHz z listy, przewijając wyłącznie fabryczną listę FM. */
    private void readList(Request request, AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo list = find(root, "freq_list");
        if (list == null) return;
        try {
            NavigableMap<Integer, Integer> page = readPage(list);
            if (page.isEmpty()) return;
            request.positions.putAll(page);
            if (page.firstKey() == 1 && !request.forward) request.forward = true;
            boolean moved = !page.equals(request.previousPage);
            request.previousPage = page;
            request.identicalPages = moved ? 0 : request.identicalPages + 1;
            int action = request.forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (request.identicalPages < 2 && list.performAction(action)) {
                request.actAfter = now + 420;
                return;
            }
            if (!request.forward) {
                request.forward = true;
                request.previousPage = null;
                return;
            }
            AccessibilityNodeInfo.CollectionInfo collection = list.getCollectionInfo();
            int count = collection != null ? collection.getRowCount() : 0;
            boolean complete = request.positions.firstKey() == 1 && request.positions.size() == request.positions.lastKey()
                && (count <= 0 || count == request.positions.size());
            List<Integer> frequencies = ScanStations.clean(request.positions, complete);
            String message = frequencies.isEmpty() ? "AS nie zwrócił stacji w paśmie FM."
                : complete ? "Wybierz stacje do presetów." : "Odczytano część fabrycznej listy AS.";
            finish(request, new Result(request.mode, null, frequencies, message, true));
        } finally {
            list.recycle();
        }
    }

    private static NavigableMap<Integer, Integer> readPage(AccessibilityNodeInfo list) {
        List<AccessibilityNodeInfo> indexes = list.findAccessibilityNodeInfosByViewId(RADIO_ID + "list_index");
        List<AccessibilityNodeInfo> frequencies = list.findAccessibilityNodeInfosByViewId(RADIO_ID + "list_freq");
        NavigableMap<Integer, Integer> page = new TreeMap<>();
        try {
            if (indexes.size() != frequencies.size()) return page;
            for (int i = 0; i < indexes.size(); i++) {
                String number = text(indexes.get(i));
                if (!number.matches("P[1-9][0-9]{0,2}")) continue;
                int position = Integer.parseInt(number.substring(1));
                page.put(position, ScanStations.parse(text(frequencies.get(i))));
            }
            return page;
        } finally {
            for (AccessibilityNodeInfo node : indexes) node.recycle();
            for (AccessibilityNodeInfo node : frequencies) node.recycle();
        }
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo visible = null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(RADIO_ID + id)) {
            if (visible == null && node.isVisibleToUser()) visible = node;
            else node.recycle();
        }
        return visible;
    }

    private static String text(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        return value == null ? "" : value.toString();
    }

    private void fail(Request request, String reason, boolean returnToRadio) {
        finish(request, new Result(request.mode, null, Collections.emptyList(), reason, returnToRadio));
    }

    private void finish(Request request, Result result) {
        if (current != request) return;
        current = null;
        main.removeCallbacks(poll);
        request.callback.accept(result);
    }
}
