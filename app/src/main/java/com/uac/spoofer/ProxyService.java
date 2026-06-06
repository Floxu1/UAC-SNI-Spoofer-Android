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
import java.security.SecureRandom;
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
    public static final String EXTRA_TUNING_MODE = "tuningMode";
    public static final String EXTRA_FAKE_PROBE_ENABLED = "fakeProbeEnabled";
    public static final String EXTRA_FAKE_PROBE_COUNT = "fakeProbeCount";
    public static final String EXTRA_FAKE_PROBE_DELAY_MS = "fakeProbeDelayMs";
    public static final String EXTRA_MULTI_FRAGMENT_SIZE = "multiFragmentSize";
    public static final String EXTRA_SNI_SPLIT_DELAY_MS = "sniSplitDelayMs";
    public static final String EXTRA_TLS_RECORD_DELAY_MS = "tlsRecordDelayMs";
    public static final String EXTRA_MULTI_DELAY_MS = "multiDelayMs";
    public static final String EXTRA_HALF_DELAY_MS = "halfDelayMs";
    public static final String EXTRA_ROUTE_PROBE_TIMEOUT_MS = "routeProbeTimeoutMs";
    public static final String EXTRA_STRATEGY_CACHE_ENABLED = "strategyCacheEnabled";
    public static final String EXTRA_STRATEGY_CACHE_TTL_MS = "strategyCacheTtlMs";
    public static final String EXTRA_LOG_LEVEL = "logLevel";

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
    private static final ConcurrentHashMap<String, String> PREFERRED_STRATEGY_BY_ROUTE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> PREFERRED_STRATEGY_TIME_BY_ROUTE = new ConcurrentHashMap<>();

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
    private ProxyTuning tuning = ProxyTuning.balanced();

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

    public static void clearStrategyCache() {
        PREFERRED_STRATEGY_BY_ROUTE.clear();
        PREFERRED_STRATEGY_TIME_BY_ROUTE.clear();
        emit("TUNING strategy cache cleared.");
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
        tuning = ProxyTuning.fromIntent(intent);
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
                + " sni=" + fakeSni + " method=" + method + " tuning=" + tuning.summary()
                + " profile=\"" + profileName + "\"");
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

            ConnectedAttempt connected = connectWithAdaptiveStrategies(connId, firstData, target);
            if (connected == null && !fallbackAddress.isEmpty() && !fallbackAddress.equals(target)) {
                emit(connId + " FAILOVER probe " + fallbackAddress + ":" + connectPort);
                connected = connectWithAdaptiveStrategies(connId, firstData, fallbackAddress);
            }
            if (connected == null) {
                throw new IOException("no server response after all strategies");
            }

            remote = connected.socket;
            target = connected.target;
            serverResponded.set(true);
            recordSuccess(target);
            client.getOutputStream().write(connected.firstResponse);
            client.getOutputStream().flush();
            downloadBytes.addAndGet(connected.firstResponse.length);
            updateTrafficSummary();
            emit(connId + " SVR_RESP " + connected.firstResponse.length + " B via "
                    + connected.strategyName + ", spoof active.");

            CountDownLatch firstRelayDone = new CountDownLatch(1);
            Socket finalRemote = remote;
            String finalTarget = target;
            Future<?> up = ioPool.submit(() -> relay(connId, client, finalRemote, false, finalTarget, serverResponded, firstRelayDone));
            Future<?> down = ioPool.submit(() -> relay(connId, finalRemote, client, true, finalTarget, serverResponded, firstRelayDone));

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

    private ConnectedAttempt connectWithAdaptiveStrategies(String connId, byte[] firstData, String target) {
        List<StrategyAttempt> attempts = buildStrategyAttempts(firstData, target);
        for (StrategyAttempt attempt : attempts) {
            Socket candidate = null;
            try {
                candidate = openRemote(target);
                emit(connId + " CONNECT " + target + ":" + connectPort + " strategy=" + attempt.name);
                if (attempt.fakeProbe) {
                    for (int i = 0; i < tuning.fakeProbeCount; i++) {
                        sendFakeSniProbe(connId, target, i + 1, tuning.fakeProbeCount);
                        if (tuning.fakeProbeDelayMs > 0) {
                            sleepQuietly(tuning.fakeProbeDelayMs);
                        }
                    }
                }
                writeAttempt(connId, candidate.getOutputStream(), firstData, attempt);
                candidate.setSoTimeout(tuning.routeProbeTimeoutMs);
                byte[] response = readFirstResponse(candidate.getInputStream());
                if (response.length > 0) {
                    candidate.setSoTimeout(0);
                    rememberPreferredStrategy(target, attempt.name);
                    return new ConnectedAttempt(candidate, target, response, attempt.name);
                }
                emit(connId + " BLOCKED no response strategy=" + attempt.name);
            } catch (IOException e) {
                emit(connId + " TRY_FAIL " + attempt.name + " " + e.getMessage());
            }
            if (candidate != null) {
                closeQuietly(candidate);
                openSockets.remove(candidate);
            }
        }
        recordFailure(target, "all strategies failed");
        return null;
    }

    private Socket openRemote(String target) throws IOException {
        Socket socket = new Socket();
        openSockets.add(socket);
        socket.connect(new InetSocketAddress(target, connectPort), 15000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        return socket;
    }

    private List<StrategyAttempt> buildStrategyAttempts(byte[] firstData, String target) {
        List<StrategyAttempt> attempts = new ArrayList<>();
        boolean tls = firstData.length > 5 && (firstData[0] & 0xff) == 0x16;
        String normalized = method == null ? "" : method.toLowerCase(Locale.US);
        boolean combined = normalized.contains("combined") || normalized.contains("fake");
        boolean fakeProbe = tuning.fakeProbeEnabled && tuning.fakeProbeCount > 0 && combined;
        if (tls) {
            if (ProxyTuning.MODE_FAST.equals(tuning.mode)) {
                attempts.add(new StrategyAttempt("raw", "raw", 0, false));
                attempts.add(new StrategyAttempt("fragment/half", "half", tuning.halfDelayMs, false));
                attempts.add(new StrategyAttempt("combined/tls_record_frag", "tls_record_frag", tuning.tlsRecordDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/sni_split", "sni_split", tuning.sniSplitDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/multi" + tuning.multiFragmentSize, "multi", tuning.multiDelayMs, fakeProbe));
            } else {
                attempts.add(new StrategyAttempt("combined/sni_split", "sni_split", tuning.sniSplitDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/tls_record_frag", "tls_record_frag", tuning.tlsRecordDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/multi" + tuning.multiFragmentSize, "multi", tuning.multiDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("fragment/half", "half", tuning.halfDelayMs, false));
                attempts.add(new StrategyAttempt("raw", "raw", 0, false));
            }
        } else {
            attempts.add(new StrategyAttempt("raw", "raw", 0, false));
        }
        String preferred = preferredStrategy(target);
        if (preferred != null) {
            for (int i = 0; i < attempts.size(); i++) {
                StrategyAttempt attempt = attempts.get(i);
                if (preferred.equals(attempt.name)) {
                    attempts.remove(i);
                    attempts.add(0, attempt);
                    break;
                }
            }
        }
        return attempts;
    }

    private String preferredStrategy(String target) {
        if (!tuning.strategyCacheEnabled) {
            return null;
        }
        String key = strategyCacheKey(target);
        String preferred = PREFERRED_STRATEGY_BY_ROUTE.get(key);
        Long savedAt = PREFERRED_STRATEGY_TIME_BY_ROUTE.get(key);
        if (preferred == null || savedAt == null) {
            return null;
        }
        if (System.currentTimeMillis() - savedAt > tuning.strategyCacheTtlMs) {
            PREFERRED_STRATEGY_BY_ROUTE.remove(key);
            PREFERRED_STRATEGY_TIME_BY_ROUTE.remove(key);
            return null;
        }
        return preferred;
    }

    private void rememberPreferredStrategy(String target, String strategyName) {
        if (!tuning.strategyCacheEnabled) {
            return;
        }
        String key = strategyCacheKey(target);
        PREFERRED_STRATEGY_BY_ROUTE.put(key, strategyName);
        PREFERRED_STRATEGY_TIME_BY_ROUTE.put(key, System.currentTimeMillis());
    }

    private String strategyCacheKey(String target) {
        return target + ":" + connectPort + "|" + fakeSni + "|" + method;
    }

    private void sendFakeSniProbe(String connId, String target, int index, int total) {
        Socket probe = null;
        try {
            probe = new Socket();
            probe.connect(new InetSocketAddress(target, connectPort), 300);
            probe.setTcpNoDelay(true);
            byte[] fakeHello = TlsClientHello.buildFakeClientHello(fakeSni);
            probe.getOutputStream().write(fakeHello);
            probe.getOutputStream().flush();
            emit(connId + " FAKE_SNI probe " + index + "/" + total + " " + fakeSni + " " + fakeHello.length + " B");
        } catch (IOException e) {
            emit(connId + " FAKE_SNI probe " + index + "/" + total + " failed: " + e.getMessage());
        } finally {
            closeQuietly(probe);
        }
    }

    private void writeAttempt(String connId, OutputStream outputStream, byte[] firstData, StrategyAttempt attempt) throws IOException {
        byte[][] fragments = TlsClientHello.fragment(firstData, attempt.fragmentStrategy, tuning.multiFragmentSize);
        if (ProxyTuning.LOG_MINIMAL.equals(tuning.logLevel)) {
            emit(connId + " FRAGMENT " + attempt.name + " " + fragments.length + " pieces");
        } else {
            emit(connId + " FRAGMENT " + attempt.name + " " + fragments.length
                    + " pieces: " + formatPieces(fragments));
        }
        for (int i = 0; i < fragments.length; i++) {
            outputStream.write(fragments[i]);
            outputStream.flush();
            if (shouldLogFragment(i, fragments.length)) {
                emit(connId + " PKT " + (i + 1) + "/" + fragments.length + " sent " + fragments[i].length + " B");
            }
            if (i < fragments.length - 1 && attempt.delayMs > 0) {
                sleepQuietly(attempt.delayMs);
            }
        }
    }

    private boolean shouldLogFragment(int index, int total) {
        if (ProxyTuning.LOG_MINIMAL.equals(tuning.logLevel)) {
            return false;
        }
        if (ProxyTuning.LOG_VERBOSE.equals(tuning.logLevel)) {
            return true;
        }
        return total <= 8 || index < 3 || index == total - 1 || ((index + 1) % 8 == 0);
    }

    private byte[] readFirstResponse(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read = inputStream.read(buffer);
        if (read <= 0) {
            return new byte[0];
        }
        return Arrays.copyOf(buffer, read);
    }

    private String formatPieces(byte[][] fragments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fragments.length; i++) {
            if (i > 0) {
                out.append(" + ");
            }
            out.append(fragments[i].length).append(" B");
        }
        return out.toString();
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
        private static final SecureRandom RANDOM = new SecureRandom();

        private static String findSni(byte[] data) {
            SniLocation location = locateSni(data);
            if (location == null) {
                return "";
            }
            return new String(data, location.offset, location.length, StandardCharsets.US_ASCII);
        }

        private static byte[][] fragment(byte[] data, String strategy, int multiFragmentSize) {
            if (data.length < 2 || "raw".equals(strategy)) {
                return new byte[][]{data};
            }
            if ("half".equals(strategy)) {
                return fragmentHalf(data);
            }
            if ("multi".equals(strategy)) {
                return fragmentMulti(data, multiFragmentSize);
            }
            if ("tls_record_frag".equals(strategy)) {
                return tlsRecordFragment(data);
            }
            return fragmentAtSni(data);
        }

        private static byte[][] fragmentAtSni(byte[] data) {
            if (data.length < 2) {
                return new byte[][]{data};
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

        private static byte[][] fragmentHalf(byte[] data) {
            int split = Math.max(1, Math.min(data.length / 2, data.length - 1));
            return new byte[][]{
                    Arrays.copyOfRange(data, 0, split),
                    Arrays.copyOfRange(data, split, data.length)
            };
        }

        private static byte[][] fragmentMulti(byte[] data, int chunkSize) {
            List<byte[]> parts = new ArrayList<>();
            for (int offset = 0; offset < data.length; offset += chunkSize) {
                int end = Math.min(offset + chunkSize, data.length);
                parts.add(Arrays.copyOfRange(data, offset, end));
            }
            return parts.toArray(new byte[parts.size()][]);
        }

        private static byte[][] tlsRecordFragment(byte[] data) {
            if (data.length < 6 || unsigned(data[0]) != 0x16) {
                return new byte[][]{data};
            }
            byte[] version = Arrays.copyOfRange(data, 1, 3);
            byte[] handshake = Arrays.copyOfRange(data, 5, data.length);
            int split = Math.max(1, Math.min(handshake.length / 2, handshake.length - 1));
            return new byte[][]{
                    tlsRecord(version, Arrays.copyOfRange(handshake, 0, split)),
                    tlsRecord(version, Arrays.copyOfRange(handshake, split, handshake.length))
            };
        }

        private static byte[] buildFakeClientHello(String sni) {
            String host = sni == null || sni.trim().isEmpty() ? ProxyConfig.DEFAULT_SPOOF_SNI : sni.trim();
            byte[] random = new byte[32];
            byte[] sessionId = new byte[32];
            byte[] keyShare = new byte[32];
            RANDOM.nextBytes(random);
            RANDOM.nextBytes(sessionId);
            RANDOM.nextBytes(keyShare);

            byte[] extensions = concat(
                    sniExtension(host),
                    hex("000a00080006001d00170018"),
                    hex("000b00020100"),
                    hex("000d00120010040305030603080708080809080a080b"),
                    hex("002b00050403040303"),
                    keyShareExtension(keyShare),
                    hex("0010000e000c02683208687474702f312e31")
            );
            byte[] padding = paddingExtension(517, 5 + 4 + 2 + 32 + 1 + sessionId.length
                    + cipherSuites().length + 2 + extensions.length);
            if (padding.length > 0) {
                extensions = concat(extensions, padding);
            }

            byte[] body = concat(
                    new byte[]{0x03, 0x03},
                    random,
                    concat(new byte[]{(byte) sessionId.length}, sessionId),
                    cipherSuites(),
                    new byte[]{0x01, 0x00},
                    u16Bytes(extensions.length),
                    extensions
            );
            byte[] handshake = concat(new byte[]{
                    0x01,
                    (byte) ((body.length >> 16) & 0xff),
                    (byte) ((body.length >> 8) & 0xff),
                    (byte) (body.length & 0xff)
            }, body);
            return tlsRecord(new byte[]{0x03, 0x01}, handshake);
        }

        private static byte[] tlsRecord(byte[] version, byte[] payload) {
            return concat(new byte[]{
                    0x16,
                    version[0],
                    version[1],
                    (byte) ((payload.length >> 8) & 0xff),
                    (byte) (payload.length & 0xff)
            }, payload);
        }

        private static byte[] sniExtension(String sni) {
            byte[] sniBytes = sni.getBytes(StandardCharsets.US_ASCII);
            byte[] entry = concat(new byte[]{
                    0x00,
                    (byte) ((sniBytes.length >> 8) & 0xff),
                    (byte) (sniBytes.length & 0xff)
            }, sniBytes);
            byte[] list = concat(u16Bytes(entry.length), entry);
            return concat(new byte[]{0x00, 0x00}, u16Bytes(list.length), list);
        }

        private static byte[] keyShareExtension(byte[] publicKey) {
            byte[] entry = concat(new byte[]{0x00, 0x1d, 0x00, 0x20}, publicKey);
            byte[] list = concat(u16Bytes(entry.length), entry);
            return concat(new byte[]{0x00, 0x33}, u16Bytes(list.length), list);
        }

        private static byte[] paddingExtension(int targetLength, int currentLength) {
            int needed = targetLength - currentLength - 4;
            if (needed <= 0) {
                return new byte[0];
            }
            return concat(new byte[]{0x00, 0x15}, u16Bytes(needed), new byte[needed]);
        }

        private static byte[] cipherSuites() {
            byte[] suites = hex("130213031301c02cc030c02bc02fcca9cca8c024c028c023c027009f009e006b006700ff");
            return concat(u16Bytes(suites.length), suites);
        }

        private static byte[] u16Bytes(int value) {
            return new byte[]{(byte) ((value >> 8) & 0xff), (byte) (value & 0xff)};
        }

        private static byte[] concat(byte[]... arrays) {
            int length = 0;
            for (byte[] array : arrays) {
                length += array.length;
            }
            byte[] out = new byte[length];
            int offset = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, out, offset, array.length);
                offset += array.length;
            }
            return out;
        }

        private static byte[] hex(String value) {
            int length = value.length();
            byte[] out = new byte[length / 2];
            for (int i = 0; i < length; i += 2) {
                out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
            }
            return out;
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

    private static final class StrategyAttempt {
        final String name;
        final String fragmentStrategy;
        final int delayMs;
        final boolean fakeProbe;

        StrategyAttempt(String name, String fragmentStrategy, int delayMs, boolean fakeProbe) {
            this.name = name;
            this.fragmentStrategy = fragmentStrategy;
            this.delayMs = delayMs;
            this.fakeProbe = fakeProbe;
        }
    }

    private static final class ConnectedAttempt {
        final Socket socket;
        final String target;
        final byte[] firstResponse;
        final String strategyName;

        ConnectedAttempt(Socket socket, String target, byte[] firstResponse, String strategyName) {
            this.socket = socket;
            this.target = target;
            this.firstResponse = firstResponse;
            this.strategyName = strategyName;
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

