package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.api.dto.ProfileResponse;
import de.blinkt.openvpn.api.dto.ServerItem;
import de.blinkt.openvpn.util.Prefs;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ServerPickerActivity extends AppCompatActivity implements ServerPickerAdapter.OnServerClick {

    private EditText etSearch;
    private RecyclerView rvServers;
    private ProgressBar progress;
    private TextView tvEmpty;

    private final List<ServerItem> all = new ArrayList<>();
    private final List<ServerItem> filtered = new ArrayList<>();
    private ServerPickerAdapter adapter;

    // flip to true if you want to instantly fetch OVPN & (later) auto-import/connect
    private static final boolean AUTO_FETCH_OVPN_ON_SELECT = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_picker);

        etSearch = findViewById(R.id.etSearch);
        rvServers = findViewById(R.id.rvServers);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);

        rvServers.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ServerPickerAdapter(filtered, this);
        rvServers.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadServers();
    }

    private void loadServers() {
        String token = Prefs.getToken(this);
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        ApiService api = RetrofitClient.service();
        api.getProfiles("Bearer " + token).enqueue(new Callback<ProfileResponse>() {
            @Override
            public void onResponse(Call<ProfileResponse> call, Response<ProfileResponse> resp) {
                progress.setVisibility(View.GONE);

                if (!resp.isSuccessful() || resp.body() == null) {
                    tvEmpty.setText("Failed to load servers");
                    tvEmpty.setVisibility(View.VISIBLE);
                    return;
                }

                ProfileResponse profile = resp.body();
                all.clear();
                if (profile.servers != null) {
                    all.addAll(profile.servers);
                }

                applyFilter(etSearch.getText() == null ? "" : etSearch.getText().toString());
                tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                if (all.isEmpty()) tvEmpty.setText("No servers available");
            }

            @Override
            public void onFailure(Call<ProfileResponse> call, Throwable t) {
                progress.setVisibility(View.GONE);
                tvEmpty.setText("Error: " + t.getMessage());
                tvEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void applyFilter(String q) {
        String qq = (q == null ? "" : q.trim().toLowerCase(Locale.US));
        filtered.clear();
        for (ServerItem s : all) {
            String label = (s.name + " " + (s.ip == null ? "" : s.ip)).toLowerCase(Locale.US);
            if (label.contains(qq)) filtered.add(s);
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onServerSelected(ServerItem s) {
        if (AUTO_FETCH_OVPN_ON_SELECT) {
            fetchAndReportConfigSize(s); // demo; you can import/connect here
        } else {
            finishWithSelection(s.id, s.name);
        }
    }

    private void finishWithSelection(int id, String name) {
        Intent r = new Intent();
        r.putExtra("server_id", id);
        r.putExtra("server_name", name);
        setResult(Activity.RESULT_OK, r);
        finish();
    }

    // Example: fetch OVPN (you can hook into your importer/connector)
    private void fetchAndReportConfigSize(ServerItem s) {
        String token = Prefs.getToken(this);
        int userId = Prefs.getUserId(this);
        if (token == null || userId == 0) {
            Toast.makeText(this, "Missing auth", Toast.LENGTH_SHORT).show();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        ApiService api = RetrofitClient.service();
        api.getOvpn("Bearer " + token, userId, s.id).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> call, Response<ResponseBody> resp) {
                progress.setVisibility(View.GONE);
                if (!resp.isSuccessful() || resp.body() == null) {
                    Toast.makeText(ServerPickerActivity.this, "Failed to fetch config", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    String cfg = resp.body().string();
                    Toast.makeText(ServerPickerActivity.this, "Config OK (" + cfg.length() + " bytes)", Toast.LENGTH_SHORT).show();

                    // TODO: import/connect:
                    // OvpnImporter.importAndConnect(ServerPickerActivity.this, cfg, "AIO " + s.name);
                    // After starting connection, you might: startActivity(new Intent(this, ConnectedActivity.class));

                    // Or just pass result back:
                    finishWithSelection(s.id, s.name);

                } catch (Exception e) {
                    Toast.makeText(ServerPickerActivity.this, "Read error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                progress.setVisibility(View.GONE);
                Toast.makeText(ServerPickerActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
