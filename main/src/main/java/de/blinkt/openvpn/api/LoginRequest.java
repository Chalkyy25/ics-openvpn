package de.blinkt.openvpn.api;

public class LoginRequest {
    public String username;
    public String password;

    public LoginRequest(String u, String p) {
        this.username = u;
        this.password = p;
    }
}
