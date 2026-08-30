package com.uacspoofer.mobile.engine.tor;

public final class HevSocks5Tunnel {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private HevSocks5Tunnel() {}

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
