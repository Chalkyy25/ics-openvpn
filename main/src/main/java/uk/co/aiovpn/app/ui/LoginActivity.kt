package uk.co.aiovpn.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    // Laravel API base (must end with /api/)
    private val baseUrl = "https://panel.aiovpn.co.uk/api/"
    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple UI (no XML)
        val user  = EditText(this).apply { hint = "Username" }
        val pass  = EditText(this).apply { hint = "Password"; inputType = 129 } // TYPE_TEXT_VARIATION_PASSWORD
        val btn   = Button(this).apply { text = "Sign in" }
        val status= TextView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad,pad,pad,pad)
            addView(user); addView(pass); addView(btn); addView(status)
        }
        setContentView(layout)

        btn.setOnClickListener {
            lifecycleScope.launch {
                status.text = "Signing in…"
                val token = login(user.text.toString().trim(), pass.text.toString())
                status.text = if (token != null) "Logged in ✔" else "Login failed"
                // TODO: save token & navigate to next screen
            }
        }
    }

    private suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject(mapOf("username" to username, "password" to password)).toString()
            val req = Request.Builder()
                .url(baseUrl + "auth/login")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                json.optString("token").takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) { null }
    }
}
