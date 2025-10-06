/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import de.blinkt.openvpn.VpnProfile;

public class ProfileManager {
    private static final String PREFS_NAME = "VPNList";
    private static final String LAST_CONNECTED_PROFILE = "lastConnectedProfile";
    private static final String TEMPORARY_PROFILE_FILENAME = "temporary-vpn-profile";

    private static ProfileManager instance;

    private static VpnProfile mLastConnectedVpn = null;
    private static VpnProfile tmpprofile = null;
    private HashMap<String, VpnProfile> profiles = new HashMap<>();

    // If a previous write failed we fall back to simplest behavior (kept for parity with old code)
    private static boolean encryptionBroken = false; // no-op now (we don't encrypt)

    private ProfileManager() { }

    private static VpnProfile get(String key) {
        if (tmpprofile != null && tmpprofile.getUUIDString().equals(key))
            return tmpprofile;

        if (instance == null) return null;
        return instance.profiles.get(key);
    }

    private synchronized static void checkInstance(Context context) {
        if (instance == null) {
            instance = new ProfileManager();
            // Removed: ProfileEncryption.initMasterCryptAlias(context);
            instance.loadVPNList(context);
        }
    }

    public static synchronized ProfileManager getInstance(Context context) {
        checkInstance(context);
        return instance;
    }

    public static void setConntectedVpnProfileDisconnected(Context c) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);
        Editor prefsedit = prefs.edit();
        prefsedit.putString(LAST_CONNECTED_PROFILE, null);
        prefsedit.apply();
    }

    /** Sets the profile that is connected (to reconnect if the service restarts). */
    public static void setConnectedVpnProfile(Context c, VpnProfile connectedProfile) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);
        Editor prefsedit = prefs.edit();
        prefsedit.putString(LAST_CONNECTED_PROFILE, connectedProfile.getUUIDString());
        prefsedit.apply();
        mLastConnectedVpn = connectedProfile;
    }

    /** Returns the profile that was last connected (to reconnect if the service restarts). */
    public static VpnProfile getLastConnectedProfile(Context c) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);
        String lastConnectedProfile = prefs.getString(LAST_CONNECTED_PROFILE, null);
        if (lastConnectedProfile != null) return get(c, lastConnectedProfile);
        return null;
    }

    public static void setTemporaryProfile(Context c, VpnProfile tmp) {
        tmp.mTemporaryProfile = true;
        ProfileManager.tmpprofile = tmp;
        tmp.addChangeLogEntry("temporary profile saved");
        saveProfile(c, tmp);
    }

    public static boolean isTempProfile() {
        return mLastConnectedVpn != null && mLastConnectedVpn == tmpprofile;
    }

    /** Always saves in plain .vp format. Old .cp/.cpold are ignored/cleaned if found. */
    public static void saveProfile(Context context, VpnProfile profile) {
        // Keep preference read for compatibility; it no longer controls encryption behavior.
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);
        boolean preferEncryption = prefs.getBoolean("preferencryption", true);
        if (encryptionBroken) preferEncryption = false; // no-op but kept for parity

        profile.mVersion += 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            profile.addChangeLogEntry("Saving version " + profile.mVersion +
                    " from process " + Application.getProcessName());
        }

        String filename = profile.getUUID().toString();
        if (profile.mTemporaryProfile) filename = TEMPORARY_PROFILE_FILENAME;

        ObjectOutputStream oos = null;
        try {
            // Always write .vp (plain)
            FileOutputStream fos = context.openFileOutput(filename + ".vp", Activity.MODE_PRIVATE);
            oos = new ObjectOutputStream(fos);
            oos.writeObject(profile);
            oos.flush();

            // Best-effort cleanup of any old encrypted artifacts if present
            File cp = context.getFileStreamPath(filename + ".cp");
            File cpold = context.getFileStreamPath(filename + ".cpold");
            if (cp.exists())  //noinspection ResultOfMethodCallIgnored
                cp.delete();
            if (cpold.exists())  //noinspection ResultOfMethodCallIgnored
                cpold.delete();

            VpnStatus.notifyProfileVersionChanged(profile.getUUIDString(), profile.mVersion, true);
        } catch (IOException e) {
            VpnStatus.logException("saving VPN profile", e);
            throw new RuntimeException(e);
        } finally {
            if (oos != null) {
                try { oos.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static VpnProfile get(Context context, String profileUUID) {
        return get(context, profileUUID, 0, 10);
    }

    public static VpnProfile get(Context context, String profileUUID, int version, int tries) {
        checkInstance(context);
        VpnProfile profile = get(profileUUID);
        int tried = 0;
        while ((profile == null || profile.mVersion < version) && (tried++ < tries)) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            instance.loadVPNList(context);
            profile = get(profileUUID);
        }

        if (tried > 5) {
            int ver = (profile == null) ? -1 : profile.mVersion;
            VpnStatus.logError(String.format(Locale.US,
                    "Used x %d tries to get current version (%d/%d) of the profile",
                    tried, ver, version));
        }
        return profile;
    }

    public static VpnProfile getLastConnectedVpn() {
        return mLastConnectedVpn;
    }

    public static VpnProfile getAlwaysOnVPN(Context context) {
        checkInstance(context);
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);
        String uuid = prefs.getString("alwaysOnVpn", null);
        return get(uuid);
    }

    public static void updateLRU(Context c, VpnProfile profile) {
        profile.mLastUsed = System.currentTimeMillis();
        // LRU does not change the profile semantics; still persist for cross-process visibility
        if (profile != tmpprofile) {
            profile.addChangeLogEntry("Saved last recently used");
            saveProfile(c, profile);
        }
    }

    public static void notifyProfileVersionChanged(Context c, String uuid, int version) {
        // The profile was saved/modified (maybe in another process). Reload if needed.
        VpnProfile loadedProfile = get(c, uuid, version, 100);
        if (loadedProfile != null & loadedProfile.mVersion >= version) {
            VpnStatus.notifyProfileVersionChanged(uuid, version, false);
        }
    }

    public Collection<VpnProfile> getProfiles() {
        return profiles.values();
    }

    public VpnProfile getProfileByName(String name) {
        for (VpnProfile vpnp : profiles.values()) {
            if (vpnp.getName().equals(name)) return vpnp;
        }
        return null;
    }

    public void saveProfileList(Context context) {
        SharedPreferences sharedprefs = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Editor editor = sharedprefs.edit();
        editor.putStringSet("vpnlist", profiles.keySet());

        // Historical quirk retained from upstream (ensures file write on some devices)
        int counter = sharedprefs.getInt("counter", 0);
        editor.putInt("counter", counter + 1);
        editor.apply();
    }

    public synchronized void addProfile(VpnProfile profile) {
        profiles.put(profile.getUUID().toString(), profile);
    }

    /**
     * Checks if a profile has been added/deleted since last loading and updates the in-memory list.
     */
    public synchronized void refreshVPNList(Context context) {
        SharedPreferences listpref = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Set<String> vlist = listpref.getStringSet("vpnlist", null);
        if (vlist == null) return;

        for (String vpnentry : vlist) {
            if (!profiles.containsKey(vpnentry)) loadVpnEntry(context, vpnentry);
        }

        Vector<String> removeUuids = new Vector<>();
        for (String profileuuid : profiles.keySet()) {
            if (!vlist.contains(profileuuid)) removeUuids.add(profileuuid);
        }
        for (String uuid : removeUuids) profiles.remove(uuid);
    }

    private synchronized void loadVPNList(Context context) {
        profiles = new HashMap<>();
        SharedPreferences listpref = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Set<String> vlist = listpref.getStringSet("vpnlist", null);
        if (vlist == null) vlist = new HashSet<>();

        // Always try to load the temporary profile too
        vlist.add(TEMPORARY_PROFILE_FILENAME);

        for (String vpnentry : vlist) loadVpnEntry(context, vpnentry);
    }

    private synchronized void loadVpnEntry(Context context, String vpnentry) {
        ObjectInputStream vpnfile = null;
        try {
            // We only support plain .vp now.
            FileInputStream vpInput = context.openFileInput(vpnentry + ".vp");
            vpnfile = new ObjectInputStream(vpInput);
            VpnProfile vp = (VpnProfile) vpnfile.readObject();

            // Sanity check
            if (vp == null || vp.mName == null || vp.getUUID() == null) return;

            vp.upgradeProfile();

            if (vpnentry.equals(TEMPORARY_PROFILE_FILENAME)) {
                tmpprofile = vp;
            } else {
                profiles.put(vp.getUUID().toString(), vp);
            }
        } catch (IOException | ClassNotFoundException e) {
            // If there are lingering .cp/.cpold files from older builds, we ignore them here.
            if (!vpnentry.equals(TEMPORARY_PROFILE_FILENAME)) {
                VpnStatus.logInfo("Could not load " + vpnentry + ".vp (plain). " +
                        "If you previously used encrypted profiles (.cp), resave profiles in this build.");
                VpnStatus.logException("Loading VPN List", e);
            }
        } finally {
            if (vpnfile != null) {
                try { vpnfile.close(); } catch (IOException ignored) {}
            }
        }
    }

    public synchronized void removeProfile(Context context, VpnProfile profile) {
        String vpnentry = profile.getUUID().toString();
        profiles.remove(vpnentry);
        saveProfileList(context);
        context.deleteFile(vpnentry + ".vp");
        if (mLastConnectedVpn == profile) mLastConnectedVpn = null;
    }
}
