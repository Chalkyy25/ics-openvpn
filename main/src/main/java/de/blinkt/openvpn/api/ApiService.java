package de.blinkt.openvpn.api;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

public interface ApiService {
    @POST("auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    @GET("profiles")
    Call<ProfileResponse> getProfiles(@Header("Authorization") String bearer);

    @GET("profiles/{id}")
    Call<ResponseBody> getOvpn(
        @Header("Authorization") String bearer,
        @Path("id") int userId,
        @Query("server_id") int serverId
    );
}
