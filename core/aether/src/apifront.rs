use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use boring::ssl::{SslConnector, SslMethod, SslVersion};
use rand::Rng;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
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
}

impl Fingerprint {
    pub fn label(self) -> &'static str {
        match self {
            Fingerprint::SplitLegacy => "split-tls12",
            Fingerprint::Modern => "plain-tls13",
            Fingerprint::ChromeLike => "chrome",
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
    match tokio::time::timeout(PROBE_TIMEOUT, TcpStream::connect(address)).await {
        Ok(Ok(stream)) => {
            drop(stream);
            Some(address)
        }
        _ => None,
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
    head.push_str("Connection: close\r\n\r\n");

    let mut wire = head.into_bytes();
    if let Some(body) = &request.body {
        wire.extend_from_slice(body);
    }
    wire
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

fn dechunk(body: &str) -> String {
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

async fn handshake(
    host: &str,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<tokio_boring::SslStream<FragmentingStream<TcpStream>>> {
    let mut offered = ech.map(|list| list.to_vec());
    let mut retried = false;
    loop {
        let tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(address))
            .await
            .map_err(|_| AetherError::Api(format!("connect to {address} timed out")))?
            .map_err(|e| AetherError::Api(format!("connect to {address}: {e}")))?;
        tcp.set_nodelay(true).ok();

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
        let stream = FragmentingStream::new(tcp, fingerprint.fragments());

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
    let collected = tokio::time::timeout(EXCHANGE_TIMEOUT, async {
        tls.write_all(&wire).await?;
        tls.flush().await?;

        let mut buffer = Vec::new();
        let mut chunk = [0u8; 8192];
        loop {
            let read = tls.read(&mut chunk).await?;
            if read == 0 {
                break;
            }
            buffer.extend_from_slice(&chunk[..read]);
            if buffer.len() > MAX_BODY {
                break;
            }
        }
        Ok::<Vec<u8>, std::io::Error>(buffer)
    })
    .await
    .map_err(|_| AetherError::Api(format!("exchange with {address} timed out")))?
    .map_err(|e| AetherError::Api(format!("exchange with {address}: {e}")))?;

    let (status, body) = parse_response(&collected)?;
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

async fn load_ech_config() -> Option<Vec<u8>> {
    match crate::dns::fetch_ech_config().await {
        Ok(list) if crate::dns::plausible_ech_list(&list) => Some(list),
        Ok(_) | Err(_) => Some(crate::dns::ECH_CONFIG_FALLBACK.to_vec()),
    }
}

/// Open a TLS session with ECH on the first edge that finishes the handshake.
pub async fn open_ech_session(host: &str, addresses: &[SocketAddr]) -> Result<ReadySession> {
    let ech = load_ech_config().await;
    let mut failure: Option<AetherError> = None;
    for address in addresses.iter().take(3) {
        note_key(&format!("trying {address} with ECH (handshake only)"));
        match handshake(host, *address, Fingerprint::ChromeLike, ech.as_deref()).await {
            Ok(tls) => {
                note_key(&format!("{address} ECH handshake ready"));
                return Ok(ReadySession {
                    tls,
                    address: *address,
                    fingerprint: Fingerprint::ChromeLike,
                    route: "ech",
                });
            }
            Err(error) => {
                note_key(&format!("{address} ECH handshake failed: {error}"));
                failure = Some(error);
            }
        }
    }
    Err(failure.unwrap_or_else(|| AetherError::Api("ECH handshake failed on every edge".into())))
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
