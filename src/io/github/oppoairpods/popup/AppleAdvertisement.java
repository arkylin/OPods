package io.github.oppoairpods.popup;

/** Minimal parser for the Apple proximity message carried by the ROM's PTC scan callback. */
final class AppleAdvertisement {
    static final class LidInfo {
        final boolean lidOpen;
        final String model;
        final int product;

        LidInfo(boolean lidOpen, String model, int product) {
            this.lidOpen = lidOpen;
            this.model = model;
            this.product = product;
        }
    }

    private AppleAdvertisement() {}

    static LidInfo parse(byte[] data) {
        if (data == null || data.length < 11) return null;
        int offset = -1;
        for (int i = 0; i + 2 < data.length; i++) {
            if ((data[i] & 0xFF) == 7) {
                int length = data[i + 1] & 0xFF;
                if (length >= 8 && i + 2 + length <= data.length) {
                    offset = i;
                    break;
                }
            }
        }
        if (offset < 0) return null;
        int length = data[offset + 1] & 0xFF;
        int end = offset + 2 + length;
        if (length < 9 || end > data.length) return null;
        int product = ((data[offset + 3] & 0xFF) << 8) | (data[offset + 4] & 0xFF);
        boolean lidOpen = (data[offset + 8] & 0x08) == 0;
        String model = product == 0x2720 ? "AirPods Pro 3" : "AirPods";
        return new LidInfo(lidOpen, model, product);
    }
}
