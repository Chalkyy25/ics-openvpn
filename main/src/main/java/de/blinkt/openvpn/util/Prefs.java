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
    public static void setServer(Context c, int id, String name) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putInt("server_id", id).putString("server_name", name).apply();

    }

    public static int getServerId(Context c, int def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getInt("server_id", def);

    }

    public static String getServerName(Context c, String def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getString("server_name", def);

    }

    public static void saveAuth(Context c, String token, int userId) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putString("token", token).putInt("user_id", userId).apply();

    }

    public static String getToken(Context c) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getString("token", null);

    }

    public static int getUserId(Context c, int def) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getInt("user_id", def);

    }

    }
    public static String getToken(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_TOKEN, null);
    public static void setServer(Context c, int id, String name) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putInt("server_id", id).putString("server_name", name).apply();

    }

    public static int getServerId(Context c, int def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getInt("server_id", def);

    }

    public static String getServerName(Context c, String def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getString("server_name", def);

    }

    public static void saveAuth(Context c, String token, int userId) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putString("token", token).putInt("user_id", userId).apply();

    }

    public static String getToken(Context c) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getString("token", null);

    }

    public static int getUserId(Context c, int def) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getInt("user_id", def);

    }

    }
    public static int getUserId(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_USERID, 0);
    public static void setServer(Context c, int id, String name) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putInt("server_id", id).putString("server_name", name).apply();

    }

    public static int getServerId(Context c, int def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getInt("server_id", def);

    }

    public static String getServerName(Context c, String def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getString("server_name", def);

    }

    public static void saveAuth(Context c, String token, int userId) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putString("token", token).putInt("user_id", userId).apply();

    }

    public static String getToken(Context c) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getString("token", null);

    }

    public static int getUserId(Context c, int def) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getInt("user_id", def);

    }

    }
    public static void setServer(Context c, int id, String name) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putInt("server_id", id).putString("server_name", name).apply();

    }

    public static int getServerId(Context c, int def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getInt("server_id", def);

    }

    public static String getServerName(Context c, String def) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        return p.getString("server_name", def);

    }

    public static void saveAuth(Context c, String token, int userId) {

        SharedPreferences p = c.getSharedPreferences("aio", Context.MODE_PRIVATE);

        p.edit().putString("token", token).putInt("user_id", userId).apply();

    }

    public static String getToken(Context c) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getString("token", null);

    }

    public static int getUserId(Context c, int def) {

        return c.getSharedPreferences("aio", Context.MODE_PRIVATE).getInt("user_id", def);

    }

}
