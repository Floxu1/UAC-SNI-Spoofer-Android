use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use boring::ssl::{SslConnector, SslMethod, SslVersion};
use rand::Rng;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
#[cfg(target_os = "android")]
use tokio::net::TcpSocket;
use tokio::net::TcpStream;

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};

const EDGE_PREFIX: [u8; 3] = [141, 101, 113];
/// MCI answers 188.114.97.6. Irancell often does not, so that address is only
/// one member of the probe pool. The key request uses an address that accepted
/// a TCP connection, not this list in order.
const PINNED_KEY_EDGES: &[&str] = &[
    "188.114.97.6:443",
    "188.114.97.1:443",
    "162.159.199.1:443",
    "162.159.198.1:443",
];
const PROBE_TIMEOUT: Duration = Duration::from_millis(1200);
const SAMPLES_PER_RANGE: usize = 4;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 512 * 1024;

const LEGACY_CIPHERS: &str = "ECDHE-ECDSA-CHACHA20-POLY1305:\
ECDHE-ECDSA-AES128-GCM-SHA256:\
ECDHE-RSA-AES128-GCM-SHA256:\
ECDHE-ECDSA-AES256-SHA:\
ECDHE-RSA-AES128-SHA:\
AES256-SHA";

const LEGACY_GROUPS: &str = "X25519:P-256";
const MODERN_GROUPS: &str = "X25519:P-256:P-384";
const CHROME_GROUPS: &str = "P-256:X25519:P-384";

const ALPN_HTTP1: &[u8] = b"\x08http/1.1";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fingerprint {
    SplitLegacy,
    Modern,
    ChromeLike,
    /// OOB key route, C#-probe variant (SChannel SslProtocols.Tls12): TLS 1.2
    /// only, no ALPN (`AETHER_OOB_ALPN=1` offers http/1.1), no GREASE.
    Oob,
}

/// Whether the OOB route offers ALPN http/1.1. Default off (C# passes null).
fn oob_alpn_enabled() -> bool {
    matches!(
        std::env::var("AETHER_OOB_ALPN").ok().as_deref().map(str::trim),
        Some("1") | Some("true") | Some("yes") | Some("on")
    )
}

impl Fingerprint {
    pub fn label(self) -> &'static str {
        match self {
            Fingerprint::SplitLegacy => "split-tls12",
            Fingerprint::Modern => "plain-tls13",
            Fingerprint::ChromeLike => "chrome",
            Fingerprint::Oob => "oob-tls12",
        }
    }

    fn all() -> [Fingerprint; 3] {
        [
            Fingerprint::SplitLegacy,
            Fingerprint::Modern,
            Fingerprint::ChromeLike,
        ]
    }

    fn fragments(self) -> FragmentConfig {
        // PattNG --fragment splits the WARP API ClientHello on every fingerprint.
        // The legacy profile stays split even when that flag is off.
        let configured = FragmentConfig::from_env();
        if configured.enabled {
            return configured;
        }
        match self {
            Fingerprint::SplitLegacy => FragmentConfig {
                enabled: true,
                size_min: 24,
                size_max: 48,
                delay_min_ms: 2,
                delay_max_ms: 8,
            },
            _ => FragmentConfig::disabled(),
        }
    }


    fn configure(self) -> Result<boring::ssl::ConnectConfiguration> {
        let mut builder =
            SslConnector::builder(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;

        let tls = |error: boring::error::ErrorStack| AetherError::Tls(error.to_string());

        match self {
            Fingerprint::SplitLegacy => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_cipher_list(LEGACY_CIPHERS).map_err(tls)?;
                builder.set_curves_list(LEGACY_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
            }
            Fingerprint::Modern => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_curves_list(MODERN_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
            }
            Fingerprint::ChromeLike => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(true);
                builder.set_permute_extensions(true);
                builder.set_curves_list(CHROME_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
                builder.enable_signed_cert_timestamps();
                builder.enable_ocsp_stapling();
            }
            Fingerprint::Oob => {
                // Match the C# SslStream probe: TLS 1.2 (default), SChannel's
                // broad default cipher set (left untouched), modern curves, no
                // GREASE, no ALPN unless asked. The ClientHello stays small and
                // ordinary, like the probe that got a key on AS48944.
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_curves_list(MODERN_GROUPS).map_err(tls)?;
                if oob_alpn_enabled() {
                    builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
                }
            }
        }

        builder.build().configure().map_err(tls)
    }
}

#[derive(Debug, Clone)]
pub struct ApiRequest {
    pub method: String,
    pub host: String,
    pub path: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct ApiResponse {
    pub status: u16,
    pub body: String,
    pub route: String,
}

pub fn random_edge_address() -> SocketAddr {
    let host = rand::thread_rng().gen_range(1..=254u8);
    let ip = Ipv4Addr::new(EDGE_PREFIX[0], EDGE_PREFIX[1], EDGE_PREFIX[2], host);
    SocketAddr::new(IpAddr::V4(ip), 443)
}

fn random_in(first: u8, second: u8, third_from: u8, third_to: u8) -> SocketAddr {
    let mut rng = rand::thread_rng();
    let third = rng.gen_range(third_from..=third_to);
    let fourth = rng.gen_range(1..=254u8);
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(first, second, third, fourth)), 443)
}

fn local_key_edge_pool() -> Vec<SocketAddr> {
    let mut list = pinned_key_edges();
    for _ in 0..SAMPLES_PER_RANGE {
        for candidate in [
            random_in(162, 159, 192, 199),
            random_in(188, 114, 96, 99),
            random_edge_address(),
        ] {
            if !list.contains(&candidate) {
                list.push(candidate);
            }
        }
    }
    list
}

fn order_answered(live: Vec<SocketAddr>) -> Vec<SocketAddr> {
    let pinned = pinned_key_edges();
    let mut preferred = Vec::new();
    let mut rest = Vec::new();
    for address in live {
        if pinned.contains(&address) {
            preferred.push(address);
        } else {
            rest.push(address);
        }
    }
    preferred.extend(rest);
    preferred
}

async fn tcp_answers(address: SocketAddr) -> Option<SocketAddr> {
    #[cfg(target_os = "android")]
    {
        let socket = if address.is_ipv4() {
            TcpSocket::new_v4().ok()?
        } else {
            TcpSocket::new_v6().ok()?
        };
        crate::platform::protect_socket(&socket).ok()?;
        let bind: SocketAddr = if address.is_ipv4() {
            "0.0.0.0:0".parse().unwrap()
        } else {
            "[::]:0".parse().unwrap()
        };
        socket.bind(bind).ok()?;
        match tokio::time::timeout(PROBE_TIMEOUT, socket.connect(address)).await {
            Ok(Ok(stream)) => {
                drop(stream);
                Some(address)
            }
            _ => None,
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        match tokio::time::timeout(PROBE_TIMEOUT, TcpStream::connect(address)).await {
            Ok(Ok(stream)) => {
                drop(stream);
                Some(address)
            }
            _ => None,
        }
    }
}

/// Find an edge that accepts TCP before any key request is sent.
///
/// The pinned MCI address is tried together with other Cloudflare ranges.
/// An address that does not answer within the probe is not used for the key.
async fn reachable_key_edges() -> Vec<SocketAddr> {
    let pool = local_key_edge_pool();
    note_key(&format!(
        "probing {} addresses for a clean key edge",
        pool.len()
    ));
    let mut pending = tokio::task::JoinSet::new();
    for address in pool.iter().copied() {
        pending.spawn(async move { tcp_answers(address).await });
    }
    let mut live = Vec::new();
    while let Some(joined) = pending.join_next().await {
        if let Ok(Some(address)) = joined {
            note_key(&format!("clean key address {address} answered"));
            live.push(address);
        }
    }
    let ordered = order_answered(live);
    if ordered.is_empty() {
        note_key("no clean key address answered; the key request will try other edges before the pinned list");
        let pinned = pinned_key_edges();
        let mut rest: Vec<SocketAddr> = pool.into_iter().filter(|address| !pinned.contains(address)).collect();
        rest.extend(pinned);
        return rest;
    }
    note_key(&format!("key request will use {}", ordered[0]));
    ordered
}

fn pinned_key_edges() -> Vec<SocketAddr> {
    PINNED_KEY_EDGES
        .iter()
        .filter_map(|raw| raw.parse().ok())
        .collect()
}

fn note_key(message: &str) {
    let line = format!("Key: {message}");
    log::info!("{line}");
    crate::ffi::record_log(line);
}

fn render_request(request: &ApiRequest) -> Vec<u8> {
    render_request_with(request, false)
}

/// HTTP/1.1 request bytes; `keep_alive` asks the server to keep the TLS
/// connection open for the next request (OOB route: POST /reg and the
/// following PATCH share one desynced connection).
fn render_request_with(request: &ApiRequest, keep_alive: bool) -> Vec<u8> {
    let mut head = String::new();
    head.push_str(&format!("{} {} HTTP/1.1\r\n", request.method, request.path));
    head.push_str(&format!("Host: {}\r\n", request.host));

    for (name, value) in &request.headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }

    head.push_str("Accept-Encoding: identity\r\n");
    head.push_str(&format!(
        "Content-Length: {}\r\n",
        request.body.as_ref().map(Vec::len).unwrap_or(0)
    ));
    if keep_alive {
        head.push_str("Connection: keep-alive\r\n\r\n");
    } else {
        head.push_str("Connection: close\r\n\r\n");
    }

    let mut wire = head.into_bytes();
    if let Some(body) = &request.body {
        wire.extend_from_slice(body);
    }
    wire
}

/// True once `raw` holds a whole HTTP/1.1 response: the head plus
/// Content-Length body bytes, or a chunked body with its 0-size terminator.
/// Without either, the end is the connection close (returns false).
fn response_complete(raw: &[u8]) -> bool {
    matches!(framed_len(raw), Some(Some(len)) if raw.len() >= len)
}

/// Exact byte length of the first HTTP/1.1 response in `raw`:
/// `None` = head not complete yet; `Some(None)` = the head is there but the
/// body length is not known yet (chunked body still arriving) or the body
/// ends only with the connection close (no framing); `Some(Some(n))` = the
/// response is the first `n` bytes.
fn framed_len(raw: &[u8]) -> Option<Option<usize>> {
    let head_end = raw.windows(4).position(|w| w == b"\r\n\r\n")?;
    let head = String::from_utf8_lossy(&raw[..head_end]).to_ascii_lowercase();
    let body_start = head_end + 4;
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);
    // 1xx / 204 / 304 carry no body.
    if matches!(status, 100..=199 | 204 | 304) {
        return Some(Some(body_start));
    }
    let mut chunked = false;
    for line in head.split("\r\n").skip(1) {
        if let Some(value) = line.strip_prefix("content-length:") {
            if let Ok(len) = value.trim().parse::<usize>() {
                return Some(Some(body_start + len));
            }
        }
        if line.starts_with("transfer-encoding:") && line.contains("chunked") {
            chunked = true;
        }
    }
    if !chunked {
        return Some(None);
    }
    Some(chunked_end(raw, body_start))
}

/// End offset of a complete chunked body that starts at `start`
/// (after the 0-size chunk and the optional trailers), if it is all there.
fn chunked_end(raw: &[u8], start: usize) -> Option<usize> {
    let mut cursor = start;
    loop {
        let rest = raw.get(cursor..)?;
        let line_len = rest.windows(2).position(|w| w == b"\r\n")?;
        let token = String::from_utf8_lossy(&rest[..line_len]);
        let size = usize::from_str_radix(token.split(';').next().unwrap_or("").trim(), 16).ok()?;
        cursor += line_len + 2;
        if size == 0 {
            // Trailers (usually none), then the empty line.
            loop {
                let rest = raw.get(cursor..)?;
                let line_len = rest.windows(2).position(|w| w == b"\r\n")?;
                cursor += line_len + 2;
                if line_len == 0 {
                    return Some(cursor);
                }
            }
        }
        cursor += size + 2;
        if cursor > raw.len() {
            return None;
        }
    }
}

/// The response may be followed by another request on the same connection:
/// exact framing and no `Connection: close` from the server.
fn reusable_after(raw: &[u8]) -> bool {
    let Some(head_end) = raw.windows(4).position(|w| w == b"\r\n\r\n") else {
        return false;
    };
    let head = String::from_utf8_lossy(&raw[..head_end]).to_ascii_lowercase();
    if !head.starts_with("http/1.1") {
        return false;
    }
    let close = head
        .split("\r\n")
        .skip(1)
        .any(|line| line.starts_with("connection:") && line.contains("close"));
    !close && matches!(framed_len(raw), Some(Some(len)) if raw.len() == len)
}

fn parse_response(raw: &[u8]) -> Result<(u16, String)> {
    let text = String::from_utf8_lossy(raw);
    let split = text
        .find("\r\n\r\n")
        .ok_or_else(|| AetherError::Api("truncated response head".into()))?;

    let head = &text[..split];
    let mut body = text[split + 4..].to_string();

    let mut lines = head.split("\r\n");
    let status_line = lines
        .next()
        .ok_or_else(|| AetherError::Api("empty response".into()))?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|token| token.parse::<u16>().ok())
        .ok_or_else(|| AetherError::Api(format!("bad status line: {status_line}")))?;

    let chunked = lines.any(|line| {
        let lowered = line.to_lowercase();
        lowered.starts_with("transfer-encoding:") && lowered.contains("chunked")
    });

    if chunked {
        body = dechunk(&body);
    }

    Ok((status, body))
}

pub(crate) fn dechunk(body: &str) -> String {
    let mut out = String::new();
    let mut cursor = 0usize;

    while cursor < body.len() {
        let line_end = match body[cursor..].find("\r\n") {
            Some(offset) => cursor + offset,
            None => break,
        };
        let token = body[cursor..line_end]
            .split(';')
            .next()
            .unwrap_or("")
            .trim();
        let size = match usize::from_str_radix(token, 16) {
            Ok(0) | Err(_) => break,
            Ok(value) => value,
        };
        let start = line_end + 2;
        let end = start + size;
        if end > body.len() {
            break;
        }
        out.push_str(&body[start..end]);
        cursor = end + 2;
    }

    out
}

/// TLS session that has finished the handshake but has not sent the HTTP body yet.
pub struct ReadySession {
    tls: tokio_boring::SslStream<FragmentingStream<TcpStream>>,
    pub address: SocketAddr,
    fingerprint: Fingerprint,
    pub route: &'static str,
}


/// Connect to `address` with Android VPN socket protection applied BEFORE
/// connect (mirrors `masque_h2::connect_tcp` / `dns.rs`). On Windows this is
/// `TcpStream::connect` plus a no-op protect, so behaviour stays identical.
async fn connect_protected(address: SocketAddr) -> Result<TcpStream> {
    #[cfg(target_os = "android")]
    {
        let started = std::time::Instant::now();
        let wall = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let socket = if address.is_ipv4() {
            TcpSocket::new_v4()
        } else {
            TcpSocket::new_v6()
        }
        .map_err(|e| AetherError::Api(format!("socket for {address}: {e}")))?;
        {
            use std::os::fd::AsRawFd;
            let fd = socket.as_raw_fd();
            match crate::platform::protect_socket(&socket) {
                Ok(()) => note_key(&format!(
                    "t={wall} socket fd={fd} protected for {address} ({} ms)",
                    started.elapsed().as_millis()
                )),
                Err(e) => {
                    note_key(&format!(
                        "t={wall} socket fd={fd} protect FAILED for {address}: {e}"
                    ));
                    return Err(AetherError::Api(format!(
                        "protect socket for {address}: {e}"
                    )));
                }
            }
        }
        let bind: SocketAddr = if address.is_ipv4() {
            "0.0.0.0:0".parse().unwrap()
        } else {
            "[::]:0".parse().unwrap()
        };
        socket
            .bind(bind)
            .map_err(|e| AetherError::Api(format!("bind for {address}: {e}")))?;
        let tcp = tokio::time::timeout(CONNECT_TIMEOUT, socket.connect(address))
            .await
            .map_err(|_| AetherError::Api(format!("connect to {address} timed out")))?
            .map_err(|e| AetherError::Api(format!("connect to {address}: {e}")))?;
        tcp.set_nodelay(true).ok();
        note_key(&format!(
            "t={} tcp connected to {address} in {} ms (protected)",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0),
            started.elapsed().as_millis()
        ));
        Ok(tcp)
    }
    #[cfg(not(target_os = "android"))]
    {
        let tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(address))
            .await
            .map_err(|_| AetherError::Api(format!("connect to {address} timed out")))?
            .map_err(|e| AetherError::Api(format!("connect to {address}: {e}")))?;
        // No-op on Windows; on other non-Android unix protects after connect.
        let _ = crate::platform::protect_socket(&tcp);
        tcp.set_nodelay(true).ok();
        Ok(tcp)
    }
}

async fn handshake(
    host: &str,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<tokio_boring::SslStream<FragmentingStream<TcpStream>>> {
    handshake_with(host, address, fingerprint, ech, None).await
}

async fn handshake_with(
    host: &str,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
    oob: Option<crate::oob::OobMethod>,
) -> Result<tokio_boring::SslStream<FragmentingStream<TcpStream>>> {
    let mut offered = ech.map(|list| list.to_vec());
    let mut retried = false;
    loop {
        let tcp = connect_protected(address).await?;

        let mut config = fingerprint.configure()?;
        // The edge certificate is for whichever name ECH puts in the clear.
        // PattNG does not verify it; a mismatch here is not a failed registration.
        config.set_verify_hostname(false);
        config.set_verify(boring::ssl::SslVerifyMode::NONE);
        if let Some(list) = offered.as_deref() {
            config.set_ech_config_list(list).map_err(|e| {
                AetherError::Tls(format!(
                    "{e} (ech {} bytes, head {:02x?})",
                    list.len(),
                    &list[..list.len().min(8)]
                ))
            })?;
        }
        let stream = if let Some(method) = oob {
            FragmentingStream::new(tcp, FragmentConfig::disabled()).with_oob(method, host)
        } else {
            FragmentingStream::new(tcp, fingerprint.fragments())
        };

        match tokio::time::timeout(
            HANDSHAKE_TIMEOUT,
            tokio_boring::connect(config, host, stream),
        )
        .await
        {
            Ok(Ok(tls)) => {
                if offered.is_some() && !tls.ssl().ech_accepted() {
                    return Err(AetherError::Api(format!(
                        "tls handshake with {address} finished without ECH"
                    )));
                }
                return Ok(tls);
            }
            Ok(Err(error)) => {
                // BoringSSL returns a 5-byte poison list unless this was an
                // authenticated ECH reject. Offering that list fails the retry
                // and hides the real handshake error.
                let message = error.to_string();
                let retry = if !retried && message.contains("ECH_REJECTED") {
                    error
                        .ssl()
                        .and_then(|ssl| ssl.get_ech_retry_configs())
                        .map(|configs| configs.to_vec())
                        .filter(|configs| crate::dns::plausible_ech_list(configs))
                } else {
                    None
                };
                if let Some(configs) = retry {
                    log::info!(
                        "[apifront] {address} rejected ECH; retrying with the returned config ({} bytes)",
                        configs.len()
                    );
                    offered = Some(configs);
                    retried = true;
                    continue;
                }
                return Err(AetherError::Api(format!(
                    "tls handshake with {address}: {error}"
                )));
            }
            Err(_) => {
                return Err(AetherError::Api(format!(
                    "tls handshake with {address} timed out"
                )))
            }
        }
    }
}

async fn write_and_read(
    mut tls: tokio_boring::SslStream<FragmentingStream<TcpStream>>,
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    route: &'static str,
) -> Result<ApiResponse> {
    if tls.ssl().selected_alpn_protocol() == Some(b"h2") {
        return Err(AetherError::Api(format!(
            "{address} negotiated http/2 which this path does not speak"
        )));
    }

    let wire = render_request(request);
    let exchange_started = std::time::Instant::now();
    #[cfg(target_os = "android")]
    {
        let wall = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        note_key(&format!(
            "t={wall} HTTP exchange start {address} / {} / {route}: {} {} wire={} bytes Connection:close",
            fingerprint.label(),
            request.method,
            request.path,
            wire.len()
        ));
    }
    // Stop as soon as the response is complete (Content-Length reached, or the
    // chunked terminator seen), like the C# probe. Waiting for EOF instead hangs
    // the full timeout when a middlebox drops or delays the server's FIN after
    // the body already arrived. `AETHER_OOB_READ=eof` restores the old wait.
    let read_eof = std::env::var("AETHER_OOB_READ")
        .map(|v| v.trim().eq_ignore_ascii_case("eof"))
        .unwrap_or(false);
    let mut buffer = Vec::new();
    let outcome = tokio::time::timeout(EXCHANGE_TIMEOUT, async {
        let write_started = std::time::Instant::now();
        tls.write_all(&wire).await?;
        tls.flush().await?;
        #[cfg(target_os = "android")]
        {
            let wall = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0);
            note_key(&format!(
                "t={wall} HTTP write flushed {address}: bytes_written={} flush_ms={}",
                wire.len(),
                write_started.elapsed().as_millis()
            ));
        }

        let mut chunk = [0u8; 8192];
        let mut first_byte_ms: Option<u128> = None;
        #[cfg(target_os = "android")]
        let first_byte_deadline = std::time::Duration::from_secs(3);
        #[cfg(not(target_os = "android"))]
        let first_byte_deadline = EXCHANGE_TIMEOUT;
        loop {
            let read = if first_byte_ms.is_none() {
                match tokio::time::timeout(first_byte_deadline, tls.read(&mut chunk)).await {
                    Ok(r) => r?,
                    Err(_) => {
                        #[cfg(target_os = "android")]
                        {
                            let wall = std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .map(|d| d.as_millis())
                                .unwrap_or(0);
                            note_key(&format!(
                                "t={wall} HTTP first_byte timeout {address}: no byte in {} ms after flush (0-byte risk)",
                                first_byte_deadline.as_millis()
                            ));
                        }
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            format!(
                                "first_byte timeout at {address} after {} ms",
                                first_byte_deadline.as_millis()
                            ),
                        ));
                    }
                }
            } else {
                tls.read(&mut chunk).await?
            };
            if read == 0 {
                #[cfg(target_os = "android")]
                {
                    let wall = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    note_key(&format!(
                        "t={wall} HTTP read EOF {address}: total={} first_byte_ms={:?} elapsed_ms={}",
                        buffer.len(),
                        first_byte_ms,
                        exchange_started.elapsed().as_millis()
                    ));
                }
                break;
            }
            if first_byte_ms.is_none() {
                let ms = exchange_started.elapsed().as_millis();
                first_byte_ms = Some(ms);
                #[cfg(target_os = "android")]
                {
                    let wall = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    note_key(&format!(
                        "t={wall} HTTP first_byte {address}: n={read} first_byte_ms={ms}"
                    ));
                }
            }
            buffer.extend_from_slice(&chunk[..read]);
            if buffer.len() > MAX_BODY {
                break;
            }
            if !read_eof && response_complete(&buffer) {
                break;
            }
        }
        Ok::<(), std::io::Error>(())
    })
    .await;
    #[cfg(target_os = "android")]
    {
        let wall = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let kind = match &outcome {
            Ok(Ok(())) => "ok",
            Ok(Err(_)) => "io_err",
            Err(_) => "timeout",
        };
        // wrote_ok: if we timed out with 0 response bytes and never logged
        // "HTTP write flushed", the write/flush hung before the server replied.
        note_key(&format!(
            "t={wall} HTTP exchange outcome {address}: kind={kind} resp_bytes={} elapsed_ms={}",
            buffer.len(),
            exchange_started.elapsed().as_millis()
        ));
    }
    // A timeout or a late read error after a complete response still counts:
    // the API already answered.
    let collected = match outcome {
        Ok(Ok(())) => buffer,
        _ if response_complete(&buffer) => buffer,
        Ok(Err(e)) => return Err(AetherError::Api(format!("exchange with {address}: {e}"))),
        Err(_) => {
            return Err(AetherError::Api(format!(
                "exchange with {address} timed out ({} response bytes)",
                buffer.len()
            )))
        }
    };

    #[cfg(target_os = "android")]
    {
        let wall = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        note_key(&format!(
            "t={wall} HTTP exchange done {address}: {} response bytes",
            collected.len()
        ));
    }
    let (status, body) = parse_response(&collected).map_err(|error| {
        AetherError::Api(format!(
            "{error} from {address} over {route} ({} bytes: {:?})",
            collected.len(),
            String::from_utf8_lossy(&collected[..collected.len().min(160)])
        ))
    })?;
    Ok(ApiResponse {
        status,
        body,
        route: format!("{address} / {} / {route}", fingerprint.label()),
    })
}

async fn exchange(
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<ApiResponse> {
    let tls = handshake(&request.host, address, fingerprint, ech).await?;
    let route = match fingerprint {
        Fingerprint::ChromeLike if ech.is_some() => "ech",
        Fingerprint::SplitLegacy => "fragment",
        _ => "tls",
    };
    write_and_read(tls, request, address, fingerprint, route).await
}

/// TLS stream type of the built-in sessions (OOB keep-alive reuse).
pub type SessionTls = tokio_boring::SslStream<FragmentingStream<TcpStream>>;

impl ReadySession {
    /// The TLS stream and its variant, for a request/response loop on one
    /// connection (`exchange_keep_alive`).
    pub fn into_parts(self) -> (SessionTls, SocketAddr, Fingerprint, &'static str) {
        (self.tls, self.address, self.fingerprint, self.route)
    }
}

/// One HTTP/1.1 request on an open TLS connection that stays open:
/// `Connection: keep-alive`, the response is read exactly by Content-Length
/// or chunked framing (never to EOF), so the next request can be written on
/// the same stream. Returns the response and whether the connection may be
/// used again. No overall timeout here; the caller bounds it.
pub async fn exchange_keep_alive(
    tls: &mut SessionTls,
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    route: &'static str,
) -> Result<(ApiResponse, bool)> {
    if tls.ssl().selected_alpn_protocol() == Some(b"h2") {
        return Err(AetherError::Api(format!(
            "{address} negotiated http/2 which this path does not speak"
        )));
    }
    let wire = render_request_with(request, true);
    tls.write_all(&wire)
        .await
        .map_err(|e| AetherError::Api(format!("exchange with {address}: write: {e}")))?;
    tls.flush()
        .await
        .map_err(|e| AetherError::Api(format!("exchange with {address}: flush: {e}")))?;
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 8192];
    let mut eof = false;
    loop {
        if let Some(Some(len)) = framed_len(&buffer) {
            if buffer.len() >= len {
                break;
            }
        }
        let read = match tls.read(&mut chunk).await {
            Ok(read) => read,
            Err(e) if response_complete(&buffer) => {
                log::debug!("[apifront] late read error after a complete response: {e}");
                break;
            }
            Err(e) => {
                return Err(AetherError::Api(format!(
                    "exchange with {address}: {e} ({} response bytes)",
                    buffer.len()
                )))
            }
        };
        if read == 0 {
            eof = true;
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.len() > MAX_BODY {
            break;
        }
    }
    if buffer.is_empty() {
        return Err(AetherError::Api(format!(
            "exchange with {address}: connection closed before any response byte"
        )));
    }
    let reusable = !eof && reusable_after(&buffer);
    let collected = match framed_len(&buffer) {
        Some(Some(len)) if buffer.len() >= len => &buffer[..len],
        _ => &buffer[..],
    };
    let (status, body) = parse_response(collected).map_err(|error| {
        AetherError::Api(format!(
            "{error} from {address} over {route} ({} bytes: {:?})",
            collected.len(),
            String::from_utf8_lossy(&collected[..collected.len().min(160)])
        ))
    })?;
    Ok((
        ApiResponse {
            status,
            body,
            route: format!("{address} / {} / {route}", fingerprint.label()),
        },
        reusable,
    ))
}

pub async fn send_on_session(session: ReadySession, request: &ApiRequest) -> Result<ApiResponse> {
    write_and_read(
        session.tls,
        request,
        session.address,
        session.fingerprint,
        session.route,
    )
    .await
}

/// One HTTP/1.1 request with `Connection: close` (exact Content-Length read).
/// Used on Android OOB key-fetch so each API call uses a fresh TLS connection
/// (per-connection packet-cap theory: keep-alive reuse can return 0 bytes).
pub async fn exchange_close(
    tls: SessionTls,
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    route: &'static str,
) -> Result<ApiResponse> {
    write_and_read(tls, request, address, fingerprint, route).await
}

async fn load_ech_config() -> Option<Vec<u8>> {
    match crate::dns::fetch_ech_config().await {
        Ok(list) if crate::dns::plausible_ech_list(&list) => Some(list),
        Ok(_) | Err(_) => Some(crate::dns::ECH_CONFIG_FALLBACK.to_vec()),
    }
}

/// Open a TLS session with ECH on the first edge that finishes the handshake.
pub async fn open_ech_session(host: &str, addresses: &[SocketAddr]) -> Result<ReadySession> {
    let ech = load_ech_config().await;
    open_ech_with(host, addresses, ech.as_deref(), "ech", "ECH").await
}

async fn open_ech_with(
    host: &str,
    addresses: &[SocketAddr],
    ech: Option<&[u8]>,
    route: &'static str,
    tag: &str,
) -> Result<ReadySession> {
    let mut failure: Option<AetherError> = None;
    for address in addresses.iter().take(3) {
        note_key(&format!("trying {address} with {tag} (handshake only)"));
        match handshake(host, *address, Fingerprint::ChromeLike, ech).await {
            Ok(tls) => {
                note_key(&format!("{address} {tag} handshake ready"));
                return Ok(ReadySession {
                    tls,
                    address: *address,
                    fingerprint: Fingerprint::ChromeLike,
                    route,
                });
            }
            Err(error) => {
                note_key(&format!("{address} {tag} handshake failed: {error}"));
                failure = Some(error);
            }
        }
    }
    Err(failure.unwrap_or_else(|| AetherError::Api(format!("{tag} handshake failed on every edge"))))
}

/// True when the auto race adds the ECH-DoH lane: the app supplied a list in
/// AETHER_API_ECH or asked for the lane with AETHER_API_DOH=1. Callers that set
/// neither (Android today) race exactly as before.
pub fn doh_lane_enabled() -> bool {
    crate::dns::supplied_api_ech().is_some()
        || matches!(
            std::env::var("AETHER_API_DOH")
                .ok()
                .as_deref()
                .map(|v| v.trim().to_ascii_lowercase())
                .as_deref(),
            Some("1" | "true" | "on" | "yes")
        )
}

/// ECHConfigList for the `doh` key route: the one the app supplied in
/// AETHER_API_ECH, else a parallel DoH-by-IP / Shecan / UDP bootstrap capped
/// at 3 s, else the built-in list.
pub async fn resolve_doh_ech() -> (Vec<u8>, String) {
    if let Some(list) = crate::dns::supplied_api_ech() {
        return (list, "AETHER_API_ECH (app prefetch)".to_string());
    }
    note_key("ECH-DoH: no app-supplied ECHConfigList; racing DoH 8.8.8.8/8.8.4.4, Shecan and public UDP");
    match crate::dns::fetch_ech_config_parallel(Duration::from_secs(3)).await {
        Ok(found) => found,
        Err(error) => {
            note_key(&format!(
                "ECH-DoH bootstrap failed ({error}); using the built-in ECHConfigList"
            ));
            (
                crate::dns::ECH_CONFIG_FALLBACK.to_vec(),
                "built-in fallback".to_string(),
            )
        }
    }
}

/// Open an ECH session with the ECHConfigList from `resolve_doh_ech`.
pub async fn open_doh_session(
    host: &str,
    addresses: &[SocketAddr],
    ech: &[u8],
    source: &str,
) -> Result<ReadySession> {
    note_key(&format!(
        "ECH-DoH using ECHConfigList from {source} ({} bytes)",
        ech.len()
    ));
    open_ech_with(host, addresses, Some(ech), "doh", "ECH-DoH").await
}

/// Open a TLS session with a split ClientHello (no ECH).
pub async fn open_fragment_session(host: &str, addresses: &[SocketAddr]) -> Result<ReadySession> {
    let mut failure: Option<AetherError> = None;
    for address in addresses.iter().take(3) {
        note_key(&format!("trying {address} with a split hello (handshake only)"));
        match handshake(host, *address, Fingerprint::SplitLegacy, None).await {
            Ok(tls) => {
                note_key(&format!("{address} split-hello handshake ready"));
                return Ok(ReadySession {
                    tls,
                    address: *address,
                    fingerprint: Fingerprint::SplitLegacy,
                    route: "fragment",
                });
            }
            Err(error) => {
                note_key(&format!("{address} split-hello handshake failed: {error}"));
                failure = Some(error);
            }
        }
    }
    Err(failure
        .unwrap_or_else(|| AetherError::Api("split-hello handshake failed on every edge".into())))
}

/// Open a plain TLS session to the API hostname on the current network (normal DNS).
pub async fn open_external_session(host: &str) -> Result<ReadySession> {
    note_key(&format!("resolving {host} on the current network"));
    let mut addrs: Vec<SocketAddr> = tokio::net::lookup_host((host, 443))
        .await
        .map_err(|e| AetherError::Api(format!("dns lookup for {host}: {e}")))?
        .collect();
    if addrs.is_empty() {
        return Err(AetherError::Api(format!("dns lookup for {host} returned no addresses")));
    }
    // Prefer IPv4 first; many Iranian SIMs still break AAAA paths for the API.
    addrs.sort_by_key(|addr| match addr.ip() {
        IpAddr::V4(_) => 0u8,
        IpAddr::V6(_) => 1u8,
    });
    let mut failure: Option<AetherError> = None;
    for address in addrs.into_iter().take(3) {
        note_key(&format!("trying {address} as {host} on the current network (handshake only)"));
        match handshake(host, address, Fingerprint::Modern, None).await {
            Ok(tls) => {
                note_key(&format!("{address} current-network handshake ready"));
                return Ok(ReadySession {
                    tls,
                    address,
                    fingerprint: Fingerprint::Modern,
                    route: "external",
                });
            }
            Err(error) => {
                note_key(&format!("{address} current-network handshake failed: {error}"));
                failure = Some(error);
            }
        }
    }
    Err(failure.unwrap_or_else(|| {
        AetherError::Api("current-network handshake failed on every resolved address".into())
    }))
}

/// TLS hello for the relay. AETHER_RELAY_HELLO = modern | chrome | split.
fn relay_fingerprint() -> Fingerprint {
    match std::env::var("AETHER_RELAY_HELLO")
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "chrome" => Fingerprint::ChromeLike,
        "split" | "legacy" => Fingerprint::SplitLegacy,
        _ => Fingerprint::Modern,
    }
}

/// Open a TLS session to the WARP registration relay (a Cloudflare Worker).
///
/// Normal TLS with the relay host as SNI, plain TLS 1.3 hello by default (the
/// Chrome-like hello finished the handshake on MCI but its data then stalled),
/// no ECH, pinned to addresses the caller resolved itself (DoH-by-IP / Shecan /
/// system race).
/// Same handshake-only contract as the other lanes: nothing is sent yet.
pub async fn open_relay_session(host: &str, addresses: &[SocketAddr]) -> Result<ReadySession> {
    let fingerprint = relay_fingerprint();
    let mut failure: Option<AetherError> = None;
    for address in addresses.iter().take(3) {
        note_key(&format!(
            "trying {address} as relay {host} ({} hello, handshake only)",
            fingerprint.label()
        ));
        match handshake(host, *address, fingerprint, None).await {
            Ok(tls) => {
                note_key(&format!("{address} relay handshake ready"));
                return Ok(ReadySession {
                    tls,
                    address: *address,
                    fingerprint,
                    route: "relay",
                });
            }
            Err(error) => {
                note_key(&format!("{address} relay handshake failed: {error}"));
                failure = Some(error);
            }
        }
    }
    Err(failure.unwrap_or_else(|| AetherError::Api(format!("relay {host}: no address to try"))))
}

/// Open a TLS session to the API host itself (real SNI, plain TLS 1.3 hello)
/// whose ClientHello leaves with an OOB desync `method` (`crate::oob`).
/// Same handshake-only contract as the other lanes: nothing is sent yet.
pub async fn open_oob_session_at(
    host: &str,
    address: SocketAddr,
    method: crate::oob::OobMethod,
    fingerprint: Fingerprint,
) -> Result<ReadySession> {
    note_key(&format!(
        "trying {address} as {host} with OOB {} / {} (handshake only)",
        method.name(),
        fingerprint.label()
    ));
    match handshake_with(host, address, fingerprint, None, Some(method)).await {
        Ok(tls) => {
            note_key(&format!("{address} OOB {} handshake ready", method.name()));
            Ok(ReadySession {
                tls,
                address,
                fingerprint,
                route: "oob",
            })
        }
        Err(error) => {
            note_key(&format!("{address} OOB {} handshake failed: {error}", method.name()));
            Err(error)
        }
    }
}

/// Probe clean key edges (TCP accept). Shared by ECH and split-hello.
pub async fn probe_key_edges() -> Vec<SocketAddr> {
    reachable_key_edges().await
}

/// ECH-only key request (manual setting). Still probes clean edges first.
pub async fn fetch_ech(request: &ApiRequest) -> Result<ApiResponse> {
    let addresses = reachable_key_edges().await;
    if addresses.is_empty() {
        return Err(AetherError::Api(
            "no camouflaged route to the api was available".into(),
        ));
    }
    let session = open_ech_session(&request.host, &addresses).await?;
    send_on_session(session, request).await
}

/// ECH-DoH key request (manual `doh` setting): ECH with the app-supplied or
/// parallel-bootstrapped ECHConfigList instead of the fixed UDP lookup.
pub async fn fetch_doh(request: &ApiRequest) -> Result<ApiResponse> {
    let ((ech, source), addresses) = tokio::join!(resolve_doh_ech(), reachable_key_edges());
    if addresses.is_empty() {
        return Err(AetherError::Api(
            "no camouflaged route to the api was available".into(),
        ));
    }
    let session = open_doh_session(&request.host, &addresses, &ech, &source).await?;
    send_on_session(session, request).await
}

/// Split-ClientHello-only key request (manual setting).
pub async fn fetch_fragment(request: &ApiRequest) -> Result<ApiResponse> {
    let addresses = reachable_key_edges().await;
    if addresses.is_empty() {
        return Err(AetherError::Api(
            "no camouflaged route to the api was available".into(),
        ));
    }
    let session = open_fragment_session(&request.host, &addresses).await?;
    send_on_session(session, request).await
}

/// Back-compat name used by older call sites; ECH only.
pub async fn fetch(request: &ApiRequest) -> Result<ApiResponse> {
    fetch_ech(request).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pinned_edge_is_only_one_member_of_the_key_pool() {
        let pool = local_key_edge_pool();
        let pinned: SocketAddr = "188.114.97.6:443".parse().unwrap();
        assert!(pool.contains(&pinned));
        assert!(pool.len() > pinned_key_edges().len());
        assert!(pool.iter().any(|address| match address.ip() {
            IpAddr::V4(ip) => ip.octets()[0] == 162,
            IpAddr::V6(_) => false,
        }));
    }

    #[test]
    fn an_answered_random_edge_is_used_ahead_of_a_silent_pinned_address() {
        let random = random_in(162, 159, 192, 199);
        let pinned: SocketAddr = "188.114.97.6:443".parse().unwrap();
        let ordered = order_answered(vec![random]);
        assert_eq!(ordered, vec![random]);
        assert!(!ordered.contains(&pinned));
        let both = order_answered(vec![random, pinned]);
        assert_eq!(both[0], pinned);
    }

    #[test]
    fn every_random_edge_address_stays_inside_the_cloudflare_range() {
        for _ in 0..64 {
            let address = random_edge_address();
            assert_eq!(address.port(), 443);
            match address.ip() {
                IpAddr::V4(v4) => {
                    let octets = v4.octets();
                    assert_eq!([octets[0], octets[1], octets[2]], EDGE_PREFIX);
                    assert!(octets[3] >= 1 && octets[3] <= 254);
                }
                IpAddr::V6(_) => panic!("the edge range is ipv4 only"),
            }
        }
    }

    #[test]
    fn the_request_carries_the_host_header_and_a_length() {
        let request = ApiRequest {
            method: "POST".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg".to_string(),
            headers: vec![("Content-Type".to_string(), "application/json".to_string())],
            body: Some(b"{\"a\":1}".to_vec()),
        };

        let wire = String::from_utf8(render_request(&request)).expect("utf8");
        assert!(wire.starts_with("POST /v0a4471/reg HTTP/1.1\r\n"));
        assert!(wire.contains("Host: api.cloudflareclient.com\r\n"));
        assert!(wire.contains("Content-Type: application/json\r\n"));
        assert!(wire.contains("Content-Length: 7\r\n"));
        assert!(wire.ends_with("\r\n\r\n{\"a\":1}"));
    }

    #[test]
    fn a_body_less_request_still_declares_a_zero_length() {
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "example.invalid".to_string(),
            path: "/".to_string(),
            headers: Vec::new(),
            body: None,
        };
        let wire = String::from_utf8(render_request(&request)).expect("utf8");
        assert!(wire.contains("Content-Length: 0\r\n"));
    }

    #[test]
    fn keep_alive_framing_is_exact() {
        let cl = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}";
        assert_eq!(framed_len(cl), Some(Some(cl.len())));
        assert!(reusable_after(cl));
        let close = b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\n{}";
        assert!(!reusable_after(close));
        let chunked = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n";
        assert_eq!(framed_len(chunked), Some(Some(chunked.len())));
        assert!(reusable_after(chunked));
        let partial = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nab";
        assert_eq!(framed_len(partial), Some(None));
        assert!(!response_complete(partial));
        let no_frame = b"HTTP/1.1 200 OK\r\n\r\nbody";
        assert!(!reusable_after(no_frame));
        assert!(response_complete(b"HTTP/1.1 204 No Content\r\n\r\n"));
    }

    #[test]
    fn a_plain_response_is_parsed() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":\"x\"}";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 200);
        assert_eq!(body, "{\"id\":\"x\"}");
    }

    #[test]
    fn a_chunked_response_is_reassembled() {
        let raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n4\r\n:1}\n\r\n0\r\n\r\n";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 200);
        assert_eq!(body, "{\"a\":1}\n");
    }

    #[test]
    fn a_rejection_status_is_reported_rather_than_hidden() {
        let raw = b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 30\r\n\r\nslow down";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 429);
        assert_eq!(body, "slow down");
    }

    #[test]
    fn a_headless_response_is_an_error() {
        assert!(parse_response(b"garbage").is_err());
    }

    #[test]
    fn each_fingerprint_builds_a_usable_configuration() {
        for fingerprint in Fingerprint::all() {
            assert!(
                fingerprint.configure().is_ok(),
                "{} should configure",
                fingerprint.label()
            );
        }
    }

    #[test]
    fn only_the_legacy_profile_splits_the_client_hello() {
        assert!(Fingerprint::SplitLegacy.fragments().enabled);
        assert!(!Fingerprint::Modern.fragments().enabled);
        assert!(!Fingerprint::ChromeLike.fragments().enabled);
    }

    #[tokio::test]
    #[ignore = "needs live network access to the cloudflare edge"]
    async fn every_fingerprint_reaches_the_live_edge() {
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg/nonexistent".to_string(),
            headers: vec![("User-Agent".to_string(), "WARP for Android".to_string())],
            body: None,
        };

        let mut reached = 0;
        for fingerprint in Fingerprint::all() {
            let address = random_edge_address();
            match exchange(&request, address, fingerprint, None).await {
                Ok(response) => {
                    reached += 1;
                    println!(
                        "{} -> {} via {}",
                        fingerprint.label(),
                        response.status,
                        response.route
                    );
                }
                Err(error) => println!("{} failed: {error}", fingerprint.label()),
            }
        }

        assert!(reached > 0, "no fingerprint reached the edge");
    }
}
