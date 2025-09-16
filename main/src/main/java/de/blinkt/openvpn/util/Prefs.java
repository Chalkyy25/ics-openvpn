package de.blinkt.openvpn.util;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "aiovpn_prefs";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USERID = "user_id";

    public static void saveAuth(Context ctx, String token, int userId) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TOKEN, token).putInt(KEY_USERID, userId).apply();
    }
    public static String getToken(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_TOKEN, null);
    }
    public static int getUserId(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_USERID, 0);
    }
}
