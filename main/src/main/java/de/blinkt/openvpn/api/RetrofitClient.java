package de.blinkt.openvpn.api;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.concurrent.TimeUnit;

import de.blinkt.openvpn.core.ICSOpenVPNApplication;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {

    // Safe default; will be overridden by manifest meta-data if present.
    private static final String DEFAULT_BASE_URL = "https://aiovpn.co.uk/api/";

    private static volatile ApiService INSTANCE;

    public static ApiService service() {
        if (INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (INSTANCE == null) {

                    // ---- Resolve base URL (use manifest meta-data if available) ----
                    String baseUrl = DEFAULT_BASE_URL;
                    try {
                        Context ctx = ICSOpenVPNApplication.getAppContext();
                        ApplicationInfo ai = ctx.getPackageManager()
                                .getApplicationInfo(ctx.getPackageName(), PackageManager.GET_META_DATA);
                        if (ai.metaData != null && ai.metaData.containsKey("AIOVPN_API_BASE_URL")) {
                            String meta = ai.metaData.getString("AIOVPN_API_BASE_URL", DEFAULT_BASE_URL);
                            if (meta != null && !meta.isEmpty()) {
                                baseUrl = meta.endsWith("/") ? meta : (meta + "/");
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    // ---- Logging (BODY in debug, BASIC otherwise) ----
                    HttpLoggingInterceptor httpLog = new HttpLoggingInterceptor();
                    httpLog.setLevel(isDebugBuild() ? HttpLoggingInterceptor.Level.BODY
                                                    : HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .addInterceptor(httpLog)
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();

                    Retrofit retrofit = new Retrofit.Builder()
                            .baseUrl(baseUrl)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(client)
                            .build();

                    INSTANCE = retrofit.create(ApiService.class);
                }
            }
        }
        return INSTANCE;
    }

    private static boolean isDebugBuild() {
        try {
            // Uses the library module’s BuildConfig
            return de.blinkt.openvpn.BuildConfig.DEBUG;
        } catch (Throwable t) {
            return false;
        }
    }

    private RetrofitClient() {}
}
