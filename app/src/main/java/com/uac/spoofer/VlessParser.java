package com.uac.spoofer;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VlessParser {
    private VlessParser() {
    }

    public static List<ProxyConfig> parseMany(String input) {
        List<ProxyConfig> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        Matcher matcher = Pattern.compile("(vless|trojan)://\\S+", Pattern.CASE_INSENSITIVE).matcher(input);
        while (matcher.find()) {
            ProxyConfig config = parseOne(matcher.group());
            if (config != null) {
                out.add(config);
            }
        }
        return out;
    }

    public static ProxyConfig parseOne(String raw) {
        try {
            String value = raw.trim();
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if (!"vless".equals(scheme) && !"trojan".equals(scheme)) {
                return null;
            }
            String host = uri.getHost();
            int uriPort = uri.getPort() > 0 ? uri.getPort() : 443;
            Map<String, String> query = parseQuery(uri.getRawQuery());
            String sni = firstNonEmpty(
                    query.get("sni"),
                    query.get("servername"),
                    query.get("serverName"),
                    query.get("host"),
                    query.get("authority"),
                    host,
                    ""
            );
            String remark = decode(uri.getRawFragment());
            if (remark.isEmpty()) {
                remark = scheme.toUpperCase(Locale.US) + " " + host;
            }
            if (host == null || host.isEmpty()) {
                return null;
            }
            ProxyConfig config = new ProxyConfig();
            config.id = UUID.randomUUID().toString();
            config.name = remark;
            config.address = ProxyConfig.DEFAULT_SPOOF_ADDRESS;
            config.fallbackAddress = ProxyConfig.DEFAULT_SPOOF_FALLBACK;
            config.port = 443;
            config.sni = ProxyConfig.DEFAULT_SPOOF_SNI;
            config.method = "combined";
            config.sourceUri = value;
            config.protocol = scheme;
            config.configHost = host;
            config.configPort = uriPort;
            return config;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ProxyConfig parsePlain(String input) {
        String value = "";
        for (String line : input.split("\\r?\\n")) {
            String cleaned = line.trim();
            if (!cleaned.isEmpty()) {
                value = cleaned;
                break;
            }
        }
        if (value.isEmpty()) {
            return null;
        }

        String connect = matchArg(value, "-connect\\s+([^\\s]+)");
        String sni = matchArg(value, "-sni\\s+([^\\s]+)");
        if (!connect.isEmpty()) {
            ProxyConfig config = new ProxyConfig();
            config.name = "Imported command";
            String[] hostPort = splitHostPort(connect, 443);
            config.address = hostPort[0];
            config.port = safePort(hostPort[1], 443);
            config.sni = sni.isEmpty() ? hostPort[0] : sni;
            return config;
        }

        String name = "Imported config";
        int hash = value.indexOf('#');
        if (hash >= 0) {
            name = value.substring(hash + 1).trim();
            value = value.substring(0, hash).trim();
        }
        if (value.contains("?")) {
            String[] parts = value.split("\\?", 2);
            value = parts[0];
            Map<String, String> query = parseQuery(parts[1]);
            sni = firstNonEmpty(query.get("sni"), query.get("host"), query.get("serverName"), "");
        }
        String[] hostPort = splitHostPort(value, 443);
        if (hostPort[0].isEmpty()) {
            return null;
        }
        ProxyConfig config = new ProxyConfig();
        config.name = name.isEmpty() ? "Imported config" : name;
        config.address = hostPort[0];
        config.port = safePort(hostPort[1], 443);
        config.sni = sni.isEmpty() ? hostPort[0] : sni;
        return config;
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

    private static String[] splitHostPort(String value, int defaultPort) {
        String host = value.trim();
        String port = String.valueOf(defaultPort);
        if (host.startsWith("[") && host.contains("]")) {
            int end = host.indexOf(']');
            port = host.length() > end + 2 && host.charAt(end + 1) == ':' ? host.substring(end + 2) : port;
            host = host.substring(1, end);
            return new String[]{host, port};
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        return new String[]{host, port};
    }

    private static int safePort(String raw, int fallback) {
        try {
            int port = Integer.parseInt(raw);
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String matchArg(String value, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
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

