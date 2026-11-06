package io.github.oppoairpods.popup;

/** Human-readable protocol and storage metadata shown in the in-process dashboard. */
final class ProtocolInfo {
    private ProtocolInfo() {}

    static String aacp(int id, String values) {
        return "AACP 09 00 " + hex(id) + " " + values + " 00 00 00 · 设备";
    }

    static String aacpSwitch(int id) {
        return aacp(id, "01|02");
    }

    static String localEvent(String key, String event) {
        return "SP " + key + " · 手机本地；" + event + " · 手机协同";
    }

    static String eq(int state, String bands) {
        return "AACP 63 00 05 00 01 " + hex(state) + " " + bands + " · 设备";
    }

    static String attWrite(int handle, String value) {
        return "ATT 12 " + hex(handle) + " 00 " + value + " · 设备";
    }

    static String attRead(int handle) {
        return "ATT 0A " + hex(handle) + " 00 · 设备回传";
    }

    static String local(String key) {
        return "SP " + key + " · 手机本地，不发送";
    }

    static String official(String action) {
        return "Intent " + action + " · 官方页面/手机协同";
    }

    static String rename() {
        return "AACP 1A 00 01 <len> 00 <UTF-8> · 设备";
    }

    private static String hex(int value) {
        String text = Integer.toHexString(value & 0xFF).toUpperCase(java.util.Locale.ROOT);
        return text.length() == 1 ? "0" + text : text;
    }
}
