package com.uac.spoofer;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

public class RealityProbeActivity extends Activity {
    private static final int MAX_DOMAINS = 180;
    private static final int DEFAULT_SCAN_TIMEOUT_SECONDS = 8;
    private static final String DEFAULT_TEST_URL = "https://www.visa.com/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger scannedDomains = new AtomicInteger(0);
    private final AtomicInteger candidates = new AtomicInteger(0);
    private final AtomicInteger queuedTests = new AtomicInteger(0);
    private final AtomicInteger finishedTests = new AtomicInteger(0);

    private ProfileStore profileStore;
    private SniSpoofScanner scanner;
    private ExecutorService testExecutor;
    private List<ProxyConfig> profiles = new ArrayList<>();
    private final List<ProbeResult> results = new ArrayList<>();
    private final Object resultsLock = new Object();

    private int runId = 0;
    private int totalDomains = 0;
    private int maxCandidates = 24;
    private int scanWorkers = 18;
    private int requestTimeoutSeconds = 12;
    private boolean scanFinished = false;

    private EditText testUrlInput;
    private TextView statusText;
    private TextView scanStepText;
    private TextView engineStepText;
    private TextView requestStepText;
    private TextView statsText;
    private TextView speedLabel;
    private TextView candidateLabel;
    private TextView timeoutLabel;
    private TextView logText;
    private ProgressBar scanProgress;
    private ProgressBar testProgress;
    private LinearLayout resultsContainer;
    private ScrollView logScroll;
    private Button startButton;
    private Button stopButton;
    private SeekBar speedSeek;
    private SeekBar candidateSeek;
    private SeekBar timeoutSeek;
    private boolean persianUi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.apply(this);
        super.onCreate(savedInstanceState);
        persianUi = LanguageStore.isPersian(this);
        profileStore = new ProfileStore(this);
        profiles = profileStore.loadProfiles();
        buildLayout();
        updateTuningLabels();
        updateStage(txt("Idle", "آماده"), txt("Waiting", "در انتظار"), txt("Waiting", "در انتظار"));
    }

    @Override
    protected void onDestroy() {
        stopProbe();
        stopProxyService();
        super.onDestroy();
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_app);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(14));
        header.setBackgroundResource(R.drawable.bg_header);
        root.addView(header, matchWrap());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, matchWrap());

        Button back = new Button(this);
        back.setText(txt("Back", "بازگشت"));
        back.setTextColor(0xffffffff);
        back.setTextSize(12);
        back.setTypeface(null, android.graphics.Typeface.BOLD);
        back.setBackgroundResource(R.drawable.bg_header_control);
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(64), dp(42)));
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText(txt("Reality Probe", "تست واقعی مسیر"));
        title.setTextColor(0xffffffff);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(12), 0, 0, 0);
        titleRow.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText(txt("Verify SNI, config engine, and web route together", "SNI، موتور کانفیگ و مسیر وب را با هم بررسی کنید"));
        subtitle.setTextColor(0xffd7e8ff);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(10), 0, 0);
        header.addView(subtitle, subtitleParams);

        LinearLayout card = card(root);
        TextView heading = label(txt("Route Lab", "آزمایش مسیر"), 17, true, R.color.ink);
        card.addView(heading, matchWrap());
        TextView hint = label(txt("Scans SNI candidates, starts Xray for each route, then checks the target through the local SOCKS path.", "SNIهای مناسب را اسکن می‌کند، برای هر مسیر Xray را روشن می‌کند و آدرس تست را از مسیر SOCKS محلی بررسی می‌کند."), 12, false, R.color.muted);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, dp(6), 0, dp(10));
        card.addView(hint, hintParams);

        testUrlInput = new EditText(this);
        testUrlInput.setHint(txt("Test URL", "آدرس تست"));
        testUrlInput.setSingleLine(true);
        testUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testUrlInput.setText(DEFAULT_TEST_URL);
        testUrlInput.setTextColor(getResources().getColor(R.color.ink));
        testUrlInput.setHintTextColor(getResources().getColor(R.color.muted));
        testUrlInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        testUrlInput.setTextSize(14);
        testUrlInput.setBackgroundResource(R.drawable.bg_input);
        testUrlInput.setPadding(dp(12), 0, dp(12), 0);
        card.addView(testUrlInput, fixedHeight(48));

        addTuningControls(card);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.setMargins(0, dp(12), 0, 0);
        card.addView(actions, actionParams);

        startButton = new Button(this);
        startButton.setText(txt("Start Probe", "شروع تست"));
        startButton.setTextColor(0xffffffff);
        startButton.setTypeface(null, android.graphics.Typeface.BOLD);
        startButton.setBackgroundResource(R.drawable.bg_run);
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        startButton.setOnClickListener(v -> startProbe());

        stopButton = new Button(this);
        stopButton.setText(txt("Stop", "توقف"));
        stopButton.setTextColor(0xffffffff);
        stopButton.setTypeface(null, android.graphics.Typeface.BOLD);
        stopButton.setEnabled(false);
        stopButton.setBackgroundResource(R.drawable.bg_stop);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 0.72f);
        stopParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> stopProbe());

        statusText = chip(txt("Idle", "آماده"), 40);
        LinearLayout.LayoutParams statusParams = fixedHeight(38);
        statusParams.setMargins(0, dp(10), 0, dp(10));
        card.addView(statusText, statusParams);

        addStagePanel(card);
        addLogPanel(card);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(resultsContainer, matchWrap());

        setContentView(scroll);
    }

    private void addTuningControls(LinearLayout card) {
        LinearLayout tuning = new LinearLayout(this);
        tuning.setOrientation(LinearLayout.VERTICAL);
        tuning.setPadding(dp(12), dp(10), dp(12), dp(10));
        tuning.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(10), 0, 0);
        card.addView(tuning, params);

        speedLabel = label("", 12, true, R.color.ink);
        tuning.addView(speedLabel, matchWrap());
        speedSeek = new SeekBar(this);
        speedSeek.setMax(3);
        speedSeek.setProgress(1);
        tuning.addView(speedSeek, matchWrap());

        candidateLabel = label("", 12, true, R.color.ink);
        tuning.addView(candidateLabel, matchWrap());
        candidateSeek = new SeekBar(this);
        candidateSeek.setMax(4);
        candidateSeek.setProgress(2);
        tuning.addView(candidateSeek, matchWrap());

        timeoutLabel = label("", 12, true, R.color.ink);
        tuning.addView(timeoutLabel, matchWrap());
        timeoutSeek = new SeekBar(this);
        timeoutSeek.setMax(4);
        timeoutSeek.setProgress(2);
        tuning.addView(timeoutSeek, matchWrap());

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTuningLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        speedSeek.setOnSeekBarChangeListener(listener);
        candidateSeek.setOnSeekBarChangeListener(listener);
        timeoutSeek.setOnSeekBarChangeListener(listener);
    }

    private void addStagePanel(LinearLayout card) {
        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.VERTICAL);
        stages.setPadding(dp(12), dp(10), dp(12), dp(10));
        stages.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        card.addView(stages, params);

        scanStepText = label(txt("SNI scan: Waiting", "اسکن SNI: در انتظار"), 13, true, R.color.ink);
        stages.addView(scanStepText, matchWrap());
        scanProgress = horizontalProgress();
        stages.addView(scanProgress, fixedHeight(12));

        engineStepText = label(txt("Engine: Waiting", "موتور: در انتظار"), 13, true, R.color.ink);
        LinearLayout.LayoutParams engineParams = matchWrap();
        engineParams.setMargins(0, dp(8), 0, 0);
        stages.addView(engineStepText, engineParams);

        requestStepText = label(txt("Request: Waiting", "درخواست: در انتظار"), 13, true, R.color.ink);
        stages.addView(requestStepText, matchWrap());
        testProgress = horizontalProgress();
        stages.addView(testProgress, fixedHeight(12));

        statsText = label(txt("0 scanned  |  0 candidates  |  0/0 tests  |  0 working", "۰ اسکن  |  ۰ گزینه  |  ۰/۰ تست  |  ۰ فعال"), 12, false, R.color.muted);
        LinearLayout.LayoutParams statsParams = matchWrap();
        statsParams.setMargins(0, dp(8), 0, 0);
        stages.addView(statsText, statsParams);
    }

    private void addLogPanel(LinearLayout card) {
        TextView title = label(txt("Probe Log", "لاگ تست"), 14, true, R.color.ink);
        card.addView(title, matchWrap());
        logScroll = new ScrollView(this);
        logScroll.setBackgroundResource(R.drawable.bg_log);
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams logParams = fixedHeight(170);
        logParams.setMargins(0, dp(8), 0, dp(10));
        card.addView(logScroll, logParams);

        logText = new TextView(this);
        logText.setTextColor(0xffd7e3ea);
        logText.setTextSize(12);
        logText.setTextDirection(View.TEXT_DIRECTION_LTR);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText, matchWrap());
    }

    private void startProbe() {
        if (running.get()) {
            return;
        }
        if (ProxyService.isRunning()) {
            toast(txt("Stop VPN first, then run Reality Probe.", "اول VPN را خاموش کنید، بعد تست واقعی را اجرا کنید."));
            return;
        }
        String testUrl = normalizedUrl(testUrlInput.getText().toString());
        if (testUrl.isEmpty()) {
            toast(txt("Enter a valid test URL.", "یک آدرس تست معتبر وارد کنید."));
            return;
        }
        profiles = profileStore.loadProfiles();
        if (profiles.isEmpty()) {
            toast(txt("No configs available.", "کانفیگی موجود نیست."));
            return;
        }
        List<String> domains = parseDomains(readAssetText("sni-spoof/domains.txt").toString(), MAX_DOMAINS);
        if (domains.isEmpty()) {
            toast(txt("No SNI domains available.", "دامنه SNI موجود نیست."));
            return;
        }

        updateTuningLabels();
        int currentRun = ++runId;
        running.set(true);
        scanFinished = false;
        totalDomains = domains.size();
        scannedDomains.set(0);
        candidates.set(0);
        queuedTests.set(0);
        finishedTests.set(0);
        synchronized (resultsLock) {
            results.clear();
        }
        resultsContainer.removeAllViews();
        logText.setText("");
        setControlsEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusText.setText(txt("Starting Reality Probe...", "در حال شروع تست واقعی..."));
        updateStage(txt("Running", "در حال اجرا"), txt("Waiting", "در انتظار"), txt("Waiting", "در انتظار"));
        updateProgress();
        addLog("START target=" + testUrl + " configs=" + profiles.size()
                + " workers=" + scanWorkers + " candidates=" + maxCandidates
                + " timeout=" + requestTimeoutSeconds + "s");

        testExecutor = Executors.newSingleThreadExecutor();
        scanner = new SniSpoofScanner();
        SniSpoofScanner.Settings settings = new SniSpoofScanner.Settings();
        settings.domains = domains;
        settings.serverIp = "";
        settings.threads = scanWorkers;
        settings.timeoutSeconds = DEFAULT_SCAN_TIMEOUT_SECONDS;
        settings.tries = 1;

        scanner.start(settings, new SniSpoofScanner.Callback() {
            @Override
            public void onProgress(int done, int total, SniSpoofScanner.Result result) {
                if (!isActive(currentRun)) {
                    return;
                }
                scannedDomains.set(done);
                if (result.ok() && candidates.get() < maxCandidates && !"N/A".equals(result.resolvedIp)) {
                    int candidateIndex = candidates.incrementAndGet();
                    addLog("SNI OK #" + candidateIndex + " " + result.domain + " -> " + result.resolvedIp);
                    mainHandler.post(() -> {
                        updateStage((persianUi ? "پیدا شد " : "Found ") + candidateIndex + "/" + maxCandidates + ": " + result.domain,
                                txt("Queued", "در صف"), txt("Waiting", "در انتظار"));
                        updateProgress();
                    });
                    for (ProxyConfig profile : profiles) {
                        queuedTests.incrementAndGet();
                        ProxyConfig testProfile = profile.copy();
                        testProfile.address = result.resolvedIp;
                        testProfile.fallbackAddress = "";
                        testProfile.port = 443;
                        testProfile.sni = result.domain;
                        testProfile.method = "combined";
                        submitRouteTest(currentRun, testProfile, result, testUrl);
                    }
                    if (candidates.get() >= maxCandidates && scanner != null) {
                        scanner.cancel();
                    }
                } else {
                    mainHandler.post(() -> {
                        updateStage((persianUi ? "در حال اسکن " : "Scanning ") + done + "/" + total,
                                txt("Queued", "در صف"), txt("Waiting", "در انتظار"));
                        updateProgress();
                    });
                }
            }

            @Override
            public void onFinished(List<SniSpoofScanner.Result> scanResults, boolean cancelled) {
                if (!isActive(currentRun)) {
                    return;
                }
                scanFinished = true;
                addLog((cancelled ? "SCAN LIMIT/STOP" : "SCAN DONE") + " candidates=" + candidates.get());
                mainHandler.post(() -> {
                    updateStage(cancelled ? txt("Scan stopped at limit", "اسکن در حد تعیین‌شده متوقف شد") : txt("Scan finished", "اسکن تمام شد"),
                            txt("Testing queue", "در حال تست صف"), txt("Running", "در حال اجرا"));
                    updateProgress();
                    maybeFinish(currentRun);
                });
            }

            @Override
            public void onError(String message) {
                if (!isActive(currentRun)) {
                    return;
                }
                addLog("SCAN ERROR " + message);
                mainHandler.post(() -> stopProbe());
            }
        });
    }

    private void submitRouteTest(int currentRun, ProxyConfig profile, SniSpoofScanner.Result sniResult, String testUrl) {
        ExecutorService executor = testExecutor;
        if (executor == null) {
            return;
        }
        executor.execute(() -> runRouteTest(currentRun, profile, sniResult, testUrl));
    }

    private void runRouteTest(int currentRun, ProxyConfig profile, SniSpoofScanner.Result sniResult, String testUrl) {
        if (!isActive(currentRun)) {
            return;
        }
        ProbeResult result = new ProbeResult();
        result.configName = profile.name;
        result.domain = sniResult.domain;
        result.ip = sniResult.resolvedIp;
        long started = System.nanoTime();
        try {
            addLog("ENGINE ON config=\"" + profile.name + "\" sni=" + profile.sni + " ip=" + profile.address);
            mainHandler.post(() -> updateStage(txt("Scan running", "اسکن در حال اجرا"), (persianUi ? "شروع: " : "Starting: ") + profile.name, txt("Waiting", "در انتظار")));
            startProxyService(profile);
            if (!waitForProxyReady(currentRun, 5200)) {
                result.error = ProxyService.getLastError().isEmpty() ? "proxy engine did not start" : ProxyService.getLastError();
                addLog("ENGINE FAIL " + safe(result.error));
                return;
            }
            if (!isActive(currentRun)) {
                return;
            }
            mainHandler.post(() -> updateStage(scanFinished ? txt("Scan finished", "اسکن تمام شد") : txt("Scan running", "اسکن در حال اجرا"), txt("Engine ON", "موتور روشن است"), "GET " + testUrl));
            addLog("REQUEST " + testUrl + " via 127.0.0.1:" + XrayRunner.SOCKS_PORT);
            HttpProbe http = requestThroughSocks(currentRun, testUrl, requestTimeoutSeconds * 1000);
            result.statusCode = http.statusCode;
            result.latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
            result.ok = http.statusCode >= 200 && http.statusCode < 500;
            result.error = http.error;
            addLog((result.ok ? "WORKS " : "FAIL  ") + profile.name + " / " + result.domain
                    + " status=" + result.statusCode + " latency=" + result.latencyMs + "ms"
                    + (result.error.isEmpty() ? "" : " error=" + result.error));
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            addLog("TEST ERROR " + safe(result.error));
        } finally {
            stopProxyService();
            sleepQuietly(220);
            if (isActive(currentRun)) {
                addProbeResult(currentRun, result);
            }
        }
    }

    private void addProbeResult(int currentRun, ProbeResult result) {
        int done = finishedTests.incrementAndGet();
        synchronized (resultsLock) {
            results.add(result);
            results.sort(Comparator
                    .comparing((ProbeResult r) -> !r.ok)
                    .thenComparingInt(r -> r.ok ? r.latencyMs : Integer.MAX_VALUE)
                    .thenComparing(r -> r.configName.toLowerCase(Locale.US))
                    .thenComparing(r -> r.domain));
        }
        mainHandler.post(() -> {
            if (!isActive(currentRun)) {
                return;
            }
            statusText.setText((persianUi ? "تست مسیرها: " : "Route tests: ") + done + "/" + queuedTests.get() + (persianUi ? "  فعال: " : "  working: ") + countWorking());
            updateStage(scanFinished ? txt("Scan finished", "اسکن تمام شد") : txt("Scan running", "اسکن در حال اجرا"),
                    txt("Queue active", "صف فعال است"), (persianUi ? "کامل شد " : "Completed ") + done + "/" + queuedTests.get());
            updateProgress();
            renderResults();
            maybeFinish(currentRun);
        });
    }

    private void maybeFinish(int currentRun) {
        if (!isActive(currentRun)) {
            return;
        }
        if (scanFinished && finishedTests.get() >= queuedTests.get()) {
            running.set(false);
            stopProxyService();
            if (testExecutor != null) {
                testExecutor.shutdownNow();
                testExecutor = null;
            }
            scanner = null;
            setControlsEnabled(true);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            updateStage(txt("Finished", "تمام شد"), txt("OFF", "خاموش"), txt("Finished", "تمام شد"));
            statusText.setText((persianUi ? "تمام شد. مسیرهای فعال: " : "Finished. Working routes: ") + countWorking());
            addLog("FINISH working=" + countWorking() + " tests=" + finishedTests.get());
        }
    }

    private void stopProbe() {
        if (!running.get() && scanner == null && testExecutor == null) {
            return;
        }
        runId++;
        running.set(false);
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        if (testExecutor != null) {
            testExecutor.shutdownNow();
            testExecutor = null;
        }
        stopProxyService();
        setControlsEnabled(true);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        updateStage(txt("Stopped", "متوقف شد"), txt("OFF", "خاموش"), txt("Stopped", "متوقف شد"));
        statusText.setText((persianUi ? "متوقف شد. مسیرهای فعال: " : "Stopped. Working routes: ") + countWorking());
        addLog("STOP requested. Engine stopped.");
    }

    private void renderResults() {
        resultsContainer.removeAllViews();
        List<ProbeResult> snapshot;
        synchronized (resultsLock) {
            snapshot = new ArrayList<>(results);
        }
        int limit = Math.min(80, snapshot.size());
        for (int i = 0; i < limit; i++) {
            resultsContainer.addView(resultRow(snapshot.get(i), i + 1));
        }
    }

    private View resultRow(ProbeResult result, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundResource(result.ok ? R.drawable.bg_result_ok : R.drawable.bg_result_fail);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);

        TextView title = label(String.format(Locale.US, "%02d  %s  %s", index, result.domain, result.ok ? (persianUi ? "فعال" : "WORKS") : (persianUi ? "ناموفق" : "FAIL")),
                14, true, R.color.ink);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(title, matchWrap());

        TextView detail = label(result.ok
                        ? result.configName + "  |  " + result.ip + "  |  HTTP " + result.statusCode + "  |  " + result.latencyMs + " ms"
                        : result.configName + "  |  " + result.ip + "  |  " + safe(result.error),
                12, false, R.color.muted);
        detail.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(detail, matchWrap());

        row.setOnClickListener(v -> {
            if (running.get()) {
                toast(txt("Stop probe before applying a result.", "قبل از اعمال نتیجه، تست را متوقف کنید."));
                return;
            }
            if (!result.ok) {
                toast(txt("Only working results can be applied.", "فقط نتیجه‌های فعال قابل اعمال هستند."));
                return;
            }
            applyResult(result);
        });
        return row;
    }

    private void applyResult(ProbeResult result) {
        List<ProxyConfig> stored = profileStore.loadProfiles();
        for (ProxyConfig profile : stored) {
            profile.address = result.ip;
            profile.fallbackAddress = "";
            profile.port = 443;
            profile.sni = result.domain;
            profile.method = "combined";
            profile.lastPingOk = false;
            profile.lastPingMs = 0;
        }
        profileStore.saveProfiles(stored);
        toast(txt("Reality Probe result applied to configs.", "نتیجه تست واقعی روی کانفیگ‌ها اعمال شد."));
    }

    private void startProxyService(ProxyConfig config) {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START);
        intent.putExtra(ProxyService.EXTRA_NAME, config.name);
        intent.putExtra(ProxyService.EXTRA_ADDRESS, config.address);
        intent.putExtra(ProxyService.EXTRA_FALLBACK, config.fallbackAddress);
        intent.putExtra(ProxyService.EXTRA_PORT, config.port);
        intent.putExtra(ProxyService.EXTRA_SNI, config.sni);
        intent.putExtra(ProxyService.EXTRA_METHOD, config.method);
        intent.putExtra(ProxyService.EXTRA_SOURCE_URI, config.sourceUri);
        intent.putExtra(ProxyService.EXTRA_PROTOCOL, config.protocol);
        intent.putExtra(ProxyService.EXTRA_CONFIG_HOST, config.configHost);
        intent.putExtra(ProxyService.EXTRA_CONFIG_PORT, config.configPort);
        ProxyTuningStore.applyToIntent(this, intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopProxyService() {
        try {
            Intent intent = new Intent(this, ProxyService.class);
            intent.setAction(ProxyService.ACTION_STOP);
            startService(intent);
            stopService(new Intent(this, ProxyService.class));
        } catch (Exception ignored) {
        }
    }

    private boolean waitForProxyReady(int currentRun, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isActive(currentRun) && System.currentTimeMillis() < deadline) {
            if (ProxyService.isRunning()) {
                return true;
            }
            sleepQuietly(120);
        }
        return false;
    }

    private HttpProbe requestThroughSocks(int currentRun, String testUrl, int timeoutMs) {
        HttpsURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", XrayRunner.SOCKS_PORT));
            connection = (HttpsURLConnection) new URL(testUrl).openConnection(proxy);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 UAC-Spoofer Reality-Probe");
            connection.setRequestProperty("Connection", "close");
            if (!isActive(currentRun)) {
                return new HttpProbe(0, "cancelled");
            }
            int status = connection.getResponseCode();
            return new HttpProbe(status, "");
        } catch (Exception e) {
            return new HttpProbe(0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isActive(int currentRun) {
        return running.get() && runId == currentRun && !Thread.currentThread().isInterrupted();
    }

    private void updateTuningLabels() {
        if (speedSeek == null) {
            return;
        }
        int[] workerOptions = {8, 14, 20, 26};
        int[] candidateOptions = {8, 16, 24, 32, 40};
        int[] timeoutOptions = {8, 10, 12, 15, 18};
        scanWorkers = workerOptions[Math.min(speedSeek.getProgress(), workerOptions.length - 1)];
        maxCandidates = candidateOptions[Math.min(candidateSeek.getProgress(), candidateOptions.length - 1)];
        requestTimeoutSeconds = timeoutOptions[Math.min(timeoutSeek.getProgress(), timeoutOptions.length - 1)];
        speedLabel.setText((persianUi ? "سرعت اسکن SNI: " : "SNI scan speed: ") + scanWorkers + (persianUi ? " پردازش" : " workers"));
        candidateLabel.setText((persianUi ? "گزینه‌های مسیر: " : "Route candidates: ") + maxCandidates);
        timeoutLabel.setText((persianUi ? "مهلت درخواست: " : "Request timeout: ") + requestTimeoutSeconds + (persianUi ? " ثانیه" : " seconds"));
    }

    private void updateStage(String scan, String engine, String request) {
        scanStepText.setText((persianUi ? "اسکن SNI: " : "SNI scan: ") + scan);
        engineStepText.setText((persianUi ? "موتور کانفیگ: " : "Config engine: ") + engine);
        requestStepText.setText((persianUi ? "درخواست وب: " : "Web request: ") + request);
    }

    private void updateProgress() {
        scanProgress.setMax(Math.max(1, totalDomains));
        scanProgress.setProgress(Math.min(scannedDomains.get(), Math.max(1, totalDomains)));
        testProgress.setMax(Math.max(1, queuedTests.get()));
        testProgress.setProgress(Math.min(finishedTests.get(), Math.max(1, queuedTests.get())));
        statsText.setText(scannedDomains.get() + "/" + totalDomains + (persianUi ? " اسکن" : " scanned")
                + "  |  " + candidates.get() + "/" + maxCandidates + (persianUi ? " گزینه" : " candidates")
                + "  |  " + finishedTests.get() + "/" + queuedTests.get() + (persianUi ? " تست" : " tests")
                + "  |  " + countWorking() + (persianUi ? " فعال" : " working"));
    }

    private void setControlsEnabled(boolean enabled) {
        testUrlInput.setEnabled(enabled);
        speedSeek.setEnabled(enabled);
        candidateSeek.setEnabled(enabled);
        timeoutSeek.setEnabled(enabled);
    }

    private void addLog(String message) {
        mainHandler.post(() -> {
            String line = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s", System.currentTimeMillis(), message);
            logText.append(line);
            logText.append("\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private int countWorking() {
        int count = 0;
        synchronized (resultsLock) {
            for (ProbeResult result : results) {
                if (result.ok) {
                    count++;
                }
            }
        }
        return count;
    }

    private StringBuilder readAssetText(String path) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<String> parseDomains(String raw, int maxDomains) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : (raw == null ? "" : raw).split("[\\s,;]+")) {
            String domain = token.trim();
            if (domain.isEmpty() || domain.startsWith("#")) {
                continue;
            }
            unique.add(domain);
            if (unique.size() >= Math.max(1, maxDomains)) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private String normalizedUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("https://")) {
            value = "https://" + value.replaceFirst("^http://", "");
        }
        try {
            URL url = new URL(value);
            return url.getHost() == null || url.getHost().trim().isEmpty() ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(12), 0, 0);
        root.addView(card, cardParams);
        return card;
    }

    private TextView label(String text, int size, boolean bold, int colorId) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getResources().getColor(colorId));
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private TextView chip(String text, int heightDp) {
        TextView view = label(text, 12, false, R.color.muted);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackgroundResource(R.drawable.bg_chip);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setMinHeight(dp(heightDp));
        return view;
    }

    private ProgressBar horizontalProgress() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1);
        bar.setProgress(0);
        return bar;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fixedHeight(int heightDp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String txt(String english, String persian) {
        return persianUi ? persian : english;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "no response" : value.trim();
    }

    private static final class HttpProbe {
        final int statusCode;
        final String error;

        HttpProbe(int statusCode, String error) {
            this.statusCode = error == null || error.isEmpty() ? statusCode : 0;
            this.error = error == null ? "" : error;
        }
    }

    private static final class ProbeResult {
        String configName = "";
        String domain = "";
        String ip = "";
        int statusCode = 0;
        int latencyMs = 0;
        boolean ok = false;
        String error = "";
    }
}
