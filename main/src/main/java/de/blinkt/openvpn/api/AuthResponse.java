package de.blinkt.openvpn.api;

import com.google.gson.annotations.SerializedName;

public class AuthResponse {
    @SerializedName("token") public String token;
    @SerializedName("user")  public User user;

    public static class User {
        @SerializedName("id")       public int id;
        @SerializedName("username") public String username;
        @SerializedName("active")   public boolean active;
        @SerializedName("expires")  public String expires;   // ISO8601
        @SerializedName("max_conn") public int maxConn;
    }
}
