package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.util.Prefs;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends Activity {

    private static final int REQ_PICK_SERVER = 2001;
    private static final int REQ_VPN_PREP   = 3001;

    private TextView tvServer;
    private Button btnConnect, btnPickServer, btnLogout;
    private ProgressBar progress;

    private Integer serverId = null;
    private String  serverName = null;
    private String  token;
    private int     userId;

    private String pendingProfileUUID; // used after VPN permission grant

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views (these IDs match your XML)
        tvServer      = req(R.id.tvServer);
        btnConnect    = req(R.id.btnConnect);
        btnPickServer = req(R.id.btnPickServer);
        btnLogout     = req(R.id.btnLogout);
        progress      = req(R.id.progress);

        token  = Prefs.getToken(this);
        userId = Prefs.getUserId(this);

        // restore last selection if any
        serverId   = Prefs.getServerId(this);
        serverName = Prefs.getServerName(this);
        updateServerText();

        btnPickServer.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ServerPickerActivity.class), REQ_PICK_SERVER)
        );

        btnConnect.setOnClickListener(v -> {
            if (serverId == null || serverId <= 0) {
                toast("Pick a server first");
                return;
            }
            if (token == null || token.isEmpty()) {
                toast("You’re not logged in. Please log in again.");
                return;
            }
            fetchAndConnect();
        });

        btnLogout.setOnClickListener(v -> {
            Prefs.clearAuth(this);
            Prefs.setServer(this, 0, null);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!loading);
        btnPickServer.setEnabled(!loading);
        btnLogout.setEnabled(!loading);
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

        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, serverId).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> resp) {
                try {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        toast("Fetch .ovpn failed (HTTP " + resp.code() + ")");
                        return;
                    }
                    String ovpn = resp.body().string();
                    if (ovpn.trim().isEmpty()) {
                        toast("Server returned empty .ovpn");
                        return;
                    }
                    startVpnWithConfig(ovpn);
                } catch (Exception e) {
                    toast("Config parse error: " + e.getMessage());
                } finally {
                    setLoading(false);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                toast(t.getMessage() == null ? "Network error" : t.getMessage());
                setLoading(false);
            }
        });
    }

    private void startVpnWithConfig(String ovpn) throws Exception {
        // Parse .ovpn → VpnProfile
        ConfigParser cp = new ConfigParser();
        cp.parseConfig(new StringReader(ovpn));
        VpnProfile profile = cp.convertProfile();
        if (profile == null) {
            toast("Invalid VPN profile");
            return;
        }
        profile.mName = "AIO • " + (serverName != null && !serverName.isEmpty() ? serverName : "Profile");

        // Save into ICS profile store
        ProfileManager pm = ProfileManager.getInstance(this);
        pm.addProfile(profile);
        ProfileManager.saveProfile(this, profile);
        pm.saveProfileList(this);

        // Request VPN permission if needed
        Intent prep = android.net.VpnService.prepare(this);
        if (prep != null) {
            pendingProfileUUID = profile.getUUID().toString();
            startActivityForResult(prep, REQ_VPN_PREP);
            return;
        }

        launchVpn(profile.getUUID().toString());
    }

    private void launchVpn(String uuid) {
        Intent i = new Intent(this, LaunchVPN.class);
        i.putExtra(LaunchVPN.EXTRA_KEY, uuid);
        startActivity(i); // only once
        toast("Connecting…");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_SERVER && resultCode == RESULT_OK && data != null) {
            serverId   = data.getIntExtra("server_id", 0);
            serverName = data.getStringExtra("server_name");
            Prefs.setServer(this, serverId, serverName);
            updateServerText();
            return;
        }

        if (requestCode == REQ_VPN_PREP) {
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
