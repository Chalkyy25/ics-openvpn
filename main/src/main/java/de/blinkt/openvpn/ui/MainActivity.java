package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.StringReader;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.util.Prefs;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends Activity {

    private static final String TAG = "AIOVPN";
    private static final int REQ_PICK_SERVER = 2001;
    private static final int REQ_VPN_PREP    = 3001;

    private TextView tvServer;
    private Button btnConnect, btnPickServer, btnLogout, btnLogs;
    private ProgressBar progress;

    private Integer serverId = null;
    private String  serverName = null;
    private String  token;
    private int     userId;

    private String pendingProfileUUID; // used after VPN permission grant

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Force verbose logging to help diagnose "no log" issues
        try {
            VpnStatus.updateLogVerbosity(5); // 0..5
            VpnStatus.setLogToLogcat(true);
            VpnStatus.logInfo("AIOVPN: app started; forcing verbose logs");
        } catch (Throwable t) {
            Log.w(TAG, "Could not force VpnStatus verbosity", t);
        }

        // Bind views
        tvServer      = req(R.id.tvServer);
        btnConnect    = req(R.id.btnConnect);
        btnPickServer = req(R.id.btnPickServer);
        btnLogout     = req(R.id.btnLogout);
        btnLogs       = req(R.id.btnLogs);
        progress      = req(R.id.progress);

        token  = Prefs.getToken(this);
        userId = Prefs.getUserId(this);

        // If not logged in, go back to login
        if (token == null || token.isEmpty()) {
            Log.d(TAG, "No token; redirecting to LoginActivity");
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // Restore last selection if any
        serverId   = Prefs.getServerId(this);
        serverName = Prefs.getServerName(this);
        updateServerText();

        // Listeners
        btnPickServer.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ServerPickerActivity.class), REQ_PICK_SERVER)
        );

        btnConnect.setOnClickListener(v -> {
            if (serverId == null || serverId <= 0) {
                toast("Pick a server first");
                return;
            }
            fetchAndConnect();
        });

        btnLogout.setOnClickListener(v -> {
            Prefs.clearAll(this);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        btnLogs.setOnClickListener(v -> openLogWindow());
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!loading);
        btnPickServer.setEnabled(!loading);
        btnLogout.setEnabled(!loading);
        // keep Logs button enabled so you can open it anytime
        btnLogs.setEnabled(true);
    }

    private void updateServerText() {
        if (serverId != null && serverId > 0 && serverName != null && !serverName.isEmpty()) {
            tvServer.setText("Server: " + serverName);
        } else {
            tvServer.setText("Server: (none)");
        }
    }

    private void fetchAndConnect() {
        setLoading(true);
        Log.d(TAG, "Fetching .ovpn for userId=" + userId + " serverId=" + serverId);

        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, serverId).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> resp) {
                try {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        String msg = "Fetch .ovpn failed (HTTP " + resp.code() + ")";
                        Log.e(TAG, msg);
                        toast(msg);
                        return;
                    }
                    String ovpn = resp.body().string();
                    if (ovpn == null || ovpn.trim().isEmpty()) {
                        Log.e(TAG, "Server returned empty .ovpn");
                        toast("Server returned empty .ovpn");
                        return;
                    }

                    // Self-check against the real config we just fetched
                    Log.d(TAG, "OVPN bytes=" + ovpn.length()
                            + " hasCA=" + ovpn.contains("<ca>")
                            + " hasTA=" + ovpn.contains("<tls-auth>")
                            + " hasKeyDir=" + ovpn.contains("key-direction")
                            + " hasAuthUserPass=" + ovpn.contains("auth-user-pass"));

                    startVpnWithConfig(ovpn);
                } catch (Exception e) {
                    Log.e(TAG, "Config parse error", e);
                    toast("Config parse error: " + e.getMessage());
                } finally {
                    setLoading(false);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                Log.e(TAG, "Network error fetching .ovpn", t);
                toast(t.getMessage() == null ? "Network error" : t.getMessage());
                setLoading(false);
            }
        });
    }

    private void startVpnWithConfig(String ovpn) throws Exception {
        Log.d(TAG, "Parsing .ovpn into VpnProfile…");

        // 1) Parse .ovpn → VpnProfile
        ConfigParser cp = new ConfigParser();
        cp.parseConfig(new StringReader(ovpn));
        VpnProfile profile = cp.convertProfile();
        if (profile == null) {
            Log.e(TAG, "convertProfile() returned null");
            toast("Invalid VPN profile");
            return;
        }

        // 2) Friendly name
        profile.mName = "AIO • " + (serverName != null && !serverName.isEmpty() ? serverName : "Profile");
        Log.d(TAG, "Profile parsed. Name=" + profile.mName);

        // 3) Inject auth creds so no prompt is needed
        try {
            profile.mUsername = Prefs.getVpnUser(this);
            profile.mPassword = Prefs.getVpnPass(this);
            // Some forks require setting the auth type constant
            try {
                profile.mAuthenticationType = de.blinkt.openvpn.VpnProfile.TYPE_USERPASS;
            } catch (Throwable ignored) { /* not all forks expose this */ }
            Log.d(TAG, "Injected auth creds: user=" + (profile.mUsername == null ? "(null)" : "(set)"));
        } catch (Throwable ignore) {
            Log.w(TAG, "Could not inject username/password into profile");
        }

        // 4) Save profile
        ProfileManager pm = ProfileManager.getInstance(this);
        pm.addProfile(profile);
        pm.saveProfile(this, profile);
        pm.saveProfileList(this);
        String uuid = profile.getUUID().toString();
        Log.d(TAG, "Saved profile UUID=" + uuid);

        // 5) Request VPN permission if needed, then launch
        Intent prep = VpnService.prepare(this);
        Log.d(TAG, "VpnService.prepare -> " + (prep == null ? "granted" : "needs dialog"));
        if (prep != null) {
            pendingProfileUUID = uuid;
            startActivityForResult(prep, REQ_VPN_PREP);
            return;
        }
        launchVpn(uuid);
    }

    private void launchVpn(String uuid) {
        Log.d(TAG, "Launching LaunchVPN with UUID=" + uuid);
        Intent i = new Intent(this, LaunchVPN.class);
        i.putExtra(LaunchVPN.EXTRA_KEY, uuid);
        startActivity(i); // only once
        toast("Connecting…");
    }

    private void openLogWindow() {
        try {
            Class<?> logWin = Class.forName("de.blinkt.openvpn.activities.LogWindow");
            startActivity(new Intent(this, logWin));
        } catch (ClassNotFoundException e) {
            toast("Log window not in this build. Use MatLog or adb logcat.");
            Log.i(TAG, "Tip: adb logcat -v time | grep -i \"OpenVPN\\|AIOVPN\\|VpnService\\|AUTH\\|TLS\"");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_SERVER && resultCode == RESULT_OK && data != null) {
            serverId   = data.getIntExtra("server_id", 0);
            serverName = data.getStringExtra("server_name");
            Prefs.setServer(this, serverId, serverName);
            updateServerText();
            Log.d(TAG, "Server selected id=" + serverId + " name=" + serverName);
            return;
        }

        if (requestCode == REQ_VPN_PREP) {
            Log.d(TAG, "VPN permission result=" + (resultCode == RESULT_OK ? "OK" : "DENIED"));
            if (resultCode == RESULT_OK && pendingProfileUUID != null) {
                launchVpn(pendingProfileUUID);
            } else {
                toast("VPN permission denied");
            }
            pendingProfileUUID = null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T req(int id) {
        T v = (T) findViewById(id);
        if (v == null) {
            throw new IllegalStateException("Missing view id in activity_main: " + getResources().getResourceName(id));
        }
        return v;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}