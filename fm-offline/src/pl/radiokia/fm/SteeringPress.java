package pl.radiokia.fm;

final class SteeringPress {
    private static final long LONG_PRESS_MS = 500;
    private long token = Long.MIN_VALUE;
    private int direction;
    private boolean finished, sought;

    /** Zwraca ±1 dla presetu, ±2 dla wyszukiwania i 0 dla zdarzenia bez działania. */
    int accept(int direction, long downTime, boolean down, boolean longPress, long elapsed) {
        if (token != downTime || this.direction != direction) {
            if (!down) return 0;
            token = downTime;
            this.direction = direction;
            finished = false;
            sought = false;
        }
        if (finished) return 0;
        if (down) {
            if (sought || (!longPress && elapsed < LONG_PRESS_MS)) return 0;
            sought = true;
            return 2 * direction;
        }
        finished = true;
        if (sought) return 0;
        return elapsed >= LONG_PRESS_MS ? 2 * direction : direction;
    }

    void cancel() {
        finished = true;
    }
}
