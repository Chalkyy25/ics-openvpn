package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.dto.AuthResponse;
import de.blinkt.openvpn.api.dto.LoginRequest;
import de.blinkt.openvpn.api.dto.ProfileResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {
    @POST("/api/auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    @GET("/api/profiles")
    Call<ProfileResponse> getProfiles(@Header("Authorization") String bearer);

    @GET("/api/profiles/{userId}")
    Call<ResponseBody> getOvpn(@Header("Authorization") String bearer,
                               @Path("userId") int userId,
                               @Query("server_id") int serverId);
}
