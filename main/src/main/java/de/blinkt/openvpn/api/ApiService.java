package de.blinkt.openvpn.api;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

// DTOs
public class LoginRequest {
    public String username;
    public String password;
    public LoginRequest(String u, String p){ this.username=u; this.password=p; }
}
public class AuthResponse {
    public String token;
    public User user;
    public static class User { public int id; public String username; }
}
public class ProfileResponse {
    public int id;
    public String username;
    public Integer max_conn;
    public java.util.List<Server> servers;
    public static class Server { public int id; public String name; public String ip; }
}

// Retrofit interface
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
