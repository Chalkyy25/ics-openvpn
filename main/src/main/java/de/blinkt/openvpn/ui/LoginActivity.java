// LoginActivity.java
package de.blinkt.openvpn.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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

public class LoginActivity extends AppCompatActivity {

    private EditText etUser, etPass;
    private Button btnLogin;
    private View btnOpenServers;    // hidden on this screen
    private ProgressBar progress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // If already logged in, jump straight to Home and clear back stack
        String existing = Prefs.getToken(this);
        if (!TextUtils.isEmpty(existing)) {
            goToMain();
            return;
        }

        etUser = findViewById(R.id.etUsername);
        etPass = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnOpenServers = findViewById(R.id.btnServers);
        progress = findViewById(R.id.progress); // Add an indeterminate ProgressBar with this id

        // Server picker requires auth — keep it hidden here
        if (btnOpenServers != null) btnOpenServers.setVisibility(View.GONE);

        btnLogin.setOnClickListener(v -> doLogin());

        // Support keyboard "Done" to submit
        if (etPass != null) {
            etPass.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doLogin();
                    return true;
                }
                return false;
            });
        }
    }

    private void doLogin() {
        String u = safeText(etUser);
        String p = safeText(etPass);

        if (u.isEmpty() || p.isEmpty()) {
            toast("Enter username and password");
            return;
        }

        setLoading(true);

        ApiService api = RetrofitClient.service();
        Call<AuthResponse> call = api.login(new LoginRequest(u, p));
        call.enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(Call<AuthResponse> call, Response<AuthResponse> resp) {
                try {
                    if (!resp.isSuccessful()) {
                        String msg = "Login failed (HTTP " + resp.code() + ")";
                        ResponseBody eb = resp.errorBody();
                        if (eb != null) {
                            try { msg += ": " + eb.string(); } catch (Exception ignored) {}
                        }
                        toast(msg);
                        return;
                    }

                    AuthResponse body = resp.body();
                    if (body == null || TextUtils.isEmpty(body.token)) {
                        toast("Login failed: empty response");
                        return;
                    }

                    int userId = (body.user != null) ? body.user.id : 0;

                    // Save API token/userId and the VPN creds (used when building VpnProfile)
                    Prefs.saveAuth(LoginActivity.this, body.token, userId);
                    Prefs.saveVpnCreds(LoginActivity.this, u, p);

                    // Proceed to Home — clear the back stack so Back won’t return here
                    goToMain();

                } finally {
                    // If goToMain() runs, activity is finishing; this is harmless.
                    setLoading(false);
                }
            }

            @Override
            public void onFailure(Call<AuthResponse> call, Throwable t) {
                toast(t.getMessage() == null ? "Network error" : t.getMessage());
                setLoading(false);
            }
        });
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) btnLogin.setEnabled(!loading);
        if (etUser != null) etUser.setEnabled(!loading);
        if (etPass != null) etPass.setEnabled(!loading);
    }

    private static String safeText(EditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}