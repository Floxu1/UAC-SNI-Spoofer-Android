package com.uac.spoofer;

import android.content.Intent;

import java.util.Locale;

public final class ProxyTuning {
    public static final String MODE_FAST = "fast";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_STEALTH = "stealth";
    public static final String MODE_CUSTOM = "custom";

    public static final String LOG_MINIMAL = "minimal";
    public static final String LOG_NORMAL = "normal";
    public static final String LOG_VERBOSE = "verbose";

    public static final int DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000;

    public String mode = MODE_BALANCED;
    public boolean fakeProbeEnabled = true;
    public int fakeProbeCount = 1;
    public int fakeProbeDelayMs = 50;
    public int multiFragmentSize = 96;
    public int sniSplitDelayMs = 45;
    public int tlsRecordDelayMs = 35;
    public int multiDelayMs = 3;
    public int halfDelayMs = 35;
    public int routeProbeTimeoutMs = 2800;
    public boolean strategyCacheEnabled = true;
    public int strategyCacheTtlMs = DEFAULT_CACHE_TTL_MS;
    public String logLevel = LOG_NORMAL;

    public static ProxyTuning balanced() {
        return new ProxyTuning().sanitize();
    }

    public static ProxyTuning fast() {
        ProxyTuning tuning = new ProxyTuning();
        tuning.mode = MODE_FAST;
        tuning.fakeProbeEnabled = false;
        tuning.fakeProbeCount = 0;
        tuning.fakeProbeDelayMs = 0;
        tuning.multiFragmentSize = 256;
        tuning.sniSplitDelayMs = 0;
        tuning.tlsRecordDelayMs = 0;
        tuning.multiDelayMs = 0;
        tuning.halfDelayMs = 0;
        tuning.routeProbeTimeoutMs = 1200;
        tuning.logLevel = LOG_MINIMAL;
        return tuning.sanitize();
    }

    public static ProxyTuning stealth() {
        ProxyTuning tuning = new ProxyTuning();
        tuning.mode = MODE_STEALTH;
        tuning.fakeProbeEnabled = true;
        tuning.fakeProbeCount = 2;
        tuning.fakeProbeDelayMs = 75;
        tuning.multiFragmentSize = 64;
        tuning.sniSplitDelayMs = 60;
        tuning.tlsRecordDelayMs = 50;
        tuning.multiDelayMs = 8;
        tuning.halfDelayMs = 50;
        tuning.routeProbeTimeoutMs = 3200;
        tuning.logLevel = LOG_NORMAL;
        return tuning.sanitize();
    }

    public static ProxyTuning preset(String mode) {
        String normalized = normalize(mode);
        if (MODE_FAST.equals(normalized)) {
            return fast();
        }
        if (MODE_STEALTH.equals(normalized)) {
            return stealth();
        }
        return balanced();
    }

    public static ProxyTuning fromIntent(Intent intent) {
        ProxyTuning tuning = balanced();
        if (intent == null) {
            return tuning;
        }
        tuning.mode = normalize(intent.getStringExtra(ProxyService.EXTRA_TUNING_MODE));
        tuning.fakeProbeEnabled = intent.getBooleanExtra(ProxyService.EXTRA_FAKE_PROBE_ENABLED, tuning.fakeProbeEnabled);
        tuning.fakeProbeCount = intent.getIntExtra(ProxyService.EXTRA_FAKE_PROBE_COUNT, tuning.fakeProbeCount);
        tuning.fakeProbeDelayMs = intent.getIntExtra(ProxyService.EXTRA_FAKE_PROBE_DELAY_MS, tuning.fakeProbeDelayMs);
        tuning.multiFragmentSize = intent.getIntExtra(ProxyService.EXTRA_MULTI_FRAGMENT_SIZE, tuning.multiFragmentSize);
        tuning.sniSplitDelayMs = intent.getIntExtra(ProxyService.EXTRA_SNI_SPLIT_DELAY_MS, tuning.sniSplitDelayMs);
        tuning.tlsRecordDelayMs = intent.getIntExtra(ProxyService.EXTRA_TLS_RECORD_DELAY_MS, tuning.tlsRecordDelayMs);
        tuning.multiDelayMs = intent.getIntExtra(ProxyService.EXTRA_MULTI_DELAY_MS, tuning.multiDelayMs);
        tuning.halfDelayMs = intent.getIntExtra(ProxyService.EXTRA_HALF_DELAY_MS, tuning.halfDelayMs);
        tuning.routeProbeTimeoutMs = intent.getIntExtra(ProxyService.EXTRA_ROUTE_PROBE_TIMEOUT_MS, tuning.routeProbeTimeoutMs);
        tuning.strategyCacheEnabled = intent.getBooleanExtra(ProxyService.EXTRA_STRATEGY_CACHE_ENABLED, tuning.strategyCacheEnabled);
        tuning.strategyCacheTtlMs = intent.getIntExtra(ProxyService.EXTRA_STRATEGY_CACHE_TTL_MS, tuning.strategyCacheTtlMs);
        tuning.logLevel = normalizeLog(intent.getStringExtra(ProxyService.EXTRA_LOG_LEVEL));
        return tuning.sanitize();
    }

    public void putToIntent(Intent intent) {
        intent.putExtra(ProxyService.EXTRA_TUNING_MODE, mode);
        intent.putExtra(ProxyService.EXTRA_FAKE_PROBE_ENABLED, fakeProbeEnabled);
        intent.putExtra(ProxyService.EXTRA_FAKE_PROBE_COUNT, fakeProbeCount);
        intent.putExtra(ProxyService.EXTRA_FAKE_PROBE_DELAY_MS, fakeProbeDelayMs);
        intent.putExtra(ProxyService.EXTRA_MULTI_FRAGMENT_SIZE, multiFragmentSize);
        intent.putExtra(ProxyService.EXTRA_SNI_SPLIT_DELAY_MS, sniSplitDelayMs);
        intent.putExtra(ProxyService.EXTRA_TLS_RECORD_DELAY_MS, tlsRecordDelayMs);
        intent.putExtra(ProxyService.EXTRA_MULTI_DELAY_MS, multiDelayMs);
        intent.putExtra(ProxyService.EXTRA_HALF_DELAY_MS, halfDelayMs);
        intent.putExtra(ProxyService.EXTRA_ROUTE_PROBE_TIMEOUT_MS, routeProbeTimeoutMs);
        intent.putExtra(ProxyService.EXTRA_STRATEGY_CACHE_ENABLED, strategyCacheEnabled);
        intent.putExtra(ProxyService.EXTRA_STRATEGY_CACHE_TTL_MS, strategyCacheTtlMs);
        intent.putExtra(ProxyService.EXTRA_LOG_LEVEL, logLevel);
    }

    public ProxyTuning copy() {
        ProxyTuning out = new ProxyTuning();
        out.mode = mode;
        out.fakeProbeEnabled = fakeProbeEnabled;
        out.fakeProbeCount = fakeProbeCount;
        out.fakeProbeDelayMs = fakeProbeDelayMs;
        out.multiFragmentSize = multiFragmentSize;
        out.sniSplitDelayMs = sniSplitDelayMs;
        out.tlsRecordDelayMs = tlsRecordDelayMs;
        out.multiDelayMs = multiDelayMs;
        out.halfDelayMs = halfDelayMs;
        out.routeProbeTimeoutMs = routeProbeTimeoutMs;
        out.strategyCacheEnabled = strategyCacheEnabled;
        out.strategyCacheTtlMs = strategyCacheTtlMs;
        out.logLevel = logLevel;
        return out;
    }

    public ProxyTuning sanitize() {
        mode = normalize(mode);
        logLevel = normalizeLog(logLevel);
        fakeProbeCount = clamp(fakeProbeEnabled ? fakeProbeCount : 0, 0, 3);
        fakeProbeDelayMs = clamp(fakeProbeDelayMs, 0, 150);
        multiFragmentSize = clampToAllowed(multiFragmentSize, new int[]{64, 96, 128, 192, 256}, 96);
        sniSplitDelayMs = clamp(sniSplitDelayMs, 0, 100);
        tlsRecordDelayMs = clamp(tlsRecordDelayMs, 0, 100);
        multiDelayMs = clamp(multiDelayMs, 0, 25);
        halfDelayMs = clamp(halfDelayMs, 0, 100);
        routeProbeTimeoutMs = clamp(routeProbeTimeoutMs, 1000, 4000);
        strategyCacheTtlMs = clamp(strategyCacheTtlMs, 60 * 1000, 60 * 60 * 1000);
        return this;
    }

    public String summary() {
        return String.format(Locale.US, "%s, fake=%s/%d, multi=%d, timeout=%dms, log=%s",
                mode, fakeProbeEnabled ? "on" : "off", fakeProbeCount, multiFragmentSize,
                routeProbeTimeoutMs, logLevel);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampToAllowed(int value, int[] allowed, int fallback) {
        for (int option : allowed) {
            if (option == value) {
                return value;
            }
        }
        return fallback;
    }

    private static String normalize(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (MODE_FAST.equals(mode) || MODE_STEALTH.equals(mode) || MODE_CUSTOM.equals(mode)) {
            return mode;
        }
        return MODE_BALANCED;
    }

    private static String normalizeLog(String value) {
        String level = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (LOG_MINIMAL.equals(level) || LOG_VERBOSE.equals(level)) {
            return level;
        }
        return LOG_NORMAL;
    }
}
