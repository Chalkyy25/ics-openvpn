package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.ApiService;
import de.blinkt.openvpn.api.RetrofitClient;
import de.blinkt.openvpn.api.dto.AuthResponse;
import de.blinkt.openvpn.api.dto.LoginRequest;
import de.blinkt.openvpn.util.Prefs;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends Activity {

    private EditText etUser, etPass;
    private Button btnLogin, btnOpenServers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // If already logged in, jump straight in
        String existing = Prefs.getToken(this);
        if (existing != null && !existing.isEmpty()) {
            goToMain();
            return;
        }

        etUser = findViewById(R.id.etUsername);
        etPass = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnOpenServers = findViewById(R.id.btnServers);

        btnLogin.setOnClickListener(v -> doLogin());
        btnOpenServers.setOnClickListener(v ->
                startActivity(new Intent(this, ServerPickerActivity.class))
        );
    }

    private void doLogin() {
        String u = safe(etUser.getText());
        String p = safe(etPass.getText());

        if (u.isEmpty() || p.isEmpty()) {
            toast("Enter username and password");
            return;
        }

        setUiEnabled(false);

        ApiService api = RetrofitClient.service();
        api.login(new LoginRequest(u, p)).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<AuthResponse> call, @NonNull Response<AuthResponse> resp) {
                try {
                    if (!resp.isSuccessful()) {
                        String msg = "HTTP " + resp.code();
                        ResponseBody eb = resp.errorBody();
                        if (eb != null) msg += " - " + eb.string();
                        toast("Login failed: " + msg);
                        return;
                    }

                    AuthResponse body = resp.body();
                    if (body == null || body.token == null || body.token.isEmpty()) {
                        toast("Login failed: empty response");
                        return;
                    }

                    int userId = (body.user != null) ? body.user.id : 0;

                    // Save API auth + OpenVPN creds (used when building VpnProfile)
                    Prefs.saveAuth(LoginActivity.this, body.token, userId);
                    Prefs.saveVpnCreds(LoginActivity.this, u, p);

                    toast("Logged in");
                    goToMain();

                } catch (Throwable t) {
                    toast("Login error: " + t.getMessage());
                } finally {
                    setUiEnabled(true);
                }
            }

            @Override
            public void onFailure(@NonNull Call<AuthResponse> call, @NonNull Throwable t) {
                toast("Network error: " + (t.getMessage() == null ? "unknown" : t.getMessage()));
                setUiEnabled(true);
            }
        });
    }

    private void goToMain() {
        try {
            Intent i = new Intent(LoginActivity.this, MainActivity.class);
            startActivity(i);
            finish();
        } catch (ActivityNotFoundException e) {
            toast("Main screen not found. Check manifest class name: " + e.getMessage());
        } catch (Throwable t) {
            toast("Failed to open main screen: " + t.getMessage());
        }
    }

    private void setUiEnabled(boolean enabled) {
        if (btnLogin != null) btnLogin.setEnabled(enabled);
        if (btnOpenServers != null) btnOpenServers.setEnabled(enabled);
    }

    private static String safe(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
