package de.blinkt.openvpn.api.dto;
import java.util.List;
public class ProfileResponse {
    public int id;
    public String username;
    public String expires;
    public int max_conn;
    public List<ServerItem> servers;
}
