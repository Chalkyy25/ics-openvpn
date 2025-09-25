package de.blinkt.openvpn.api;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class RetrofitClient {

    // Your panel API base URL (keep the trailing slash)
    private static final String BASE_URL = "https://aiovpn.co.uk/api/";

    private static volatile ApiService INSTANCE;

    public static ApiService service() {
        if (INSTANCE == null) {
            synchronized (RetrofitClient.class) {
                if (INSTANCE == null) {
                    HttpLoggingInterceptor httpLog = new HttpLoggingInterceptor();
                    httpLog.setLevel(HttpLoggingInterceptor.Level.BASIC);

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
