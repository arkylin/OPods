package io.github.oppoairpods.popup;

import java.util.Map;
import java.util.WeakHashMap;

/** Marks only the SeekBarPreference instances created by this module. */
final class SliderRegistry {
    private static final Map<Object, Boolean> OURS = new WeakHashMap<>();

    private SliderRegistry() {}

    static void register(Object preference) {
        if (preference == null) return;
        synchronized (OURS) {
            OURS.put(preference, Boolean.TRUE);
        }
    }

    static boolean isOurs(Object preference) {
        if (preference == null) return false;
        synchronized (OURS) {
            return OURS.containsKey(preference);
        }
    }
}
