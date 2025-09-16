package de.blinkt.openvpn.util;

import android.content.Context;
import android.content.Intent;

import java.io.StringReader;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;

public class OvpnImporter {
    public static void importAndConnect(Context context, String ovpnConfig, String profileName) throws Exception {
        ConfigParser parser = new ConfigParser();
        parser.parseConfig(new StringReader(ovpnConfig));
        VpnProfile profile = parser.convertProfile();
        profile.mName = profileName != null ? profileName : "AIO VPN";

        ProfileManager pm = ProfileManager.getInstance(context);
        pm.addProfile(profile);
        pm.saveProfile(context, profile);
        pm.saveProfileList(context);

        Intent intent = new Intent(context, LaunchVPN.class);
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
