package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.dto.AuthResponse;
import de.blinkt.openvpn.api.dto.LoginRequest;
import de.blinkt.openvpn.api.dto.ProfileResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

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
