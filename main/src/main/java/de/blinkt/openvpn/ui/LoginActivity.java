package de.blinkt.openvpn.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.content.Intent;

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

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

        ApiService api = RetrofitClient.service();
        api.login(new LoginRequest(u, p)).enqueue(new Callback<AuthResponse>() {
            @Override public void onResponse(Call<AuthResponse> call, Response<AuthResponse> resp) {
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
                    Prefs.saveAuth(LoginActivity.this, body.token, userId);
                    toast("Logged in");

                    // Go to main screen
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } catch (Throwable t) {
                    toast("Login error: " + t.getMessage());
                }
            }
            @Override public void onFailure(Call<AuthResponse> call, Throwable t) {
                toast("Network error: " + (t.getMessage() == null ? "unknown" : t.getMessage()));
            }
        });
    }

    private static String safe(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
