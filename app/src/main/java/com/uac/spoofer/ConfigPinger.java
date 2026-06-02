package com.uac.spoofer;

import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class ConfigPinger {
    public interface Callback {
        void onResult(ProxyConfig config, boolean ok, double latencyMs, int done, int total);

        void onFinished(boolean cancelled);
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService executor;

    public void start(List<ProxyConfig> configs, int workers, int timeoutSeconds, Callback callback) {
        cancelled.set(false);
        executor = Executors.newFixedThreadPool(Math.max(1, Math.min(32, workers)));
        List<Future<?>> futures = new ArrayList<>();
        Counter counter = new Counter();
        int total = configs.size();
        for (ProxyConfig config : configs) {
            futures.add(executor.submit(() -> {
                PingResult result = ping(config, timeoutSeconds);
                int done = counter.next();
                callback.onResult(config, result.ok, result.latencyMs, done, total);
            }));
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            for (Future<?> future : futures) {
                if (cancelled.get()) {
                    break;
                }
                try {
                    future.get();
                } catch (Exception ignored) {
                }
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            callback.onFinished(cancelled.get());
        });
    }

    public void cancel() {
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private PingResult ping(ProxyConfig config, int timeoutSeconds) {
        long started = System.nanoTime();
        Socket tcp = new Socket();
        SSLSocket ssl = null;
        try {
            int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
            tcp.connect(new InetSocketAddress(config.address, config.port), timeoutMs);
            tcp.setSoTimeout(timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            ssl = (SSLSocket) factory.createSocket(tcp, config.sni, config.port, true);
            ssl.setSoTimeout(timeoutMs);
            setSni(ssl, config.sni);
            ssl.startHandshake();
            OutputStream output = ssl.getOutputStream();
            output.write(("HEAD / HTTP/1.1\r\nHost: " + config.sni + "\r\nUser-Agent: UAC-Spoofer\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(ssl.getInputStream()));
            int status = parseStatus(reader.readLine());
            double latency = (System.nanoTime() - started) / 1_000_000.0;
            return new PingResult(status == 0 || (status >= 200 && status < 500), latency);
        } catch (Exception ignored) {
            return new PingResult(false, 0);
        } finally {
            closeQuietly(ssl);
            closeQuietly(tcp);
        }
    }

    private static void setSni(SSLSocket socket, String sni) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Object parameters = socket.getSSLParameters();
                Class<?> sniClass = Class.forName("javax.net.ssl.SNIHostName");
                Object sniName = sniClass.getConstructor(String.class).newInstance(sni);
                parameters.getClass()
                        .getMethod("setServerNames", List.class)
                        .invoke(parameters, Collections.singletonList(sniName));
                socket.getClass()
                        .getMethod("setSSLParameters", parameters.getClass())
                        .invoke(socket, parameters);
            } catch (Exception ignored) {
            }
        }
    }

    private static int parseStatus(String line) {
        if (line == null) {
            return 0;
        }
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class PingResult {
        final boolean ok;
        final double latencyMs;

        PingResult(boolean ok, double latencyMs) {
            this.ok = ok;
            this.latencyMs = latencyMs;
        }
    }

    private static final class Counter {
        int value = 0;

        synchronized int next() {
            value++;
            return value;
        }
    }
}

