package de.blinkt.openvpn.api;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class ProfilesResponse {
    @SerializedName("id")        public int id;
    @SerializedName("username")  public String username;
    @SerializedName("expires")   public String expires;
    @SerializedName("max_conn")  public int maxConn;
    @SerializedName("servers")   public List<Server> servers;

    public static class Server {
        @SerializedName("id")   public int id;
        @SerializedName("name") public String name;
        @SerializedName("ip")   public String ip;
    }
}
