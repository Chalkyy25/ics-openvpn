package de.blinkt.openvpn.api;

public class LoginRequest {
    private String username;
    private String password;
    public LoginRequest(String u, String p) { this.username = u; this.password = p; }
}
