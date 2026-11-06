package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

/**
 * Drops only the media/call audio profiles (LibrePods "Disconnect AirPods when not wearing").
 * The ACL and the AACP channel stay up, so wearing them again is still noticed; reconnecting
 * audio is left to the official popup / official connection flow.
 */
final class AudioProfiles {
    private AudioProfiles() {}

    static void disconnectAudio(Context context, BluetoothAdapter adapter, BluetoothDevice device) {
        if (adapter == null || device == null) return;
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
            try {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int type, BluetoothProfile proxy) {
                        try {
                            proxy.getClass().getMethod("disconnect", BluetoothDevice.class)
                                    .invoke(proxy, device);
                            Logx.i("audio profile " + type + " disconnected (not worn)");
                        } catch (Throwable error) {
                            Logx.w("audio profile " + type + " disconnect failed", error);
                        } finally {
                            adapter.closeProfileProxy(type, proxy);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int type) {
                    }
                }, profile);
            } catch (Throwable error) {
                Logx.w("audio profile proxy unavailable", error);
            }
        }
    }
}
