package de.blinkt.openvpn.core;

// Binder interface used by UI/API classes to talk to the service.
interface IOpenVPNServiceInternal {
    boolean protect(int fd);
    void userPause(boolean shouldBePaused);
    boolean stopVPN(boolean replaceConnection);
    void addAllowedExternalApp(String packagename);
    boolean isAllowedExternalApp(String packagename);
    void challengeResponse(String response);
}
