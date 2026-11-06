package io.github.oppoairpods.popup;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Lid-open advert parsing, RPA resolution and proximity key handling; plain JVM. */
public final class ProximityTest {
    // Bluetooth Core Specification sample data for ah(): k, r and the 24-bit result, LSB first.
    private static final byte[] SPEC_IRK = bytes(
            0x9b, 0x7d, 0x39, 0x0a, 0xa6, 0x10, 0x10, 0x34, 0x05, 0xad, 0xc8, 0x57, 0xa3, 0x34, 0x02, 0xec);
    private static final byte[] SPEC_PRAND = bytes(0x94, 0x81, 0x70);
    private static final byte[] SPEC_HASH = bytes(0xaa, 0xfb, 0x0d);

    public static void main(String[] args) throws Exception {
        addressResolution();
        advertParsing();
        decryption();
        keyResponse();
        accommodationPacket();
        System.out.println("Proximity tests passed");
    }

    private static void addressResolution() {
        check(Arrays.equals(ProximityCrypto.ah(SPEC_IRK, SPEC_PRAND), SPEC_HASH), "spec ah() vector");
        // Address string is MSB first: prand 70:81:94, hash 0D:FB:AA.
        check(ProximityCrypto.verifyRpa("70:81:94:0D:FB:AA", SPEC_IRK), "spec RPA resolves");
        check(ProximityCrypto.verifyRpa("70:81:94:0d:fb:aa", SPEC_IRK), "lower-case address");
        check(!ProximityCrypto.verifyRpa("70:81:94:0D:FB:AB", SPEC_IRK), "wrong hash rejected");
        byte[] otherIrk = SPEC_IRK.clone();
        otherIrk[0] ^= 1;
        check(!ProximityCrypto.verifyRpa("70:81:94:0D:FB:AA", otherIrk), "other IRK rejected");
        check(!ProximityCrypto.verifyRpa("F0:81:94:0D:FB:AA", SPEC_IRK), "non-RPA top bits rejected");
        check(!ProximityCrypto.verifyRpa("not-an-address", SPEC_IRK), "garbage address");
        check(!ProximityCrypto.verifyRpa("70:81:94:0D:FB:AA", null), "missing IRK");
    }

    /** 07 19 01 <model 27 20> <status> <pods> <flags|case> <lid> <color> <conn> + 16 encrypted. */
    private static byte[] proximity(int lid) {
        byte[] p = new byte[27];
        p[0] = 0x07;
        p[1] = 0x19;
        p[2] = 0x01;
        p[3] = 0x27;
        p[4] = 0x20;
        p[5] = 0x28;          // primary left, not in case -> left in-ear bit is 0x08
        p[6] = 0x58;          // left 8 (80%), right 5 (50%)
        p[7] = 0x16;          // flags 1 = left charging, case 6 (60%)
        p[8] = (byte) lid;    // bit 3 clear = lid open
        p[9] = 0x00;
        p[10] = 0x04;         // idle
        for (int i = 11; i < 27; i++) p[i] = (byte) i;
        return p;
    }

    private static void advertParsing() {
        byte[] message = proximity(0x00);
        AppleAdvertisement.LidInfo bare = AppleAdvertisement.parse(message);
        check(bare != null && bare.lidOpen, "bare message parsed, lid open");
        check("AirPods Pro 3".equals(bare.model) && bare.product == 0x2720, "model from product id");
        check(bare.paired && bare.leftInEar && !bare.rightInEar, "paired and wear bits");
        check(bare.leftBattery == 80 && bare.rightBattery == 50 && bare.caseBattery == 60, "nibble battery");
        check(bare.leftCharging && !bare.rightCharging && !bare.caseCharging, "charging flags");
        check(bare.connectionState == 0x04 && "空闲".equals(bare.connectionLabel()), "connection state");

        AppleAdvertisement.LidInfo closed = AppleAdvertisement.parse(proximity(0x08));
        check(closed != null && !closed.lidOpen, "lid closed bit");

        // Full advertising record: flags AD, then manufacturer AD with Apple's company id.
        byte[] manufacturer = new byte[2 + 2 + message.length];
        manufacturer[0] = (byte) (1 + 2 + message.length);
        manufacturer[1] = (byte) 0xFF;
        manufacturer[2] = 0x4C;
        manufacturer[3] = 0x00;
        System.arraycopy(message, 0, manufacturer, 4, message.length);
        byte[] record = concat(bytes(0x02, 0x01, 0x06), manufacturer);
        AppleAdvertisement.LidInfo fromRecord = AppleAdvertisement.parse(record);
        check(fromRecord != null && fromRecord.lidOpen && fromRecord.leftBattery == 80, "record parsed");
        check(fromRecord.payload.length == 27 && fromRecord.payload[0] == 7, "payload is the 07 message");

        // Apple data that carries another TLV before the proximity message.
        byte[] tlvs = concat(bytes(0x0C, 0x02, 0x00, 0x00), message);
        byte[] withCompany = concat(bytes(0x4C, 0x00), tlvs);
        AppleAdvertisement.LidInfo fromCompany = AppleAdvertisement.parse(withCompany);
        check(fromCompany != null && fromCompany.rightBattery == 50, "company-prefixed TLV list");

        check(AppleAdvertisement.parse(bytes(0x10, 0x05, 0x01, 0x02, 0x03)) == null, "non-proximity data");
        check(AppleAdvertisement.parse(null) == null, "null data");
    }

    private static void decryption() throws Exception {
        byte[] encKey = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        byte[] plain = new byte[16];
        plain[1] = (byte) (0x80 | 77);  // left 77% charging
        plain[2] = 42;                  // right 42%
        plain[3] = (byte) 0xFF;         // case unknown
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"));
        byte[] message = proximity(0x00);
        System.arraycopy(cipher.doFinal(plain), 0, message, 11, 16);

        byte[] decrypted = ProximityCrypto.decryptPayload(message, encKey);
        check(Arrays.equals(decrypted, plain), "decrypts last 16 bytes");
        AppleAdvertisement.LidInfo exact = AppleAdvertisement.parse(message).withDecrypted(decrypted);
        check(exact.leftBattery == 77 && exact.leftCharging, "exact left battery");
        check(exact.rightBattery == 42 && !exact.rightCharging, "exact right battery");
        check(exact.caseBattery == -1 && !exact.caseCharging, "unknown case battery");
        check(ProximityCrypto.decryptPayload(message, new byte[5]) == null, "bad key length");
    }

    private static void keyResponse() {
        byte[] irk = new byte[16];
        byte[] enc = new byte[16];
        for (int i = 0; i < 16; i++) {
            irk[i] = (byte) (0xA0 + i);
            enc[i] = (byte) (0x10 + i);
        }
        byte[] packet = concat(bytes(4, 0, 4, 0, 0x31, 0, 2),
                concat(concat(bytes(0x01, 0, 16, 0), irk), concat(bytes(0x04, 0, 16, 0), enc)));
        AacpProtocol.Event event = AacpProtocol.parse(packet);
        check(event != null && event.type == AacpProtocol.Event.TYPE_PROXIMITY_KEYS, "keys event type");
        check(Arrays.equals(event.irk, irk) && Arrays.equals(event.encKey, enc), "keys parsed");
        check(AacpProtocol.parse(bytes(4, 0, 4, 0, 0x31, 0, 1, 0x01, 0, 16)) == null, "truncated keys");

        byte[] request = AacpProtocol.keyRequest(AacpProtocol.KEY_IRK | AacpProtocol.KEY_ENC);
        check(Arrays.equals(request, bytes(4, 0, 4, 0, 0x30, 0, 0x05, 0)), "key request packet");
    }

    private static void accommodationPacket() {
        float[] eq = {0f, 0.25f, 0.5f, 0.75f, 1f, 2f, -1f, 0.5f};
        byte[] packet = AacpProtocol.headphoneAccommodation(eq, true, false);
        check(packet.length == 12 + 128, "accommodation length");
        check(Arrays.equals(Arrays.copyOf(packet, 12),
                bytes(4, 0, 4, 0, 0x53, 0, 0x84, 0, 2, 2, 1, 2)), "accommodation header");
        ByteBuffer body = ByteBuffer.wrap(packet, 12, 128).order(ByteOrder.LITTLE_ENDIAN);
        float[] block = new float[8];
        for (int i = 0; i < 8; i++) block[i] = body.getFloat();
        check(block[1] == 0.25f && block[5] == 1f && block[6] == 0f, "bands clamped to 0..1");
        for (int i = 0; i < 24; i++) body.getFloat();
        check(!body.hasRemaining(), "four repeated blocks");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
