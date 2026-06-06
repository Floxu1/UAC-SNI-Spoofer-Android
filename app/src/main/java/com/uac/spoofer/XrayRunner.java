package com.uac.spoofer;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrayRunner {
    public interface Logger {
        void log(String message);
    }

    public static final int SOCKS_PORT = 10808;

    private Process process;
    private ExecutorService logExecutor;

    public synchronized void start(Context context, ProxyConfig profile, Logger logger) throws Exception {
        stop();
        if (profile.sourceUri == null || profile.sourceUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Selected config has no V2Ray/Trojan URI.");
        }
        File binary = prepareBinary(context);
        File config = new File(context.getFilesDir(), "xray-config.json");
        writeText(config, buildConfig(profile).toString(2));

        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "run",
                "-config",
                config.getAbsolutePath()
        );
        builder.directory(context.getFilesDir());
        builder.redirectErrorStream(true);
        process = builder.start();
        sleepQuietly(600);
        try {
            int exitCode = process.exitValue();
            String output = readAvailableOutput(process);
            stop();
            throw new IllegalStateException("Xray exited immediately with code " + exitCode
                    + (output.isEmpty() ? "." : ": " + output));
        } catch (IllegalThreadStateException ignored) {
        }
        logExecutor = Executors.newSingleThreadExecutor();
        logExecutor.execute(() -> readLogs(process, logger));
        logger.log("XRAY started socks=127.0.0.1:" + SOCKS_PORT + " outbound=" + profile.protocol);
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (logExecutor != null) {
            logExecutor.shutdownNow();
            logExecutor = null;
        }
    }

    private File prepareBinary(Context context) throws Exception {
        xrayAbi();
        File source = new File(context.getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (!source.exists() || source.length() == 0) {
            throw new IllegalStateException("Xray binary is not available for this CPU.");
        }
        if (!source.canExecute() && !source.setExecutable(true, false)) {
            throw new IllegalStateException("Xray binary is not executable on this device.");
        }
        return source;
    }

    private static String xrayAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) {
                return abi;
            }
        }
        throw new IllegalStateException("This APK includes Xray only for arm64-v8a and x86_64.");
    }

    private static JSONObject buildConfig(ProxyConfig profile) throws Exception {
        ParsedConfig parsed = ParsedConfig.parse(profile);

        JSONObject inbound = new JSONObject();
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", SOCKS_PORT);
        inbound.put("protocol", "socks");
        inbound.put("settings", new JSONObject()
                .put("auth", "noauth")
                .put("udp", true));

        JSONObject outbound = new JSONObject();
        outbound.put("protocol", parsed.protocol);
        if ("trojan".equals(parsed.protocol)) {
            outbound.put("settings", new JSONObject()
                    .put("servers", new JSONArray()
                            .put(new JSONObject()
                                    .put("address", profile.configHost)
                                    .put("port", profile.configPort)
                                    .put("password", parsed.user))));
        } else if ("vless".equals(parsed.protocol)) {
            outbound.put("settings", new JSONObject()
                    .put("vnext", new JSONArray()
                            .put(new JSONObject()
                                    .put("address", profile.configHost)
                                    .put("port", profile.configPort)
                                    .put("users", new JSONArray()
                                            .put(new JSONObject()
                                                    .put("id", parsed.user)
                                                    .put("encryption", "none"))))));
        } else {
            throw new IllegalArgumentException("Unsupported config protocol: " + parsed.protocol);
        }

        JSONObject stream = new JSONObject();
        stream.put("network", parsed.network);
        stream.put("security", "tls");
        JSONObject tlsSettings = new JSONObject()
                .put("serverName", parsed.sni);
        if (!parsed.fingerprint.isEmpty()) {
            tlsSettings.put("fingerprint", parsed.fingerprint);
        }
        if (!parsed.pinnedPeerCertSha256.isEmpty()) {
            tlsSettings.put("pinnedPeerCertSha256", parsed.pinnedPeerCertSha256);
        }
        if (!parsed.verifyPeerCertByName.isEmpty()) {
            tlsSettings.put("verifyPeerCertByName", parsed.verifyPeerCertByName);
        }
        stream.put("tlsSettings", tlsSettings);
        if ("httpupgrade".equals(parsed.network)) {
            stream.put("httpupgradeSettings", new JSONObject()
                    .put("path", parsed.path)
                    .put("host", parsed.hostHeader));
        } else {
            stream.put("wsSettings", new JSONObject()
                    .put("path", parsed.path)
                    .put("headers", new JSONObject().put("Host", parsed.hostHeader)));
        }
        outbound.put("streamSettings", stream);

        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("inbounds", new JSONArray().put(inbound))
                .put("outbounds", new JSONArray().put(outbound));
    }

    private static void readLogs(Process process, Logger logger) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.log("XRAY " + line);
            }
        } catch (Exception ignored) {
        }
    }

    private static String readAvailableOutput(Process process) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append(" | ");
                }
                out.append(line);
                if (out.length() > 500) {
                    return out.substring(0, 500);
                }
            }
        } catch (Exception ignored) {
        }
        return out.toString().trim();
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes("UTF-8"));
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ParsedConfig {
        String protocol;
        String user;
        String sni;
        String hostHeader;
        String path;
        String network;
        String fingerprint;
        String pinnedPeerCertSha256;
        String verifyPeerCertByName;

        static ParsedConfig parse(ProxyConfig profile) throws Exception {
            URI uri = new URI(profile.sourceUri);
            Map<String, String> query = parseQuery(uri.getRawQuery());
            ParsedConfig out = new ParsedConfig();
            out.protocol = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            out.user = uri.getUserInfo() == null ? "" : uri.getUserInfo();
            String configSni = firstNonEmpty(
                    query.get("sni"),
                    query.get("serverName"),
                    query.get("servername"),
                    query.get("host"),
                    query.get("authority"),
                    uri.getHost()
            );
            out.sni = firstNonEmpty(configSni, profile.sni);
            out.hostHeader = firstNonEmpty(query.get("host"), query.get("authority"), configSni, profile.sni);
            out.path = firstNonEmpty(query.get("path"), "/");
            if (!out.path.startsWith("/")) {
                out.path = "/" + out.path;
            }
            out.network = firstNonEmpty(query.get("type"), "ws").toLowerCase(Locale.US);
            if (!"httpupgrade".equals(out.network)) {
                out.network = "ws";
            }
            out.fingerprint = firstNonEmpty(query.get("fp"), query.get("fingerprint"));
            out.pinnedPeerCertSha256 = firstNonEmpty(query.get("pinnedPeerCertSha256"), query.get("pcs"));
            out.verifyPeerCertByName = firstNonEmpty(query.get("verifyPeerCertByName"), query.get("vcn"));
            if (out.user.isEmpty() || out.sni.isEmpty()) {
                throw new IllegalArgumentException("Invalid config URI.");
            }
            return out;
        }

        private static Map<String, String> parseQuery(String raw) {
            Map<String, String> out = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
                String value = decode(eq >= 0 ? pair.substring(eq + 1) : "");
                out.put(key, value);
            }
            return out;
        }

        private static String decode(String value) {
            if (value == null) {
                return "";
            }
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (Exception ignored) {
                return value;
            }
        }

        private static String firstNonEmpty(String... values) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
            return "";
        }
    }
}

