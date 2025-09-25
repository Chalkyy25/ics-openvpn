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
