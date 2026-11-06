package io.github.oppoairpods.popup;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Plain JVM tests for the protocol, ATT payload and gesture logic; no Android runtime needed. */
public final class AacpProtocolTest {
    private static final byte[] BATTERY = hex(
            "04 00 04 00 04 00 03 02 01 64 02 01 04 01 5F 02 01 08 01 27 02 01");
    private static final byte[] CONTROL = hex("04 00 04 00 09 00 0D 02 00 00 00");

    public static void main(String[] args) {
        packetBuilders();
        batteryParsing();
        framing();
        information();
        attPayloads();
        headGestures();
        System.out.println("AACP protocol tests passed");
    }

    private static void packetBuilders() {
        check((AacpProtocol.HANDSHAKE[6] & 0xFF) == 0x02, "handshake protocol version");
        byte[] control = AacpProtocol.control(0x0D, 2);
        check(control.length == 11, "control length");
        check((control[6] & 0xFF) == 0x0D, "control id");
        check((control[7] & 0xFF) == 2, "control value");

        byte[] rename = AacpProtocol.rename("OPods");
        check(rename.length == 14, "rename length " + rename.length);
        check((rename[4] & 0xFF) == 0x1A, "rename opcode");
        check((rename[7] & 0xFF) == 5, "rename name length");
        check("OPods".equals(new String(rename, 9, 5, StandardCharsets.UTF_8)), "rename value");

        check(AacpProtocol.sameControlValues(new int[]{1}, new int[]{1, 0, 0, 0}), "short payload equals padded");
        check(!AacpProtocol.sameControlValues(new int[]{1}, new int[]{2, 0, 0, 0}), "different payload");
        check(!AacpProtocol.sameControlValues(null, new int[]{1}), "missing payload");
    }

    private static void batteryParsing() {
        byte[] battery = new byte[22];
        System.arraycopy(AacpProtocol.HEADER, 0, battery, 0, 4);
        battery[4] = 4;
        battery[5] = 0;
        battery[6] = 3;
        battery[7] = 4; battery[9] = 55; battery[10] = 1;
        battery[12] = 2; battery[14] = 60; battery[15] = 2;
        battery[17] = 8; battery[19] = 70; battery[20] = 0;
        AacpProtocol.Event event = AacpProtocol.parse(battery);
        check(event != null && event.type == AacpProtocol.Event.TYPE_BATTERY, "battery type");
        check(event.left == 55 && event.right == 60 && event.caseLevel == 70, "battery levels");
        check(event.leftStatus == 1 && event.rightStatus == 2 && event.caseStatus == 0,
                "battery component statuses");
        check((event.chargingMask & 1) != 0 && (event.chargingMask & 2) == 0, "charging mask");

        byte[] optimized = battery.clone();
        optimized[10] = 5;
        event = AacpProtocol.parse(optimized);
        check(event != null && event.leftStatus == 5, "optimized charging status");
        check((event.chargingMask & 1) != 0, "optimized charging mask");

        byte[] stem = {4, 0, 4, 0, 0x19, 0, 5, 1};
        event = AacpProtocol.parse(stem);
        check(event != null && event.type == AacpProtocol.Event.TYPE_STEM_PRESS, "stem type");
        check(event.stemPressType == 5 && event.stemPressBud == 1, "stem payload");
    }

    private static void framing() {
        byte[] wear = {4, 0, 4, 0, 6, 0, 0, 1};
        List<byte[]> packets = new AacpProtocol.Framer().feed(concat(CONTROL, wear));
        check(packets.size() == 2, "coalesced frames " + packets.size());
        check(packets.get(1)[4] == 6, "coalesced wear opcode");

        // The AirPods handshake ACK starts 01 00 04 00 (LibrePods HANDSHAKE_ACK). Whatever its
        // length, it must not eat the frames that follow it in later reads.
        for (int zeros = 0; zeros <= 14; zeros++) {
            AacpProtocol.Framer framer = new AacpProtocol.Framer();
            byte[] ack = new byte[4 + zeros];
            ack[0] = 1; ack[2] = 4;
            check(framer.feed(ack).isEmpty(), "ack yields no frame");
            check(framer.feed(CONTROL).size() == 1, "control after ack with " + zeros + " zeros");
            List<byte[]> next = framer.feed(BATTERY);
            check(next.size() == 1, "battery after ack with " + zeros + " zeros");
            AacpProtocol.Event event = AacpProtocol.parse(next.get(0));
            check(event != null && event.left == 95 && event.right == 100 && event.caseLevel == 39,
                    "battery values after ack");
        }

        AacpProtocol.Framer framer = new AacpProtocol.Framer();
        check(framer.feed(hex("AA BB 00 00")).isEmpty(), "junk read");
        check(framer.feed(BATTERY).size() == 1, "battery after junk read");

        framer = new AacpProtocol.Framer();
        packets = framer.feed(concat(hex("04 00 04 00 4B 00 00 00"), BATTERY));
        check(packets.size() == 1 && packets.get(0)[4] == 4, "battery after invalid 0x4B");

        framer = new AacpProtocol.Framer();
        check(framer.feed(java.util.Arrays.copyOf(BATTERY, 10)).isEmpty(), "partial battery waits");
        packets = framer.feed(java.util.Arrays.copyOfRange(BATTERY, 10, BATTERY.length));
        check(packets.size() == 1 && packets.get(0).length == BATTERY.length, "split battery joins");

        byte[] head = new byte[64];
        System.arraycopy(AacpProtocol.HEADER, 0, head, 0, 4);
        head[4] = 0x17;
        head[10] = 0x44;
        check(AacpProtocol.isHeadTracking(head), "head tracking detection");
        AacpProtocol.Event event = AacpProtocol.parse(head);
        check(event != null && event.type == AacpProtocol.Event.TYPE_HEAD_TRACKING, "head tracking type");
    }

    private static void information() {
        String[] fields = {"AirPods 5", "A3531", "Apple Inc.", "SN123", "1.0", "1.1", "HW1",
                "UPD", "LSN", "RSN", "1.2.3"};
        byte[] text = String.join("\u0000", fields).getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[6 + text.length];
        System.arraycopy(AacpProtocol.HEADER, 0, packet, 0, 4);
        packet[4] = 0x1D;
        System.arraycopy(text, 0, packet, 6, text.length);
        AacpProtocol.Event event = AacpProtocol.parse(packet);
        check(event != null && event.type == AacpProtocol.Event.TYPE_INFO, "info type");
        check("AirPods 5".equals(event.name), "info name");
        check("A3531".equals(event.modelNumber), "info model");
        check("SN123".equals(event.serialNumber), "info serial");
        check("1.2.3".equals(event.version3), "info version3");
    }

    private static void attPayloads() {
        AirPodsAttData.Settings settings = AirPodsAttData.Settings.defaults();
        check(settings.balance == 0f && settings.netAmplification == 0f, "neutral defaults");
        settings.transparencyEnabled = true;
        settings.leftEq[0] = 25f;
        settings.rightEq[7] = 75f;
        settings.leftTone = 0.3f;
        settings.leftAmbientNoiseReduction = 0.6f;
        settings.leftConversationBoost = true;
        settings.rightConversationBoost = true;
        settings.setAmpBalance(0.2f, 0.4f);

        byte[] transparency = AirPodsAttData.buildTransparency(settings, null);
        check(transparency.length == 100, "transparency size without own voice");
        AirPodsAttData.Settings parsed = AirPodsAttData.parseTransparency(transparency);
        check(parsed != null && parsed.transparencyEnabled, "transparency enabled");
        check(near(parsed.leftEq[0], 25f) && near(parsed.rightEq[7], 75f), "transparency eq keeps device scale");
        check(parsed.leftConversationBoost && parsed.rightConversationBoost, "transparency boost");
        // Balance must round-trip in direction and must not drift the overall amplification.
        check(near(parsed.netAmplification, 0.2f) && near(parsed.balance, 0.4f), "amp/balance round trip");
        check(parsed.rightAmplification > parsed.leftAmplification, "positive balance favours right");

        settings.setAmpBalance(0.2f, -0.4f);
        parsed = AirPodsAttData.parseTransparency(AirPodsAttData.buildTransparency(settings, null));
        check(near(parsed.netAmplification, 0.2f) && near(parsed.balance, -0.4f), "negative balance round trip");
        check(parsed.leftAmplification > parsed.rightAmplification, "negative balance favours left");
        for (int pass = 0; pass < 4; pass++) {
            parsed.setAmpBalance(parsed.netAmplification, parsed.balance);
            parsed = AirPodsAttData.parseTransparency(AirPodsAttData.buildTransparency(parsed, null));
        }
        check(near(parsed.netAmplification, 0.2f), "repeated writes keep amplification");

        byte[] withOwnVoice = new byte[104];
        settings.ownVoiceAmplification = 0.5f;
        check(AirPodsAttData.buildTransparency(settings, withOwnVoice).length == 104,
                "transparency keeps own voice when the device reports it");

        // Hearing aid data needs the device-owned header from a real read.
        check(AirPodsAttData.buildHearingAid(settings, null) == null, "no hearing write without template");
        check(AirPodsAttData.buildHearingAid(settings, new byte[50]) == null, "no hearing write on short template");
        byte[] template = new byte[104];
        template[0] = 2; template[1] = 2; template[2] = 0x60;
        byte[] hearing = AirPodsAttData.buildHearingAid(settings, template);
        check(hearing != null && hearing[0] == 2 && hearing[1] == 2 && hearing[2] == 0x64,
                "hearing header comes from the device");
        parsed = AirPodsAttData.parseHearingAid(hearing);
        check(parsed != null && near(parsed.rightEq[7], 75f), "hearing right eq");
        check(near(parsed.ownVoiceAmplification, 0.5f), "hearing own voice");

        AirPodsAttData.Settings ears = AirPodsAttData.Settings.defaults();
        ears.setEq(3, 40f, false, true);
        check(ears.leftEq[3] == 0f && ears.rightEq[3] == 40f, "right-ear EQ leaves left ear alone");
        ears.setEq(3, 10f, true, false);
        check(ears.leftEq[3] == 10f && ears.rightEq[3] == 40f, "left-ear EQ leaves right ear alone");
    }

    private static void headGestures() {
        HeadGestureTracker tracker = new HeadGestureTracker();
        long now = 1_000L;
        check(tracker.accept(pose(0, 0), now) == HeadGestureTracker.NONE, "baseline");
        int result = HeadGestureTracker.NONE;
        int[] nod = {0, 300, 0, -300, 0, 300, 0, -300, 0};
        for (int value : nod) {
            now += 80L;
            int gesture = tracker.accept(pose(0, value), now);
            if (gesture != HeadGestureTracker.NONE) result = gesture;
        }
        check(result == HeadGestureTracker.NOD, "nod recognized");

        tracker = new HeadGestureTracker();
        result = HeadGestureTracker.NONE;
        tracker.accept(pose(0, 0), now);
        for (int value : nod) {
            now += 80L;
            int gesture = tracker.accept(pose(value, 0), now);
            if (gesture != HeadGestureTracker.NONE) result = gesture;
        }
        check(result == HeadGestureTracker.SHAKE, "shake recognized");

        tracker = new HeadGestureTracker();
        result = HeadGestureTracker.NONE;
        tracker.accept(pose(0, 0), now);
        for (int i = 0; i < 40; i++) {
            now += 80L;
            int gesture = tracker.accept(pose(i * 3, (i % 2) * 40), now);
            if (gesture != HeadGestureTracker.NONE) result = gesture;
        }
        check(result == HeadGestureTracker.NONE, "slow drift and jitter ignored");
    }

    private static byte[] pose(int horizontal, int vertical) {
        byte[] packet = new byte[64];
        System.arraycopy(AacpProtocol.HEADER, 0, packet, 0, 4);
        packet[4] = 0x17;
        packet[51] = (byte) horizontal;
        packet[52] = (byte) (horizontal >> 8);
        packet[53] = (byte) vertical;
        packet[54] = (byte) (vertical >> 8);
        return packet;
    }

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) < 0.0001f;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = (byte) Integer.parseInt(parts[i], 16);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
