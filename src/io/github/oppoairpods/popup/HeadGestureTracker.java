package io.github.oppoairpods.popup;

/** Lightweight nod/shake recognizer for AACP head-tracking packets. */
final class HeadGestureTracker {
    static final int NONE = 0;
    static final int NOD = 1;
    static final int SHAKE = 2;

    private long windowStart;
    private double lastVertical;
    private double lastHorizontal;
    private int lastVerticalDirection;
    private int lastHorizontalDirection;
    private int verticalTurns;
    private int horizontalTurns;
    private double verticalTravel;
    private double horizontalTravel;
    private int lastGesture;

    int accept(byte[] packet) {
        if (packet == null || packet.length < 55) return NONE;
        long now = System.currentTimeMillis();
        if (windowStart == 0L || now - windowStart > 1800L) resetWindow(now);
        double horizontal = signed16(packet[51], packet[52]);
        double vertical = signed16(packet[53], packet[54]);
        double deltaV = vertical - lastVertical;
        double deltaH = horizontal - lastHorizontal;
        verticalTravel = Math.max(verticalTravel, Math.abs(deltaV));
        horizontalTravel = Math.max(horizontalTravel, Math.abs(deltaH));
        int directionV = deltaV > 90 ? 1 : deltaV < -90 ? -1 : 0;
        int directionH = deltaH > 90 ? 1 : deltaH < -90 ? -1 : 0;
        if (directionV != 0 && lastVerticalDirection != 0 && directionV != lastVerticalDirection) {
            verticalTurns++;
        }
        if (directionH != 0 && lastHorizontalDirection != 0 && directionH != lastHorizontalDirection) {
            horizontalTurns++;
        }
        if (directionV != 0) lastVerticalDirection = directionV;
        if (directionH != 0) lastHorizontalDirection = directionH;
        lastVertical = vertical;
        lastHorizontal = horizontal;
        lastGesture = NONE;
        if (verticalTurns >= 3 && verticalTravel >= 320) {
            lastGesture = NOD;
            resetWindow(now);
        } else if (horizontalTurns >= 3 && horizontalTravel >= 320) {
            lastGesture = SHAKE;
            resetWindow(now);
        }
        return lastGesture;
    }

    private void resetWindow(long now) {
        windowStart = now;
        lastVertical = 0;
        lastHorizontal = 0;
        lastVerticalDirection = 0;
        lastHorizontalDirection = 0;
        verticalTurns = 0;
        horizontalTurns = 0;
        verticalTravel = 0;
        horizontalTravel = 0;
    }

    private static double signed16(byte low, byte high) {
        return (short) (((high & 0xFF) << 8) | (low & 0xFF));
    }
}