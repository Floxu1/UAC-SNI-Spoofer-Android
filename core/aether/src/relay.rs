//! WARP registration relay transport.
//!
//! A tiny HTTPS client that hands the register / enroll / device-refresh calls
//! to the user's Cloudflare Worker relay instead of talking to
//! `api.cloudflareclient.com` directly. The relay forwards them to Cloudflare
//! from Cloudflare's own edge, so the SNI that leaves the phone is the relay
//! host (e.g. a `*.workers.dev` name), which MCI does not filter, and the
//! censored `api.cloudflareclient.com` / `cloudflare-ech.com` names never
//! appear on the wire.
//!
//! The relay list comes from `AETHER_API_RELAY` (comma-separated), defaulting
//! to the built-in Worker URL. The hosts are resolved through the core's own
//! DoH-by-IP / Shecan / system race (not the system resolver alone, which the
//! user's proxy DNS may bend), and the TLS connection is pinned to the resolved
//! Cloudflare address while still presenting a normal SNI of the relay host.

use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use rand::seq::SliceRandom;

use crate::apifront::{ApiRequest, ApiResponse, ReadySession};
use crate::error::{AetherError, Result};

/// Built-in relay used when `AETHER_API_RELAY` is unset. The app overrides it
/// from config so a refreshed list can be pushed without a core rebuild.
const RELAY_DEFAULT: &str = "https://dark-brook-3ef5.behroozuac5.workers.dev/";
const RELAY_TIMEOUT: Duration = Duration::from_secs(15);
/// Budget for resolve + TCP probe + TLS handshake on one relay URL.
const RELAY_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Cloudflare anycast edges that served the default relay host from MCI.
/// The IPv6 pair is raced from the first moment too: on an MCI phone hotspot
/// (2026-10-22) every IPv4 TLS handshake to Cloudflare timed out while IPv6
/// was ready at once, and DoH-by-IP only returns A records. Without IPv6
/// routing those two fail instantly ("network unreachable"), costing nothing.
const FALLBACK_EDGES: &[&str] = &[
    "104.21.60.172",
    "172.67.198.113",
    "2606:4700:3035::6815:3cac",
    "2606:4700:3037::ac43:c671",
];

fn note(message: &str) {
    let line = format!("Key: {message}");
    log::info!("{line}");
    crate::ffi::record_log(line);
}

/// The relay URLs to try, shuffled for load-spreading and fail-over.
pub fn relay_urls() -> Vec<String> {
    let raw = std::env::var("AETHER_API_RELAY").unwrap_or_default();
    let mut urls: Vec<String> = raw
        .split(',')
        .map(|s| s.trim().trim_matches('"').to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if urls.is_empty() {
        urls.push(RELAY_DEFAULT.to_string());
    }
    urls.shuffle(&mut rand::thread_rng());
    urls
}

fn host_of(url: &str) -> Option<String> {
    let rest = url
        .trim_start_matches("https://")
        .trim_start_matches("http://");
    let host = rest.split('/').next().unwrap_or("");
    let host = host.split(':').next().unwrap_or("");
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// A relay URL whose host has been resolved to live Cloudflare addresses.
pub struct RelayTarget {
    pub url: String,
    pub host: String,
    pub addrs: Vec<SocketAddr>,
}

#[derive(serde::Serialize)]
struct RelayRequest {
    method: String,
    path: String,
    headers: serde_json::Map<String, serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    body: Option<String>,
}

#[derive(serde::Deserialize)]
struct RelayEnvelope {
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    status: u16,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

fn build_payload(request: &ApiRequest) -> RelayRequest {
    let mut headers = serde_json::Map::new();
    for (name, value) in &request.headers {
        // The Content-Type is set by the relay; forwarding it is harmless but
        // the relay only keeps its allow-list anyway.
        headers.insert(name.clone(), serde_json::Value::String(value.clone()));
    }
    let body = request
        .body
        .as_ref()
        .map(|bytes| String::from_utf8_lossy(bytes).to_string());
    RelayRequest {
        method: request.method.clone(),
        path: request.path.clone(),
        headers,
        body,
    }
}

fn path_of(url: &str) -> String {
    let rest = url
        .trim_start_matches("https://")
        .trim_start_matches("http://");
    match rest.find('/') {
        Some(index) => rest[index..].to_string(),
        None => "/".to_string(),
    }
}

/// The relay HTTP request that carries `request` (the normal API call).
fn wrap(target: &RelayTarget, request: &ApiRequest) -> Result<ApiRequest> {
    let payload = serde_json::to_vec(&build_payload(request))
        .map_err(|e| AetherError::Api(format!("relay encode: {e}")))?;
    Ok(ApiRequest {
        method: "POST".to_string(),
        host: target.host.clone(),
        path: path_of(&target.url),
        headers: vec![
            ("Content-Type".to_string(), "application/json".to_string()),
            ("Accept".to_string(), "application/json".to_string()),
            (
                "User-Agent".to_string(),
                crate::consts::UA_REGISTER.to_string(),
            ),
        ],
        body: Some(payload),
    })
}

/// Decode the relay envelope into the upstream Cloudflare status and body.
fn unwrap(host: &str, address: SocketAddr, response: ApiResponse) -> Result<ApiResponse> {
    let envelope: RelayEnvelope = serde_json::from_str(&response.body).map_err(|e| {
        AetherError::Api(format!(
            "relay {host} returned non-envelope (http {}): {e}; body={}",
            response.status,
            &response.body[..response.body.len().min(200)]
        ))
    })?;
    if !envelope.ok {
        let reason = envelope
            .error
            .unwrap_or_else(|| format!("relay refused (http {})", response.status));
        return Err(AetherError::Api(format!("relay {host}: {reason}")));
    }
    Ok(ApiResponse {
        status: envelope.status,
        body: envelope.body.unwrap_or_default(),
        route: format!("relay {host} via {address}"),
    })
}

fn spawn_handshake(
    set: &mut tokio::task::JoinSet<Result<ReadySession>>,
    host: &str,
    addr: SocketAddr,
) {
    let host = host.to_string();
    set.spawn(async move { crate::apifront::open_relay_session(&host, &[addr]).await });
}

/// Resolve + handshake as fast as possible: TLS handshakes start at once on the
/// built-in Cloudflare edges (every Cloudflare edge serves every proxied host),
/// and on each Cloudflare address the resolver race returns as it arrives. The
/// first ready session wins; nothing is sent yet.
pub async fn connect(url: &str) -> Result<(RelayTarget, ReadySession)> {
    let host = host_of(url)
        .ok_or_else(|| AetherError::Api(format!("relay url has no host: {url}")))?;
    let mut tried: Vec<SocketAddr> = Vec::new();
    let mut set: tokio::task::JoinSet<Result<ReadySession>> = tokio::task::JoinSet::new();
    for raw in FALLBACK_EDGES {
        if let Ok(ip) = raw.parse::<IpAddr>() {
            let addr = SocketAddr::new(ip, 443);
            tried.push(addr);
            spawn_handshake(&mut set, &host, addr);
        }
    }
    // Every resolver answer is used, not only the first: the DoH-by-IP race
    // returns A records only, and the system resolver (A + AAAA) or DoH AAAA
    // may be the one that brings the address family that actually works.
    let mut resolvers: tokio::task::JoinSet<(Vec<IpAddr>, String)> = tokio::task::JoinSet::new();
    {
        let host = host.clone();
        resolvers.spawn(async move {
            crate::dns::resolve_a_parallel(&host, Duration::from_secs(4)).await
        });
    }
    {
        let host = host.clone();
        resolvers.spawn(async move {
            (
                crate::dns::system_lookup(&host, Duration::from_secs(4)).await,
                "system resolver A+AAAA".to_string(),
            )
        });
    }
    {
        let host = host.clone();
        resolvers.spawn(async move {
            (crate::dns::resolve_aaaa_doh(&host).await, "doh AAAA".to_string())
        });
    }
    let mut last: Option<AetherError> = None;
    let deadline = tokio::time::sleep(RELAY_CONNECT_TIMEOUT);
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            joined = resolvers.join_next(), if !resolvers.is_empty() => {
                if let Some(Ok((ips, source))) = joined {
                    let cloudflare: Vec<IpAddr> = ips
                        .into_iter()
                        .filter(|ip| crate::dns::is_cloudflare_ip(*ip))
                        .collect();
                    if !cloudflare.is_empty() {
                        note(&format!(
                            "relay {host} resolved to {} ({source}); built-in edges {} raced too",
                            cloudflare.iter().map(|ip| ip.to_string()).collect::<Vec<_>>().join(", "),
                            FALLBACK_EDGES.join(", ")
                        ));
                    }
                    for ip in cloudflare {
                        let addr = SocketAddr::new(ip, 443);
                        if !tried.contains(&addr) {
                            tried.push(addr);
                            spawn_handshake(&mut set, &host, addr);
                        }
                    }
                }
                if set.is_empty() && resolvers.is_empty() {
                    break;
                }
            }
            joined = set.join_next(), if !set.is_empty() => {
                match joined {
                    Some(Ok(Ok(session))) => {
                        set.abort_all();
                        let mut addrs = vec![session.address];
                        addrs.extend(tried.into_iter().filter(|a| *a != session.address));
                        return Ok((
                            RelayTarget { url: url.to_string(), host: host.clone(), addrs },
                            session,
                        ));
                    }
                    Some(Ok(Err(error))) => last = Some(error),
                    _ => {}
                }
                if set.is_empty() && resolvers.is_empty() {
                    break;
                }
            }
            _ = &mut deadline => {
                set.abort_all();
                last = Some(AetherError::Api(format!("relay {host}: handshake timed out")));
                break;
            }
        }
    }
    Err(last.unwrap_or_else(|| AetherError::Api(format!("relay {host}: no Cloudflare address answered"))))
}

/// Send `request` through an open relay session and return the upstream answer.
pub async fn send_on(
    target: &RelayTarget,
    session: ReadySession,
    request: &ApiRequest,
) -> Result<ApiResponse> {
    let address = session.address;
    note(&format!(
        "relay {} -> {} {} via {address}",
        target.host, request.method, request.path
    ));
    let wire = wrap(target, request)?;
    let response = tokio::time::timeout(
        RELAY_TIMEOUT,
        crate::apifront::send_on_session(session, &wire),
    )
    .await
    .map_err(|_| AetherError::Api(format!("relay {} timed out", target.host)))??;
    unwrap(&target.host, address, response)
}

/// Manual-route entry point: shuffle the relay list, resolve and fail over.
pub async fn fetch(request: &ApiRequest) -> Result<ApiResponse> {
    let urls = relay_urls();
    let mut last: Option<AetherError> = None;
    for url in urls {
        let attempt = async {
            let (target, session) = connect(&url).await?;
            send_on(&target, session, request).await
        };
        match tokio::time::timeout(RELAY_TIMEOUT + RELAY_CONNECT_TIMEOUT, attempt).await {
            Ok(Ok(response)) => return Ok(response),
            Ok(Err(error)) => {
                note(&format!("relay {url} failed: {error}"));
                last = Some(error);
            }
            Err(_) => {
                note(&format!("relay {url} timed out"));
                last = Some(AetherError::Api(format!("relay {url} timed out")));
            }
        }
    }
    Err(last.unwrap_or_else(|| AetherError::Api("no relay URL was configured".into())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_is_parsed_from_a_url() {
        assert_eq!(
            host_of("https://dark-brook-3ef5.behroozuac5.workers.dev/").as_deref(),
            Some("dark-brook-3ef5.behroozuac5.workers.dev")
        );
        assert_eq!(host_of("https://relay.example.com:8443/r").as_deref(), Some("relay.example.com"));
        assert_eq!(host_of("https:///").as_deref(), None);
    }

    #[test]
    fn the_path_defaults_to_root() {
        assert_eq!(path_of("https://a.workers.dev"), "/");
        assert_eq!(path_of("https://a.workers.dev/"), "/");
        assert_eq!(path_of("https://a.example/r"), "/r");
    }

    #[test]
    fn an_envelope_is_unwrapped_into_the_upstream_answer() {
        let address: SocketAddr = "104.21.60.172:443".parse().unwrap();
        let ok = ApiResponse {
            status: 200,
            body: "{\"ok\":true,\"status\":404,\"body\":\"{}\"}".to_string(),
            route: String::new(),
        };
        let inner = unwrap("r.example", address, ok).expect("unwrapped");
        assert_eq!(inner.status, 404);
        assert_eq!(inner.body, "{}");
        let refused = ApiResponse {
            status: 403,
            body: "{\"ok\":false,\"status\":0,\"error\":\"route not allowed\"}".to_string(),
            route: String::new(),
        };
        assert!(unwrap("r.example", address, refused).is_err());
    }

    #[test]
    fn the_built_in_edges_cover_both_families_and_are_cloudflare() {
        let ips: Vec<IpAddr> = FALLBACK_EDGES.iter().map(|s| s.parse().unwrap()).collect();
        assert!(ips.iter().any(|ip| ip.is_ipv4()));
        assert!(ips.iter().any(|ip| ip.is_ipv6()));
        assert!(ips.iter().all(|ip| crate::dns::is_cloudflare_ip(*ip)));
    }

    #[test]
    fn the_default_relay_is_used_when_the_env_is_empty() {
        std::env::remove_var("AETHER_API_RELAY");
        let urls = relay_urls();
        assert_eq!(urls.len(), 1);
        assert!(urls[0].contains("workers.dev"));
    }

    #[test]
    fn a_comma_list_is_split_and_trimmed() {
        std::env::set_var("AETHER_API_RELAY", " https://a.example/ , https://b.example/ ");
        let urls = relay_urls();
        assert_eq!(urls.len(), 2);
        assert!(urls.iter().any(|u| u == "https://a.example/"));
        assert!(urls.iter().any(|u| u == "https://b.example/"));
        std::env::remove_var("AETHER_API_RELAY");
    }

    #[test]
    fn the_payload_carries_method_path_headers_and_body() {
        let request = ApiRequest {
            method: "POST".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg".to_string(),
            headers: vec![("CF-Client-Version".to_string(), "a-6.35-4471".to_string())],
            body: Some(b"{\"a\":1}".to_vec()),
        };
        let payload = build_payload(&request);
        assert_eq!(payload.method, "POST");
        assert_eq!(payload.path, "/v0a4471/reg");
        assert_eq!(payload.body.as_deref(), Some("{\"a\":1}"));
        assert_eq!(
            payload.headers.get("CF-Client-Version").and_then(|v| v.as_str()),
            Some("a-6.35-4471")
        );
    }
}
