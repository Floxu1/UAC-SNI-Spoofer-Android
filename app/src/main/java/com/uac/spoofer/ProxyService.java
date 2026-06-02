package com.uac.spoofer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyService extends Service {
    public static final String ACTION_START = "com.uac.spoofer.START";
    public static final String ACTION_STOP = "com.uac.spoofer.STOP";

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_FALLBACK = "fallbackAddress";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_SNI = "sni";
    public static final String EXTRA_METHOD = "method";
    public static final String EXTRA_SOURCE_URI = "sourceUri";
    public static final String EXTRA_PROTOCOL = "protocol";
    public static final String EXTRA_CONFIG_HOST = "configHost";
    public static final String EXTRA_CONFIG_PORT = "configPort";

    private static final String CHANNEL_ID = "uac_spoofer_proxy";
    private static final String TAG = "UacSpoofer";
    private static final int NOTIFICATION_ID = 40443;
    private static final int BUFFER_SIZE = 65535;
    private static final int MAX_LOG_LINES = 600;
    private static final int FAILOVER_THRESHOLD = 3;

    private static final String LISTEN_HOST = "127.0.0.1";
    private static final int LISTEN_PORT = 40443;
    private static final String IDLE_PROFILE = "No config";
    private static final String IDLE_TARGET = "";
    private static final String IDLE_SNI = "";
    private static final int DEFAULT_PORT = 443;

    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean RUNNING = false;
    private static volatile String ACTIVE_PROFILE = IDLE_PROFILE;
    private static volatile String ACTIVE_TARGET = IDLE_TARGET;
    private static volatile String ACTIVE_SNI = IDLE_SNI;
    private static volatile int ACTIVE_PORT = DEFAULT_PORT;
    private static volatile String TRAFFIC_SUMMARY = "0 B / 0 B";
    private static volatile String LAST_ERROR = "";

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final AtomicLong uploadBytes = new AtomicLong(0);
    private final AtomicLong downloadBytes = new AtomicLong(0);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final Object targetLock = new Object();

    private ExecutorService ioPool;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private XrayRunner xrayRunner;
    private int currentFailureCount = 0;

    private String profileName = ACTIVE_PROFILE;
    private String primaryAddress = ACTIVE_TARGET;
    private String fallbackAddress = "";
    private int connectPort = ACTIVE_PORT;
    private String fakeSni = ACTIVE_SNI;
    private String method = "combined";
    private String sourceUri = "";
    private String protocol = "trojan";
    private String configHost = "127.0.0.1";
    private int configPort = 40443;

    public interface Listener {
        void onLogSnapshot(List<String> lines);

        void onLogLine(String line);

        void onProxyState(boolean running, String targetLabel, String trafficSummary);
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        listener.onLogSnapshot(getLogSnapshot());
        listener.onProxyState(RUNNING, getActiveTargetLabel(), TRAFFIC_SUMMARY);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    public static String getTrafficSummary() {
        return TRAFFIC_SUMMARY;
    }

    public static String getActiveTargetLabel() {
        if (!RUNNING || ACTIVE_TARGET.trim().isEmpty()) {
            return "No active proxy";
        }
        return ACTIVE_PROFILE + "  |  " + ACTIVE_TARGET + ":" + ACTIVE_PORT + " / " + ACTIVE_SNI;
    }

    public static void logEvent(String message) {
        emit(message);
    }

    public static String getLastError() {
        return LAST_ERROR;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        readConfig(intent);
        startForegroundNow();
        startProxy();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopProxy();
        stopForegroundCompat();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void readConfig(Intent intent) {
        if (intent == null) {
            return;
        }
        profileName = stringExtra(intent, EXTRA_NAME, profileName);
        primaryAddress = stringExtra(intent, EXTRA_ADDRESS, primaryAddress);
        fallbackAddress = stringExtra(intent, EXTRA_FALLBACK, fallbackAddress);
        connectPort = intent.getIntExtra(EXTRA_PORT, connectPort);
        fakeSni = stringExtra(intent, EXTRA_SNI, fakeSni);
        method = stringExtra(intent, EXTRA_METHOD, method);
        sourceUri = stringExtra(intent, EXTRA_SOURCE_URI, sourceUri);
        protocol = stringExtra(intent, EXTRA_PROTOCOL, protocol);
        configHost = stringExtra(intent, EXTRA_CONFIG_HOST, configHost);
        configPort = intent.getIntExtra(EXTRA_CONFIG_PORT, configPort);
        if (connectPort <= 0 || connectPort > 65535) {
            connectPort = 443;
        }
        if (configPort <= 0 || configPort > 65535) {
            configPort = 40443;
        }
        if (fakeSni.trim().isEmpty()) {
            fakeSni = primaryAddress;
        }
        if (method.trim().isEmpty()) {
            method = "combined";
        }
    }

    private static String stringExtra(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void startProxy() {
        if (!active.compareAndSet(false, true)) {
            emit("RUN ignored: proxy is already active.");
            return;
        }
        LAST_ERROR = "";

        synchronized (targetLock) {
            ACTIVE_PROFILE = profileName;
            ACTIVE_TARGET = primaryAddress;
            ACTIVE_PORT = connectPort;
            ACTIVE_SNI = fakeSni;
            currentFailureCount = 0;
        }

        uploadBytes.set(0);
        downloadBytes.set(0);
        TRAFFIC_SUMMARY = "0 B / 0 B";

        ioPool = Executors.newCachedThreadPool();
        try {
            serverSocket = openServerSocket();
            emit("READY listening on " + LISTEN_HOST + ":" + LISTEN_PORT);
        } catch (IOException e) {
            startupError("Cannot listen on " + LISTEN_HOST + ":" + LISTEN_PORT + ": " + e.getMessage());
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return;
        }

        if (!startXray()) {
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return;
        }

        RUNNING = true;
        publishState();
        acceptThread = new Thread(this::acceptLoop, "uac-accept");
        acceptThread.start();
        emit("RUN " + LISTEN_HOST + ":" + LISTEN_PORT + " -> " + getTarget() + ":" + connectPort
                + " sni=" + fakeSni + " method=" + method + " profile=\"" + profileName + "\"");
    }

    private ServerSocket openServerSocket() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(LISTEN_HOST), LISTEN_PORT));
        return server;
    }

    private boolean startXray() {
        ProxyConfig profile = new ProxyConfig();
        profile.name = profileName;
        profile.address = primaryAddress;
        profile.fallbackAddress = fallbackAddress;
        profile.port = connectPort;
        profile.sni = fakeSni;
        profile.method = method;
        profile.sourceUri = sourceUri;
        profile.protocol = protocol;
        profile.configHost = configHost;
        profile.configPort = configPort;
        try {
            xrayRunner = new XrayRunner();
            xrayRunner.start(this, profile, ProxyService::emit);
            return true;
        } catch (Exception e) {
            startupError("Xray: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            xrayRunner = null;
            return false;
        }
    }

    private void startupError(String message) {
        LAST_ERROR = message;
        emit("START ERROR " + message);
    }

    private void stopProxy() {
        boolean wasActive = active.getAndSet(false);
        boolean wasRunning = RUNNING;
        closeQuietly(serverSocket);
        serverSocket = null;
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();

        if (ioPool != null) {
            ioPool.shutdownNow();
            try {
                ioPool.awaitTermination(800, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ioPool = null;
        }

        if (acceptThread != null) {
            Thread thread = acceptThread;
            thread.interrupt();
            if (Thread.currentThread() != thread) {
                try {
                    thread.join(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            acceptThread = null;
        }
        if (xrayRunner != null) {
            xrayRunner.stop();
            xrayRunner = null;
        }

        resetActiveState();
        publishState();
        if (wasActive || wasRunning) {
            emit("STOP proxy stopped.");
        }
    }

    private void resetActiveState() {
        synchronized (targetLock) {
            ACTIVE_PROFILE = IDLE_PROFILE;
            ACTIVE_TARGET = IDLE_TARGET;
            ACTIVE_PORT = DEFAULT_PORT;
            ACTIVE_SNI = IDLE_SNI;
            currentFailureCount = 0;
        }
        profileName = IDLE_PROFILE;
        primaryAddress = IDLE_TARGET;
        fallbackAddress = "";
        connectPort = DEFAULT_PORT;
        fakeSni = IDLE_SNI;
        sourceUri = "";
        TRAFFIC_SUMMARY = "0 B / 0 B";
        RUNNING = false;
    }

    private void acceptLoop() {
        try {
            ServerSocket server = serverSocket;
            while (active.get()) {
                if (server == null || server.isClosed()) {
                    break;
                }
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                openSockets.add(client);
                int id = connectionCounter.incrementAndGet();
                ioPool.execute(() -> handleClient(id, client));
            }
        } catch (SocketException e) {
            if (active.get()) {
                emit("ERROR listener socket closed: " + e.getMessage());
            }
        } catch (IOException e) {
            emit("ERROR cannot listen on " + LISTEN_HOST + ":" + LISTEN_PORT + ": " + e.getMessage());
            active.set(false);
            resetActiveState();
            publishState();
            stopSelf();
        } finally {
            closeQuietly(serverSocket);
        }
    }

    private void handleClient(int id, Socket client) {
        String connId = String.format(Locale.US, "C%06d", id);
        Socket remote = null;
        String target = getTarget();
        AtomicBoolean serverResponded = new AtomicBoolean(false);

        try {
            emit(connId + " NEW " + client.getRemoteSocketAddress());
            client.setSoTimeout(30000);
            byte[] firstData = readFirstPacket(client.getInputStream());
            client.setSoTimeout(0);
            if (firstData.length == 0) {
                emit(connId + " CLOSE no initial data.");
                return;
            }

            String clientSni = TlsClientHello.findSni(firstData);
            if (clientSni.isEmpty()) {
                emit(connId + " TLS unknown first packet, " + firstData.length + " B");
            } else {
                emit(connId + " TLS real_sni=" + clientSni + " size=" + firstData.length + " B");
            }

            remote = new Socket();
            openSockets.add(remote);
            remote.connect(new InetSocketAddress(target, connectPort), 15000);
            remote.setTcpNoDelay(true);
            remote.setKeepAlive(true);

            emit(connId + " CONNECT " + target + ":" + connectPort);
            if (!writeFragmented(connId, remote.getOutputStream(), firstData)) {
                remote.getOutputStream().write(firstData);
                remote.getOutputStream().flush();
                emit(connId + " FALLBACK sent raw ClientHello.");
            }

            CountDownLatch firstRelayDone = new CountDownLatch(1);
            Socket finalRemote = remote;
            Future<?> up = ioPool.submit(() -> relay(connId, client, finalRemote, false, target, serverResponded, firstRelayDone));
            Future<?> down = ioPool.submit(() -> relay(connId, finalRemote, client, true, target, serverResponded, firstRelayDone));

            firstRelayDone.await();
            closeQuietly(client);
            closeQuietly(remote);
            up.cancel(true);
            down.cancel(true);

            if (active.get() && !serverResponded.get()) {
                recordFailure(target, "no server response");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (active.get()) {
                emit(connId + " ERROR " + e.getMessage());
                recordFailure(target, e.getMessage());
            }
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
            openSockets.remove(client);
            if (remote != null) {
                openSockets.remove(remote);
            }
            emit(connId + " CLOSED");
        }
    }

    private byte[] readFirstPacket(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read = inputStream.read(buffer);
        if (read <= 0) {
            return new byte[0];
        }
        int expected = tlsRecordLength(buffer, read);
        while (expected > read && read < buffer.length) {
            int next = inputStream.read(buffer, read, Math.min(buffer.length - read, expected - read));
            if (next <= 0) {
                break;
            }
            read += next;
        }
        return Arrays.copyOf(buffer, read);
    }

    private int tlsRecordLength(byte[] data, int read) {
        if (read < 5 || (data[0] & 0xff) != 0x16) {
            return read;
        }
        int length = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
        int total = 5 + length;
        return total > 0 && total <= BUFFER_SIZE ? total : read;
    }

    private boolean writeFragmented(String connId, OutputStream outputStream, byte[] firstData) {
        try {
            byte[][] fragments = TlsClientHello.fragmentAtSni(firstData);
            emit(connId + " FRAGMENT sni_split " + fragments.length + " pieces: "
                    + fragments[0].length + " B + " + fragments[1].length + " B");
            for (int i = 0; i < fragments.length; i++) {
                outputStream.write(fragments[i]);
                outputStream.flush();
                emit(connId + " PKT " + (i + 1) + "/" + fragments.length + " sent " + fragments[i].length + " B");
                if (i < fragments.length - 1) {
                    sleepQuietly(100);
                }
            }
            return true;
        } catch (IOException e) {
            emit(connId + " FRAGMENT failed: " + e.getMessage());
            return false;
        }
    }

    private void relay(
            String connId,
            Socket source,
            Socket destination,
            boolean serverToClient,
            String target,
            AtomicBoolean serverResponded,
            CountDownLatch firstRelayDone
    ) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            while (active.get()) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
                if (serverToClient) {
                    long total = downloadBytes.addAndGet(read);
                    if (serverResponded.compareAndSet(false, true)) {
                        recordSuccess(target);
                        emit(connId + " SVR_RESP " + read + " B, spoof active.");
                    } else if (total % 65536 < read) {
                        emit(connId + " DOWN " + formatBytes(total));
                    }
                } else {
                    long total = uploadBytes.addAndGet(read);
                    if (total % 65536 < read) {
                        emit(connId + " UP " + formatBytes(total));
                    }
                }
                updateTrafficSummary();
            }
        } catch (IOException ignored) {
        } finally {
            firstRelayDone.countDown();
        }
    }

    private String getTarget() {
        synchronized (targetLock) {
            return ACTIVE_TARGET;
        }
    }

    private void recordFailure(String target, String reason) {
        synchronized (targetLock) {
            if (!target.equals(ACTIVE_TARGET)) {
                return;
            }
            currentFailureCount++;
            emit("FAIL " + target + " " + currentFailureCount + "/" + FAILOVER_THRESHOLD + " " + reason);
            if (!fallbackAddress.isEmpty()
                    && !fallbackAddress.equals(ACTIVE_TARGET)
                    && currentFailureCount >= FAILOVER_THRESHOLD) {
                ACTIVE_TARGET = fallbackAddress;
                currentFailureCount = 0;
                emit("FAILOVER switched to " + fallbackAddress + ":" + connectPort);
                publishState();
            }
        }
    }

    private void recordSuccess(String target) {
        synchronized (targetLock) {
            if (target.equals(ACTIVE_TARGET)) {
                currentFailureCount = 0;
            }
        }
    }

    private void updateTrafficSummary() {
        TRAFFIC_SUMMARY = formatBytes(uploadBytes.get()) + " / " + formatBytes(downloadBytes.get());
        publishState();
    }

    @SuppressWarnings("deprecation")
    private void startForegroundNow() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "UAC Spoofer",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Local proxy status");
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
                .setContentTitle("UAC Spoofer is running")
                .setContentText(LISTEN_HOST + ":" + LISTEN_PORT + " -> " + primaryAddress + ":" + connectPort)
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

    private static void emit(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + message;
        Log.i(TAG, line);
        synchronized (LOG_LOCK) {
            LOGS.addLast(line);
            while (LOGS.size() > MAX_LOG_LINES) {
                LOGS.removeFirst();
            }
        }
        for (Listener listener : LISTENERS) {
            listener.onLogLine(line);
        }
    }

    private static List<String> getLogSnapshot() {
        synchronized (LOG_LOCK) {
            return new ArrayList<>(LOGS);
        }
    }

    private static void publishState() {
        for (Listener listener : LISTENERS) {
            listener.onProxyState(RUNNING, getActiveTargetLabel(), TRAFFIC_SUMMARY);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static final class TlsClientHello {
        private static String findSni(byte[] data) {
            SniLocation location = locateSni(data);
            if (location == null) {
                return "";
            }
            return new String(data, location.offset, location.length, StandardCharsets.US_ASCII);
        }

        private static byte[][] fragmentAtSni(byte[] data) {
            if (data.length < 2) {
                return new byte[][]{data, new byte[0]};
            }
            SniLocation location = locateSni(data);
            int split = data.length / 2;
            if (location != null) {
                split = location.offset + Math.max(1, location.length / 2);
            }
            split = Math.max(1, Math.min(split, data.length - 1));
            return new byte[][]{
                    Arrays.copyOfRange(data, 0, split),
                    Arrays.copyOfRange(data, split, data.length)
            };
        }

        private static SniLocation locateSni(byte[] data) {
            if (data.length < 5 || unsigned(data[0]) != 0x16) {
                return null;
            }

            int pos = 5;
            if (pos + 4 > data.length || unsigned(data[pos]) != 0x01) {
                return null;
            }
            pos += 4;

            if (pos + 34 > data.length) {
                return null;
            }
            pos += 2;
            pos += 32;

            if (pos >= data.length) {
                return null;
            }
            int sessionIdLength = unsigned(data[pos]);
            pos += 1 + sessionIdLength;

            if (pos + 2 > data.length) {
                return null;
            }
            int cipherSuiteLength = u16(data, pos);
            pos += 2 + cipherSuiteLength;

            if (pos >= data.length) {
                return null;
            }
            int compressionLength = unsigned(data[pos]);
            pos += 1 + compressionLength;

            if (pos + 2 > data.length) {
                return null;
            }
            int extensionLength = u16(data, pos);
            pos += 2;
            int extensionEnd = Math.min(pos + extensionLength, data.length);

            while (pos + 4 <= extensionEnd) {
                int extensionType = u16(data, pos);
                int extensionDataLength = u16(data, pos + 2);
                pos += 4;
                if (pos + extensionDataLength > extensionEnd) {
                    return null;
                }

                if (extensionType == 0x0000) {
                    SniLocation location = parseServerNameExtension(data, pos, extensionDataLength);
                    if (location != null) {
                        return location;
                    }
                }
                pos += extensionDataLength;
            }
            return null;
        }

        private static SniLocation parseServerNameExtension(byte[] data, int offset, int length) {
            if (length < 5 || offset + length > data.length) {
                return null;
            }
            int listLength = u16(data, offset);
            int pos = offset + 2;
            int end = Math.min(pos + listLength, offset + length);
            while (pos + 3 <= end) {
                int nameType = unsigned(data[pos]);
                int nameLength = u16(data, pos + 1);
                pos += 3;
                if (nameLength <= 0 || pos + nameLength > end) {
                    return null;
                }
                if (nameType == 0) {
                    return new SniLocation(pos, nameLength);
                }
                pos += nameLength;
            }
            return null;
        }

        private static int u16(byte[] data, int offset) {
            return (unsigned(data[offset]) << 8) | unsigned(data[offset + 1]);
        }

        private static int unsigned(byte value) {
            return value & 0xff;
        }
    }

    private static final class SniLocation {
        final int offset;
        final int length;

        SniLocation(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
}

