package de.blinkt.openvpn.api;

import java.util.List;

public class ProfileResponse {
    public int id;
    public String username;
    public String expires;
    public int max_conn;
    public List<Server> servers;

    public static class Server {
        public int id;
        public String name;
        public String ip;
        public String proto;
        public Integer port;
    }
}
