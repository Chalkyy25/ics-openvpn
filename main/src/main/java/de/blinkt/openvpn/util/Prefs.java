// Prefs.java
package de.blinkt.openvpn.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String FILE = "aiovpn_prefs";
    private Prefs() {}
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    // --- Auth token & user ---
    public static void saveAuth(Context c, String token, int userId) {
        p(c).edit().putString("token", token).putInt("user_id", userId).apply();
    }
    public static String getToken(Context c) { return p(c).getString("token", null); }
    public static int getUserId(Context c)    { return p(c).getInt("user_id", 0); }
    public static int getUserId(Context c, int def) { return p(c).getInt("user_id", def); }

    // --- VPN creds (username/password for OpenVPN auth-user-pass) ---
    public static void saveVpnCreds(Context c, String user, String pass) {
        p(c).edit().putString("vpn_user", user).putString("vpn_pass", pass).apply();
    }
    public static String getVpnUser(Context c) { return p(c).getString("vpn_user", ""); }
    public static String getVpnPass(Context c) { return p(c).getString("vpn_pass", ""); }

    // --- Selected server ---
    public static void setServer(Context c, int id, String name) {
        p(c).edit().putInt("server_id", id).putString("server_name", name).apply();
    }
    public static int getServerId(Context c)           { return p(c).getInt("server_id", 0); }
    public static int getServerId(Context c, int def)  { return p(c).getInt("server_id", def); }
    public static String getServerName(Context c)      { return p(c).getString("server_name", null); }
    public static String getServerName(Context c, String def) { return p(c).getString("server_name", def); }

    // --- Clear everything on logout ---
    public static void clearAll(Context c) {
        p(c).edit()
                .remove("token")
                .remove("user_id")
                .remove("vpn_user")
                .remove("vpn_pass")
                .remove("server_id")
                .remove("server_name")
                .apply();
    }
}
