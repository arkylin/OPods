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
            Settings value = new Settings();
            value.netAmplification = 0.5f;
            value.balance = 0.5f;
            return value;
        }
    }

    static Settings parseTransparency(byte[] data) {
        if (data == null || data.length < 100) return null;
        return parse(data, true);
    }

    static Settings parseHearingAid(byte[] data) {
        if (data == null || data.length < 104) return null;
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
        value.netAmplification = (value.leftAmplification + value.rightAmplification) / 2f;
        value.balance = value.rightAmplification - value.leftAmplification;
        return value;
    }

    static byte[] buildTransparency(Settings value, byte[] current) {
        byte[] data = current != null && current.length >= 104
                ? Arrays.copyOf(current, 104) : new byte[104];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(0, value.transparencyEnabled ? 1f : 0f);
        putSettings(buffer, value, 4, 52, 100);
        return data;
    }

    static byte[] buildHearingAid(Settings value, byte[] current) {
        byte[] data = current != null && current.length >= 104
                ? Arrays.copyOf(current, 104) : new byte[104];
        data[2] = 0x64;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        putSettings(buffer, value, 4, 52, 100);
        return data;
    }

    private static void putSettings(ByteBuffer buffer, Settings value, int leftEqOffset,
                                    int rightEqOffset, int ownVoiceOffset) {
        for (int i = 0; i < 8; i++) buffer.putFloat(leftEqOffset + i * 4, value.leftEq[i]);
        buffer.putFloat(36, value.leftAmplification);
        buffer.putFloat(40, value.leftTone);
        buffer.putFloat(44, value.leftConversationBoost ? 1f : 0f);
        buffer.putFloat(48, value.leftAmbientNoiseReduction);
        for (int i = 0; i < 8; i++) buffer.putFloat(rightEqOffset + i * 4, value.rightEq[i]);
        buffer.putFloat(84, value.rightAmplification);
        buffer.putFloat(88, value.rightTone);
        buffer.putFloat(92, value.rightConversationBoost ? 1f : 0f);
        buffer.putFloat(96, value.rightAmbientNoiseReduction);
        buffer.putFloat(ownVoiceOffset, value.ownVoiceAmplification);
    }
}
