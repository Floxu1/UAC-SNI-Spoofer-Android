package com.uac.spoofer;

import android.annotation.SuppressLint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SniSpoofScanner {
    public interface Callback {
        void onProgress(int done, int total, Result result);

        void onFinished(List<Result> results, boolean cancelled);

        void onError(String message);
    }

    public static class Settings {
        public List<String> domains = new ArrayList<>();
        public String serverIp = "";
        public int timeoutSeconds = 20;
        public int threads = 30;
        public int tries = 3;
    }

    public static class Result {
        public final String domain;
        public String resolvedIp = "N/A";
        public String cfIp = "";
        public String colo = "";
        public String country = "";
        public String ray = "";
        public int pingMs = 9999;
        public int stability = 0;
        public int score = -9999;
        public boolean success = false;

        public Result(String domain) {
            this.domain = domain;
        }

        public boolean ok() {
            return success;
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService workers;
    private ExecutorService manager;

    public void start(Settings settings, Callback callback) {
        cancelled.set(false);
        manager = Executors.newSingleThreadExecutor();
        manager.execute(() -> run(settings, callback));
    }

    public void cancel() {
        cancelled.set(true);
        if (workers != null) {
            workers.shutdownNow();
        }
        if (manager != null) {
            manager.shutdownNow();
        }
    }

    private void run(Settings settings, Callback callback) {
        List<Result> results = Collections.synchronizedList(new ArrayList<>());
        try {
            int total = settings.domains.size();
            int threadCount = Math.max(1, Math.min(100, settings.threads));
            workers = Executors.newFixedThreadPool(threadCount);
            List<Future<?>> futures = new ArrayList<>();
            Counter counter = new Counter();
            for (String domain : settings.domains) {
                futures.add(workers.submit(() -> {
                    if (cancelled.get()) {
                        return;
                    }
                    Result result = check(domain, settings.serverIp, settings.timeoutSeconds, settings.tries);
                    if (cancelled.get()) {
                        return;
                    }
                    if (result.ok()) {
                        results.add(result);
                    }
                    callback.onProgress(counter.next(), total, result);
                }));
            }
            for (Future<?> future : futures) {
                if (cancelled.get()) {
                    break;
                }
                try {
                    future.get();
                } catch (Exception ignored) {
                }
            }
            workers.shutdownNow();
            List<Result> sorted = new ArrayList<>(results);
            sorted.sort(Comparator
                    .comparingInt((Result r) -> -r.stability)
                    .thenComparingInt(r -> r.pingMs)
                    .thenComparing(r -> r.domain));
            callback.onFinished(sorted, cancelled.get());
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? "SNI scanner failed." : e.getMessage());
        }
    }

    private Result check(String domain, String serverIp, int timeoutSeconds, int tries) {
        Result result = new Result(domain);
        result.resolvedIp = resolve(domain);
        int ok = 0;
        int pingTotal = 0;
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        String expectedIp = serverIp == null ? "" : serverIp.trim();
        boolean matchedExpectedIp = false;
        for (int i = 0; i < Math.max(1, tries) && !cancelled.get(); i++) {
            Trace trace = requestTrace(domain, timeoutMs);
            if (trace.ok) {
                ok++;
                pingTotal += trace.pingMs;
                result.cfIp = trace.cfIp;
                result.colo = trace.colo;
                result.country = trace.country;
                result.ray = trace.ray;
                if (!expectedIp.isEmpty() && expectedIp.equals(trace.cfIp)) {
                    matchedExpectedIp = true;
                }
            }
        }
        result.stability = (int) ((ok / (double) Math.max(1, tries)) * 100);
        result.pingMs = ok > 0 ? pingTotal / ok : 9999;
        result.score = result.stability * 10 - result.pingMs;
        result.success = ok > 0 && (expectedIp.isEmpty() || matchedExpectedIp);
        return result;
    }

    @SuppressLint({"TrustAllX509TrustManager", "BadHostnameVerifier"})
    private Trace requestTrace(String domain, int timeoutMs) {
        long started = System.nanoTime();
        HttpsURLConnection connection = null;
        try {
            URL url = new URL("https://" + domain + "/cdn-cgi/trace");
            connection = (HttpsURLConnection) url.openConnection();
            connection.setSSLSocketFactory(trustAllContext().getSocketFactory());
            connection.setHostnameVerifier(trustAllHostnameVerifier());
            connection.setConnectTimeout(Math.min(5000, timeoutMs));
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 UAC-Spoofer");
            connection.connect();
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                }
            }
            if (body.indexOf("ip=") < 0) {
                return Trace.fail();
            }
            Trace trace = new Trace();
            trace.ok = true;
            trace.pingMs = (int) ((System.nanoTime() - started) / 1_000_000);
            for (String line : body.toString().split("\\n")) {
                if (line.startsWith("ip=")) {
                    trace.cfIp = line.substring(3).trim();
                } else if (line.startsWith("colo=")) {
                    trace.colo = line.substring(5).trim();
                } else if (line.startsWith("loc=")) {
                    trace.country = line.substring(4).trim();
                } else if (line.startsWith("ray=")) {
                    trace.ray = line.substring(4).trim();
                }
            }
            return trace;
        } catch (Exception ignored) {
            return Trace.fail();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String resolve(String domain) {
        try {
            return InetAddress.getByName(domain).getHostAddress();
        } catch (Exception ignored) {
            return "N/A";
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    }

    private static HostnameVerifier trustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }

    private static final class Trace {
        boolean ok;
        int pingMs;
        String cfIp = "";
        String colo = "";
        String country = "";
        String ray = "";

        static Trace fail() {
            return new Trace();
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

