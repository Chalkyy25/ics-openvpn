package de.blinkt.openvpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import de.blinkt.openvpn.ui.MainActivity;

public final class Prefs {
    private static final String FILE = "aiovpn_prefs";
    private static MainActivity mainActivity;

    private Prefs() {}
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    // Auth
    public static void saveAuth(Context c, String token, int userId) {
        p(c).edit().putString("token", token).putInt("user_id", userId).apply();
    }
    public static String getToken(Context c) { return p(c).getString("token", null); }
    public static int getUserId(Context c) { return p(c).getInt("user_id", 0); }
    // Overload with default (for existing call sites)
    public static int getUserId(Context c, int def) { return p(c).getInt("user_id", def); }

    // Selected server
    public static void setServer(Context c, int id, String name) {
        p(c).edit().putInt("server_id", id).putString("server_name", name).apply();
    }
    public static int getServerId(Context c) { return p(c).getInt("server_id", 0); }
    public static int getServerId(Context c, int def) { return p(c).getInt("server_id", def); }
    public static String getServerName(Context c) { return p(c).getString("server_name", null); }
    public static String getServerName(Context c, String def) { return p(c).getString("server_name", def); }

    public static void clearAuth(MainActivity mainActivity) {
        Prefs.mainActivity = mainActivity;
    }
}
