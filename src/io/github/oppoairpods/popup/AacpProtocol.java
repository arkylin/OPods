package io.github.oppoairpods.popup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Framing and packet builders for Apple Accessory Communication Protocol over BR/EDR L2CAP. */
final class AacpProtocol {
    static final int PSM = 0x1001;
    static final byte[] HEADER = {4, 0, 4, 0};
    static final byte[] HANDSHAKE = {0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    static final int OP_BATTERY = 0x04;
    static final int OP_EAR_DETECTION = 0x06;
    static final int OP_CONTROL = 0x09;
    static final int OP_HEAD_TRACKING = 0x17;
    static final int OP_STEM_PRESS = 0x19;
    static final int OP_INFORMATION = 0x1D;
    static final int OP_CONVERSATION = 0x4B;
    static final int OP_EQUALIZER = 0x63;
    static final int OP_PROXIMITY_KEYS_REQUEST = 0x30;
    static final int OP_PROXIMITY_KEYS_RESPONSE = 0x31;
    static final int OP_HEADPHONE_ACCOMMODATION = 0x53;

    static final int KEY_IRK = 0x01;
    static final int KEY_ENC = 0x04;

    static final int CONTROL_LISTENING_MODE = 0x0D;
    static final int CONTROL_MICROPHONE = 0x01;
    static final int CONTROL_SINGLE_EAR_ANC = 0x1B;
    static final int CONTROL_CONVERSATION = 0x28;
    static final int CONTROL_ADAPTIVE_VOLUME = 0x26;
    static final int CONTROL_ADAPTIVE_STRENGTH = 0x2E;
    static final int CONTROL_ALLOW_OFF = 0x34;
    static final int CONTROL_EAR_DETECTION = 0x0A;
    static final int CONTROL_PRESS_SPEED = 0x17;
    static final int CONTROL_PRESS_HOLD = 0x18;
    static final int CONTROL_VOLUME_SWIPE = 0x25;
    static final int CONTROL_VOLUME_SWIPE_DURATION = 0x23;
    static final int CONTROL_TONE_VOLUME = 0x1F;
    static final int CONTROL_SLEEP_DETECTION = 0x35;
    static final int CONTROL_OPTIMIZED_CHARGING = 0x3B;
    static final int CONTROL_LISTENING_MODES = 0x1A;
    static final int CONTROL_AUTO_ANSWER = 0x1E;
    static final int CONTROL_AUTOMATIC_CONNECTION = 0x20;
    static final int CONTROL_CALL_MANAGEMENT = 0x24;
    static final int CONTROL_HEARING_AID = 0x2C;
    static final int CONTROL_HEARING_ASSIST = 0x33;
    static final int CONTROL_HPS_GAIN_SWIPE = 0x2F;
    static final int CONTROL_PPE_TOGGLE = 0x37;
    static final int CONTROL_PPE_CAP_LEVEL = 0x38;
    static final int CONTROL_STEM_CONFIG = 0x39;
    static final int CONTROL_ALLOW_AUTO_CONNECT = 0x36;
    // Defined by LibrePods' ControlCommandIdentifiers; most are not exposed in its own UI.
    static final int CONTROL_BUTTON_SEND_MODE = 0x05;
    static final int CONTROL_OWNS_CONNECTION = 0x06;
    static final int CONTROL_VOICE_TRIGGER = 0x12;
    static final int CONTROL_SINGLE_CLICK_MODE = 0x14;
    static final int CONTROL_DOUBLE_CLICK_MODE = 0x15;
    static final int CONTROL_CLICK_HOLD_MODE = 0x16;
    static final int CONTROL_CROWN_ROTATION = 0x1C;
    static final int CONTROL_SOFTWARE_MUTE = 0x27;
    static final int CONTROL_SSL = 0x29;
    static final int CONTROL_HRM_STATE = 0x30;
    static final int CONTROL_IN_CASE_TONE = 0x31;
    static final int CONTROL_SIRI_MULTITONE = 0x32;

    private AacpProtocol() {}

    static byte[] features() {
        return concat(HEADER, new byte[]{0x4D, 0, (byte) 0xD7, 0, 0, 0, 0, 0, 0, 0});
    }

    static byte[] notifications() {
        return concat(HEADER, new byte[]{0x0F, 0, -1, -1, -1, -1});
    }

    /** Proximity key request (LibrePods sends IRK | ENC = 0x05); the reply is opcode 0x31. */
    static byte[] keyRequest(int types) {
        return concat(HEADER, new byte[]{OP_PROXIMITY_KEYS_REQUEST, 0, (byte) types, 0});
    }

    /**
     * Headphone accommodation EQ (LibrePods sendPhoneMediaEQ): 8 bands of 0..1, repeated four
     * times, applied to calls and/or media (01 = on, 02 = off).
     */
    static byte[] headphoneAccommodation(float[] eq, boolean phone, boolean media) {
        if (eq == null || eq.length != 8) throw new IllegalArgumentException("EQ needs 8 bands");
        byte[] head = {4, 0, 4, 0, OP_HEADPHONE_ACCOMMODATION, 0, (byte) 0x84, 0, 2, 2,
                (byte) (phone ? 1 : 2), (byte) (media ? 1 : 2)};
        java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(128).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int block = 0; block < 4; block++) {
            for (int band = 0; band < 8; band++) body.putFloat(Math.max(0f, Math.min(1f, eq[band])));
        }
        return concat(head, body.array());
    }

    static byte[] control(int id, int... values) {
        byte[] packet = new byte[11];
        System.arraycopy(HEADER, 0, packet, 0, 4);
        packet[4] = 0x09;
        packet[5] = 0;
        packet[6] = (byte) id;
        for (int i = 0; i < 4; i++) packet[7 + i] = (byte) (i < values.length ? values[i] : 0);
        return packet;
    }

    static byte[] equalizer(int enabled, int low, int mid, int high) {
        return concat(HEADER, new byte[]{0x63, 0, 5, 0, 1, (byte) enabled,
                (byte) clamp(low), (byte) clamp(mid), (byte) clamp(high)});
    }

    static byte[] rename(String name) {
        byte[] bytes = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64) bytes = Arrays.copyOf(bytes, 64);
        byte[] packet = new byte[9 + bytes.length];
        System.arraycopy(HEADER, 0, packet, 0, 4);
        packet[4] = 0x1A;
        packet[5] = 0;
        packet[6] = 1;
        packet[7] = (byte) bytes.length;
        packet[8] = 0;
        System.arraycopy(bytes, 0, packet, 9, bytes.length);
        return packet;
    }

    static byte[] headTracking(boolean start, boolean alternate) {
        byte[] body;
        if (start && alternate) {
            body = new byte[]{0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x0F, 0x00, 0x08,
                    0x73, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02, 0x1A, 0x05, 0x01,
                    0x40, (byte) 0x9C, 0x00, 0x00};
        } else if (start) {
            body = new byte[]{0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x10, 0x00, 0x08,
                    (byte) 0xA1, 0x02, 0x42, 0x0B, 0x08, 0x0E, 0x10, 0x02, 0x1A,
                    0x05, 0x01, 0x40, (byte) 0x9C, 0x00, 0x00};
        } else if (alternate) {
            body = new byte[]{0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x0F, 0x00, 0x08,
                    0x75, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02, 0x1A, 0x05, 0x01,
                    0x00, 0x00, 0x00, 0x00};
        } else {
            body = new byte[]{0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x11, 0x00, 0x08,
                    0x7E, 0x10, 0x02, 0x42, 0x0B, 0x08, 0x4E, 0x10, 0x02, 0x1A,
                    0x05, 0x01, 0x00, 0x00, 0x00, 0x00};
        }
        return concat(HEADER, body);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Compares control payloads; the device always reports four values, callers often send one. */
    static boolean sameControlValues(int[] first, int[] second) {
        if (first == null || second == null) return first == second;
        int size = Math.max(first.length, second.length);
        for (int i = 0; i < size; i++) {
            int a = i < first.length ? first[i] : 0;
            int b = i < second.length ? second[i] : 0;
            if (a != b) return false;
        }
        return true;
    }

    static final class Event {
        static final int TYPE_BATTERY = 1;
        static final int TYPE_CONTROL = 2;
        static final int TYPE_WEAR = 3;
        static final int TYPE_INFO = 4;
        static final int TYPE_EQUALIZER = 5;
        static final int TYPE_CONVERSATION = 6;
        static final int TYPE_HEAD_TRACKING = 7;
        static final int TYPE_STEM_PRESS = 8;
        static final int TYPE_PROXIMITY_KEYS = 9;

        final int type;
        int left = -1;
        int right = -1;
        int caseLevel = -1;
        int leftStatus = -1;
        int rightStatus = -1;
        int caseStatus = -1;
        int chargingMask;
        int controlId = -1;
        int[] values = new int[0];
        int primaryWear = -1;
        int secondaryWear = -1;
        String name = "";
        String modelNumber = "";
        String firmware = "";
        int eqState;
        int eqLow;
        int eqMid;
        int eqHigh;
        int conversationSubtype;
        int conversationStatus;
        String manufacturer = "";
        String serialNumber = "";
        String leftSerialNumber = "";
        String rightSerialNumber = "";
        String version1 = "";
        String version2 = "";
        String version3 = "";
        String hardwareRevision = "";
        String updaterIdentifier = "";
        int stemPressType = -1;
        int stemPressBud = -1;
        byte[] raw = new byte[0];
        byte[] irk;
        byte[] encKey;

        Event(int type) {
            this.type = type;
        }
    }

    static Event parse(byte[] packet) {
        if (packet == null || packet.length < 6 || !startsWith(packet, HEADER)) return null;
        int opcode = u(packet, 4);
        switch (opcode) {
            case OP_BATTERY:
                return parseBattery(packet);
            case OP_CONTROL:
                if (packet.length < 8) return null;
                Event control = new Event(Event.TYPE_CONTROL);
                control.controlId = u(packet, 6);
                int size = Math.min(4, packet.length - 7);
                control.values = new int[size];
                for (int i = 0; i < size; i++) control.values[i] = u(packet, 7 + i);
                return control;
            case OP_EAR_DETECTION:
                if (packet.length != 8) return null;
                Event wear = new Event(Event.TYPE_WEAR);
                wear.primaryWear = u(packet, 6);
                wear.secondaryWear = u(packet, 7);
                if (wear.primaryWear > 3 || wear.secondaryWear > 3) return null;
                return wear;
            case OP_INFORMATION:
                return parseInformation(packet);
            case OP_STEM_PRESS:
                if (packet.length < 8) return null;
                Event stem = new Event(Event.TYPE_STEM_PRESS);
                stem.stemPressType = u(packet, 6);
                stem.stemPressBud = u(packet, 7);
                return stem;
            case OP_HEAD_TRACKING:
                if (packet.length < 55) return null;
                Event head = new Event(Event.TYPE_HEAD_TRACKING);
                head.raw = packet;
                return head;
            case OP_EQUALIZER:
                if (packet.length < 13 || u(packet, 6) != 5 || u(packet, 7) != 0) return null;
                Event eq = new Event(Event.TYPE_EQUALIZER);
                eq.eqState = u(packet, 9);
                eq.eqLow = u(packet, 10);
                eq.eqMid = u(packet, 11);
                eq.eqHigh = u(packet, 12);
                if (eq.eqState < 1 || eq.eqState > 2 || eq.eqLow > 100 || eq.eqMid > 100 || eq.eqHigh > 100) return null;
                return eq;
            case OP_PROXIMITY_KEYS_RESPONSE:
                return parseProximityKeys(packet);
            case 0x4B:
                if (packet.length < 8) return null;
                Event conversation = new Event(Event.TYPE_CONVERSATION);
                conversation.conversationSubtype = packet.length >= 10 ? u(packet, 8) : 0;
                conversation.conversationStatus = packet.length >= 10 ? u(packet, 9) : 0;
                return conversation;
            default:
                return null;
        }
    }

    /** 04 00 04 00 31 00 <count> then per key: <type> <?> <len> <?> <key bytes> (LibrePods). */
    private static Event parseProximityKeys(byte[] packet) {
        if (packet.length < 7) return null;
        int count = u(packet, 6);
        int offset = 7;
        Event event = new Event(Event.TYPE_PROXIMITY_KEYS);
        for (int i = 0; i < count; i++) {
            if (offset + 4 > packet.length) break;
            int type = u(packet, offset);
            int length = u(packet, offset + 2);
            offset += 4;
            if (offset + length > packet.length) break;
            byte[] key = Arrays.copyOfRange(packet, offset, offset + length);
            if (type == KEY_IRK) event.irk = key;
            else if (type == KEY_ENC) event.encKey = key;
            offset += length;
        }
        return event.irk != null || event.encKey != null ? event : null;
    }

    private static Event parseBattery(byte[] packet) {
        if (packet.length < 7) return null;
        int count = u(packet, 6);
        if (count < 1 || count > 8 || packet.length < 7 + count * 5) return null;
        Event event = new Event(Event.TYPE_BATTERY);
        for (int i = 0; i < count; i++) {
            int pos = 7 + i * 5;
            int component = u(packet, pos);
            int level = u(packet, pos + 2);
            int status = u(packet, pos + 3);
            if (component == 2) {
                event.right = level <= 100 && status != 4 ? level : -1;
                event.rightStatus = status;
            } else if (component == 4) {
                event.left = level <= 100 && status != 4 ? level : -1;
                event.leftStatus = status;
            } else if (component == 8) {
                event.caseLevel = level <= 100 && status != 4 ? level : -1;
                event.caseStatus = status;
            }
            if (status == 1 || status == 5) {
                if (component == 2) event.chargingMask |= 2;
                else if (component == 4) event.chargingMask |= 1;
                else if (component == 8) event.chargingMask |= 4;
            }
        }
        return event;
    }

    private static Event parseInformation(byte[] packet) {
        String text = new String(packet, 6, packet.length - 6, StandardCharsets.UTF_8);
        String[] fields = text.split("\u0000");
        int modelIndex = -1;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].matches("A[0-9]{4}")) {
                modelIndex = i;
                break;
            }
        }
        if (modelIndex < 0) return null;
        Event event = new Event(Event.TYPE_INFO);
        event.name = modelIndex > 0 && !fields[modelIndex - 1].trim().isEmpty()
                ? fields[modelIndex - 1].trim() : "AirPods";
        event.modelNumber = fields[modelIndex].trim();
        event.manufacturer = field(fields, modelIndex + 1);
        event.serialNumber = field(fields, modelIndex + 2);
        event.version1 = field(fields, modelIndex + 3);
        event.version2 = field(fields, modelIndex + 4);
        event.hardwareRevision = field(fields, modelIndex + 5);
        event.updaterIdentifier = field(fields, modelIndex + 6);
        event.leftSerialNumber = field(fields, modelIndex + 7);
        event.rightSerialNumber = field(fields, modelIndex + 8);
        event.version3 = field(fields, modelIndex + 9);
        for (int i = modelIndex + 1; i < fields.length; i++) {
            String value = fields[i].trim();
            if (value.matches("[0-9]+[A-Z][0-9]+[a-zA-Z]?")) {
                event.firmware = value;
                break;
            }
        }
        return event;
    }

    private static String field(String[] fields, int index) {
        return index >= 0 && index < fields.length ? fields[index].trim() : "";
    }

    static boolean isHeadTracking(byte[] data) {
        if (data == null || data.length < 60 || !startsWith(data, HEADER)) return false;
        if (u(data, 4) != OP_HEAD_TRACKING) return false;
        return data.length > 10 && (u(data, 10) == 0x44 || u(data, 10) == 0x45);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static int u(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    /** AACP may coalesce several packets in one L2CAP read; keep only complete frames. */
    static final class Framer {
        private static final int INVALID_LENGTH = -1;
        private static final int NEED_MORE_DATA = -2;

        private byte[] pending = new byte[0];

        List<byte[]> feed(byte[] data) {
            List<byte[]> result = new ArrayList<>();
            if (data == null || data.length == 0) return result;
            if (pending.length + data.length > 65536) pending = new byte[0];
            byte[] combined = new byte[pending.length + data.length];
            System.arraycopy(pending, 0, combined, 0, pending.length);
            System.arraycopy(data, 0, combined, pending.length, data.length);
            pending = combined;
            while (pending.length > 0) {
                // Each read is one L2CAP SDU, so non-AACP data such as the handshake ACK
                // (01 00 04 00 ...) can be dropped whole. Keeping any tail would let its bytes
                // merge with the next frame and swallow it.
                int start = nextHeader(pending, 0);
                if (start < 0) {
                    pending = new byte[0];
                    break;
                }
                if (start > 0) pending = Arrays.copyOfRange(pending, start, pending.length);
                if (pending.length < 7) break;
                int size = frameLength(pending);
                if (size == NEED_MORE_DATA) break;
                if (size < 6 || size > 4096) {
                    int next = nextHeader(pending, 4);
                    if (next < 0) {
                        pending = new byte[0];
                        break;
                    }
                    pending = Arrays.copyOfRange(pending, next, pending.length);
                    continue;
                }
                if (pending.length < size) break;
                result.add(Arrays.copyOfRange(pending, 0, size));
                pending = Arrays.copyOfRange(pending, size, pending.length);
            }
            return result;
        }

        private static int frameLength(byte[] packet) {
            int opcode = packet[4] & 0xFF;
            switch (opcode) {
                case OP_BATTERY:
                    return 7 + (packet[6] & 0xFF) * 5;
                case OP_CONTROL:
                    return 11;
                case OP_EAR_DETECTION:
                    return 8;
                case OP_STEM_PRESS:
                    return 8;
                case OP_EQUALIZER:
                    return 13;
                case 0x4B: {
                    if (packet.length < 8) return NEED_MORE_DATA;
                    int payload = (packet[6] & 0xFF) | ((packet[7] & 0xFF) << 8);
                    return payload >= 1 && payload <= 64 ? 8 + payload : INVALID_LENGTH;
                }
                case 0x0E:
                    return 13;
                default: {
                    int next = nextHeader(packet, 4);
                    return next < 0 ? packet.length : next;
                }
            }
        }

        private static int nextHeader(byte[] data, int from) {
            for (int i = from; i + 4 <= data.length; i++) {
                if (data[i] == 4 && data[i + 1] == 0 && data[i + 2] == 4 && data[i + 3] == 0) return i;
            }
            return -1;
        }
    }
}

