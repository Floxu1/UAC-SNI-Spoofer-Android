use std::collections::VecDeque;
use std::ffi::{c_char, CStr, CString};
use std::net::SocketAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;
use std::sync::{LazyLock, Mutex, OnceLock, RwLock};

use serde::{Deserialize, Serialize};
use tokio::sync::Notify;

use crate::{
    platform, EndpointDiscovery, IpScan, MasqueTransport, Protocol, ScanMode, StartOptions,
    TlsCurvePreset, TunnelAddresses,
};

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    Status {
        status: String,
        detail: Option<String>,
    },
    Traffic {
        tx: u64,
        rx: u64,
    },
    /// The tunnel's own exit address, measured from inside the tunnel.
    ///
    /// Separate from `Status` because it arrives later than "connected" and can
    /// arrive more than once (a reconnect re-measures).
    ///
    /// No country field: the core cannot determine one. DNS exposes the RIR
    /// registration (US for all of Cloudflare) but not a geolocation database,
    /// and those disagree — 104.28.214.161 is registered to ARIN/US and
    /// geolocates to Tehran. The app resolves the country from this address.
    ExitIp {
        ip: String,
    },
    Log {
        message: String,
    },
}

pub type EventCallback = unsafe extern "C" fn(json: *const c_char);

static EVENT_CALLBACK: RwLock<Option<EventCallback>> = RwLock::new(None);
static LAST_ERROR: LazyLock<Mutex<CString>> =
    LazyLock::new(|| Mutex::new(CString::new("").unwrap()));
static LAST_RESULT: LazyLock<Mutex<CString>> =
    LazyLock::new(|| Mutex::new(CString::new("").unwrap()));
static LAST_LOG: LazyLock<Mutex<CString>> = LazyLock::new(|| Mutex::new(CString::new("").unwrap()));
static LOGS: LazyLock<Mutex<VecDeque<String>>> = LazyLock::new(|| Mutex::new(VecDeque::new()));
static LOG_PATH: LazyLock<Mutex<Option<String>>> = LazyLock::new(|| Mutex::new(None));
const MAX_LOG_FILE_BYTES: u64 = 512 * 1024;
static RUNNING: AtomicBool = AtomicBool::new(false);
static READY: AtomicBool = AtomicBool::new(false);
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static STOP_NOTIFY: OnceLock<Notify> = OnceLock::new();
/// Number of `aether_prepare_json` calls in flight (key fetch / cert renewal).
static PREPARE_ACTIVE: AtomicUsize = AtomicUsize::new(0);
/// Set by `aether_cancel_prepare` so an in-flight prepare gives up instead of running
/// its key race / certificate loop to completion while the app disconnects.
static PREPARE_CANCEL: AtomicBool = AtomicBool::new(false);
static PREPARE_NOTIFY: OnceLock<Notify> = OnceLock::new();

/// Upper bound for tearing down a native runtime after its main future ended.
/// `Runtime::drop` waits forever for blocking tasks (for example a
/// `getaddrinfo` behind `lookup_host` on a filtered network); this caps it.
const RUNTIME_SHUTDOWN_GRACE: Duration = Duration::from_millis(1_500);

#[no_mangle]
pub extern "C" fn aether_set_event_callback(callback: Option<EventCallback>) {
    *EVENT_CALLBACK.write().unwrap() = callback;
}

pub(crate) fn emit_event(event: Event) {
    let Some(callback) = *EVENT_CALLBACK.read().unwrap() else {
        return;
    };
    if let Ok(json) = serde_json::to_string(&event) {
        if let Ok(c_str) = CString::new(json) {
            unsafe { callback(c_str.as_ptr()) };
        }
    }
}

pub(crate) fn emit_status(status: impl ToString, detail: Option<String>) {
    emit_event(Event::Status {
        status: status.to_string(),
        detail,
    });
}

pub(crate) fn emit_traffic(tx: u64, rx: u64) {
    emit_event(Event::Traffic { tx, rx });
}

pub(crate) fn emit_exit_ip(ip: String) {
    emit_event(Event::ExitIp { ip });
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct NativeStartOptions {
    config_path: String,
    protocol: String,
    listen: String,
    wireguard_config_path: Option<String>,
    masque_config_path: Option<String>,
    forced_peer: Option<String>,
    scan_mode: String,
    ip_scan: String,
    obfuscation_profile: Option<String>,
    obfuscation_parameters: Option<String>,
    retry_obfuscation_profiles: bool,
    endpoint_cache_path: Option<String>,
    endpoint_discovery: String,
    masque_transport: String,
    tls_curve_preset: String,
    wireguard_data_check: bool,
    log_level: Option<String>,
    perf_profile: Option<String>,
    h2_fragmentation: Option<bool>,
    dns_servers: Option<String>,
    route_block: Option<String>,
    route_direct: Option<String>,
    routes_file: Option<String>,
    team: Option<String>,
    access_client_id: Option<String>,
    access_client_secret: Option<String>,
    access_token: Option<String>,
    access_email: Option<String>,
    gateway: bool,
    upstream_proxy: Option<String>,
    /// `ip:port` for the HTTP CONNECT proxy, or empty/absent for none.
    ///
    /// Android-only channel for what the CLI reads from `AETHER_HTTP_PROXY`: a
    /// process cannot set an environment variable on itself before the library is
    /// already loaded and running, so LAN sharing on the WARP transports had no way
    /// to ask for the HTTP listener at all before this field existed.
    http_proxy: Option<String>,
    #[serde(default = "default_true")]
    scan_once: bool,
    api_camo: String,
    #[serde(default)]
    renew_masque: bool,
    #[serde(default)]
    quic_tuned: bool,
    /// HTTP/2 speed options. Missing keys keep the defaults (all on).
    #[serde(default = "default_true")]
    h2_large_windows: bool,
    #[serde(default = "default_true")]
    h2_socket_tuning: bool,
    #[serde(default = "default_true")]
    h2_speed_pick: bool,
}

impl Default for NativeStartOptions {
    fn default() -> Self {
        Self {
            config_path: String::new(),
            protocol: "masque".into(),
            listen: "127.0.0.1:1819".into(),
            wireguard_config_path: None,
            masque_config_path: None,
            forced_peer: None,
            scan_mode: "balanced".into(),
            ip_scan: "v4".into(),
            obfuscation_profile: None,
            obfuscation_parameters: None,
            retry_obfuscation_profiles: true,
            endpoint_cache_path: None,
            endpoint_discovery: "cache".into(),
            masque_transport: "h3".into(),
            tls_curve_preset: "chrome".into(),
            wireguard_data_check: true,
            log_level: None,
            perf_profile: None,
            h2_fragmentation: None,
            dns_servers: None,
            route_block: None,
            route_direct: None,
            routes_file: None,
            team: None,
            access_client_id: None,
            access_client_secret: None,
            access_token: None,
            access_email: None,
            gateway: false,
            upstream_proxy: None,
            http_proxy: None,
            scan_once: true,
            api_camo: String::new(),
            renew_masque: false,
            quic_tuned: false,
            h2_large_windows: true,
            h2_socket_tuning: true,
            h2_speed_pick: true,
        }
    }
}

fn default_true() -> bool {
    true
}

impl TryFrom<NativeStartOptions> for StartOptions {
    type Error = String;

    fn try_from(value: NativeStartOptions) -> Result<Self, Self::Error> {
        if value.config_path.trim().is_empty() {
            return Err("config_path is required".into());
        }

        let mut options = StartOptions::new(Protocol::parse(&value.protocol), value.config_path);
        options.listen = parse_address("listen", &value.listen)?;
        options.wireguard_config_path = value.wireguard_config_path;
        options.masque_config_path = value.masque_config_path;
        options.forced_peer = value
            .forced_peer
            .map(|peer| parse_address("forced_peer", &peer))
            .transpose()?;
        options.scan_mode = ScanMode::parse(&value.scan_mode);
        options.ip_scan = IpScan::parse(&value.ip_scan);
        options.obfuscation_profile = value.obfuscation_profile;
        options.obfuscation_parameters = value
            .obfuscation_parameters
            .filter(|parameters| !parameters.trim().is_empty());
        options.retry_obfuscation_profiles = value.retry_obfuscation_profiles;
        options.endpoint_cache_path = value
            .endpoint_cache_path
            .filter(|path| !path.trim().is_empty());
        options.endpoint_discovery = EndpointDiscovery::parse(&value.endpoint_discovery);
        options.masque_transport = MasqueTransport::parse(&value.masque_transport);
        options.tls_curve_preset = TlsCurvePreset::parse(&value.tls_curve_preset);
        options.wireguard_data_check = value.wireguard_data_check;
        options.log_level = value.log_level.filter(|level| !level.trim().is_empty());
        options.perf_profile = value
            .perf_profile
            .filter(|profile| !profile.trim().is_empty());
        options.h2_fragmentation = value.h2_fragmentation;
        options.dns_servers = value.dns_servers.filter(|value| !value.trim().is_empty());
        options.route_block = value.route_block.filter(|value| !value.trim().is_empty());
        options.route_direct = value.route_direct.filter(|value| !value.trim().is_empty());
        options.routes_file = value.routes_file.filter(|value| !value.trim().is_empty());
        options.team = value.team.filter(|value| !value.trim().is_empty());
        options.access_client_id = value
            .access_client_id
            .filter(|value| !value.trim().is_empty());
        options.access_client_secret = value
            .access_client_secret
            .filter(|value| !value.trim().is_empty());
        options.access_token = value.access_token.filter(|value| !value.trim().is_empty());
        options.access_email = value.access_email.filter(|value| !value.trim().is_empty());
        options.gateway = value.gateway;
        options.upstream_proxy = value.upstream_proxy.filter(|v| !v.trim().is_empty());
        options.scan_once = value.scan_once;
        options.api_camo = value.api_camo;
        options.renew_masque = value.renew_masque;
        options.quic_tuned = value.quic_tuned;
        options.h2_large_windows = value.h2_large_windows;
        options.h2_socket_tuning = value.h2_socket_tuning;
        options.h2_speed_pick = value.h2_speed_pick;
        options.http_proxy = match value.http_proxy.as_deref().map(str::trim) {
            None | Some("") => None,
            Some(raw) => Some(parse_address("http_proxy", raw)?),
        };
        Ok(options)
    }
}

fn parse_address(name: &str, value: &str) -> Result<SocketAddr, String> {
    value
        .parse()
        .map_err(|_| format!("invalid {name}: {value}"))
}

fn set_last_error(error: impl ToString) {
    let clean = error.to_string().replace('\0', " ");
    if !clean.is_empty() {
        persist_log_line(&format!("ERROR {clean}"));
    }
    *LAST_ERROR.lock().unwrap() = CString::new(clean).unwrap();
}

fn clear_logs() {
    LOGS.lock().unwrap().clear();
    *LAST_LOG.lock().unwrap() = CString::new("").unwrap();
    persist_log_line("--- new session ---");
}

pub(crate) fn set_log_path(path: Option<String>) {
    if let Some(ref path) = path {
        if let Ok(meta) = std::fs::metadata(path) {
            if meta.len() > MAX_LOG_FILE_BYTES {
                let _ = std::fs::remove_file(path);
            }
        }
    }
    *LOG_PATH.lock().unwrap() = path;
}

fn persist_log_line(line: &str) {
    let path = LOG_PATH.lock().unwrap().clone();
    let Some(path) = path else {
        return;
    };
    let stamp = chrono::Local::now().format("%H:%M:%S");
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .and_then(|mut file| {
            use std::io::Write;
            writeln!(file, "{stamp}  {line}")
        });
}

pub(crate) fn record_log(message: impl ToString) {
    let text = message.to_string().replace('\0', " ");
    emit_event(Event::Log {
        message: text.clone(),
    });
    persist_log_line(&text);
    let snapshot = {
        let mut logs = LOGS.lock().unwrap();
        if logs.len() == 400 {
            logs.pop_front();
        }
        logs.push_back(text);
        logs.iter().cloned().collect::<Vec<_>>().join("\n")
    };
    *LAST_LOG.lock().unwrap() = CString::new(snapshot).unwrap();
}

fn set_last_result(result: impl ToString) {
    *LAST_RESULT.lock().unwrap() = CString::new(result.to_string()).unwrap();
}

unsafe fn options_from_json(json: *const c_char) -> Result<StartOptions, String> {
    if json.is_null() {
        return Err("configuration pointer is null".into());
    }

    // SAFETY: caller promises valid, NUL-terminated string for this call.
    let json = unsafe { CStr::from_ptr(json) }
        .to_str()
        .map_err(|error| format!("configuration is not UTF-8: {error}"))?;
    serde_json::from_str::<NativeStartOptions>(json)
        .map_err(|error| error.to_string())
        .and_then(StartOptions::try_from)
}

fn stop_notify() -> &'static Notify {
    STOP_NOTIFY.get_or_init(Notify::new)
}

async fn wait_for_stop() {
    loop {
        let notified = stop_notify().notified();
        if STOP_REQUESTED.load(Ordering::Acquire) {
            return;
        }
        notified.await;
    }
}

fn prepare_notify() -> &'static Notify {
    PREPARE_NOTIFY.get_or_init(Notify::new)
}

async fn wait_for_prepare_cancel() {
    loop {
        let notified = prepare_notify().notified();
        if PREPARE_CANCEL.load(Ordering::Acquire) {
            return;
        }
        notified.await;
    }
}

struct PrepareGuard;

impl Drop for PrepareGuard {
    fn drop(&mut self) {
        PREPARE_ACTIVE.fetch_sub(1, Ordering::AcqRel);
    }
}

struct RunningGuard;

impl Drop for RunningGuard {
    fn drop(&mut self) {
        RUNNING.store(false, Ordering::Release);
        READY.store(false, Ordering::Release);
    }
}

/// Starts Aether on the calling thread from a UTF-8 JSON configuration.
///
/// Returns 0 when the tunnel exits normally, -1 for invalid input, -2 for a
/// runtime/tunnel error, and -3 if a panic was contained at the FFI boundary.
/// The Android caller must invoke this on a worker thread.
#[no_mangle]
pub unsafe extern "C" fn aether_start_json(json: *const c_char) -> i32 {
    unsafe { aether_start_json_inner(json, None) }
}

/// Starts Aether in Android TUN mode. The caller retains ownership of `tun_fd`.
#[no_mangle]
pub unsafe extern "C" fn aether_start_json_with_tun(json: *const c_char, tun_fd: i32) -> i32 {
    if tun_fd < 0 {
        set_last_error("TUN file descriptor is invalid");
        return -1;
    }
    unsafe { aether_start_json_inner(json, Some(tun_fd)) }
}

unsafe fn aether_start_json_inner(json: *const c_char, tun_fd: Option<i32>) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut options = match unsafe { options_from_json(json) } {
            Ok(options) => options,
            Err(error) => {
                set_last_error(error);
                return -1;
            }
        };
        options.tun_fd = tun_fd;

        if RUNNING
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            set_last_error("Aether tunnel already running");
            return -2;
        }
        let _running = RunningGuard;
        STOP_REQUESTED.store(false, Ordering::Release);
        READY.store(false, Ordering::Release);
        set_last_error("");
        clear_logs();
        emit_status("starting", None);
        record_log("Native tunnel started");

        let runtime = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime,
            Err(error) => {
                set_last_error(error);
                return -2;
            }
        };

        let code = runtime.block_on(async {
            tokio::select! {
                result = crate::start(options) => match result {
                    Ok(()) => 0,
                    Err(error) => {
                        set_last_error(error);
                        -2
                    }
                },
                _ = wait_for_stop() => 0,
            }
        });
        // Bounded teardown: never let a stuck blocking task keep RUNNING set.
        runtime.shutdown_timeout(RUNTIME_SHUTDOWN_GRACE);
        code
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            set_last_error("panic in Aether native core");
            -3
        }
    }
}

/// Loads or provisions account identity and stores JSON addresses in last_result.
#[no_mangle]
pub unsafe extern "C" fn aether_prepare_json(json: *const c_char) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let options = match unsafe { options_from_json(json) } {
            Ok(options) => options,
            Err(error) => {
                set_last_error(error);
                return -1;
            }
        };
        PREPARE_ACTIVE.fetch_add(1, Ordering::AcqRel);
        let _active = PrepareGuard;
        PREPARE_CANCEL.store(false, Ordering::Release);
        let runtime = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime,
            Err(error) => {
                set_last_error(error);
                return -2;
            }
        };

        let outcome = runtime.block_on(async {
            tokio::select! {
                result = crate::prepare(&options) => Some(result),
                _ = wait_for_prepare_cancel() => None,
            }
        });
        runtime.shutdown_timeout(RUNTIME_SHUTDOWN_GRACE);
        let Some(outcome) = outcome else {
            record_log("Key: request cancelled because the connection was stopped");
            set_last_error("cancelled: connection stopped");
            return -4;
        };
        match outcome {
            Ok(TunnelAddresses {
                ipv4,
                ipv6,
                gateway_proxy,
                organization,
            }) => {
                set_last_result(serde_json::json!({
                    "ipv4": ipv4,
                    "ipv6": ipv6,
                    "gateway_proxy": gateway_proxy,
                    "organization": organization,
                }));
                0
            }
            Err(error) => {
                set_last_error(error);
                -2
            }
        }
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            set_last_error("panic in Aether native core");
            -3
        }
    }
}

unsafe fn required_text(value: *const c_char, name: &str) -> Result<String, String> {
    if value.is_null() {
        return Err(format!("{name} is required"));
    }
    let value = unsafe { CStr::from_ptr(value) }
        .to_str()
        .map_err(|error| format!("{name} is not UTF-8: {error}"))?
        .trim()
        .to_string();
    if value.is_empty() {
        Err(format!("{name} is required"))
    } else {
        Ok(value)
    }
}

#[no_mangle]
pub unsafe extern "C" fn aether_zt_request_email_code(
    team: *const c_char,
    email: *const c_char,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let team = match unsafe { required_text(team, "team") } {
            Ok(value) => value,
            Err(error) => {
                set_last_error(error);
                return -1;
            }
        };
        let email = match unsafe { required_text(email, "email") } {
            Ok(value) => value,
            Err(error) => {
                set_last_error(error);
                return -1;
            }
        };
        let runtime = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime,
            Err(error) => {
                set_last_error(error);
                return -2;
            }
        };
        match runtime.block_on(crate::zerotrust::request_email_code(&team, &email)) {
            Ok(()) => {
                set_last_error("");
                0
            }
            Err(error) => {
                set_last_error(error);
                -2
            }
        }
    }));
    result.unwrap_or_else(|_| {
        set_last_error("panic while requesting the Zero Trust email code");
        -3
    })
}

#[no_mangle]
pub unsafe extern "C" fn aether_zt_confirm_email_code(code: *const c_char) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let code = match unsafe { required_text(code, "code") } {
            Ok(value) => value,
            Err(error) => {
                set_last_error(error);
                return -1;
            }
        };
        let runtime = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime,
            Err(error) => {
                set_last_error(error);
                return -2;
            }
        };
        match runtime.block_on(crate::zerotrust::confirm_email_code(&code)) {
            Ok(token) => {
                set_last_result(serde_json::json!({ "token": token }));
                set_last_error("");
                0
            }
            Err(error) => {
                set_last_error(error);
                -2
            }
        }
    }));
    result.unwrap_or_else(|_| {
        set_last_error("panic while confirming the Zero Trust email code");
        -3
    })
}

/// Returns the most recent native error. Copy it before another native call.
#[no_mangle]
pub extern "C" fn aether_last_error() -> *const c_char {
    LAST_ERROR.lock().unwrap().as_ptr()
}

#[no_mangle]
pub extern "C" fn aether_last_result() -> *const c_char {
    LAST_RESULT.lock().unwrap().as_ptr()
}

#[no_mangle]
pub extern "C" fn aether_last_log() -> *const c_char {
    LAST_LOG.lock().unwrap().as_ptr()
}

#[no_mangle]
pub extern "C" fn aether_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr().cast()
}

#[no_mangle]
pub extern "C" fn aether_set_socket_protector(protector: Option<platform::SocketProtector>) {
    platform::set_socket_protector(protector);
}

/// Requests shutdown of the active native tunnel. Returns 1 if running, else 0.
#[no_mangle]
pub extern "C" fn aether_stop() -> i32 {
    if !RUNNING.load(Ordering::Acquire) {
        return 0;
    }

    STOP_REQUESTED.store(true, Ordering::Release);
    stop_notify().notify_waiters();
    1
}

/// Aborts an in-flight `aether_prepare_json` (key race, certificate renewal).
/// `aether_stop` only reaches a RUNNING tunnel, and prepare runs before that,
/// so a disconnect during key/cert work used to wait for it to finish.
/// Returns 1 if a prepare was in flight, else 0.
#[no_mangle]
pub extern "C" fn aether_cancel_prepare() -> i32 {
    if PREPARE_ACTIVE.load(Ordering::Acquire) == 0 {
        return 0;
    }
    PREPARE_CANCEL.store(true, Ordering::Release);
    prepare_notify().notify_waiters();
    1
}

#[no_mangle]
pub extern "C" fn aether_is_running() -> i32 {
    i32::from(RUNNING.load(Ordering::Acquire))
}

#[no_mangle]
pub extern "C" fn aether_is_ready() -> i32 {
    i32::from(READY.load(Ordering::Acquire))
}

pub(crate) fn mark_ready() {
    READY.store(true, Ordering::Release);
    emit_status("connected", None);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_android_start_options() {
        let native: NativeStartOptions = serde_json::from_str(
            r#"{"config_path":"/data/user/0/app/files/aether.toml","protocol":"masque","masque_transport":"h2"}"#,
        )
        .unwrap();
        let options = StartOptions::try_from(native).unwrap();

        assert_eq!(options.protocol, Protocol::Masque);
        assert_eq!(options.masque_transport, MasqueTransport::H2);
        assert_eq!(options.listen, "127.0.0.1:1819".parse().unwrap());
        assert_eq!(options.scan_mode, ScanMode::Balanced);
        assert!(options.scan_once);
        assert_eq!(options.tls_curve_preset, TlsCurvePreset::Chrome);
        assert!(options.wireguard_data_check);
    }

    #[test]
    fn parses_advanced_android_start_options() {
        let native: NativeStartOptions = serde_json::from_str(
            r#"{"config_path":"aether.toml","tls_curve_preset":"compatibility","wireguard_data_check":false,"log_level":"debug","perf_profile":"high","h2_fragmentation":true,"dns_servers":"9.9.9.9","route_block":"ads.example","team":"acme","gateway":true}"#,
        )
        .unwrap();
        let options = StartOptions::try_from(native).unwrap();

        assert_eq!(options.tls_curve_preset, TlsCurvePreset::Compatibility);
        assert!(!options.wireguard_data_check);
        assert_eq!(options.log_level.as_deref(), Some("debug"));
        assert_eq!(options.perf_profile.as_deref(), Some("high"));
        assert_eq!(options.h2_fragmentation, Some(true));
        assert_eq!(options.dns_servers.as_deref(), Some("9.9.9.9"));
        assert_eq!(options.route_block.as_deref(), Some("ads.example"));
        assert_eq!(options.team.as_deref(), Some("acme"));
        assert!(options.gateway);
    }

    #[test]
    fn rejects_missing_config_path() {
        let native: NativeStartOptions = serde_json::from_str("{}").unwrap();
        assert!(StartOptions::try_from(native).is_err());
    }
}
