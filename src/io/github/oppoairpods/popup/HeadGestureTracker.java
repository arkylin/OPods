package io.github.oppoairpods.popup;

/** Lightweight nod/shake recognizer for AACP head-tracking packets. */
final class HeadGestureTracker {
    static final int NONE = 0;
    static final int NOD = 1;
    static final int SHAKE = 2;

    private static final long GESTURE_WINDOW_MS = 1800L;
    private static final int TURN_THRESHOLD = 150;
    private static final int MIN_TRAVEL = 400;
    private static final int MIN_TURNS = 3;
    private static final int MAX_VALID_VALUE = 6000;

    private final Axis horizontal = new Axis();
    private final Axis vertical = new Axis();
    private boolean hasBaseline;
    private long motionStart;

    int accept(byte[] packet) {
        return accept(packet, System.currentTimeMillis());
    }

    /** Starts a fresh recognition window, e.g. when a call starts ringing. */
    void reset() {
        hasBaseline = false;
        motionStart = 0L;
    }

    int accept(byte[] packet, long now) {
        if (packet == null || packet.length < 55) return NONE;
        int horizontalValue = signed16(packet[51], packet[52]);
        int verticalValue = signed16(packet[53], packet[54]);
        if (Math.abs(horizontalValue) > MAX_VALID_VALUE || Math.abs(verticalValue) > MAX_VALID_VALUE) {
            return NONE;
        }
        if (!hasBaseline || (motionStart != 0L && now - motionStart > GESTURE_WINDOW_MS)) {
            horizontal.reset(horizontalValue);
            vertical.reset(verticalValue);
            hasBaseline = true;
            motionStart = 0L;
            return NONE;
        }
        horizontal.add(horizontalValue);
        vertical.add(verticalValue);
        if (motionStart == 0L && (horizontal.moving() || vertical.moving())) motionStart = now;
        if (vertical.matched()) {
            hasBaseline = false;
            return NOD;
        }
        if (horizontal.matched()) {
            hasBaseline = false;
            return SHAKE;
        }
        return NONE;
    }

    private static int signed16(byte low, byte high) {
        return (short) (((high & 0xFF) << 8) | (low & 0xFF));
    }

    /** Counts direction reversals once the signal retreats far enough from its running extreme. */
    private static final class Axis {
        private int extreme;
        private int direction;
        private int turns;
        private int min;
        private int max;

        void reset(int value) {
            extreme = value;
            direction = 0;
            turns = 0;
            min = value;
            max = value;
        }

        void add(int value) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            int delta = value - extreme;
            if (direction == 0) {
                if (Math.abs(delta) >= TURN_THRESHOLD) {
                    direction = delta > 0 ? 1 : -1;
                    extreme = value;
                }
            } else if (delta * direction > 0) {
                extreme = value;
            } else if (Math.abs(delta) >= TURN_THRESHOLD) {
                direction = -direction;
                extreme = value;
                turns++;
            }
        }

        boolean moving() {
            return direction != 0;
        }

        boolean matched() {
            return turns >= MIN_TURNS && max - min >= MIN_TRAVEL;
        }
    }
}
