package de.blinkt.openvpn.api;

public class AuthResponse {
    public String token;
    public User user;
    public static class User {
        public int id;
        public String username;
        public String expires;
        public int max_conn;
    }
}
