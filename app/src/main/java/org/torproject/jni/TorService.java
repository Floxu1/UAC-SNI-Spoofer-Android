package org.torproject.jni;

/**
 * JNI host for Guardian Project {@code libtor.so}. Native symbols are bound to
 * this exact class name; keep the two pointer fields so {@code GetFieldID} succeeds.
 */
public final class TorService {
    static {
        System.loadLibrary("tor");
    }

    @SuppressWarnings("unused")
    private long torConfiguration = -1;
    @SuppressWarnings("unused")
    private int torControlFd = -1;

    public native boolean createTorConfiguration();

    public native void mainConfigurationFree();

    public native boolean mainConfigurationSetCommandLine(String[] args);

    public native boolean mainConfigurationSetupControlSocket();

    public native int runMain();
}
