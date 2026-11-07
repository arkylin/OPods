package io.github.oppoairpods.popup;

/** Lid-open wake decisions made inside the Accessory scan; plain JVM. */
public final class LidGateTest {
    private static final String OWN = "4A:11:22:33:44:55";
    private static final String OTHER = "5B:66:77:88:99:AA";

    public static void main(String[] args) {
        hardwareFilter();
        statusMessages();
        oneWakePerOpening();
        weakStartThenNear();
        wornAndClosed();
        crowdedRoom();
        System.out.println("LidGate tests passed");
    }

    /** 07 19 01 <model 27 20> <status> <pods> <flags|case> <lid> <color> <conn> + 16 encrypted. */
    private static byte[] proximity(int prefix, int status, int lid) {
        byte[] p = new byte[27];
        p[0] = 0x07;
        p[1] = 0x19;
        p[2] = (byte) prefix;
        p[3] = 0x27;
        p[4] = 0x20;
        p[5] = (byte) status;
        p[6] = 0x58;
        p[7] = 0x16;
        p[8] = (byte) lid;
        p[10] = 0x04;
        for (int i = 11; i < 27; i++) p[i] = (byte) (i * 7);
        return p;
    }

    private static byte[] lidOpen() {
        return proximity(0x01, 0x75, 0x31 & ~LidGate.LID_CLOSED_BIT);
    }

    /** Mirrors ScanFilter.matchesPartialData: every masked bit equals the pattern. */
    private static boolean filterMatches(byte[] data) {
        byte[] pattern = LidGate.pattern();
        byte[] mask = LidGate.mask();
        if (data.length < pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if ((mask[i] & data[i]) != (mask[i] & pattern[i])) return false;
        }
        return true;
    }

    private static void hardwareFilter() {
        check(LidGate.pattern().length == 27 && LidGate.mask().length == 27, "pattern spans the message");
        check(filterMatches(lidOpen()), "lid-open message passes the hardware filter");
        check(!filterMatches(proximity(0x01, 0x75, 0x39)), "lid-closed message is filtered in hardware");
        byte[] nearby = lidOpen();
        nearby[0] = 0x10;
        check(!filterMatches(nearby), "other Apple messages are filtered in hardware");
        byte[] shortMessage = new byte[20];
        shortMessage[0] = 0x07;
        shortMessage[1] = 0x19;
        check(!filterMatches(shortMessage), "short message cannot match");
    }

    private static void statusMessages() {
        LidGate gate = new LidGate();
        check(gate.offer(OWN, -50, null, 0) == LidGate.Verdict.NOT_OURS, "non-Apple result left to the ROM");
        check(gate.offer(OWN, -50, proximity(0x07, 0x75, 0x31), 0) == LidGate.Verdict.NOT_OURS,
                "pairing-mode message left to the ROM's new-device popup");
        byte[] other = lidOpen();
        other[1] = 0x0F;
        check(gate.offer(OWN, -50, other, 0) == LidGate.Verdict.NOT_OURS, "07 0F pairing message left to the ROM");
        check(gate.offer(null, -50, lidOpen(), 0) == LidGate.Verdict.IGNORE, "no address, no wake");
    }

    private static void oneWakePerOpening() {
        LidGate gate = new LidGate();
        check(gate.offer(OWN, -55, lidOpen(), 1_000) == LidGate.Verdict.WAKE, "first message of an opening wakes");
        // Repeats several times a second, and across LOW_POWER scan windows, while the lid stays open.
        for (long t = 1_200; t < 60_000; t += 4_000) {
            check(gate.offer(OWN, -55, lidOpen(), t) == LidGate.Verdict.IGNORE, "repeat at " + t + " ignored");
        }
        long closedAt = 57_200 + LidGate.SESSION_GAP_MS;
        check(gate.offer(OWN, -55, lidOpen(), closedAt) == LidGate.Verdict.WAKE, "reopening after a gap wakes again");
    }

    private static void weakStartThenNear() {
        LidGate gate = new LidGate();
        check(gate.offer(OWN, -85, lidOpen(), 0) == LidGate.Verdict.IGNORE, "far opening does not wake");
        check(gate.offer(OWN, -60, lidOpen(), 1_500) == LidGate.Verdict.WAKE, "same opening brought near wakes once");
        check(gate.offer(OWN, -60, lidOpen(), 2_500) == LidGate.Verdict.IGNORE, "then stays quiet");
    }

    private static void wornAndClosed() {
        LidGate gate = new LidGate();
        // Primary left, out of the case, both in-ear bits (0x08 | 0x02) set.
        check(gate.offer(OWN, -50, proximity(0x01, 0x2A, 0x00), 0) == LidGate.Verdict.IGNORE, "worn pair does not wake");
        check(gate.offer(OWN, -50, proximity(0x01, 0x75, 0x39), 100) == LidGate.Verdict.IGNORE,
                "closed lid does not wake");
        check(gate.offer(OWN, -50, lidOpen(), 200) == LidGate.Verdict.WAKE, "opening after that wakes");
    }

    private static void crowdedRoom() {
        LidGate gate = new LidGate();
        check(gate.offer(OWN, -50, lidOpen(), 0) == LidGate.Verdict.WAKE, "first case wakes");
        check(gate.offer(OTHER, -50, lidOpen(), 500) == LidGate.Verdict.IGNORE, "second case inside 2 s waits");
        check(gate.offer(OTHER, -50, lidOpen(), 500 + LidGate.MIN_WAKE_INTERVAL_MS) == LidGate.Verdict.WAKE,
                "and wakes once the interval passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
