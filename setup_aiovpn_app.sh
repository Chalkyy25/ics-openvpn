#!/bin/bash
set -euo pipefail

# --- Adjust this if your repo path differs ---
PROJECT_ROOT="${PROJECT_ROOT:-$PWD}"
JAVA_ROOT="$PROJECT_ROOT/main/src/main/java"
RES_ROOT="$PROJECT_ROOT/main/src/main/res"
PKG_PATH="de/blinkt/openvpn"
API_DIR="$JAVA_ROOT/$PKG_PATH/api"
UI_DIR="$JAVA_ROOT/$PKG_PATH/ui"
UTIL_DIR="$JAVA_ROOT/$PKG_PATH/util"
MANIFEST="$PROJECT_ROOT/main/src/main/AndroidManifest.xml"
GRADLE="$PROJECT_ROOT/main/build.gradle.kts"

echo "==> Project root: $PROJECT_ROOT"

mkdir -p "$API_DIR" "$UI_DIR" "$UTIL_DIR" "$RES_ROOT/layout"

# ---------- API layer ----------
cat > "$API_DIR/Config.java" <<'EOF'
package de.blinkt.openvpn.api;

public class Config {
    public static final String API_BASE = "https://panel.aiovpn.co.uk/api";
}
EOF

cat > "$API_DIR/LoginRequest.java" <<'EOF'
package de.blinkt.openvpn.api;

public class LoginRequest {
    private String username;
    private String password;
    public LoginRequest(String u, String p) { this.username = u; this.password = p; }
}
EOF

cat > "$API_DIR/AuthResponse.java" <<'EOF'
package de.blinkt.openvpn.api;

public class AuthResponse {
    public String token;
    public User user;
    public static class User {
        public int id;
        public String username;
        public String expires;
        public int max_conn;
    }
}
EOF

cat > "$API_DIR/ProfileResponse.java" <<'EOF'
package de.blinkt.openvpn.api;

import java.util.List;

public class ProfileResponse {
    public int id;
    public String username;
    public String expires;
    public int max_conn;
    public List<Server> servers;

    public static class Server {
        public int id;
        public String name;
        public String ip;
        public String proto;
        public Integer port;
    }
}
EOF

cat > "$API_DIR/ApiService.java" <<'EOF'
package de.blinkt.openvpn.api;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

public interface ApiService {
    @POST("auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    @GET("profiles")
    Call<ProfileResponse> getProfiles(@Header("Authorization") String bearer);

    @GET("profiles/{id}")
    Call<ResponseBody> getOvpn(
        @Header("Authorization") String bearer,
        @Path("id") int userId,
        @Query("server_id") int serverId
    );
}
EOF

cat > "$API_DIR/RetrofitClient.java" <<'EOF'
package de.blinkt.openvpn.api;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static Retrofit retrofit;
    public static Retrofit get() {
        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl(Config.API_BASE + "/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }
    public static ApiService service() { return get().create(ApiService.class); }
}
EOF

# ---------- Util: Token storage + OVPN import ----------
cat > "$UTIL_DIR/Prefs.java" <<'EOF'
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
EOF

cat > "$UTIL_DIR/OvpnImporter.java" <<'EOF'
package de.blinkt.openvpn.util;

import android.content.Context;
import android.content.Intent;

import java.io.StringReader;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;

public class OvpnImporter {
    public static void importAndConnect(Context context, String ovpnConfig, String profileName) throws Exception {
        ConfigParser parser = new ConfigParser();
        parser.parseConfig(new StringReader(ovpnConfig));
        VpnProfile profile = parser.convertProfile();
        profile.mName = profileName != null ? profileName : "AIO VPN";

        ProfileManager pm = ProfileManager.getInstance(context);
        pm.addProfile(profile);
        pm.saveProfile(context, profile);
        pm.saveProfileList(context);

        Intent intent = new Intent(context, LaunchVPN.class);
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
EOF

# ---------- UI: LoginActivity ----------
cat > "$UI_DIR/LoginActivity.java" <<'EOF'
package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.*;
import de.blinkt.openvpn.util.Prefs;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import android.content.Intent;

public class LoginActivity extends Activity {
    private EditText etUser, etPass;
    private Button btnLogin, btnOpenServers;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUser = findViewById(R.id.etUsername);
        etPass = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnOpenServers = findViewById(R.id.btnServers);

        btnLogin.setOnClickListener(v -> doLogin());
        btnOpenServers.setOnClickListener(v -> {
            startActivity(new Intent(this, ServerPickerActivity.class));
        });
    }

    private void doLogin() {
        String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString().trim();
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        ApiService api = RetrofitClient.service();
        api.login(new LoginRequest(u, p)).enqueue(new Callback<AuthResponse>() {
            @Override public void onResponse(Call<AuthResponse> call, Response<AuthResponse> resp) {
                if (resp.isSuccessful() && resp.body()!=null && resp.body().token!=null) {
                    int userId = resp.body().user != null ? resp.body().user.id : 0;
                    Prefs.saveAuth(LoginActivity.this, resp.body().token, userId);
                    Toast.makeText(LoginActivity.this, "Logged in", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(LoginActivity.this, "Login failed", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<AuthResponse> call, Throwable t) {
                Toast.makeText(LoginActivity.this, "Error: "+t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
EOF

# ---------- UI: ServerPickerActivity ----------
cat > "$UI_DIR/ServerPickerActivity.java" <<'EOF'
package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.*;
import de.blinkt.openvpn.util.Prefs;
import de.blinkt.openvpn.util.OvpnImporter;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.ArrayList;
import java.util.List;

public class ServerPickerActivity extends Activity {
    private ListView listView;
    private ProfileResponse profile;
    private List<String> labels = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_picker);
        listView = findViewById(R.id.lvServers);

        loadServers();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (profile == null || profile.servers == null) return;
            ProfileResponse.Server s = profile.servers.get(position);
            fetchAndConnect(s);
        });
    }

    private void loadServers() {
        String token = Prefs.getToken(this);
        if (token == null) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show(); return;
        }
        ApiService api = RetrofitClient.service();
        api.getProfiles("Bearer "+token).enqueue(new Callback<ProfileResponse>() {
            @Override public void onResponse(Call<ProfileResponse> call, Response<ProfileResponse> resp) {
                if (resp.isSuccessful() && resp.body()!=null) {
                    profile = resp.body();
                    labels.clear();
                    if (profile.servers != null) {
                        for (ProfileResponse.Server s : profile.servers) {
                            labels.add(s.name + " • " + s.ip);
                        }
                        listView.setAdapter(new ArrayAdapter<>(ServerPickerActivity.this,
                                android.R.layout.simple_list_item_1, labels));
                    } else {
                        Toast.makeText(ServerPickerActivity.this, "No servers", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(ServerPickerActivity.this, "Fetch failed", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ProfileResponse> call, Throwable t) {
                Toast.makeText(ServerPickerActivity.this, "Error: "+t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchAndConnect(ProfileResponse.Server s) {
        String token = Prefs.getToken(this);
        int userId = Prefs.getUserId(this);
        if (token == null || userId == 0) {
            Toast.makeText(this, "Missing auth", Toast.LENGTH_SHORT).show(); return;
        }
        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer "+token, userId, s.id).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> call, Response<ResponseBody> resp) {
                if (resp.isSuccessful() && resp.body()!=null) {
                    try {
                        String cfg = resp.body().string();
                        OvpnImporter.importAndConnect(ServerPickerActivity.this, cfg, "AIO "+s.name);
                    } catch (Exception e) {
                        Toast.makeText(ServerPickerActivity.this, "Parse error: "+e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(ServerPickerActivity.this, "Config failed: "+resp.code(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                Toast.makeText(ServerPickerActivity.this, "Error: "+t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
EOF

# ---------- Layouts ----------
cat > "$RES_ROOT/layout/activity_login.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
  android:orientation="vertical" android:padding="24dp"
  android:layout_width="match_parent" android:layout_height="match_parent">

  <EditText
    android:id="@+id/etUsername"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="Username"
    android:inputType="textNoSuggestions" />

  <EditText
    android:id="@+id/etPassword"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="Password"
    android:inputType="textPassword"
    android:layout_marginTop="12dp" />

  <Button
    android:id="@+id/btnLogin"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Login"
    android:layout_marginTop="16dp" />

  <Button
    android:id="@+id/btnServers"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Open Server Picker"
    android:layout_marginTop="12dp" />
</LinearLayout>
EOF

cat > "$RES_ROOT/layout/activity_server_picker.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
  android:layout_width="match_parent" android:layout_height="match_parent">
  <ListView
    android:id="@+id/lvServers"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
</FrameLayout>
EOF

# ---------- Manifest edits ----------
if ! grep -q 'android.permission.INTERNET' "$MANIFEST"; then
  sed -i 's#<manifest#<manifest\n    <uses-permission android:name="android.permission.INTERNET" />#' "$MANIFEST"
fi

# insert activities inside <application> ... </application>
# add a dedicated launcher for LoginActivity so you can open it directly
if ! grep -q 'de.blinkt.openvpn.ui.LoginActivity' "$MANIFEST"; then
  awk '
  /<application/ && !ins { print; print "        <activity android:name=\"de.blinkt.openvpn.ui.LoginActivity\">"; 
    print "            <intent-filter>"; 
    print "                <action android:name=\"android.intent.action.MAIN\" />"; 
    print "                <category android:name=\"android.intent.category.LAUNCHER\" />"; 
    print "            </intent-filter>"; 
    print "        </activity>"; 
    print "        <activity android:name=\"de.blinkt.openvpn.ui.ServerPickerActivity\"/>"; 
    ins=1; next } { print }' "$MANIFEST" > "$MANIFEST.tmp" && mv "$MANIFEST.tmp" "$MANIFEST"
fi

# ---------- Gradle deps ----------
# Add Retrofit/OkHttp only if missing
if ! grep -q 'com.squareup.retrofit2:retrofit' "$GRADLE"; then
  sed -i '/dependencies\s*{.*/a\
    implementation("com.squareup.retrofit2:retrofit:2.11.0")\
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")\
    implementation("com.squareup.okhttp3:okhttp:4.12.0")' "$GRADLE"
fi

echo "==> Files created & project patched."

echo "==> Running gradle sync/build instruction:"
echo "   ./gradlew :main:assembleDebug  (or assembleRelease with your signing)"
