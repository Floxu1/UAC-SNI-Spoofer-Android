use std::net::IpAddr;
use std::net::Ipv4Addr;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use boring::pkey::PKey;
use boring::ssl::{SslConnector, SslMethod, SslVersion};
use boring::x509::X509;
use bytes::Bytes;
use futures::FutureExt;
use http::Method;
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{mpsc, oneshot};

use crate::consts;
use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};
use crate::masque::{self, Capsule, CapsuleParser};
use crate::quic::{AssignedAddr, Control, Internals};
use crate::tls;

const H2_ALPN: &[u8] = b"\x02h2";
const CHROME_GROUPS: &str = "P-256:X25519:P-384";
static H2_FALLBACK: AtomicBool = AtomicBool::new(false);
static H2_PREFERRED: AtomicBool = AtomicBool::new(false);
static H2_EXTENDED: AtomicBool = AtomicBool::new(false);

// HTTP/2 flow-control windows for the single CONNECT-IP stream. The h2 crate
// defaults to 65,535 bytes for both the stream and the connection, which caps
// the whole tunnel's download at ~64 KB per round trip (~5 Mbps at 100 ms).
const H2_STREAM_WINDOW: u32 = 8 * 1024 * 1024;
const H2_CONNECTION_WINDOW: u32 = 16 * 1024 * 1024;
const H2_MAX_SEND_BUFFER: usize = 1024 * 1024;

// Smaller windows when the app turns "large HTTP/2 windows" off (less memory).
const H2_STREAM_WINDOW_SMALL: u32 = 1024 * 1024;
const H2_CONNECTION_WINDOW_SMALL: u32 = 4 * 1024 * 1024;
/// SO_SNDBUF / SO_RCVBUF asked for on the outer TCP socket when socket tuning is on.
const H2_SOCKET_BUFFER: u32 = 1024 * 1024;

fn env_flag(key: &str, default: bool) -> bool {
    match std::env::var(key) {
        Ok(v) => matches!(v.trim().to_ascii_lowercase().as_str(), "1" | "true" | "yes" | "on"),
        Err(_) => default,
    }
}

/// `AETHER_MASQUE_H2_LARGE_WINDOWS` (default on): 16 MB connection / 8 MB stream.
pub(crate) fn large_windows() -> bool {
    env_flag("AETHER_MASQUE_H2_LARGE_WINDOWS", true)
}

/// `AETHER_MASQUE_H2_SOCKET_TUNING` (default on): TCP_NODELAY + 1 MB socket buffers.
pub(crate) fn socket_tuning() -> bool {
    env_flag("AETHER_MASQUE_H2_SOCKET_TUNING", true)
}

/// `AETHER_MASQUE_H2_SPEED_PICK` (default on): race the top saved clean
/// addresses and keep the quickest instead of the first that answers.
pub(crate) fn speed_pick() -> bool {
    env_flag("AETHER_MASQUE_H2_SPEED_PICK", true)
}

fn h2_builder() -> h2::client::Builder {
    let (stream, connection) = if large_windows() {
        (H2_STREAM_WINDOW, H2_CONNECTION_WINDOW)
    } else {
        (H2_STREAM_WINDOW_SMALL, H2_CONNECTION_WINDOW_SMALL)
    };
    let mut builder = h2::client::Builder::new();
    builder
        .initial_window_size(stream)
        .initial_connection_window_size(connection)
        .max_send_buffer_size(H2_MAX_SEND_BUFFER);
    builder
}
struct AbortOnDrop(tokio::task::JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

fn h2_keepalive_interval() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_H2_KEEPALIVE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(15);
    Duration::from_secs(secs)
}

fn h2_keepalive_timeout() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(20);
    Duration::from_secs(secs)
}

async fn connect_tcp(peer: SocketAddr) -> Result<TcpStream> {
    let socket = if peer.is_ipv4() {
        TcpSocket::new_v4()
    } else {
        TcpSocket::new_v6()
    }
    .map_err(AetherError::Io)?;
    crate::platform::protect_socket(&socket).map_err(AetherError::Io)?;
    if socket_tuning() {
        // Best effort: the kernel may clamp these; failure is not fatal.
        let _ = socket.set_send_buffer_size(H2_SOCKET_BUFFER);
        let _ = socket.set_recv_buffer_size(H2_SOCKET_BUFFER);
        let _ = socket.set_nodelay(true);
    }
    // Bind unspecified so Android does not pick the VPN address (172.16.0.2)
    // as the source after the TUN interface is already up.
    let bind = if peer.is_ipv4() {
        "0.0.0.0:0".parse().unwrap()
    } else {
        "[::]:0".parse().unwrap()
    };
    socket.bind(bind).map_err(AetherError::Io)?;
    socket.connect(peer).await.map_err(AetherError::Io)
}

pub struct H2TunnelConfig {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub local_ipv4: Ipv4Addr,
    pub quiet: bool,
    pub pin_endpoint: bool,
    pub expected_pins: Vec<Vec<u8>>,
}

pub fn enabled() -> bool {
    if H2_PREFERRED.load(Ordering::Acquire) || H2_FALLBACK.load(Ordering::Acquire) {
        return true;
    }
    match std::env::var("AETHER_MASQUE_HTTP2") {
        Ok(v) => {
            let v = v.trim().to_lowercase();
            v == "1" || v == "true" || v == "h2" || v == "yes" || v == "on"
        }
        Err(_) => false,
    }
}

pub fn set_preferred(enabled: bool) {
    H2_PREFERRED.store(enabled, Ordering::Release);
    H2_FALLBACK.store(false, Ordering::Release);
}

pub fn use_extended_connect(enabled: bool) {
    H2_EXTENDED.store(enabled, Ordering::Release);
}

fn extended_connect() -> bool {
    H2_EXTENDED.load(Ordering::Acquire)
}

pub fn enable_fallback() {
    H2_FALLBACK.store(true, Ordering::Release);
}

/// Undo [enable_fallback], leaving an explicit user choice alone.
///
/// The fallback used to be a one-way door: once an HTTP/3 scan came up empty the
/// flag stayed set for the life of the process, so every later reconnect went
/// straight to HTTP/2 and never asked UDP again. On a carrier that drops UDP/443
/// but leaves other UDP ports open that is the difference between connecting and
/// never connecting — the field log from Irancell showed nine minutes of HTTP/2
/// scanning after a single failed HTTP/3 pass, while plain WireGuard on the same
/// network worked immediately.
///
/// `H2_PREFERRED` is deliberately untouched: that is the user pinning HTTP/2 in
/// settings, not a fallback we chose for them.
pub fn clear_fallback() {
    H2_FALLBACK.store(false, Ordering::Release);
}

pub fn h2_peer(quic_peer: SocketAddr) -> SocketAddr {
    if let Ok(v) = std::env::var("AETHER_MASQUE_H2_PEER") {
        if let Ok(addr) = v.trim().parse::<SocketAddr>() {
            return addr;
        }
    }
    quic_peer
}

fn log_or_debug(quiet: bool, msg: String) {
    if quiet {
        log::debug!("{msg}");
    } else {
        log::info!("{msg}");
    }
}

fn data_check_enabled_for(no_data_check: Option<&str>) -> bool {
    no_data_check.is_none()
}

fn data_check_enabled() -> bool {
    data_check_enabled_for(std::env::var("AETHER_MASQUE_NO_DATA_CHECK").ok().as_deref())
}

fn validation_timeout() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_VALIDATE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(10);
    Duration::from_secs(secs)
}

const DATA_PROBE_REQUIRED_SUCCESSES: u32 = 2;

fn build_tls(cfg: &H2TunnelConfig) -> Result<boring::ssl::ConnectConfiguration> {
    let mut builder =
        SslConnector::builder(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;

    builder
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_max_proto_version(Some(SslVersion::TLS1_3))
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    builder.set_grease_enabled(true);
    // Chrome's TLS 1.2 selection, the fingerprint PattNG sends on MASQUE.
    builder
        .set_cipher_list("ALL:!aPSK:!ECDSA+SHA1:!3DES")
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let groups = std::env::var("AETHER_TLS_GROUPS").ok();
    let groups = groups
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(CHROME_GROUPS);
    builder
        .set_curves_list(groups)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    builder
        .set_alpn_protos(H2_ALPN)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let cert = X509::from_pem(&cfg.cert_pem).map_err(|e| AetherError::Tls(e.to_string()))?;
    let key =
        PKey::private_key_from_pem(&cfg.key_pem).map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_certificate(&cert)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_private_key(&key)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let pin_refs: Vec<&[u8]> = cfg.expected_pins.iter().map(|p| p.as_slice()).collect();
    tls::install_verification(&mut *builder, cfg.pin_endpoint, &pin_refs)?;

    let connector = builder.build();
    let mut config = connector
        .configure()
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let use_pin_verification = cfg.pin_endpoint && !cfg.expected_pins.is_empty();
    config.set_verify_hostname(!use_pin_verification);
    config.set_use_server_name_indication(true);

    Ok(config)
}

/// PattNG's HTTP/2 carrier: a plain CONNECT to `authority:443` with
/// `cf-connect-proto`, not an extended CONNECT. Extended CONNECT (`:protocol`)
/// is what the public edge answers 400 to. The h2 crate omits `:scheme` and
/// `:path` for this shape, which is what that carrier sends.
fn authority_host(cfg: &H2TunnelConfig) -> String {
    let host = cfg.authority.trim();
    if host.contains(':') {
        host.to_string()
    } else {
        format!("{host}:443")
    }
}

fn build_connect_request(cfg: &H2TunnelConfig) -> Result<http::Request<()>> {
    build_connect_request_shape(cfg, extended_connect())
}

fn build_connect_request_shape(cfg: &H2TunnelConfig, extended: bool) -> Result<http::Request<()>> {
    let authority = authority_host(cfg);
    if !extended {
        let uri = format!("https://{authority}");
        return http::Request::builder()
            .method(Method::CONNECT)
            .uri(uri)
            .header("cf-connect-proto", consts::CF_CONNECT_PROTOCOL)
            .header("pq-enabled", "false")
            .header("user-agent", "")
            .body(())
            .map_err(|e| AetherError::Masque(format!("build request: {e}")));
    }
    let host = cfg.authority.trim().trim_end_matches(":443");
    let path = if cfg.path.starts_with('/') {
        cfg.path.clone()
    } else {
        format!("/{}", cfg.path)
    };
    let uri = format!("https://{host}{path}");
    let mut request = http::Request::builder()
        .method(Method::CONNECT)
        .uri(uri)
        .header("capsule-protocol", "?1")
        .header("user-agent", "")
        .body(())
        .map_err(|e| AetherError::Masque(format!("build request: {e}")))?;
    request
        .extensions_mut()
        .insert(h2::ext::Protocol::from_static(consts::CF_CONNECT_PROTOCOL));
    Ok(request)
}

/// Test-only accessor for [`build_connect_request`].
///
/// The header shape is the entire fix for the `status 400` failures, so it is
/// asserted from `main.rs`'s test module rather than left uncovered.
#[doc(hidden)]
pub fn connect_request_for_test(cfg: &H2TunnelConfig) -> Result<http::Request<()>> {
    build_connect_request(cfg)
}

/// Irancell completes a fragmented ClientHello and rejects ECH. MCI completes a
/// whole ClientHello for www.cloudflare.com and drops the fragmented one. Race
/// both, and do not require ECH.
async fn connect_tls(
    cfg: &H2TunnelConfig,
) -> Result<tokio_boring::SslStream<FragmentingStream<TcpStream>>> {
    let fragmented = FragmentConfig::from_env();
    if !fragmented.enabled {
        return connect_tls_once(cfg, FragmentConfig::disabled()).await;
    }
    let plain = connect_tls_once(cfg, FragmentConfig::disabled());
    let split = connect_tls_once(cfg, fragmented);
    tokio::pin!(plain);
    tokio::pin!(split);
    let mut plain_err: Option<AetherError> = None;
    let mut split_err: Option<AetherError> = None;
    loop {
        tokio::select! {
            biased;
            result = &mut plain, if plain_err.is_none() => match result {
                Ok(tls) => return Ok(tls),
                Err(error) => plain_err = Some(error),
            },
            result = &mut split, if split_err.is_none() => match result {
                Ok(tls) => return Ok(tls),
                Err(error) => split_err = Some(error),
            },
            else => break,
        }
    }
    Err(plain_err.or(split_err).unwrap_or_else(|| {
        AetherError::Tls("h2 tls handshake: no attempt".into())
    }))
}

async fn connect_tls_once(
    cfg: &H2TunnelConfig,
    fragment: FragmentConfig,
) -> Result<tokio_boring::SslStream<FragmentingStream<TcpStream>>> {
    let tls_config = build_tls(cfg)?;
    let tcp = connect_tcp(cfg.peer).await?;
    let _ = tcp.set_nodelay(socket_tuning());
    let stream = FragmentingStream::new(tcp, fragment);
    match tokio::time::timeout(
        Duration::from_secs(8),
        tokio_boring::connect(tls_config, &cfg.sni, stream),
    )
    .await
    {
        Ok(Ok(tls)) => Ok(tls),
        Ok(Err(error)) => Err(AetherError::Tls(format!("h2 tls handshake: {error}"))),
        Err(_) => Err(AetherError::Tls(format!(
            "h2 tls handshake with {} timed out{}",
            cfg.peer,
            if fragment.enabled { " (fragmented)" } else { "" }
        ))),
    }
}

async fn response_snippet(response: &mut http::Response<h2::RecvStream>) -> String {
    let body = response.body_mut();
    let chunk = match tokio::time::timeout(Duration::from_millis(700), body.data()).await {
        Ok(Some(Ok(bytes))) => bytes,
        _ => return String::new(),
    };
    let text: String = chunk
        .iter()
        .take(96)
        .map(|byte| {
            let c = *byte as char;
            if c.is_ascii_graphic() || c == ' ' {
                c
            } else {
                ' '
            }
        })
        .collect();
    let _ = body.flow_control().release_capacity(chunk.len());
    text.trim().to_string()
}

pub async fn verify_h2(cfg: &H2TunnelConfig, timeout: Duration) -> Result<Duration> {
    verify_h2_shape(cfg, timeout, false).await
}

pub async fn verify_h2_extended(cfg: &H2TunnelConfig, timeout: Duration) -> Result<Duration> {
    verify_h2_shape(cfg, timeout, true).await
}

async fn verify_h2_shape(
    cfg: &H2TunnelConfig,
    timeout: Duration,
    extended: bool,
) -> Result<Duration> {
    let start = Instant::now();
    let data_check = data_check_enabled();

    let attempt = async {
        let tls = connect_tls(cfg).await?;
        let builder = h2_builder();
        let (h2, connection) = builder.handshake(tls)
            .await
            .map_err(|e| AetherError::Masque(format!("h2 handshake: {e}")))?;
        let driver = tokio::spawn(async move {
            let _ = connection.await;
        });
        let mut h2 = h2
            .ready()
            .await
            .map_err(|e| AetherError::Masque(format!("h2 ready: {e}")))?;
        let req = build_connect_request_shape(cfg, extended)?;
        let (resp_fut, mut send_stream) = h2
            .send_request(req, false)
            .map_err(|e| AetherError::Masque(format!("send_request: {e}")))?;
        let mut response = resp_fut
            .await
            .map_err(|e| AetherError::Masque(format!("await response: {e}")))?;
        let status = response.status();
        if !status.is_success() {
            let detail = response_snippet(&mut response).await;
            driver.abort();
            return Err(AetherError::Masque(format!(
                "h2 connect-ip status {} {detail}",
                status.as_u16()
            )));
        }

        if !data_check {
            driver.abort();
            return Ok(());
        }

        let mut recv_body = response.into_body();
        let mut capsules = CapsuleParser::new();
        let probe = masque::build_dns_probe_packet(cfg.local_ipv4);
        let framed = masque::encode_datagram_capsule(&probe);
        if let Err(e) = send_capsule(&mut send_stream, Bytes::from(framed)).await {
            driver.abort();
            return Err(e);
        }

        let mut probe_successes: u32 = 0;

        loop {
            match futures::future::poll_fn(|cx| recv_body.poll_data(cx)).await {
                Some(Ok(chunk)) => {
                    let _ = recv_body.flow_control().release_capacity(chunk.len());
                    capsules.push(&chunk);
                    loop {
                        match capsules.next() {
                            Ok(Some(Capsule::Datagram(_))) => {
                                probe_successes += 1;
                                if probe_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                                    driver.abort();
                                    return Ok(());
                                }
                                let framed = masque::encode_datagram_capsule(&probe);
                                if let Err(e) =
                                    send_capsule(&mut send_stream, Bytes::from(framed)).await
                                {
                                    driver.abort();
                                    return Err(e);
                                }
                            }
                            Ok(Some(_)) => continue,
                            Ok(None) => break,
                            Err(_) => break,
                        }
                    }
                }
                Some(Err(e)) => {
                    driver.abort();
                    return Err(AetherError::Masque(format!("h2 body: {e}")));
                }
                None => {
                    driver.abort();
                    return Err(AetherError::Masque("h2 stream closed before data".into()));
                }
            }
        }
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(Ok(())) => Ok(start.elapsed()),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AetherError::Other("h2 verify timeout".into())),
    }
}

pub async fn run(
    cfg: H2TunnelConfig,
    internals: Internals,
    addr_tx: Option<mpsc::Sender<AssignedAddr>>,
    ready_tx: Option<oneshot::Sender<()>>,
) -> Result<()> {
    let (outbound_rx, inbound_tx, mut ctrl_rx) = internals.into_parts();
    let quiet = cfg.quiet;
    let data_check = data_check_enabled();
    let probe_packet = masque::build_dns_probe_packet(cfg.local_ipv4);
    let mut ready_tx = ready_tx;
    let mut ready_fired = false;
    let mut validate_successes: u32 = 0;

    log_or_debug(quiet, format!("[h2] connecting tcp to {}", cfg.peer));
    let tls = connect_tls(&cfg).await?;
    log_or_debug(
        quiet,
        format!(
            "[h2] tls established; alpn={}",
            String::from_utf8_lossy(tls.ssl().selected_alpn_protocol().unwrap_or(b""))
        ),
    );

    let builder = h2_builder();
    let (h2, mut connection) = builder.handshake(tls)
        .await
        .map_err(|e| AetherError::Masque(format!("h2 handshake: {e}")))?;

    let mut ping_pong = connection
        .ping_pong()
        .ok_or_else(|| AetherError::Masque("h2 connection does not support ping".into()))?;

    let driver_handle = tokio::spawn(async move {
        if let Err(e) = connection.await {
            log::debug!("[h2] connection driver ended: {e}");
        }
    });
    let _driver_guard = AbortOnDrop(driver_handle);

    let mut h2 = h2
        .ready()
        .await
        .map_err(|e| AetherError::Masque(format!("h2 ready: {e}")))?;

    let req = build_connect_request(&cfg)?;

    let (resp_fut, mut send_stream) = h2
        .send_request(req, false)
        .map_err(|e| AetherError::Masque(format!("send_request: {e}")))?;
    log_or_debug(
        quiet,
        format!("[h2] connect-ip request sent to {}", cfg.authority),
    );

    let response = resp_fut
        .await
        .map_err(|e| AetherError::Masque(format!("await response: {e}")))?;
    let status = response.status();
    log_or_debug(
        quiet,
        format!("[h2] connect-ip status: {}", status.as_u16()),
    );
    if !status.is_success() {
        return Err(AetherError::Masque(format!(
            "h2 connect-ip status {}",
            status.as_u16()
        )));
    }
    let mut recv_body = response.into_body();
    let mut capsules = CapsuleParser::new();

    let mut validate_deadline: Option<Instant> = None;
    if data_check {
        let framed = masque::encode_datagram_capsule(&probe_packet);
        if let Err(e) = send_capsule(&mut send_stream, Bytes::from(framed)).await {
            log::debug!("[h2] initial data-plane probe: {e}");
        }
        validate_deadline = Some(Instant::now() + validation_timeout());
        log_or_debug(
            quiet,
            "[h2] validating data-plane (end-to-end probe) before exposing socks5".to_string(),
        );
    } else if !ready_fired {
        ready_fired = true;
        crate::ffi::mark_ready();
        if let Some(tx) = ready_tx.take() {
            let _ = tx.send(());
        }
    }

    let mut probe_interval = tokio::time::interval(Duration::from_millis(700));
    probe_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let keepalive_period = h2_keepalive_interval();
    let mut keepalive_interval = tokio::time::interval(keepalive_period);
    keepalive_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut awaiting_pong = false;
    let mut pong_deadline: Option<Instant> = None;
    let keepalive_timeout = h2_keepalive_timeout();

    // Sending lives in its own task. Waiting for HTTP/2 send window used to
    // happen inside a select arm, which froze the receive side for as long as
    // the upload was window-limited, so an upload stalled the download.
    let (send_cmd_tx, send_cmd_rx) = mpsc::channel::<SendCmd>(SEND_CMD_QUEUE);
    let (sender_done_tx, mut sender_done) = oneshot::channel::<Result<()>>();
    let sender_handle = tokio::spawn(async move {
        let result = sender_loop(send_stream, outbound_rx, send_cmd_rx).await;
        let _ = sender_done_tx.send(result);
    });
    let _sender_guard = AbortOnDrop(sender_handle);

    loop {
        if data_check && !ready_fired {
            if let Some(dl) = validate_deadline {
                if Instant::now() >= dl {
                    log::warn!(
                        "[h2] data-plane validation timed out; edge accepts control but drops traffic"
                    );
                    close_sender(&send_cmd_tx, &mut sender_done).await;
                    return Err(AetherError::Masque(
                        "h2 data-plane validation timeout (handshake ok, no traffic)".into(),
                    ));
                }
            }
        }

        if let Some(dl) = pong_deadline {
            if Instant::now() >= dl {
                let message = format!(
                    "HTTP/2 keepalive PONG missed after {:?}; retaining active tunnel",
                    keepalive_timeout
                );
                log::warn!("[h2] {message}");
                crate::ffi::record_log(message);
                // Cloudflare edges can carry CONNECT-IP datagrams while silently dropping
                // HTTP/2 PING frames.  Traffic and the app health check are authoritative.
                awaiting_pong = false;
                pong_deadline = None;
            }
        }

        // Not `biased`: every arm gets a fair turn, so a steady stream of
        // outbound packets (or ACKs) can no longer starve the receive arm.
        tokio::select! {
            _ = keepalive_interval.tick(), if ready_fired && !awaiting_pong => {
                match ping_pong.send_ping(h2::Ping::opaque()) {
                    Ok(()) => {
                        awaiting_pong = true;
                        pong_deadline = Some(Instant::now() + keepalive_timeout);
                        log::debug!("[h2] keepalive ping sent");
                    }
                    Err(e) => log::debug!("[h2] keepalive ping send failed: {e}"),
                }
            }

            pong = std::future::poll_fn(|cx| ping_pong.poll_pong(cx)), if awaiting_pong => {
                match pong {
                    Ok(_) => {
                        awaiting_pong = false;
                        pong_deadline = None;
                        log::debug!("[h2] keepalive pong received");
                    }
                    Err(e) => {
                        let message = format!("HTTP/2 keepalive PONG failed: {e}; retaining active tunnel");
                        log::warn!("[h2] {message}");
                        crate::ffi::record_log(message);
                        awaiting_pong = false;
                        pong_deadline = None;
                    }
                }
            }

            _ = probe_interval.tick(), if data_check && !ready_fired => {
                let framed = masque::encode_datagram_capsule(&probe_packet);
                if let Err(e) = send_cmd_tx.try_send(SendCmd::Capsule(Bytes::from(framed))) {
                    log::trace!("[h2] data-plane probe resend: {e}");
                }
            }

            ctrl = ctrl_rx.recv() => {
                match ctrl {
                    Some(Control::Close) | None => {
                        close_sender(&send_cmd_tx, &mut sender_done).await;
                        log_or_debug(quiet, "[h2] closing tunnel".to_string());
                        return Ok(());
                    }
                    Some(Control::Migrate) => {}
                }
            }

            done = &mut sender_done => {
                return match done {
                    Ok(Ok(())) => Ok(()),
                    Ok(Err(e)) => {
                        log::debug!("[h2] send: {e}");
                        Err(e)
                    }
                    Err(_) => Err(AetherError::Masque("h2 sender stopped".into())),
                };
            }

            data = futures::future::poll_fn(|cx| recv_body.poll_data(cx)) => {
                // Take whatever else is already buffered (up to PACKET_BATCH
                // chunks) before going back to select.
                let mut next = data;
                let mut ended: Option<Result<()>> = None;
                let mut taken = 0usize;
                loop {
                    match next {
                        Some(Ok(chunk)) => {
                            let _ = recv_body.flow_control().release_capacity(chunk.len());
                            capsules.push(&chunk);
                        }
                        Some(Err(e)) => {
                            log::warn!("[h2] recv body error: {e}");
                            ended = Some(Err(AetherError::Masque(format!("h2 body: {e}"))));
                            break;
                        }
                        None => {
                            log_or_debug(quiet, "[h2] server closed stream".to_string());
                            ended = Some(Ok(()));
                            break;
                        }
                    }
                    taken += 1;
                    if taken >= PACKET_BATCH {
                        break;
                    }
                    match futures::future::poll_fn(|cx| recv_body.poll_data(cx)).now_or_never() {
                        Some(more) => next = more,
                        None => break,
                    }
                }
                let got_data = drain_capsules(&mut capsules, &inbound_tx, &addr_tx);
                if got_data && !ready_fired {
                    validate_successes += 1;
                    log::debug!(
                        "[h2] data-plane round-trip {}/{} confirmed",
                        validate_successes, DATA_PROBE_REQUIRED_SUCCESSES
                    );
                    if validate_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                        ready_fired = true;
                        validate_deadline = None;
                        crate::ffi::mark_ready();
                        if let Some(tx) = ready_tx.take() {
                            let _ = tx.send(());
                        }
                        log_or_debug(quiet, "[h2] tunnel validated (end-to-end data confirmed); exposing socks5".to_string());
                    } else {
                        let framed = masque::encode_datagram_capsule(&probe_packet);
                        if let Err(e) = send_cmd_tx.try_send(SendCmd::Capsule(Bytes::from(framed))) {
                            log::trace!("[h2] follow-up data-plane probe: {e}");
                        }
                    }
                }
                if let Some(end) = ended {
                    return end;
                }
            }
        }
    }
}

/// Packets/chunks handled per wake-up in each direction (same as quic.rs).
const PACKET_BATCH: usize = 32;
/// Upper bound for one coalesced DATA write. Capsules are a byte stream, so
/// several datagram capsules can share one send; staying at one default frame
/// keeps the capacity wait bounded even if the peer's window is small.
const SEND_COALESCE_BYTES: usize = 16 * 1024;
const SEND_CMD_QUEUE: usize = 64;

enum SendCmd {
    Capsule(Bytes),
    Close,
}

/// Owns the HTTP/2 send half: device packets (batched) and control capsules.
/// Ends the stream on Close or when the device side goes away.
async fn sender_loop(
    mut send_stream: h2::SendStream<Bytes>,
    mut outbound_rx: mpsc::Receiver<Vec<u8>>,
    mut cmd_rx: mpsc::Receiver<SendCmd>,
) -> Result<()> {
    let mut cmd_open = true;
    loop {
        tokio::select! {
            cmd = cmd_rx.recv(), if cmd_open => {
                match cmd {
                    Some(SendCmd::Capsule(data)) => {
                        if let Err(e) = send_capsule(&mut send_stream, data).await {
                            log::trace!("[h2] control capsule: {e}");
                        }
                    }
                    Some(SendCmd::Close) => {
                        let _ = send_stream.send_data(Bytes::new(), true);
                        return Ok(());
                    }
                    None => cmd_open = false,
                }
            }

            pkt = outbound_rx.recv() => {
                let first = match pkt {
                    Some(p) => p,
                    None => {
                        let _ = send_stream.send_data(Bytes::new(), true);
                        return Ok(());
                    }
                };
                let mut group: Vec<u8> = masque::encode_datagram_capsule(&first);
                for _ in 1..PACKET_BATCH {
                    let more = match outbound_rx.try_recv() {
                        Ok(p) => p,
                        Err(_) => break,
                    };
                    let framed = masque::encode_datagram_capsule(&more);
                    if group.len() + framed.len() > SEND_COALESCE_BYTES {
                        send_capsule(&mut send_stream, Bytes::from(std::mem::take(&mut group))).await?;
                    }
                    group.extend_from_slice(&framed);
                }
                send_capsule(&mut send_stream, Bytes::from(group)).await?;
            }
        }
    }
}

/// Ask the sender to end the stream and give it a moment to do so.
async fn close_sender(
    send_cmd_tx: &mpsc::Sender<SendCmd>,
    sender_done: &mut oneshot::Receiver<Result<()>>,
) {
    if send_cmd_tx.try_send(SendCmd::Close).is_ok() {
        let _ = tokio::time::timeout(Duration::from_millis(300), sender_done).await;
    }
}

async fn send_capsule(send: &mut h2::SendStream<Bytes>, data: Bytes) -> Result<()> {
    let len = data.len();
    if len == 0 {
        return Ok(());
    }

    send.reserve_capacity(len);
    while send.capacity() < len {
        match futures::future::poll_fn(|cx| send.poll_capacity(cx)).await {
            Some(Ok(_)) => {}
            Some(Err(e)) => return Err(AetherError::Masque(format!("h2 capacity: {e}"))),
            None => return Err(AetherError::Masque("h2 stream closed".into())),
        }
    }

    send.send_data(data, false)
        .map_err(|e| AetherError::Masque(format!("h2 send_data: {e}")))?;
    Ok(())
}

fn drain_capsules(
    capsules: &mut CapsuleParser,
    inbound_tx: &mpsc::Sender<Vec<u8>>,
    addr_tx: &Option<mpsc::Sender<AssignedAddr>>,
) -> bool {
    let mut delivered = false;
    loop {
        match capsules.next() {
            Ok(Some(Capsule::Datagram(payload))) => {
                let pkt = match masque::strip_datagram_context(&payload) {
                    Some(inner) => inner,
                    None => {
                        log::trace!("[h2] discarding a datagram that is not an ip packet");
                        continue;
                    }
                };
                delivered = true;
                match inbound_tx.try_send(pkt) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        log::trace!("[h2] inbound queue full, dropping datagram");
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => return delivered,
                }
            }
            Ok(Some(Capsule::AddressAssign(addrs))) => {
                for a in addrs {
                    if let Some(ip) = bytes_to_ip(a.ip_version, &a.address) {
                        log::info!("[h2] edge assigned {}/{}", ip, a.prefix_len);
                        if let Some(tx) = addr_tx {
                            let _ = tx.try_send(AssignedAddr {
                                ip,
                                prefix: a.prefix_len,
                            });
                        }
                    }
                }
            }
            Ok(Some(Capsule::RouteAdvertisement(routes))) => {
                log::info!("[h2] received {} route advertisements", routes.len());
            }
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(e) => {
                log::trace!("[h2] capsule parse: {e}");
                break;
            }
        }
    }
    delivered
}

fn bytes_to_ip(version: u8, bytes: &[u8]) -> Option<IpAddr> {
    match version {
        4 if bytes.len() == 4 => Some(IpAddr::V4([bytes[0], bytes[1], bytes[2], bytes[3]].into())),
        6 if bytes.len() == 16 => {
            let mut b = [0u8; 16];
            b.copy_from_slice(bytes);
            Some(IpAddr::V6(b.into()))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::data_check_enabled_for;
    use super::{clear_fallback, enable_fallback, enabled, set_preferred};

    #[test]
    fn h2_data_validation_is_on_by_default() {
        assert!(data_check_enabled_for(None));
        assert!(!data_check_enabled_for(Some("1")));
    }

    #[test]
    fn a_fallback_can_be_undone_but_the_users_own_choice_survives() {
        // One test, not two: these flags are process-globals and the test
        // harness runs threads in parallel, so splitting them would let the two
        // halves interleave.
        set_preferred(false);
        assert!(!enabled(), "no fallback and no preference means HTTP/3");
        enable_fallback();
        assert!(enabled());
        clear_fallback();
        assert!(!enabled(), "clearing the fallback must return us to HTTP/3");

        set_preferred(true);
        clear_fallback();
        assert!(enabled(), "a user who pinned HTTP/2 keeps HTTP/2");
        set_preferred(false);
    }
}
