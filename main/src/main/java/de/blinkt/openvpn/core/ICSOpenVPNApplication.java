package de.blinkt.openvpn.core;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public class ICSOpenVPNApplication extends Application {

    private static ICSOpenVPNApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannelsSafely();
    }

    /** Optional helper for callers that want a Context */
    public static Context getAppContext() {
        return instance;
    }

    private void createNotificationChannelsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Try to read channel ids from OpenVPNService via reflection (if they exist)
            String bgId = reflectStaticString(
                    "de.blinkt.openvpn.core.OpenVPNService",
                    "NOTIFICATION_CHANNEL_BG_ID",
                    "openvpn_bg"
            );
            String userReqId = reflectStaticString(
                    "de.blinkt.openvpn.core.OpenVPNService",
                    "NOTIFICATION_CHANNEL_USERREQ_ID",
                    "openvpn_default"
            );

            NotificationChannel bg = new NotificationChannel(
                    bgId, "VPN background", NotificationManager.IMPORTANCE_LOW
            );
            bg.setDescription("Background VPN service");

            NotificationChannel def = new NotificationChannel(
                    userReqId, "VPN", NotificationManager.IMPORTANCE_DEFAULT
            );
            def.setDescription("VPN notifications");

            nm.createNotificationChannel(bg);
            nm.createNotificationChannel(def);
        } catch (Throwable ignored) {
            // Don't crash if anything goes wrong creating channels
        }
    }

    private static String reflectStaticString(String clazz, String field, String fallback) {
        try {
            Class<?> c = Class.forName(clazz);
            Object v = c.getField(field).get(null);
            if (v instanceof String s && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}
        return fallback;
    }
}
