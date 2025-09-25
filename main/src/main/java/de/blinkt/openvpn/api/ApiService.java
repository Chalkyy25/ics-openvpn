package de.blinkt.openvpn.api;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {
    @POST("auth/login")
    Call<AuthResponse> login(@Body LoginRequest req);

    @GET("profiles")
    Call<ProfileResponse> getProfiles(@Header("Authorization") String bearer);

    @GET("profiles/{userId}")
    Call<ResponseBody> getOvpn(
            @Header("Authorization") String bearer,
            @Path("userId") int userId,
            @Query("server_id") int serverId
    );
}
