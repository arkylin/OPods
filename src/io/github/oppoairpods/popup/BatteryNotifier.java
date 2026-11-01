package io.github.oppoairpods.popup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** Optional low-battery notification; no extra process or service is created. */
final class BatteryNotifier {
    private static final String CHANNEL = "oppo_airpods_battery";
    private static long lastAlertAt;

    private BatteryNotifier() {}

    static void onBattery(AirPodsController controller, int left, int right, int caseLevel, int chargingMask) {
        if (!controller.batteryAlerts()) return;
        int low = -1;
        String label = "";
        if (left >= 0 && left <= 20 && (chargingMask & 1) == 0) {
            low = left;
            label = "左耳";
        } else if (right >= 0 && right <= 20 && (chargingMask & 2) == 0) {
            low = right;
            label = "右耳";
        } else if (caseLevel >= 0 && caseLevel <= 10 && (chargingMask & 4) == 0) {
            low = caseLevel;
            label = "充电盒";
        }
        if (low < 0) return;
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < 30L * 60L * 1000L) return;
        lastAlertAt = now;
        try {
            Context context = controller.context();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "AirPods 电量", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("AirPods 低电量提醒");
            manager.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("AirPods 电量低")
                    .setContentText(label + "剩余 " + low + "%")
                    .setAutoCancel(true)
                    .build();
            manager.notify(0x0A1B, notification);
        } catch (Throwable error) {
            Logx.w("battery notification failed", error);
        }
    }
}
