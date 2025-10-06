/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static de.blinkt.openvpn.VpnProfile.EXTRA_PROFILEUUID;
import static de.blinkt.openvpn.VpnProfile.EXTRA_PROFILE_VERSION;
import static de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED;
import static de.blinkt.openvpn.core.ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT;
import static de.blinkt.openvpn.core.NetworkSpace.IpAddress;

import android.Manifest.permission;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.activities.DisconnectVPN;
import de.blinkt.openvpn.api.ExternalAppDatabase;
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener;
import de.blinkt.openvpn.core.VpnStatus.StateListener;

public class OpenVPNService extends VpnService implements StateListener, Callback, ByteCountListener {

    public static final String START_SERVICE = "de.blinkt.openvpn.START_SERVICE";
    public static final String START_SERVICE_STICKY = "de.blinkt.openvpn.START_SERVICE_STICKY";
    public static final String ALWAYS_SHOW_NOTIFICATION = "de.blinkt.openvpn.NOTIFICATION_ALWAYS_VISIBLE";

    public static final String EXTRA_DO_NOT_REPLACE_RUNNING_VPN = "de.blinkt.openvpn.DO_NOT_REPLACE_RUNNING_VPN";
    public static final String EXTRA_START_REASON = "de.blinkt.openvpn.startReason";

    public static final String DISCONNECT_VPN = "de.blinkt.openvpn.DISCONNECT_VPN";
    public static final String NOTIFICATION_CHANNEL_BG_ID = "openvpn_bg";
    public static final String NOTIFICATION_CHANNEL_NEWSTATUS_ID = "openvpn_newstat";
    public static final String NOTIFICATION_CHANNEL_USERREQ_ID = "openvpn_userreq";

    public static final String VPNSERVICE_TUN = "vpnservice-tun";
    public static final String ORBOT_PACKAGE_NAME = "org.torproject.android";
    public static final String EXTRA_CHALLENGE_TXT = "de.blinkt.openvpn.core.CR_TEXT_CHALLENGE";
    public static final String EXTRA_CHALLENGE_OPENURL = "de.blinkt.openvpn.core.OPENURL_CHALLENGE";
    private static final String PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN";
    private static final String RESUME_VPN = "de.blinkt.openvpn.RESUME_VPN";

    private static final int PRIORITY_MIN = -2;
    private static final int PRIORITY_DEFAULT = 0;
    private static final int PRIORITY_MAX = 2;

    private static boolean mNotificationAlwaysVisible = false;

    static class TunConfig {
        private final Vector<String> mDnslist = new Vector<>();
        private final NetworkSpace mRoutes = new NetworkSpace();
        private final NetworkSpace mRoutesv6 = new NetworkSpace();
        private Vector<String> mSearchDomainList = new Vector<>();
        private CIDRIP mLocalIP = null;
        private int mMtu;
        private String mLocalIPv6 = null;
        private ProxyInfo mProxyInfo;
    }

    private TunConfig tunConfig = new TunConfig();

    private final Object mProcessLock = new Object();
    private String lastChannel;
    private Thread mProcessThread = null;
    private VpnProfile mProfile;

    private DeviceStateReceiver mDeviceStateReceiver;
    private boolean mDisplayBytecount = false;
    private boolean mStarting = false;
    private long mConnecttime;
    private OpenVPNManagement mManagement;

    private final IBinder mBinder = new IOpenVPNServiceInternal.Stub() {
        @Override public boolean protect(int fd) {
            return OpenVPNService.this.protect(fd);
        }

        @Override public void userPause(boolean shouldbePaused) {
            OpenVPNService.this.userPause(shouldbePaused);
        }

        // DO NOT annotate @Override against a superclass; this overrides the AIDL method
        @Override public boolean stopVPN(boolean replaceConnection) {
            return OpenVPNService.this.stopVPN(replaceConnection);
        }

        // Call the database directly (these methods do not exist on the service)
        @Override public void addAllowedExternalApp(String packagename) {
            ExternalAppDatabase extapps = new ExternalAppDatabase(OpenVPNService.this);
            if (extapps.checkAllowingModifyingRemoteControl(OpenVPNService.this)) {
                extapps.addApp(packagename);
            }
        }

        @Override public boolean isAllowedExternalApp(String packagename) {
            ExternalAppDatabase extapps = new ExternalAppDatabase(OpenVPNService.this);
            return extapps.checkRemoteActionPermission(OpenVPNService.this, packagename);
        }

        @Override public void challengeResponse(String response) {
            if (mManagement != null) {
                String b64 = Base64.encodeToString(
                        response.getBytes(Charset.forName("UTF-8")),
                        Base64.NO_WRAP
                );
                mManagement.sendCRResponse(b64);
            }
        }
    };

    private TunConfig mLastTunCfg;
    private String mRemoteGW;
    private Handler guiHandler;
    private Toast mlastToast;
    private Runnable mOpenVPNThread;
    private HandlerThread mCommandHandlerThread;
    private Handler mCommandHandler;

    // Byte/s pretty-print helper
    public static String humanReadableByteCount(long bytes, boolean speed, Resources res) {
        if (speed) bytes = bytes * 8;
        int unit = speed ? 1000 : 1024;
        int exp = Math.max(0, Math.min((int)(Math.log(bytes) / Math.log(unit)), 3));
        float val = (float)(bytes / Math.pow(unit, exp));
        if (speed) switch (exp) {
            case 0: return res.getString(R.string.bits_per_second, val);
            case 1: return res.getString(R.string.kbits_per_second, val);
            case 2: return res.getString(R.string.mbits_per_second, val);
            default: return res.getString(R.string.gbits_per_second, val);
        } else switch (exp) {
            case 0: return res.getString(R.string.volume_byte, val);
            case 1: return res.getString(R.string.volume_kbyte, val);
            case 2: return res.getString(R.string.volume_mbyte, val);
            default: return res.getString(R.string.volume_gbyte, val);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (START_SERVICE.equals(action)) return mBinder;
        return super.onBind(intent);
    }

    @Override public void onRevoke() {
        VpnStatus.logError(R.string.permission_revoked);
        final OpenVPNManagement mgmt = mManagement;
        mCommandHandler.post(() -> mgmt.stopVPN(false));
        endVpnService();
    }

    public void openvpnStopped() { endVpnService(); }

    private boolean isAlwaysActiveEnabled() {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(this);
        return prefs.getBoolean("restartvpnonboot", false);
    }

    boolean isVpnAlwaysOnEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isAlwaysOn();
        return false;
    }

    private void endVpnService() {
        if (!isVpnAlwaysOnEnabled() && !isAlwaysActiveEnabled()) {
            keepVPNAlive.unscheduleKeepVPNAliveJobService(this);
        }
        synchronized (mProcessLock) { mProcessThread = null; }
        VpnStatus.removeByteCountListener(this);
        unregisterDeviceStateReceiver(mDeviceStateReceiver);
        mDeviceStateReceiver = null;
        ProfileManager.setConntectedVpnProfileDisconnected(this);
        mOpenVPNThread = null;
        if (!mStarting) {
            stopForeground(!mNotificationAlwaysVisible);
            if (!mNotificationAlwaysVisible) {
                stopSelf();
                VpnStatus.removeStateListener(this);
            }
        }
    }

    private void showNotification(final String msg, String tickerText, @NonNull String channel,
                                  long when, ConnectionStatus status, Intent intent) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        int icon = getIconByConnectionStatus(status);
        Notification.Builder nb = new Notification.Builder(this);

        int priority = PRIORITY_DEFAULT;
        if (NOTIFICATION_CHANNEL_BG_ID.equals(channel)) priority = PRIORITY_MIN;
        else if (NOTIFICATION_CHANNEL_USERREQ_ID.equals(channel)) priority = PRIORITY_MAX;

        nb.setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSmallIcon(icon)
                .setContentTitle(mProfile != null ? getString(R.string.notifcation_title, mProfile.mName)
                        : getString(R.string.notifcation_title_notconnect))
                .setContentText(msg);

        if (status == LEVEL_WAITING_FOR_USER_INPUT && intent != null) {
            nb.setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            nb.setContentIntent(getGraphPendingIntent());
        }
        if (when != 0) nb.setWhen(when);

        jbNotificationExtras(priority, nb);
        addVpnActionsToNotification(nb);
        lpNotificationExtras(nb, Notification.CATEGORY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb.setChannelId(channel);
        }
        if (!TextUtils.isEmpty(tickerText)) nb.setTicker(tickerText);

        @SuppressWarnings("deprecation")
        Notification n = nb.getNotification();

        int id = channel.hashCode();
        nm.notify(id, n);

        // IMPORTANT: use SPECIAL_USE FGS on API 34+ to match manifest.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(id, n);
        }

        if (lastChannel != null && !channel.equals(lastChannel)) nm.cancel(lastChannel.hashCode());
        lastChannel = channel;

        if (runningOnAndroidTV() && !(priority < 0)) {
            guiHandler.post(() -> {
                if (mlastToast != null) mlastToast.cancel();
                String name = (mProfile != null) ? mProfile.mName : "OpenVPN";
                mlastToast = Toast.makeText(getBaseContext(),
                        String.format(Locale.getDefault(), "%s - %s", name, msg),
                        Toast.LENGTH_SHORT);
                mlastToast.show();
            });
        }
    }

    private void lpNotificationExtras(Notification.Builder nb, String category) {
        nb.setCategory(category);
        nb.setLocalOnly(true);
    }

    private boolean runningOnAndroidTV() {
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private int getIconByConnectionStatus(ConnectionStatus level) {
        switch (level) {
            case LEVEL_AUTH_FAILED:
            case LEVEL_NONETWORK:
            case LEVEL_NOTCONNECTED: return R.drawable.ic_stat_vpn_offline;
            case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
            case LEVEL_WAITING_FOR_USER_INPUT: return R.drawable.ic_stat_vpn_outline;
            case LEVEL_CONNECTING_SERVER_REPLIED: return R.drawable.ic_stat_vpn_empty_halo;
            case LEVEL_VPNPAUSED: return android.R.drawable.ic_media_pause;
            case UNKNOWN_LEVEL:
            case LEVEL_CONNECTED:
            default: return R.drawable.ic_stat_vpn;
        }
    }

    private void jbNotificationExtras(int priority, Notification.Builder nb) {
        try {
            if (priority != 0) {
                Method setpriority = nb.getClass().getMethod("setPriority", int.class);
                setpriority.invoke(nb, priority);
                Method setUsesChronometer = nb.getClass().getMethod("setUsesChronometer", boolean.class);
                setUsesChronometer.invoke(nb, true);
            }
        } catch (NoSuchMethodException | IllegalArgumentException |
                 InvocationTargetException | IllegalAccessException e) {
            VpnStatus.logException(e);
        }
    }

    private void addVpnActionsToNotification(Notification.Builder nb) {
        Intent disconnectVPN = new Intent(this, DisconnectVPN.class).setAction(DISCONNECT_VPN);
        PendingIntent disconnectPI = PendingIntent.getActivity(this, 0, disconnectVPN, PendingIntent.FLAG_IMMUTABLE);
        nb.addAction(R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_connection), disconnectPI);

        Intent pauseVPN = new Intent(this, OpenVPNService.class);
        if (mDeviceStateReceiver == null || !mDeviceStateReceiver.isUserPaused()) {
            pauseVPN.setAction(PAUSE_VPN);
            PendingIntent pi = PendingIntent.getService(this, 0, pauseVPN, PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(R.drawable.ic_menu_pause, getString(R.string.pauseVPN), pi);
        } else {
            pauseVPN.setAction(RESUME_VPN);
            PendingIntent pi = PendingIntent.getService(this, 0, pauseVPN, PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(R.drawable.ic_menu_play, getString(R.string.resumevpn), pi);
        }
    }

    PendingIntent getUserInputIntent(String needed) {
        Intent intent = new Intent(getApplicationContext(), LaunchVPN.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("need", needed);
        return PendingIntent.getActivity(this, 12, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    PendingIntent getGraphPendingIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, getPackageName() + ".activities.MainActivity"));
        intent.putExtra("PAGE", "graph");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    synchronized void registerDeviceStateReceiver(DeviceStateReceiver rcv) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);

        rcv.networkStateChange(this);
        registerReceiver(rcv, filter);
        VpnStatus.addByteCountListener(rcv);
    }

    synchronized void unregisterDeviceStateReceiver(DeviceStateReceiver rcv) {
        if (rcv != null) try {
            VpnStatus.removeByteCountListener(rcv);
            unregisterReceiver(rcv);
        } catch (IllegalArgumentException ignored) {}
    }

    public void userPause(boolean shouldBePaused) {
        if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(shouldBePaused);
    }

     public boolean stopVPN(boolean replaceConnection) {
        if (getManagement() != null) return getManagement().stopVPN(replaceConnection);
        return false;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(ALWAYS_SHOW_NOTIFICATION, false))
            mNotificationAlwaysVisible = true;

        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);

        if (intent != null && PAUSE_VPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(true);
            return START_NOT_STICKY;
        }
        if (intent != null && RESUME_VPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(false);
            return START_NOT_STICKY;
        }
        if (intent != null && START_SERVICE.equals(intent.getAction())) return START_NOT_STICKY;
        if (intent != null && START_SERVICE_STICKY.equals(intent.getAction())) return START_REDELIVER_INTENT;

        VpnStatus.logInfo(R.string.building_configration);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || !foregroundNotificationVisible()) {
            VpnStatus.updateStateString("VPN_GENERATE_CONFIG", "", R.string.building_configration, ConnectionStatus.LEVEL_START);
            showNotification(VpnStatus.getLastCleanLogMessage(this),
                    VpnStatus.getLastCleanLogMessage(this),
                    NOTIFICATION_CHANNEL_NEWSTATUS_ID, 0, ConnectionStatus.LEVEL_START, null);
        }

        mCommandHandler.post(() -> startOpenVPN(intent, startId));
        return START_STICKY;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean foregroundNotificationVisible() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        StatusBarNotification[] n = nm.getActiveNotifications();
        return n.length > 0;
    }

    private VpnProfile fetchVPNProfile(Intent intent) {
        VpnProfile vpnProfile;
        String startReason;
        if (intent != null && intent.hasExtra(EXTRA_PROFILEUUID)) {
            String uuid = intent.getStringExtra(EXTRA_PROFILEUUID);
            int version = intent.getIntExtra(EXTRA_PROFILE_VERSION, 0);
            startReason = intent.getStringExtra(EXTRA_START_REASON);
            if (startReason == null) startReason = "(unknown)";
            vpnProfile = ProfileManager.get(this, uuid, version, 100);
        } else {
            vpnProfile = ProfileManager.getLastConnectedProfile(this);
            startReason = "Using last connected profile (started with null intent, always-on or restart after crash)";
            VpnStatus.logInfo(R.string.service_restarted);
            if (vpnProfile == null) {
                startReason = "could not get last connected profile, using default (started with null intent, always-on or restart after crash)";
                Log.d("OpenVPN", "Got no last connected profile on null intent. Assuming always on.");
                vpnProfile = ProfileManager.getAlwaysOnVPN(this);
                if (vpnProfile == null) return null;
            }
            vpnProfile.checkForRestart(this);
        }
        VpnStatus.logDebug(String.format("Fetched VPN profile (%s) triggered by %s",
                vpnProfile != null ? vpnProfile.getName() : "(null)", startReason));
        return vpnProfile;
    }

    private boolean checkVPNPermission(VpnProfile startprofile) {
        if (prepare(this) == null) return true;

        Notification.Builder nb = new Notification.Builder(this).setAutoCancel(true)
                .setSmallIcon(android.R.drawable.ic_dialog_info);

        Intent launchVPNIntent = new Intent(this, LaunchVPN.class);
        launchVPNIntent.putExtra(LaunchVPN.EXTRA_KEY, startprofile.getUUIDString());
        launchVPNIntent.putExtra(EXTRA_START_REASON, "OpenService lacks permission");
        launchVPNIntent.putExtra(LaunchVPN.EXTRA_HIDELOG, true);
        launchVPNIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        launchVPNIntent.setAction(Intent.ACTION_MAIN);

        showNotification(getString(R.string.permission_requested), "",
                NOTIFICATION_CHANNEL_USERREQ_ID, 0, LEVEL_WAITING_FOR_USER_INPUT, launchVPNIntent);

        VpnStatus.updateStateString("USER_INPUT", "waiting for user input",
                R.string.permission_requested, LEVEL_WAITING_FOR_USER_INPUT, launchVPNIntent);
        return false;
    }

    private void startOpenVPN(Intent intent, int startId) {
        VpnProfile vp = fetchVPNProfile(intent);
        if (vp == null) { stopSelf(startId); return; }
        if (!checkVPNPermission(vp)) return;

        boolean noReplace = (intent != null) && intent.getBooleanExtra(EXTRA_DO_NOT_REPLACE_RUNNING_VPN, false);
        if (mProfile != null && mProfile == vp && (intent == null || noReplace)) {
            VpnStatus.logInfo(R.string.ignore_vpn_start_request, mProfile.getName());
            return;
        }

        mProfile = vp;
        ProfileManager.setConnectedVpnProfile(this, vp);
        VpnStatus.setConnectedVPNProfile(vp.getUUIDString());
        keepVPNAlive.scheduleKeepVPNAliveJobService(this, vp);

        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String tmpDir;
        try { tmpDir = getApplication().getCacheDir().getCanonicalPath(); }
        catch (IOException e) { tmpDir = "/tmp"; }

        String[] argv = VPNLaunchHelper.buildOpenvpnArgv(this);

        mStarting = true;
        stopOldOpenVPNProcess(mManagement, mOpenVPNThread);
        mStarting = false;

        boolean useOpenVPN3 = VpnProfile.doUseOpenVPN3(this);

        if (!useOpenVPN3) {
            OpenVpnManagementThread mgmtThread = new OpenVpnManagementThread(mProfile, this);
            if (mgmtThread.openManagementInterface(this)) {
                Thread sockThread = new Thread(mgmtThread, "OpenVPNManagementThread");
                sockThread.start();
                mManagement = mgmtThread;
                VpnStatus.logInfo("started Socket Thread");
            } else { endVpnService(); return; }
        }

        Runnable processThread;
        if (useOpenVPN3) {
            OpenVPNManagement mOpenVPN3 = instantiateOpenVPN3Core();
            processThread = (Runnable) mOpenVPN3;
            mManagement = mOpenVPN3;
        } else {
            processThread = new OpenVPNThread(this, argv, nativeLibDir, tmpDir);
        }

        synchronized (mProcessLock) {
            mProcessThread = new Thread(processThread, "OpenVPNProcessThread");
            mProcessThread.start();
        }

        if (!useOpenVPN3) {
            try {
                mProfile.writeConfigFileOutput(this, ((OpenVPNThread) processThread).getOpenVPNStdin());
            } catch (IOException | ExecutionException | InterruptedException e) {
                VpnStatus.logException("Error generating config file", e);
                endVpnService();
                return;
            }
        }

        final DeviceStateReceiver old = mDeviceStateReceiver;
        final DeviceStateReceiver neu = new DeviceStateReceiver(mManagement);
        guiHandler.post(() -> {
            if (old != null) unregisterDeviceStateReceiver(old);
            registerDeviceStateReceiver(neu);
            mDeviceStateReceiver = neu;
        });
    }

    private void stopOldOpenVPNProcess(OpenVPNManagement management, Runnable mgmtThread) {
        if (management != null) {
            if (mgmtThread instanceof OpenVPNThread) ((OpenVPNThread) mgmtThread).setReplaceConnection();
            if (management.stopVPN(true)) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        forceStopOpenVpnProcess();
    }

    public void forceStopOpenVpnProcess() {
        synchronized (mProcessLock) {
            if (mProcessThread != null) {
                mProcessThread.interrupt();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private OpenVPNManagement instantiateOpenVPN3Core() {
        try {
            Class<?> cl = Class.forName("de.blinkt.openvpn.core.OpenVPNThreadv3");
            return (OpenVPNManagement) cl.getConstructor(OpenVPNService.class, VpnProfile.class).newInstance(this, mProfile);
        } catch (IllegalArgumentException | InstantiationException | InvocationTargetException |
                 NoSuchMethodException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    public IBinder asBinder() { return mBinder; }

    @Override public void onCreate() {
        super.onCreate();
        guiHandler = new Handler(getMainLooper());
        mCommandHandlerThread = new HandlerThread("OpenVPNServiceCommandThread");
        mCommandHandlerThread.start();
        mCommandHandler = new Handler(mCommandHandlerThread.getLooper());
        ensureNotificationChannels();
    }

    private void ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel newStat = new NotificationChannel(
                NOTIFICATION_CHANNEL_NEWSTATUS_ID, "VPN status", NotificationManager.IMPORTANCE_LOW);
        newStat.setDescription("AIO VPN connection status");
        newStat.setShowBadge(false);

        NotificationChannel bg = new NotificationChannel(
                NOTIFICATION_CHANNEL_BG_ID, "VPN background", NotificationManager.IMPORTANCE_MIN);
        bg.setDescription("Background VPN notification");
        bg.setShowBadge(false);

        NotificationChannel userReq = new NotificationChannel(
                NOTIFICATION_CHANNEL_USERREQ_ID, "VPN action required", NotificationManager.IMPORTANCE_DEFAULT);
        userReq.setDescription("Tap to complete VPN action");
        userReq.setShowBadge(false);

        nm.createNotificationChannel(newStat);
        nm.createNotificationChannel(bg);
        nm.createNotificationChannel(userReq);
    }

    @Override public void onDestroy() {
        synchronized (mProcessLock) {
            if (mProcessThread != null && mManagement != null) mManagement.stopVPN(true);
        }
        if (mDeviceStateReceiver != null) {
            unregisterDeviceStateReceiver(mDeviceStateReceiver);
            mDeviceStateReceiver = null;
        }
        VpnStatus.removeStateListener(this);
        VpnStatus.flushLog();
    }

    private static String getTunConfigString(TunConfig tc) {
        if (tc == null) return "NULL";
        String cfg = "TUNCFG UNQIUE STRING ips:";
        if (tc.mLocalIP != null) cfg += tc.mLocalIP.toString();
        if (tc.mLocalIPv6 != null) cfg += tc.mLocalIPv6;
        cfg += "routes: " + TextUtils.join("|", tc.mRoutes.getNetworks(true)) + TextUtils.join("|", tc.mRoutesv6.getNetworks(true));
        cfg += "excl. routes:" + TextUtils.join("|", tc.mRoutes.getNetworks(false)) + TextUtils.join("|", tc.mRoutesv6.getNetworks(false));
        cfg += "dns: " + TextUtils.join("|", tc.mDnslist);
        cfg += "domain: " + TextUtils.join("|", tc.mSearchDomainList);
        cfg += "mtu: " + tc.mMtu;
        cfg += "proxyInfo: " + tc.mProxyInfo;
        return cfg;
    }

    public ParcelFileDescriptor openTun() {
        ParcelFileDescriptor pfd = openTun(tunConfig);
        mLastTunCfg = tunConfig;
        tunConfig = new TunConfig();
        return pfd;
    }

    private ParcelFileDescriptor openTun(TunConfig tc) {
        Builder builder = new Builder();

        VpnStatus.logInfo(R.string.last_openvpn_tun_config);

        if (mProfile == null) {
            VpnStatus.logError("OpenVPN tries to open a VPN descriptor with mProfile==null, please report this bug with log!");
            return null;
        }

        boolean allowUnsetAF = !mProfile.mBlockUnusedAddressFamilies;
        if (allowUnsetAF) allowAllAFFamilies(builder);

        if (tc.mLocalIP == null && tc.mLocalIPv6 == null) {
            VpnStatus.logError(getString(R.string.opentun_no_ipaddr));
            return null;
        }

        if (tc.mLocalIP != null) {
            if (!VpnProfile.doUseOpenVPN3(this)) addLocalNetworksToRoutes(tc);
            try { builder.addAddress(tc.mLocalIP.mIp, tc.mLocalIP.len); }
            catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dns_add_error, tc.mLocalIP, iae.getLocalizedMessage());
                return null;
            }
        }

        if (tc.mLocalIPv6 != null) {
            String[] ipv6parts = tc.mLocalIPv6.split("/");
            try { builder.addAddress(ipv6parts[0], Integer.parseInt(ipv6parts[1])); }
            catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.ip_add_error, tc.mLocalIPv6, iae.getLocalizedMessage());
                return null;
            }
        }

        for (String dns : tc.mDnslist) {
            try { builder.addDnsServer(dns); }
            catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dns_add_error, dns, iae.getLocalizedMessage());
            }
        }

        builder.setMtu(tc.mMtu);

        Collection<IpAddress> positiveIPv4Routes = tc.mRoutes.getPositiveIPList();
        Collection<IpAddress> positiveIPv6Routes = tc.mRoutesv6.getPositiveIPList();

        if ("samsung".equals(Build.BRAND) && tc.mDnslist.size() >= 1) {
            try {
                IpAddress dnsServer = new IpAddress(new CIDRIP(tc.mDnslist.get(0), 32), true);
                boolean dnsIncluded = false;
                for (IpAddress net : positiveIPv4Routes) if (net.containsNet(dnsServer)) dnsIncluded = true;
                if (!dnsIncluded) {
                    VpnStatus.logWarning("Warning Samsung Android 5.0+ devices ignore DNS servers outside the VPN range. Adding route to DNS " + tc.mDnslist.get(0));
                    positiveIPv4Routes.add(dnsServer);
                }
            } catch (Exception e) {
                if (!tc.mDnslist.get(0).contains(":"))
                    VpnStatus.logError("Error parsing DNS Server IP: " + tc.mDnslist.get(0));
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installRoutesExcluded(builder, tc.mRoutes);
            installRoutesExcluded(builder, tc.mRoutesv6);
        } else {
            installRoutesPostiveOnly(builder, positiveIPv4Routes, positiveIPv6Routes);
        }

        for (String domain : tc.mSearchDomainList) builder.addSearchDomain(domain);

        String ipv4info = allowUnsetAF ? "(not set, allowed)" : "(not set)";
        String ipv6info = allowUnsetAF ? "(not set, allowed)" : "(not set)";
        int ipv4len = -1;

        if (tc.mLocalIP != null) { ipv4len = tc.mLocalIP.len; ipv4info = tc.mLocalIP.mIp; }
        if (tc.mLocalIPv6 != null) { ipv6info = tc.mLocalIPv6; }

        if ((!tc.mRoutes.getNetworks(false).isEmpty() || !tc.mRoutesv6.getNetworks(false).isEmpty()) && isLockdownEnabledCompat()) {
            VpnStatus.logInfo("VPN lockdown enabled (do not allow apps to bypass VPN) enabled. Excluded routes will not allow app bypass.");
        }

        VpnStatus.logInfo(R.string.local_ip_info, ipv4info, ipv4len, ipv6info, tc.mMtu);
        VpnStatus.logInfo(R.string.dns_server_info, TextUtils.join(", ", tc.mDnslist), TextUtils.join(", ", tc.mSearchDomainList));
        VpnStatus.logInfo(R.string.routes_info_incl, TextUtils.join(", ", tc.mRoutes.getNetworks(true)), TextUtils.join(", ", tc.mRoutesv6.getNetworks(true)));
        VpnStatus.logInfo(R.string.routes_info_excl, TextUtils.join(", ", tc.mRoutes.getNetworks(false)), TextUtils.join(", ", tc.mRoutesv6.getNetworks(false)));
        if (tc.mProxyInfo != null) VpnStatus.logInfo(R.string.proxy_info, tc.mProxyInfo.getHost(), tc.mProxyInfo.getPort());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            VpnStatus.logDebug(R.string.routes_debug, TextUtils.join(", ", positiveIPv4Routes), TextUtils.join(", ", positiveIPv6Routes));
        }

        setAllowedVpnPackages(builder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) builder.setUnderlyingNetworks(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);

        String session = mProfile.mName;
        if (tc.mLocalIP != null && tc.mLocalIPv6 != null)
            session = getString(R.string.session_ipv6string, session, tc.mLocalIP, tc.mLocalIPv6);
        else if (tc.mLocalIP != null)
            session = getString(R.string.session_ipv4string, session, tc.mLocalIP);
        else session = getString(R.string.session_ipv4string, session, tc.mLocalIPv6);

        builder.setSession(session);

        if (tc.mDnslist.isEmpty()) VpnStatus.logInfo(R.string.warn_no_dns);

        setHttpProxy(builder, tc);
        builder.setConfigureIntent(getGraphPendingIntent());

        try {
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) throw new NullPointerException("Android establish() returned null");
            return tun;
        } catch (Exception e) {
            VpnStatus.logError(R.string.tun_open_error);
            VpnStatus.logError(getString(R.string.error) + e.getLocalizedMessage());
            return null;
        }
    }

    private void installRoutesExcluded(Builder b, NetworkSpace routes) {
        for (IpAddress ipIncl : routes.getNetworks(true)) {
            try { b.addRoute(ipIncl.getPrefix()); }
            catch (UnknownHostException | IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + ipIncl + " " + ia.getLocalizedMessage());
            }
        }
        for (IpAddress ipExcl : routes.getNetworks(false)) {
            try { b.excludeRoute(ipExcl.getPrefix()); }
            catch (UnknownHostException | IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + ipExcl + " " + ia.getLocalizedMessage());
            }
        }
    }

    private void installRoutesPostiveOnly(Builder b, Collection<IpAddress> v4, Collection<IpAddress> v6) {
        IpAddress multicastRange = new IpAddress(new CIDRIP("224.0.0.0", 3), true);
        for (IpAddress r : v4) {
            try {
                if (multicastRange.containsNet(r)) VpnStatus.logDebug(R.string.ignore_multicast_route, r.toString());
                else b.addRoute(r.getIPv4Address(), r.networkMask);
            } catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + r + " " + ia.getLocalizedMessage());
            }
        }
        for (IpAddress r6 : v6) {
            try { b.addRoute(r6.getIPv6Address(), r6.networkMask); }
            catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + r6 + " " + ia.getLocalizedMessage());
            }
        }
    }

    private void setHttpProxy(Builder b, TunConfig tc) {
        if (tc.mProxyInfo != null && Build.VERSION.SDK_INT >= 29) b.setHttpProxy(tc.mProxyInfo);
        else if (tc.mProxyInfo != null) VpnStatus.logWarning("HTTP Proxy needs Android 10 or later.");
    }

    private boolean isLockdownEnabledCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isLockdownEnabled();
        return false;
    }

    private void allowAllAFFamilies(Builder b) {
        b.allowFamily(OsConstants.AF_INET);
        b.allowFamily(OsConstants.AF_INET6);
    }

    private void addLocalNetworksToRoutes(TunConfig tc) {
        for (String net : NetworkUtils.getLocalNetworks(this, false)) {
            String[] p = net.split("/");
            String ipAddr = p[0];
            int netMask = Integer.parseInt(p[1]);
            if (ipAddr.equals(tc.mLocalIP.mIp)) continue;
            if (mProfile.mAllowLocalLAN) tc.mRoutes.addIP(new CIDRIP(ipAddr, netMask), false);
        }
        if (mProfile.mAllowLocalLAN) {
            for (String net : NetworkUtils.getLocalNetworks(this, true)) addRoutev6(net, false);
        }
    }

    private void setAllowedVpnPackages(Builder b) {
        boolean usesOrbot = false;
        for (Connection c : mProfile.mConnections)
            if (c.mProxyType == Connection.ProxyType.ORBOT) usesOrbot = true;

        if (usesOrbot)
            VpnStatus.logDebug("Profile uses Orbot. Excluding Orbot from VPN if needed.");

        boolean atLeastOneAllowed = false;

        if (mProfile.mAllowedAppsVpnAreDisallowed && usesOrbot) {
            try { b.addDisallowedApplication(ORBOT_PACKAGE_NAME); }
            catch (PackageManager.NameNotFoundException ignored) { VpnStatus.logDebug("Orbot not installed?"); }
        }

        for (String pkg : new Vector<>(mProfile.mAllowedAppsVpn)) {
            try {
                if (mProfile.mAllowedAppsVpnAreDisallowed) b.addDisallowedApplication(pkg);
                else {
                    if (!(usesOrbot && ORBOT_PACKAGE_NAME.equals(pkg))) {
                        b.addAllowedApplication(pkg);
                        atLeastOneAllowed = true;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                mProfile.mAllowedAppsVpn.remove(pkg);
                VpnStatus.logInfo(R.string.app_no_longer_exists, pkg);
            }
        }

        if (!mProfile.mAllowedAppsVpnAreDisallowed && !atLeastOneAllowed) {
            VpnStatus.logDebug(R.string.no_allowed_app, getPackageName());
            try { b.addAllowedApplication(getPackageName()); }
            catch (PackageManager.NameNotFoundException e) { VpnStatus.logError("This should not happen: " + e.getLocalizedMessage()); }
        }

        if (mProfile.mAllowedAppsVpnAreDisallowed)
            VpnStatus.logDebug(R.string.disallowed_vpn_apps_info, TextUtils.join(", ", mProfile.mAllowedAppsVpn));
        else
            VpnStatus.logDebug(R.string.allowed_vpn_apps_info, TextUtils.join(", ", mProfile.mAllowedAppsVpn));

        if (mProfile.mAllowAppVpnBypass) { b.allowBypass(); VpnStatus.logDebug("Apps may bypass VPN"); }
    }

    public void addDNS(String dns) { tunConfig.mDnslist.add(dns); }

    public void addDNS(String dns, int port) {
        if (port != 0 && port != 53) VpnStatus.logInfo(R.string.dnsserver_ignore_port, port, dns);
        tunConfig.mDnslist.add(dns);
    }

    public void addSearchDomain(String domain) { tunConfig.mSearchDomainList.add(domain); }

    public void addRoute(CIDRIP route, boolean include) { tunConfig.mRoutes.addIP(route, include); }

    public boolean addHttpProxy(String proxy, int port) {
        try { tunConfig.mProxyInfo = ProxyInfo.buildDirectProxy(proxy, port); }
        catch (Exception e) { VpnStatus.logError("Could not set proxy" + e.getLocalizedMessage()); return false; }
        return true;
    }

    public void addRoute(String dest, String mask, String gateway, String device) {
        CIDRIP route = new CIDRIP(dest, mask);
        boolean include = isAndroidTunDevice(device);

        IpAddress gatewayIP = new IpAddress(new CIDRIP(gateway, 32), false);

        if (tunConfig.mLocalIP == null) {
            VpnStatus.logError("Local IP address unset; opening tun will likely fail.");
            return;
        }
        IpAddress localNet = new IpAddress(tunConfig.mLocalIP, true);
        if (localNet.containsNet(gatewayIP)) include = true;

        if (gateway != null && ("255.255.255.255".equals(gateway) || gateway.equals(mRemoteGW))) include = true;

        if (route.len == 32 && !mask.equals("255.255.255.255")) VpnStatus.logWarning(R.string.route_not_cidr, dest, mask);
        if (route.normalise()) VpnStatus.logWarning(R.string.route_not_netip, dest, route.len, route.mIp);

        tunConfig.mRoutes.addIP(route, include);
    }

    public void addRoutev6(String network, String device) {
        boolean included = isAndroidTunDevice(device);
        addRoutev6(network, included);
    }

    public void addRoutev6(String network, boolean included) {
        String[] v6parts = network.split("/");
        try {
            Inet6Address ip = (Inet6Address) InetAddress.getAllByName(v6parts[0])[0];
            int mask = Integer.parseInt(v6parts[1]);
            tunConfig.mRoutesv6.addIPv6(ip, mask, included);
        } catch (UnknownHostException e) {
            VpnStatus.logException(e);
        }
    }

    private boolean isAndroidTunDevice(String device) {
        return device != null && (device.startsWith("tun") || "(null)".equals(device) || VPNSERVICE_TUN.equals(device));
    }

    public void setMtu(int mtu) { tunConfig.mMtu = mtu; }

    public void setLocalIP(CIDRIP cdrip) { tunConfig.mLocalIP = cdrip; }

    public void setLocalIP(String local, String netmask, int mtu, String mode) {
        tunConfig.mLocalIP = new CIDRIP(local, netmask);
        tunConfig.mMtu = mtu;
        mRemoteGW = null;

        long netMaskAsInt = CIDRIP.getInt(netmask);

        if (tunConfig.mLocalIP.len == 32 && !"255.255.255.255".equals(netmask)) {
            int masklen; long mask;
            if ("net30".equals(mode)) { masklen = 30; mask = 0xfffffffc; }
            else { masklen = 31; mask = 0xfffffffe; }

            if ((netMaskAsInt & mask) == (tunConfig.mLocalIP.getInt() & mask)) tunConfig.mLocalIP.len = masklen;
            else {
                tunConfig.mLocalIP.len = 32;
                if (!"p2p".equals(mode)) VpnStatus.logWarning(R.string.ip_not_cidr, local, netmask, mode);
            }
        }
        if (("p2p".equals(mode) && tunConfig.mLocalIP.len < 32) || ("net30".equals(mode) && tunConfig.mLocalIP.len < 30)) {
            VpnStatus.logWarning(R.string.ip_looks_like_subnet, local, netmask, mode);
        }

        if (tunConfig.mLocalIP.len <= 31) {
            CIDRIP interfaceRoute = new CIDRIP(tunConfig.mLocalIP.mIp, tunConfig.mLocalIP.len);
            interfaceRoute.normalise();
            addRoute(interfaceRoute, true);
        }
        mRemoteGW = netmask;
    }

    public void setLocalIPv6(String ipv6addr) { tunConfig.mLocalIPv6 = ipv6addr; }

    @Override
    public void updateState(String state, String logmessage, int resid, ConnectionStatus level, Intent intent) {
        doSendBroadcast(state, level);
        if (mProcessThread == null && !mNotificationAlwaysVisible) return;

        String channel = NOTIFICATION_CHANNEL_NEWSTATUS_ID;
        if (level == LEVEL_CONNECTED) {
            mDisplayBytecount = true;
            mConnecttime = System.currentTimeMillis();
            if (!runningOnAndroidTV()) channel = NOTIFICATION_CHANNEL_BG_ID;
        } else {
            mDisplayBytecount = false;
        }
        showNotification(VpnStatus.getLastCleanLogMessage(this),
                VpnStatus.getLastCleanLogMessage(this), channel, 0, level, intent);
    }

    @Override public void setConnectedVPN(String uuid) {}

    private void doSendBroadcast(String state, ConnectionStatus level) {
        Intent vpnstatus = new Intent("de.blinkt.openvpn.VPN_STATUS");
        vpnstatus.putExtra("status", level.toString());
        vpnstatus.putExtra("detailstatus", state);
        sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        if (!mDisplayBytecount) return;
        String netstat = String.format(getString(R.string.statusline_bytecount),
                humanReadableByteCount(in, false, getResources()),
                humanReadableByteCount(diffIn / OpenVPNManagement.mBytecountInterval, true, getResources()),
                humanReadableByteCount(out, false, getResources()),
                humanReadableByteCount(diffOut / OpenVPNManagement.mBytecountInterval, true, getResources()));
        showNotification(netstat, null, NOTIFICATION_CHANNEL_BG_ID, mConnecttime, LEVEL_CONNECTED, null);
    }

    @Override public boolean handleMessage(Message msg) {
        Runnable r = msg.getCallback();
        if (r != null) { r.run(); return true; }
        return false;
    }

    public OpenVPNManagement getManagement() { return mManagement; }

    public String getTunReopenStatus() {
        String current = getTunConfigString(tunConfig);
        return current.equals(getTunConfigString(mLastTunCfg)) ? "NOACTION" : "OPEN_BEFORE_CLOSE";
    }

    public void requestInputFromUser(int resid, String needed) {
        VpnStatus.updateStateString("NEED", "need " + needed, resid, LEVEL_WAITING_FOR_USER_INPUT);
        showNotification(getString(resid), getString(resid), NOTIFICATION_CHANNEL_NEWSTATUS_ID, 0, LEVEL_WAITING_FOR_USER_INPUT, null);
    }

    private Intent getWebAuthIntent(String url, boolean external, Notification.Builder nb) {
        int reason = R.string.openurl_requested;
        nb.setContentTitle(getString(reason)).setContentText(url);

        // Open the URL in a browser (external = true/false ignored here)
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public void trigger_sso(String info) {
        String method = info.split(":", 2)[0];

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb = new Notification.Builder(this).setAutoCancel(true).setSmallIcon(android.R.drawable.ic_dialog_info);

        Intent intent;
        int reason;

        switch (method) {
            case "OPEN_URL": {
                reason = R.string.openurl_requested;
                String url = info.split(":", 2)[1];
                intent = getWebAuthIntent(url, false, nb);
                break;
            }
            case "WEB_AUTH": {
                reason = R.string.openurl_requested;
                String[] parts = info.split(":", 3);
                if (parts.length < 3) { VpnStatus.logError("WEB_AUTH method with invalid argument found"); return; }
                String url = parts[2];
                String[] flags = parts[1].split(",");
                boolean external = false;
                for (String flag : flags) { if ("external".equals(flag)) { external = true; break; } }
                intent = getWebAuthIntent(url, external, nb);
                break;
            }
            case "CR_TEXT": {
                String challenge = info.split(":", 2)[1];
                reason = R.string.crtext_requested;
                nb.setContentTitle(getString(reason)).setContentText(challenge);
                intent = new Intent();
                intent.setComponent(new ComponentName(this, getPackageName() + ".activities.CredentialsPopup"));
                intent.putExtra(EXTRA_CHALLENGE_TXT, challenge);
                break;
            }
            default:
                VpnStatus.logError("Unknown SSO method found: " + method);
                return;
        }

        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        VpnStatus.updateStateString("USER_INPUT", "waiting for user input", reason, LEVEL_WAITING_FOR_USER_INPUT, intent);
        nb.setContentIntent(pIntent);

        jbNotificationExtras(PRIORITY_MAX, nb);
        lpNotificationExtras(nb, Notification.CATEGORY_STATUS);

        String channel = NOTIFICATION_CHANNEL_USERREQ_ID;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nb.setChannelId(channel);

        @SuppressWarnings("deprecation")
        Notification notification = nb.getNotification();
        nm.notify(channel.hashCode(), notification);
    }
}