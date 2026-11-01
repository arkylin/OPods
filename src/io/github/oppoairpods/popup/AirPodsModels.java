package io.github.oppoairpods.popup;

import java.util.Locale;

/** Model capabilities used by the official page and the AACP control layer. */
final class AirPodsModels {
    static final int CAP_LISTENING = 1;
    static final int CAP_CONVERSATION = 1 << 1;
    static final int CAP_STEM = 1 << 2;
    static final int CAP_HEAD_GESTURES = 1 << 3;
    static final int CAP_LOUD_SOUND = 1 << 4;
    static final int CAP_PPE = 1 << 5;
    static final int CAP_SLEEP = 1 << 6;
    static final int CAP_HEARING_AID = 1 << 7;
    static final int CAP_ADAPTIVE_AUDIO = 1 << 8;
    static final int CAP_ADAPTIVE_VOLUME = 1 << 9;
    static final int CAP_SWIPE_VOLUME = 1 << 10;
    static final int CAP_HRM = 1 << 11;
    static final int CAP_SPATIAL = 1 << 12;

    static final class Model {
        final String name;
        final String modelNumber;
        final int capabilities;

        Model(String name, String modelNumber, int capabilities) {
            this.name = name;
            this.modelNumber = modelNumber;
            this.capabilities = capabilities;
        }

        boolean has(int capability) {
            return (capabilities & capability) != 0;
        }
    }

    private static final int AIRPODS5 = CAP_LISTENING | CAP_CONVERSATION | CAP_STEM
            | CAP_HEAD_GESTURES | CAP_SLEEP | CAP_ADAPTIVE_AUDIO
            | CAP_ADAPTIVE_VOLUME | CAP_SPATIAL;
    private static final int AIRPODS5_WCC = AIRPODS5 | CAP_SWIPE_VOLUME;
    private static final int PRO3 = AIRPODS5_WCC | CAP_LOUD_SOUND | CAP_PPE
            | CAP_HEARING_AID | CAP_HRM;

    private AirPodsModels() {}

    static Model fromModelNumber(String modelNumber) {
        String value = modelNumber == null ? "" : modelNumber.trim().toUpperCase(Locale.ROOT);
        if (value.equals("A3531") || value.equals("A3532") || value.equals("A3533")) {
            return new Model("AirPods 5", value, AIRPODS5);
        }
        if (value.equals("A3439") || value.equals("A3440") || value.equals("A3441")) {
            return new Model("AirPods 5 (无线充电盒)", value, AIRPODS5_WCC);
        }
        if (value.equals("A3063") || value.equals("A3064") || value.equals("A3065")) {
            return new Model("AirPods Pro 3", value, PRO3);
        }
        return null;
    }

    static Model fromName(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (value.contains("airpods pro 3")) {
            return new Model("AirPods Pro 3", "", PRO3);
        }
        if (value.contains("airpods 5")) {
            return new Model("AirPods 5", "", AIRPODS5_WCC);
        }
        if (value.contains("airpods pro")) {
            return new Model("AirPods Pro", "", PRO3 & ~CAP_HRM);
        }
        if (value.contains("airpods")) {
            return new Model("AirPods", "", AIRPODS5);
        }
        return null;
    }

    static String displayName(String modelNumber, String fallback) {
        Model model = fromModelNumber(modelNumber);
        if (model != null) return model.name;
        model = fromName(fallback);
        return model != null ? model.name : (fallback == null || fallback.isEmpty() ? "AirPods" : fallback);
    }
}
