package de.blinkt.openvpn.activities;

import android.os.Bundle;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import de.blinkt.openvpn.R;

public class LogWindow extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.log_window);
        TextView tv = findViewById(R.id.log_text);
        // TODO: hook this up to your real log source
        tv.setText("OpenVPN log will appear here…");
    }
}