package io.github.oppoairpods.popup;

/** Listening-mode switching against stale AirPods reports; plain JVM. */
public final class ListeningModeTrackerTest {
    public static void main(String[] args) {
        // 11-04 15:40 log: tap adaptive (4) 0.25 s after open, a state dump still says Off (1)
        // 0.6 s later, the AirPods confirm 4 another 0.5 s later.
        ListeningModeTracker tracker = new ListeningModeTracker(3000L);
        tracker.select(4);
        check(tracker.sent(4, 250L), "send schedules expiry");
        check(tracker.report(1, 840L), "stale Off report ignored");
        check(tracker.pending() == 4, "choice still shown");
        check(!tracker.report(4, 1350L), "confirmation passes through");
        check(tracker.pending() == 0, "confirmed choice cleared");
        check(tracker.expire(3300L) == 0, "nothing to revert after confirmation");

        // The AirPods refuse (for example not worn) and keep Off: Off wins after the window.
        tracker = new ListeningModeTracker(3000L);
        tracker.select(4);
        tracker.sent(4, 0L);
        check(tracker.report(1, 1000L), "refusal ignored inside the window");
        check(tracker.expire(1500L) == 0 && tracker.pending() == 4, "window not over yet");
        check(tracker.expire(3100L) == 1, "AirPods mode shown after the window");
        check(tracker.pending() == 0, "pending cleared after expiry");

        // A dump that arrived while the command was still queued says nothing about the result.
        tracker = new ListeningModeTracker(3000L);
        tracker.select(2);
        check(tracker.report(1, 100L), "dump before the send ignored");
        tracker.sent(2, 500L);
        check(tracker.expire(3600L) == 0, "no answer after the send keeps the choice");

        // Two quick taps: only the last one counts.
        tracker = new ListeningModeTracker(3000L);
        tracker.select(2);
        tracker.sent(2, 0L);
        tracker.select(3);
        tracker.sent(3, 500L);
        check(tracker.report(2, 1000L), "echo of the first tap ignored");
        check(tracker.expire(3200L) == 0 && tracker.pending() == 3, "window restarts with the last send");
        check(!tracker.report(3, 1600L), "last tap confirmed");

        // Sends for a mode that is no longer pending do not move the window.
        tracker = new ListeningModeTracker(3000L);
        tracker.select(3);
        check(!tracker.sent(2, 0L), "unrelated send ignored");
        tracker.dropIfUnsent(3);
        check(tracker.pending() == 0, "unsent choice dropped");

        // Without a pending choice every report is accepted.
        tracker.reset();
        check(!tracker.report(1, 0L), "idle reports pass");
        System.out.println("Listening mode tracker tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
