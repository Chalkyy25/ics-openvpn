package de.blinkt.openvpn.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/** Modern, UI-less control helpers for the OpenVPNService. */
public final class VpnController {

    private VpnController() {}

    /** Stop VPN without launching any activity. Calls onStopped on the main thread. */
    public static void stopVpn(Context context, Runnable onStopped) {
        Intent svc = new Intent(context, OpenVPNService.class);
        svc.setAction(OpenVPNService.START_SERVICE);

        ServiceConnection sc = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IOpenVPNServiceInternal m =
                            IOpenVPNServiceInternal.Stub.asInterface(binder);
                    if (m != null) m.stopVPN(false);
                } catch (Throwable ignored) { }
                try { context.unbindService(this); } catch (Throwable ignored) { }

                if (onStopped != null) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(onStopped);
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };

        try {
            context.bindService(svc, sc, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) {
            if (onStopped != null) onStopped.run(); // best-effort fallback
        }
    }
}
