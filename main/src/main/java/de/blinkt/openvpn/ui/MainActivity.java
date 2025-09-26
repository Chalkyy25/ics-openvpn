
package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.StringReader;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.util.Prefs;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.api.dto.ProfileResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends Activity {

    private static final int REQ_PICK_SERVER = 2001;

    private TextView tvSelectedServer, tvStatus;
    private Button btnPick, btnConnect;

    private Integer serverId = null;
    private String  serverName = null;
    private String  token;
    private int     userId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSelectedServer = findViewById(R.id.tvSelectedServer);
        tvStatus         = findViewById(R.id.tvStatus);
        btnPick          = findViewById(R.id.btnPickServer);
        btnConnect       = findViewById(R.id.btnConnect);

        token  = Prefs.getToken(this);
        userId = Prefs.getUserId(this);

        // restore last selection if any
        serverId   = Prefs.getServerId(this);
        serverName = Prefs.getServerName(this);
        updateServerText();

        btnPick.setOnClickListener(v ->
            startActivityForResult(new Intent(this, ServerPickerActivity.class), REQ_PICK_SERVER)
        );

        btnConnect.setOnClickListener(v -> {
            if (serverId == null || serverId == 0) {
                Toast.makeText(this, "Pick a server first", Toast.LENGTH_SHORT).show();
                return;
            }
            connectSelectedServer();
        });
    }

    private void updateServerText() {
        if (serverId != null && serverId > 0 && serverName != null) {
            tvSelectedServer.setText("Selected: " + serverName);
        } else {
            tvSelectedServer.setText("No server selected");
        }
    }

    private void connectSelectedServer() {
        tvStatus.setText("Status: fetching config…");
        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, serverId).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> call, Response<ResponseBody> resp) {
                if (!resp.isSuccessful() || resp.body()==null) {
                    tvStatus.setText("Status: failed to fetch config");
                    Toast.makeText(MainActivity.this, "Fetch .ovpn failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    String ovpn = resp.body().string();
                    startVpnWithConfig(ovpn);
                } catch (Exception e) {
                    tvStatus.setText("Status: parse error");
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                tvStatus.setText("Status: network error");
                Toast.makeText(MainActivity.this, t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startVpnWithConfig(String ovpn) throws Exception {
        // Parse .ovpn → VpnProfile and start
        ConfigParser cp = new ConfigParser();
        cp.parseConfig(new StringReader(ovpn));
        VpnProfile profile = cp.convertProfile();
        profile.mName = "AIO • " + (serverName != null ? serverName : "Profile");

        ProfileManager pm = ProfileManager.getInstance(this);
        pm.addProfile(profile);
        pm.saveProfile(this, profile);
        pm.saveProfileList(this);

        Intent i = new Intent(this, LaunchVPN.class);
        i.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
        i./* action removed for compatibility */
        startActivity(i);

        tvStatus.setText("Status: connecting…");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_SERVER && resultCode == RESULT_OK && data != null) {
            serverId   = data.getIntExtra("server_id", 0);
            serverName = data.getStringExtra("server_name");
            Prefs.setServer(this, serverId, serverName);
            updateServerText();
        }
    }
}

