package hev.sockstun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.uac.spoofer.MainActivity;
import com.uac.spoofer.ProxyService;
import com.uac.spoofer.R;
import com.uac.spoofer.XrayRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class TProxyService extends VpnService {
    private static native void TProxyStartService(String configPath, int fd);

    private static native void TProxyStopService();

    @SuppressWarnings("unused")
    private static native long[] TProxyGetStats();

    public static final String ACTION_CONNECT = "hev.sockstun.CONNECT";
    public static final String ACTION_DISCONNECT = "hev.sockstun.DISCONNECT";

    private static final String CHANNEL_ID = "uac_spoofer_vpn";
    private static final int NOTIFICATION_ID = 40444;
    private static final int TUNNEL_MTU = 8500;
    private static final int TASK_STACK_SIZE = 81920;
    private static final String TUNNEL_IPV4 = "198.18.0.1";
    private static final String TUNNEL_IPV6 = "fc00::1";
    private static final String MAPPED_DNS = "198.18.0.2";
    private static final Object TUNNEL_LOCK = new Object();
    private static boolean nativeTunnelStarted = false;

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private ParcelFileDescriptor tunFd;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_DISCONNECT.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopTunnel();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        stopSelf();
        super.onRevoke();
    }

    private void startTunnel() {
        synchronized (TUNNEL_LOCK) {
            if (tunFd != null && nativeTunnelStarted) {
                return;
            }
            if (nativeTunnelStarted) {
                stopTunnel();
            } else if (tunFd != null) {
                try {
                    tunFd.close();
                } catch (IOException ignored) {
                }
                tunFd = null;
            }

            Builder builder = new Builder();
            builder.setSession("UAC Spoofer VPN");
            builder.setBlocking(false);
            builder.setMtu(TUNNEL_MTU);
            builder.addAddress(TUNNEL_IPV4, 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addAddress(TUNNEL_IPV6, 128);
            builder.addRoute("::", 0);
            builder.addDnsServer(MAPPED_DNS);
            allowInstalledApps(builder);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.allowFamily(android.system.OsConstants.AF_INET);
                builder.allowFamily(android.system.OsConstants.AF_INET6);
            }

            tunFd = builder.establish();
            if (tunFd == null) {
                ProxyService.logEvent("VPN ERROR cannot establish tunnel.");
                stopSelf();
                return;
            }

            File configFile = new File(getCacheDir(), "tun2socks.yml");
            try {
                writeConfig(configFile);
                TProxyStartService(configFile.getAbsolutePath(), tunFd.getFd());
                nativeTunnelStarted = true;
                startForegroundCompat();
                ProxyService.logEvent("VPN tunnel started.");
            } catch (Exception e) {
                ProxyService.logEvent("VPN ERROR " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                stopTunnel();
                stopSelf();
            }
        }
    }

    private void allowInstalledApps(Builder builder) {
        String self = getPackageName();
        boolean addedAny = false;
        try {
            List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            for (PackageInfo info : packages) {
                if (info == null || info.packageName == null || self.equals(info.packageName)) {
                    continue;
                }
                try {
                    builder.addAllowedApplication(info.packageName);
                    addedAny = true;
                } catch (PackageManager.NameNotFoundException | SecurityException | IllegalArgumentException ignored) {
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (!addedAny) {
            try {
                builder.addDisallowedApplication(self);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
    }

    private void stopTunnel() {
        synchronized (TUNNEL_LOCK) {
            boolean hadTunnel = nativeTunnelStarted || tunFd != null;
            stopForegroundCompat();
            try {
                TProxyStopService();
            } catch (Throwable ignored) {
            }
            nativeTunnelStarted = false;
            if (tunFd != null) {
                try {
                    tunFd.close();
                } catch (IOException ignored) {
                }
                tunFd = null;
            }
            if (hadTunnel) {
                ProxyService.logEvent("STOP vpn tunnel stopped.");
            }
        }
    }

    private void writeConfig(File file) throws Exception {
        String text = "misc:\n"
                + "  task-stack-size: " + TASK_STACK_SIZE + "\n"
                + "tunnel:\n"
                + "  mtu: " + TUNNEL_MTU + "\n"
                + "socks5:\n"
                + "  port: " + XrayRunner.SOCKS_PORT + "\n"
                + "  address: '127.0.0.1'\n"
                + "  udp: 'tcp'\n"
                + "mapdns:\n"
                + "  address: " + MAPPED_DNS + "\n"
                + "  port: 53\n"
                + "  network: 240.0.0.0\n"
                + "  netmask: 240.0.0.0\n"
                + "  cache-size: 10000\n";
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes("UTF-8"));
        }
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "UAC Spoofer VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent launchIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle("UAC Spoofer VPN is running")
                .setContentText("TUN -> SOCKS -> Xray -> SNI spoof")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}

