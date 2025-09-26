package de.blinkt.openvpn.api.dto;
public class AuthResponse {
    public String token;
    public User user;
    public static class User { public int id; public String username; public boolean active; }
}
