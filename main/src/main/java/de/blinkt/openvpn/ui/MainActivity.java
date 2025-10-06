package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.StringReader;
import java.util.Locale;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.util.LocationFormat;
import de.blinkt.openvpn.util.Prefs;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    // UI
    private View powerButton, cardServer;
    private TextView tvConnectedTitle, tvStatus, tvHint, tvTimer, tvBytesIn, tvBytesOut;
    private TextView tvServerTitle, tvServerSubtitle, tvFlag;

    // State
    private long connectedSince = 0L;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean connectingOrStopping = false;

    // Auth / selection
    private String token;
    private int userId;
    private Integer serverId;
    private String serverName;

    // Intents
    private static final int REQ_PICK_SERVER = 501;
    private static final int REQ_VPN_PREP = 3001;
    private String pendingProfileId;

    // ---- 1s timer for session clock ----
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (connectedSince > 0) {
                long s = (System.currentTimeMillis() - connectedSince) / 1000;
                long hH = s / 3600; s %= 3600; long m = s / 60; s %= 60;
                tvTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", hH, m, s));
            }
            h.postDelayed(this, 1000);
        }
    };

    // ---- Lifecycle ----
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connected);

        // Find views
        powerButton      = findViewById(R.id.powerButton);
        cardServer       = findViewById(R.id.cardServer);
        tvConnectedTitle = findViewById(R.id.tvConnectedTitle);
        tvStatus         = findViewById(R.id.tvStatus);
        tvHint           = findViewById(R.id.tvHint);
        tvTimer          = findViewById(R.id.tvTimer);
        tvBytesIn        = findViewById(R.id.tvBytesIn);
        tvBytesOut       = findViewById(R.id.tvBytesOut);
        tvServerTitle    = findViewById(R.id.tvServerTitle);
        tvServerSubtitle = findViewById(R.id.tvServerSubtitle);
        tvFlag           = findViewById(R.id.tvFlag);

        // Load persisted auth + last server
        token      = Prefs.getToken(this);
        userId     = Prefs.getUserId(this);
        serverId   = Prefs.getServerId(this);
        serverName = Prefs.getServerName(this);

        // Apply server title/subtitle/flag safely
        if (serverName != null && !serverName.isEmpty()) {
            tvServerTitle.setText(serverName);
            tvServerSubtitle.setText(LocationFormat.formatSubtitle(serverName));
            tvFlag.setText(LocationFormat.flagFromLabel(serverName));
        } else {
            tvServerSubtitle.setText("Tap to choose");
            tvFlag.setText("🏳️");
        }

        powerButton.setOnClickListener(v -> onPowerTapped());
        cardServer.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ServerPickerActivity.class), REQ_PICK_SERVER));

        findViewById(R.id.navVpn).setOnClickListener(v -> {});
        findViewById(R.id.navMap).setOnClickListener(v -> Toast.makeText(this, "Map coming soon", Toast.LENGTH_SHORT).show());
        findViewById(R.id.navOptions).setOnClickListener(v -> Toast.makeText(this, "Options coming soon", Toast.LENGTH_SHORT).show());

        setDisconnectedUi(); // initial visual; we’ll sync to real state onResume()
    }

    // ---- Power Button ----
    private void onPowerTapped() {
        if (connectingOrStopping) return;

        if ("Connected".contentEquals(tvStatus.getText())) {
            // Disconnect
            setButtonsEnabled(false);
            connectingOrStopping = true;
            requestDisconnectReliable(() -> {
                setDisconnectedUi();
                connectingOrStopping = false;
                setButtonsEnabled(true);
            });
            return;
        }

        // Connect
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Please log in", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        if (serverId == null || serverId <= 0) {
            Toast.makeText(this, "Pick a server first", Toast.LENGTH_SHORT).show();
            startActivityForResult(new Intent(this, ServerPickerActivity.class), REQ_PICK_SERVER);
            return;
        }

        connectingOrStopping = true;
        setButtonsEnabled(false);
        setConnectingUi();
        fetchAndConnect(serverId, serverName);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (powerButton != null) powerButton.setEnabled(enabled);
        if (cardServer  != null) cardServer.setEnabled(enabled);
    }

    // ---- Reliable disconnect ----
    private void requestDisconnectReliable(@Nullable Runnable then) {
        try { // A) Legacy activity
            Intent i = new Intent(this, de.blinkt.openvpn.activities.DisconnectVPN.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i);
        } catch (Throwable ignored) { }

        try { // B) Binder stop
            Intent svc = new Intent(this, OpenVPNService.class);
            svc.setAction(OpenVPNService.START_SERVICE);
            bindService(svc, new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                    try {
                        IOpenVPNServiceInternal m = IOpenVPNServiceInternal.Stub.asInterface(b);
                        if (m != null) {
                            try { m.stopVPN(true); } catch (Throwable t) { m.stopVPN(false); }
                        }
                    } catch (Throwable ignored2) { }
                    try { unbindService(this); } catch (Throwable ignored3) { }
                }
                @Override public void onServiceDisconnected(android.content.ComponentName n) { }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) { }

        try { // C) Best-effort broadcast
            Intent stop = new Intent(this, OpenVPNService.class);
            stop.setAction("de.blinkt.openvpn.STOP");
            startService(stop);
        } catch (Throwable ignored) { }

        ProfileManager.setConntectedVpnProfileDisconnected(this);

        // Poll until VPN is really down
        final int[] tries = {0};
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (!isSystemVpnActive() || tries[0] >= 10) {
                    if (then == null) setDisconnectedUi(); else then.run();
                } else {
                    tries[0]++;
                    h.postDelayed(this, 300);
                }
            }
        };
        h.postDelayed(poll, 300);
    }

    // ---- Connect flow ----
    private void fetchAndConnect(int sid, String sname) {
        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, sid).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> resp) {
                try {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        Toast.makeText(MainActivity.this, "Failed to fetch config (" + resp.code() + ")", Toast.LENGTH_SHORT).show();
                        setDisconnectedUi(); connectingOrStopping = false; setButtonsEnabled(true); return;
                    }
                    String ovpn = resp.body().string();
                    if (ovpn == null || ovpn.trim().isEmpty()) {
                        Toast.makeText(MainActivity.this, "Empty config", Toast.LENGTH_SHORT).show();
                        setDisconnectedUi(); connectingOrStopping = false; setButtonsEnabled(true); return;
                    }

                    // Ensure UI creds + UDP exit notify
                    ovpn = ovpn.replaceAll("(?im)^\\s*auth-user-pass\\s+\\S+\\s*$", "auth-user-pass");
                    String lower = ovpn.toLowerCase(Locale.US);
                    if (lower.contains("proto udp") && !lower.matches("(?s).*\\bexplicit-exit-notify\\b.*")) {
                        ovpn += "\nexplicit-exit-notify 3\n";
                    }

                    startVpnWithConfig(ovpn, sname);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Parse error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    setDisconnectedUi(); connectingOrStopping = false; setButtonsEnabled(true);
                }
            }
            @Override public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                Toast.makeText(MainActivity.this, t.getMessage() == null ? "Network error" : t.getMessage(), Toast.LENGTH_SHORT).show();
                setDisconnectedUi(); connectingOrStopping = false; setButtonsEnabled(true);
            }
        });
    }

    private void startVpnWithConfig(String ovpn, String sname) throws Exception {
        ConfigParser cp = new ConfigParser();
        cp.parseConfig(new StringReader(ovpn));
        VpnProfile profile = cp.convertProfile();
        if (profile == null) {
            Toast.makeText(this, "Invalid profile", Toast.LENGTH_SHORT).show();
            setDisconnectedUi(); connectingOrStopping = false; setButtonsEnabled(true); return;
        }

        profile.mName = "AIO • " + (sname == null ? "Profile" : sname);

        try {
            profile.mUsername = Prefs.getVpnUser(this);
            profile.mPassword = Prefs.getVpnPass(this);
            try { profile.mAuthenticationType = de.blinkt.openvpn.VpnProfile.TYPE_USERPASS; } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        ProfileManager pm = ProfileManager.getInstance(this);
        pm.addProfile(profile);
        ProfileManager.saveProfile(this, profile);
        pm.saveProfileList(this);

        pendingProfileId = profile.getUUID().toString();

        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN_PREP);
        else startVpnNow(profile);
    }

    private void startVpnNow(VpnProfile profile) {
        de.blinkt.openvpn.core.VPNLaunchHelper.startOpenVpn(profile, getBaseContext(), "ui", false);
        connectedSince = System.currentTimeMillis();
        setConnectedUi();
        connectingOrStopping = false;
        setButtonsEnabled(true);
    }

    private void switchServer(int newServerId, String newServerName) {
        if (connectingOrStopping) return;
        connectingOrStopping = true;
        setButtonsEnabled(false);

        // Update selection + UI
        serverId = newServerId;
        serverName = newServerName;
        tvServerTitle.setText(serverName);
        tvServerSubtitle.setText(LocationFormat.formatSubtitle(serverName));
        tvFlag.setText(LocationFormat.flagFromLabel(serverName));
        Prefs.setServer(this, serverId, serverName);

        setConnectingUi();
        requestDisconnectReliable(() -> fetchAndConnect(serverId, serverName));
    }

    // ---- UI helpers ----
    private void setConnectedUi() {
        tvConnectedTitle.setText("Connected");
        tvStatus.setText("Connected");
        tvHint.setText("You can use other apps normally");
        if (connectedSince == 0L) connectedSince = System.currentTimeMillis();
    }

    private void setConnectingUi() {
        tvConnectedTitle.setText("Connecting…");
        tvStatus.setText("Connecting…");
        tvHint.setText("Establishing secure tunnel");
    }

    private void setDisconnectedUi() {
        tvConnectedTitle.setText("Disconnected");
        tvStatus.setText("Disconnected");
        tvHint.setText("Tap to connect");
        connectedSince = 0L;
        tvTimer.setText("00:00:00");
        tvBytesIn.setText("DL 0 KB");
        tvBytesOut.setText("UL 0 KB");
    }

    // ---- System VPN state reflectors ----
    private boolean isSystemVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void syncUiWithSystemVpn() {
        if (isSystemVpnActive()) setConnectedUi(); else setDisconnectedUi();
    }

    // ---- Lifecycle ----
    @Override protected void onResume() {
        super.onResume();
        h.post(tick);
        syncUiWithSystemVpn();
    }

    @Override protected void onPause()  {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_SERVER && resultCode == Activity.RESULT_OK && data != null) {
            int sid = data.getIntExtra("server_id", 0);
            String sname = data.getStringExtra("server_name");
            if (sid > 0 && sname != null) {
                // Update UI immediately
                tvServerTitle.setText(sname);
                tvServerSubtitle.setText(LocationFormat.formatSubtitle(sname));
                tvFlag.setText(LocationFormat.flagFromLabel(sname));
                // Switch (disconnect then reconnect)
                switchServer(sid, sname);
            } else {
                Toast.makeText(this, "Invalid server selection", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_VPN_PREP) {
            if (resultCode == Activity.RESULT_OK && pendingProfileId != null) {
                VpnProfile p = ProfileManager.get(this, pendingProfileId);
                if (p != null) startVpnNow(p);
                else {
                    setDisconnectedUi();
                    Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show();
                    connectingOrStopping = false;
                    setButtonsEnabled(true);
                }
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
                setDisconnectedUi();
                connectingOrStopping = false;
                setButtonsEnabled(true);
            }
            pendingProfileId = null;
        }
    }
}