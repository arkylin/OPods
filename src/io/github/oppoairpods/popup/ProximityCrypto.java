package io.github.oppoairpods.popup;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bluetooth address resolution and proximity-message decryption with the keys the AirPods hand
 * out over AACP (opcode 0x31). Same math as LibrePods' BluetoothCryptography / BLEManager.
 */
final class ProximityCrypto {
    private ProximityCrypto() {}

    /** True when {@code address} is a resolvable private address generated from {@code irk}. */
    static boolean verifyRpa(String address, byte[] irk) {
        if (irk == null || irk.length != 16) return false;
        byte[] rpa = addressLsbFirst(address);
        if (rpa == null) return false;
        // Resolvable private addresses carry 0b01 in the two most significant bits.
        if (((rpa[5] & 0xFF) >> 6) != 0x01) return false;
        byte[] hash = ah(irk, new byte[]{rpa[3], rpa[4], rpa[5]});
        return hash != null && hash[0] == rpa[0] && hash[1] == rpa[1] && hash[2] == rpa[2];
    }

    /** Random address hash function ah(k, r) from the Bluetooth Core Specification. */
    static byte[] ah(byte[] irkLsbFirst, byte[] prandLsbFirst) {
        byte[] padded = new byte[16];
        System.arraycopy(prandLsbFirst, 0, padded, 0, 3);
        byte[] encrypted = e(irkLsbFirst, padded);
        return encrypted == null ? null : Arrays.copyOfRange(encrypted, 0, 3);
    }

    /** Security function e: AES-128 where key, input and output are little-endian byte arrays. */
    static byte[] e(byte[] keyLsbFirst, byte[] dataLsbFirst) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(reversed(keyLsbFirst), "AES"));
            return reversed(cipher.doFinal(reversed(dataLsbFirst)));
        } catch (GeneralSecurityException error) {
            return null;
        }
    }

    /** Decrypts the last 16 bytes of the proximity message with the ENC key. */
    static byte[] decryptPayload(byte[] payload, byte[] encKey) {
        if (payload == null || payload.length < 16 || encKey == null || encKey.length != 16) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"));
            return cipher.doFinal(payload, payload.length - 16, 16);
        } catch (GeneralSecurityException error) {
            return null;
        }
    }

    private static byte[] addressLsbFirst(String address) {
        if (address == null) return null;
        String[] parts = address.trim().toUpperCase(Locale.ROOT).split(":");
        if (parts.length != 6) return null;
        byte[] result = new byte[6];
        try {
            for (int i = 0; i < 6; i++) result[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        } catch (NumberFormatException invalid) {
            return null;
        }
        return result;
    }

    private static byte[] reversed(byte[] value) {
        byte[] result = new byte[value.length];
        for (int i = 0; i < value.length; i++) result[i] = value[value.length - 1 - i];
        return result;
    }
}
