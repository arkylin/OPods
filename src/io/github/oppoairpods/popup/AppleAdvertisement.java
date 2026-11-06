package io.github.oppoairpods.popup;

import java.util.Arrays;

/**
 * Parser for the Apple proximity-pairing message (type 0x07) carried by the ROM's PTC scan
 * results. Field layout follows LibrePods' BLEManager: indices are relative to the 0x07 type byte.
 */
final class AppleAdvertisement {
    static final int TYPE_PROXIMITY_PAIRING = 0x07;
    private static final int AD_MANUFACTURER_SPECIFIC = 0xFF;
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final int MIN_PAYLOAD = 11;

    static final class LidInfo {
        final boolean lidOpen;
        final String model;
        final int product;
        final boolean paired;
        final int leftBattery;
        final int rightBattery;
        final int caseBattery;
        final boolean leftCharging;
        final boolean rightCharging;
        final boolean caseCharging;
        final boolean leftInEar;
        final boolean rightInEar;
        final int connectionState;
        final boolean flipped;
        /** The proximity message starting at its 0x07 type byte; the last 16 bytes are encrypted. */
        final byte[] payload;

        LidInfo(boolean lidOpen, String model, int product, boolean paired, int leftBattery,
                int rightBattery, int caseBattery, boolean leftCharging, boolean rightCharging,
                boolean caseCharging, boolean leftInEar, boolean rightInEar, int connectionState,
                boolean flipped, byte[] payload) {
            this.lidOpen = lidOpen;
            this.model = model;
            this.product = product;
            this.paired = paired;
            this.leftBattery = leftBattery;
            this.rightBattery = rightBattery;
            this.caseBattery = caseBattery;
            this.leftCharging = leftCharging;
            this.rightCharging = rightCharging;
            this.caseCharging = caseCharging;
            this.leftInEar = leftInEar;
            this.rightInEar = rightInEar;
            this.connectionState = connectionState;
            this.flipped = flipped;
            this.payload = payload;
        }

        /** Exact battery from the part decrypted with the ENC key (LibrePods layout). */
        LidInfo withDecrypted(byte[] decrypted) {
            if (decrypted == null || decrypted.length != 16) return this;
            int leftByte = decrypted[flipped ? 2 : 1] & 0xFF;
            int rightByte = decrypted[flipped ? 1 : 2] & 0xFF;
            int caseByte = decrypted[3] & 0xFF;
            return new LidInfo(lidOpen, model, product, paired,
                    exactLevel(leftByte), exactLevel(rightByte), exactLevel(caseByte),
                    (leftByte & 0x80) != 0, (rightByte & 0x80) != 0,
                    caseByte != 0xFF && (caseByte & 0x80) != 0,
                    leftInEar, rightInEar, connectionState, flipped, payload);
        }

        String connectionLabel() {
            switch (connectionState) {
                case 0x00: return "未连接";
                case 0x04: return "空闲";
                case 0x05: return "播放中";
                case 0x06: return "通话中";
                case 0x07: return "来电响铃";
                case 0x09: return "挂断中";
                default: return "未知(0x" + Integer.toHexString(connectionState) + ")";
            }
        }

        String batteryText() {
            return "左 " + level(leftBattery, leftCharging) + " · 右 " + level(rightBattery, rightCharging)
                    + " · 盒 " + level(caseBattery, caseCharging);
        }

        private static String level(int value, boolean charging) {
            if (value < 0) return "--";
            return value + "%" + (charging ? "⚡" : "");
        }
    }

    private AppleAdvertisement() {}

    static LidInfo parse(byte[] data) {
        byte[] payload = applePayload(data);
        return payload == null ? null : parsePayload(payload);
    }

    /** Accepts a raw advertising record, Apple manufacturer data, or the bare 0x07 message. */
    static byte[] applePayload(byte[] data) {
        if (data == null || data.length < MIN_PAYLOAD) return null;
        byte[] fromRecord = fromAdStructures(data);
        if (fromRecord != null) return fromRecord;
        if ((data[0] & 0xFF) == (APPLE_COMPANY_ID & 0xFF) && data[1] == 0) {
            byte[] message = findProximity(data, 2);
            if (message != null) return message;
        }
        byte[] bare = findProximity(data, 0);
        if (bare != null) return bare;
        // 3.4.47 heuristic for unknown wrappers: the first 07 <len> run that fits the buffer.
        for (int i = 0; i + 2 < data.length; i++) {
            if ((data[i] & 0xFF) != TYPE_PROXIMITY_PAIRING) continue;
            int length = data[i + 1] & 0xFF;
            if (length >= MIN_PAYLOAD - 2 && i + 2 + length <= data.length) {
                return Arrays.copyOfRange(data, i, i + 2 + length);
            }
        }
        return null;
    }

    private static byte[] fromAdStructures(byte[] record) {
        int pos = 0;
        while (pos < record.length) {
            int length = record[pos] & 0xFF;
            if (length == 0) break;
            if (pos + 1 + length > record.length) return null;
            int type = record[pos + 1] & 0xFF;
            if (type == AD_MANUFACTURER_SPECIFIC && length >= 3) {
                int company = (record[pos + 2] & 0xFF) | ((record[pos + 3] & 0xFF) << 8);
                if (company == APPLE_COMPANY_ID) {
                    byte[] message = findProximity(Arrays.copyOfRange(record, pos + 4, pos + 1 + length), 0);
                    if (message != null) return message;
                }
            }
            pos += 1 + length;
        }
        return null;
    }

    /** Walks Apple's type-length-value list from {@code start} and returns the 0x07 entry. */
    private static byte[] findProximity(byte[] data, int start) {
        int pos = start;
        while (pos + 1 < data.length) {
            int type = data[pos] & 0xFF;
            int length = data[pos + 1] & 0xFF;
            int end = pos + 2 + length;
            if (end > data.length) return null;
            if (type == TYPE_PROXIMITY_PAIRING) {
                return length >= MIN_PAYLOAD - 2 ? Arrays.copyOfRange(data, pos, end) : null;
            }
            pos = end;
        }
        return null;
    }

    static LidInfo parsePayload(byte[] p) {
        if (p == null || p.length < MIN_PAYLOAD || (p[0] & 0xFF) != TYPE_PROXIMITY_PAIRING) return null;
        boolean paired = (p[2] & 0xFF) == 1;
        int product = ((p[3] & 0xFF) << 8) | (p[4] & 0xFF);
        int status = p[5] & 0xFF;
        int pods = p[6] & 0xFF;
        int flagsCase = p[7] & 0xFF;
        boolean lidOpen = ((p[8] & 0xFF) & 0x08) == 0;
        int connection = p[10] & 0xFF;

        boolean primaryLeft = ((status >> 5) & 0x01) == 1;
        boolean thisInCase = ((status >> 6) & 0x01) == 1;
        boolean xor = primaryLeft ^ thisInCase;
        boolean leftInEar = xor ? (status & 0x08) != 0 : (status & 0x02) != 0;
        boolean rightInEar = xor ? (status & 0x02) != 0 : (status & 0x08) != 0;
        boolean flipped = !primaryLeft;

        int leftNibble = flipped ? (pods >> 4) & 0x0F : pods & 0x0F;
        int rightNibble = flipped ? pods & 0x0F : (pods >> 4) & 0x0F;
        int flags = (flagsCase >> 4) & 0x0F;
        boolean leftCharging = flipped ? (flags & 0x02) != 0 : (flags & 0x01) != 0;
        boolean rightCharging = flipped ? (flags & 0x01) != 0 : (flags & 0x02) != 0;
        boolean caseCharging = (flags & 0x04) != 0;
        return new LidInfo(lidOpen, modelName(product), product, paired,
                nibbleLevel(leftNibble), nibbleLevel(rightNibble), nibbleLevel(flagsCase & 0x0F),
                leftCharging, rightCharging, caseCharging, leftInEar, rightInEar, connection,
                flipped, p);
    }

    /** Unencrypted battery nibbles: 0..9 are tens of percent, A..E full, F unknown. */
    private static int nibbleLevel(int nibble) {
        if (nibble <= 9) return nibble * 10;
        return nibble <= 0x0E ? 100 : -1;
    }

    private static int exactLevel(int value) {
        if (value == 0xFF) return -1;
        int level = value & 0x7F;
        return level <= 100 ? level : -1;
    }

    static String modelName(int product) {
        switch (product) {
            case 0x0220: return "AirPods 1";
            case 0x0F20: return "AirPods 2";
            case 0x1320: return "AirPods 3";
            case 0x1920: return "AirPods 4";
            case 0x1B20: return "AirPods 4 (ANC)";
            case 0x0E20: return "AirPods Pro";
            case 0x1420: return "AirPods Pro 2";
            case 0x2420: return "AirPods Pro 2 (USB-C)";
            case 0x2720: return "AirPods Pro 3";
            case 0x0A20: return "AirPods Max";
            case 0x1F20: return "AirPods Max (USB-C)";
            default: return "AirPods";
        }
    }
}
