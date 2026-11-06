package io.github.oppoairpods.popup;

/**
 * Keeps a freshly chosen listening mode on screen until the AirPods confirm it. Reports of the
 * previous mode that are still in flight (state dumps, the ~1 s switch latency) are ignored; if
 * the AirPods answer with another mode after receiving the command, that mode wins once the
 * confirm window has passed.
 */
final class ListeningModeTracker {
    private static final long UNSENT = Long.MAX_VALUE;

    private final long confirmMs;
    private int pending;
    private long sentAt = UNSENT;
    private int reported;
    private long reportedAt;

    ListeningModeTracker(long confirmMs) {
        this.confirmMs = confirmMs;
    }

    synchronized void select(int mode) {
        pending = mode;
        sentAt = UNSENT;
    }

    /** True when the expiry check should run {@code confirmMs} from now. */
    synchronized boolean sent(int mode, long now) {
        if (pending == 0 || pending != mode) return false;
        sentAt = now;
        return true;
    }

    /** Records a report; true when it predates the pending choice and must not reach the UI. */
    synchronized boolean report(int mode, long now) {
        reported = mode;
        reportedAt = now;
        if (pending == 0) return false;
        if (mode == pending) {
            pending = 0;
            sentAt = UNSENT;
            return false;
        }
        return true;
    }

    /** After the confirm window: the mode the AirPods actually kept, or 0 to keep the choice. */
    synchronized int expire(long now) {
        if (pending == 0 || sentAt == UNSENT || now - sentAt < confirmMs) return 0;
        int choice = pending;
        long sent = sentAt;
        pending = 0;
        sentAt = UNSENT;
        return reported >= 1 && reported != choice && reportedAt >= sent ? reported : 0;
    }

    /** Drops a choice that never got sent, so it cannot stay pinned on screen. */
    synchronized void dropIfUnsent(int mode) {
        if (pending == mode && sentAt == UNSENT) pending = 0;
    }

    synchronized int pending() {
        return pending;
    }

    synchronized void reset() {
        pending = 0;
        sentAt = UNSENT;
        reported = 0;
        reportedAt = 0L;
    }
}
