package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.ProfileResponse;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.api.ServerItem;
import de.blinkt.openvpn.util.Prefs;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ServerPickerActivity extends Activity {

    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<ServerItem> servers = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_picker);

        listView = findViewById(R.id.serverList);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ServerItem s = servers.get(position);
            finishWithSelection(s.id, s.name);
        });

        loadServers();
    }

    private void finishWithSelection(int id, String name) {
        Intent r = new Intent();
        r.putExtra("server_id", id);
        r.putExtra("server_name", name);
        setResult(Activity.RESULT_OK, r);
        finish();
    }

    private void loadServers() {
        String token = Prefs.getToken(this);
        if (token == null) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ApiService api = RetrofitClient.service();
        api.getProfiles("Bearer " + token).enqueue(new Callback<ProfileResponse>() {
            @Override public void onResponse(Call<ProfileResponse> call, Response<ProfileResponse> resp) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    Toast.makeText(ServerPickerActivity.this, "Failed to load servers", Toast.LENGTH_SHORT).show();
                    return;
                }
                ProfileResponse profile = resp.body();
                servers.clear();
                labels.clear();
                if (profile.servers != null) {
                    servers.addAll(profile.servers);
                    for (ServerItem s : profile.servers) {
                        labels.add(s.name + (s.ip != null ? " (" + s.ip + ")" : ""));
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void onFailure(Call<ProfileResponse> call, Throwable t) {
                Toast.makeText(ServerPickerActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Optional: fetch and auto-import a config (not used in list click above)
    private void fetchAndImport(ServerItem s) {
        String token = Prefs.getToken(this);
        int userId = Prefs.getUserId(this);
        if (token == null || userId == 0) {
            Toast.makeText(this, "Missing auth", Toast.LENGTH_SHORT).show();
            return;
        }
        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, s.id).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> call, Response<ResponseBody> resp) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    Toast.makeText(ServerPickerActivity.this, "Failed to fetch config", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    String cfg = resp.body().string();
                    // OvpnImporter.importAndConnect(ServerPickerActivity.this, cfg, "AIO " + s.name);
                    Toast.makeText(ServerPickerActivity.this, "Got config (" + cfg.length() + " bytes)", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(ServerPickerActivity.this, "Read error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                Toast.makeText(ServerPickerActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
