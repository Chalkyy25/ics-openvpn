package de.blinkt.openvpn.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface ApiService {
    @POST("/api/auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    // Optional: used later if you fetch profiles after login
    @GET("/api/profiles")
    Call<ProfilesResponse> profiles(@Header("Authorization") String bearerToken);
}
