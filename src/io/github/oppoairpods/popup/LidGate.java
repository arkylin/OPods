package io.github.oppoairpods.popup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides, inside the Accessory process, which AirPods proximity messages from the ROM's own
 * persistent scan are worth waking MyDevices for: one wake per lid opening, nothing for the
 * messages the earbuds keep sending while worn or while the lid stays open. Plain JVM.
 */
final class LidGate {
    enum Verdict {
        /** Not an AirPods status message; the ROM handles it as before. */
        NOT_OURS,
        /** An AirPods status message that needs no wake (same opening, worn, closed, far). */
        IGNORE,
        /** First usable message of a new lid opening: wake MyDevices once. */
        WAKE
    }

    /** Proximity-pairing message: 07 19, then 25 bytes; indices below count from the 07. */
    static final int MESSAGE_LENGTH = 27;
    static final int PREFIX_INDEX = 2;
    /** Pairing-mode prefix; those messages stay with the ROM's own new-device popup. */
    static final int PREFIX_PAIRING = 0x07;
    static final int LID_INDEX = 8;
    /** Bit 3 of the lid byte is set while the lid is closed (LibrePods / HyperPods-X layout). */
    static final int LID_CLOSED_BIT = 0x08;
    /** Same floor as the ROM's own AirPods registration (AirpodsScanHelper.MINRSSI). */
    static final int MIN_RSSI = -70;
    /**
     * Longer than one LOW_POWER scan interval (5.12 s), the slowest duty the ROM's scan drops to
     * while the screen is on, so a lid left open is still one opening between scan windows.
     */
    static final long SESSION_GAP_MS = 8000L;
    /** Several cases opened at once (a meeting room) still cost at most one wake per 2 s. */
    static final long MIN_WAKE_INTERVAL_MS = 2000L;
    private static final int MAX_SESSIONS = 32;

    private static final class Session {
        long lastSeen;
        boolean woken;
    }

    private final Map<String, Session> sessions = new LinkedHashMap<String, Session>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    private long lastWakeAt = Long.MIN_VALUE / 2;

    /**
     * Manufacturer-data pattern for the hardware filter. It spans the whole 27-byte message:
     * the offloaded filter only matched Apple messages once the pattern covered the payload.
     */
    static byte[] pattern() {
        byte[] pattern = new byte[MESSAGE_LENGTH];
        pattern[0] = 0x07;
        pattern[1] = 0x19;
        // pattern[LID_INDEX] stays 0: the lid-closed bit must be clear.
        return pattern;
    }

    static byte[] mask() {
        byte[] mask = new byte[MESSAGE_LENGTH];
        mask[0] = (byte) 0xFF;
        mask[1] = (byte) 0xFF;
        mask[LID_INDEX] = (byte) LID_CLOSED_BIT;
        return mask;
    }

    /** Apple manufacturer data (after the 0x004C id) holding an AirPods status message. */
    static boolean isStatusMessage(byte[] data) {
        return data != null && data.length >= MESSAGE_LENGTH
                && data[0] == 0x07 && (data[1] & 0xFF) == 0x19
                && (data[PREFIX_INDEX] & 0xFF) != PREFIX_PAIRING;
    }

    synchronized Verdict offer(String address, int rssi, byte[] data, long now) {
        if (!isStatusMessage(data)) return Verdict.NOT_OURS;
        AppleAdvertisement.LidInfo info = AppleAdvertisement.parse(data);
        if (info == null || !info.lidOpen || address == null || address.isEmpty()) return Verdict.IGNORE;
        // Both earbuds in the ears is someone listening, not a case being opened.
        if (info.leftInEar && info.rightInEar) return Verdict.IGNORE;
        Session session = sessions.get(address);
        if (session == null || now - session.lastSeen >= SESSION_GAP_MS) {
            session = new Session();
            sessions.put(address, session);
        }
        session.lastSeen = now;
        if (session.woken || rssi < MIN_RSSI || now - lastWakeAt < MIN_WAKE_INTERVAL_MS) {
            return Verdict.IGNORE;
        }
        session.woken = true;
        lastWakeAt = now;
        return Verdict.WAKE;
    }
}
