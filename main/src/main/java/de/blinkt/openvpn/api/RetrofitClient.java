package de.blinkt.openvpn.api;

import de.blinkt.openvpn.BuildConfig;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {
    // NOTE: trailing slash required when using relative paths in ApiService
    private static final String BASE_URL = "https://aiovpn.co.uk/api/";
    private static volatile ApiService INSTANCE;

    public static ApiService service() {
        if (INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (INSTANCE == null) {
                    HttpLoggingInterceptor httpLog = new HttpLoggingInterceptor();
                    httpLog.setLevel(BuildConfig.DEBUG
                            ? HttpLoggingInterceptor.Level.BODY
                            : HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .addInterceptor(httpLog)
                            .build();

                    Retrofit retrofit = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(client)
                            .build();

                    INSTANCE = retrofit.create(ApiService.class);
                }
            }
        }
        return INSTANCE;
    }

    private RetrofitClient() {}
}
