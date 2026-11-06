package io.github.oppoairpods.popup;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Raw ATT characteristic payloads used by AirPods Pro hearing and transparency controls. */
final class AirPodsAttData {
    static final int HANDLE_TRANSPARENCY = 0x18;
    static final int CCCD_TRANSPARENCY = 0x19;
    static final int HANDLE_LOUD_SOUND_REDUCTION = 0x1B;
    static final int HANDLE_HEARING_AID = 0x2A;
    static final int CCCD_HEARING_AID = 0x2B;

    private static final int TRANSPARENCY_SIZE = 100;
    private static final int OWN_VOICE_SIZE = 104;
    private static final int OWN_VOICE_OFFSET = 100;

    private AirPodsAttData() {}

    static final class Settings {
        boolean transparencyEnabled;
        final float[] leftEq = new float[8];
        final float[] rightEq = new float[8];
        float leftAmplification;
        float rightAmplification;
        float leftTone;
        float rightTone;
        boolean leftConversationBoost;
        boolean rightConversationBoost;
        float leftAmbientNoiseReduction;
        float rightAmbientNoiseReduction;
        float netAmplification;
        float balance;
        float ownVoiceAmplification;

        static Settings defaults() {
            return new Settings();
        }

        /** Inverse of parse(): net is the mean of both ears and balance is right minus left. */
        void setAmpBalance(float net, float newBalance) {
            netAmplification = clamp(net, -1f, 1f);
            balance = clamp(newBalance, -1f, 1f);
            leftAmplification = clamp(netAmplification - balance / 2f, -1f, 1f);
            rightAmplification = clamp(netAmplification + balance / 2f, -1f, 1f);
        }

        void setEq(int band, float value, boolean left, boolean right) {
            if (left) leftEq[band] = value;
            if (right) rightEq[band] = value;
        }
    }

    static Settings parseTransparency(byte[] data) {
        if (data == null || data.length < TRANSPARENCY_SIZE) return null;
        return parse(data, true);
    }

    static Settings parseHearingAid(byte[] data) {
        if (data == null || data.length < OWN_VOICE_SIZE) return null;
        return parse(data, false);
    }

    private static Settings parse(byte[] data, boolean transparency) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (!transparency) {
            buffer.get();
            buffer.get();
            buffer.getShort();
        }
        Settings value = new Settings();
        if (transparency) value.transparencyEnabled = buffer.getFloat() > 0.5f;
        for (int i = 0; i < 8; i++) value.leftEq[i] = buffer.getFloat();
        value.leftAmplification = buffer.getFloat();
        value.leftTone = buffer.getFloat();
        value.leftConversationBoost = buffer.getFloat() > 0.5f;
        value.leftAmbientNoiseReduction = buffer.getFloat();
        for (int i = 0; i < 8; i++) value.rightEq[i] = buffer.getFloat();
        value.rightAmplification = buffer.getFloat();
        value.rightTone = buffer.getFloat();
        value.rightConversationBoost = buffer.getFloat() > 0.5f;
        value.rightAmbientNoiseReduction = buffer.getFloat();
        if (buffer.remaining() >= 4) value.ownVoiceAmplification = buffer.getFloat();
        value.netAmplification = clamp((value.leftAmplification + value.rightAmplification) / 2f, -1f, 1f);
        value.balance = clamp(value.rightAmplification - value.leftAmplification, -1f, 1f);
        return value;
    }

    static byte[] buildTransparency(Settings value, byte[] current) {
        // Own-voice amplification only exists on firmware that reports the 104-byte value.
        int size = current != null && current.length >= OWN_VOICE_SIZE ? OWN_VOICE_SIZE : TRANSPARENCY_SIZE;
        byte[] data = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(0, value.transparencyEnabled ? 1f : 0f);
        putSettings(buffer, value);
        return data;
    }

    /**
     * Returns null without a template read from the device: the payload starts with a device-owned
     * header, and writing a made-up one could overwrite the audiogram stored on the AirPods.
     */
    static byte[] buildHearingAid(Settings value, byte[] current) {
        if (current == null || current.length < OWN_VOICE_SIZE) return null;
        byte[] data = Arrays.copyOf(current, OWN_VOICE_SIZE);
        data[2] = 0x64;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        putSettings(buffer, value);
        return data;
    }

    private static void putSettings(ByteBuffer buffer, Settings value) {
        for (int i = 0; i < 8; i++) buffer.putFloat(4 + i * 4, value.leftEq[i]);
        buffer.putFloat(36, value.leftAmplification);
        buffer.putFloat(40, value.leftTone);
        buffer.putFloat(44, value.leftConversationBoost ? 1f : 0f);
        buffer.putFloat(48, value.leftAmbientNoiseReduction);
        for (int i = 0; i < 8; i++) buffer.putFloat(52 + i * 4, value.rightEq[i]);
        buffer.putFloat(84, value.rightAmplification);
        buffer.putFloat(88, value.rightTone);
        buffer.putFloat(92, value.rightConversationBoost ? 1f : 0f);
        buffer.putFloat(96, value.rightAmbientNoiseReduction);
        if (buffer.capacity() >= OWN_VOICE_SIZE) {
            buffer.putFloat(OWN_VOICE_OFFSET, value.ownVoiceAmplification);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
