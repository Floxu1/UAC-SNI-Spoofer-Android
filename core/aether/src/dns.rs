use std::net::SocketAddr;
use std::time::Duration;

use tokio::net::UdpSocket;
use tokio::time::timeout_at;

use crate::error::{AetherError, Result};

pub const BOOTSTRAP_DNS: &[&str] = &["1.1.1.1:53", "1.0.0.1:53", "8.8.8.8:53"];
pub const ECH_HOSTS: &[&str] = &["cloudflare-ech.com", "crypto.cloudflare.com"];

/// ECHConfigList of cloudflare-ech.com captured 2026-10-09. Used when the
/// network answers the HTTPS lookup with bytes BoringSSL will not accept.
pub const ECH_CONFIG_FALLBACK: &[u8] = &[
    0x00, 0x45, 0xfe, 0x0d, 0x00, 0x41, 0x79, 0x00, 0x20, 0x00, 0x20, 0x88, 0x16, 0xf6, 0xbe, 0x48,
    0xf2, 0x70, 0xf7, 0x5a, 0x51, 0xab, 0xe4, 0x69, 0xa8, 0x32, 0x56, 0xa2, 0xaf, 0x43, 0xc1, 0xc2,
    0xea, 0xb5, 0x25, 0xfe, 0xae, 0xc9, 0xd6, 0x07, 0x8b, 0xff, 0x33, 0x00, 0x04, 0x00, 0x01, 0x00,
    0x01, 0x00, 0x12, 0x63, 0x6c, 0x6f, 0x75, 0x64, 0x66, 0x6c, 0x61, 0x72, 0x65, 0x2d, 0x65, 0x63,
    0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
];

pub fn plausible_ech_list(raw: &[u8]) -> bool {
    if raw.len() < 8 || raw.len() > 4096 {
        return false;
    }
    let list_len = u16::from_be_bytes([raw[0], raw[1]]) as usize;
    list_len + 2 == raw.len() && raw[2] == 0xfe && raw[3] == 0x0d
}

const RR_HTTPS: u16 = 65;
const SVCPARAM_ECH: u16 = 5;

pub async fn fetch_ech_config() -> Result<Vec<u8>> {
    for host in ECH_HOSTS {
        for server in BOOTSTRAP_DNS {
            let addr: SocketAddr = match server.parse() {
                Ok(a) => a,
                Err(_) => continue,
            };
            match query_ech(addr, host).await {
                Ok(ech) if !ech.is_empty() => {
                    log::info!(
                        "fetched ECHConfigList ({} bytes) for {host} via {server}",
                        ech.len()
                    );
                    return Ok(ech);
                }
                Ok(_) => {}
                Err(e) => log::debug!("ech bootstrap {host}@{server} failed: {e}"),
            }
        }
    }
    Err(AetherError::Ech("no ECHConfigList resolved".into()))
}

async fn query_ech(server: SocketAddr, host: &str) -> Result<Vec<u8>> {
    let bind = if server.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    };
    let sock = UdpSocket::bind(bind).await?;
    crate::platform::protect_socket(&sock).map_err(AetherError::Io)?;
    sock.connect(server).await?;

    let (query, id) = build_query(host, RR_HTTPS);
    sock.send(&query).await?;

    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    let mut buf = [0u8; 4096];

    loop {
        let n = timeout_at(deadline, sock.recv(&mut buf))
            .await
            .map_err(|_| AetherError::Ech("dns timeout".into()))??;

        if !response_matches(&buf[..n], id, host, RR_HTTPS) {
            log::debug!("discarding an ech dns reply that does not match the query");
            continue;
        }

        return parse_https_ech(&buf[..n])
            .ok_or_else(|| AetherError::Ech("no ech svcparam".into()));
    }
}

pub fn response_matches(
    msg: &[u8],
    expected_id: u16,
    expected_name: &str,
    expected_qtype: u16,
) -> bool {
    if msg.len() < 12 {
        return false;
    }
    if u16::from_be_bytes([msg[0], msg[1]]) != expected_id {
        return false;
    }
    if msg[2] & 0x80 == 0 {
        return false;
    }
    if u16::from_be_bytes([msg[4], msg[5]]) != 1 {
        return false;
    }

    let mut pos = 12;
    for label in expected_name.split('.') {
        if label.is_empty() {
            continue;
        }
        let len = match msg.get(pos) {
            Some(value) => *value as usize,
            None => return false,
        };
        if len != label.len() {
            return false;
        }
        pos += 1;
        let end = match pos.checked_add(len) {
            Some(value) if value <= msg.len() => value,
            _ => return false,
        };
        if !msg[pos..end].eq_ignore_ascii_case(label.as_bytes()) {
            return false;
        }
        pos = end;
    }

    if msg.get(pos) != Some(&0) {
        return false;
    }
    pos += 1;

    if pos + 4 > msg.len() {
        return false;
    }

    u16::from_be_bytes([msg[pos], msg[pos + 1]]) == expected_qtype
}

fn build_query(name: &str, qtype: u16) -> (Vec<u8>, u16) {
    let mut q = Vec::with_capacity(32 + name.len());
    let id: u16 = rand::random();
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00]);
    q.extend_from_slice(&[0x00, 0x01]);
    q.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in name.split('.') {
        if label.is_empty() {
            continue;
        }
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0x00);
    q.extend_from_slice(&qtype.to_be_bytes());
    q.extend_from_slice(&[0x00, 0x01]);
    (q, id)
}

fn parse_https_ech(msg: &[u8]) -> Option<Vec<u8>> {
    if msg.len() < 12 {
        return None;
    }
    let qd = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let an = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut pos = 12;

    for _ in 0..qd {
        pos = skip_name(msg, pos)?;
        pos = pos.checked_add(4)?;
    }

    for _ in 0..an {
        pos = skip_name(msg, pos)?;
        if pos + 10 > msg.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([msg[pos], msg[pos + 1]]);
        let rdlen = u16::from_be_bytes([msg[pos + 8], msg[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > msg.len() {
            return None;
        }
        if rtype == RR_HTTPS {
            if let Some(ech) = parse_svcparams_ech(msg, pos, rdlen) {
                return Some(ech);
            }
        }
        pos += rdlen;
    }
    None
}

fn parse_svcparams_ech(msg: &[u8], rdata_start: usize, rdlen: usize) -> Option<Vec<u8>> {
    let end = rdata_start + rdlen;
    if rdata_start + 2 > end {
        return None;
    }
    let mut p = skip_name(msg, rdata_start + 2)?;

    while p + 4 <= end {
        let key = u16::from_be_bytes([msg[p], msg[p + 1]]);
        let len = u16::from_be_bytes([msg[p + 2], msg[p + 3]]) as usize;
        p += 4;
        if p + len > end {
            return None;
        }
        if key == SVCPARAM_ECH {
            return Some(msg[p..p + len].to_vec());
        }
        p += len;
    }
    None
}

fn skip_name(buf: &[u8], mut pos: usize) -> Option<usize> {
    loop {
        let len = *buf.get(pos)?;
        if len & 0xc0 == 0xc0 {
            return Some(pos + 2);
        }
        if len == 0 {
            return Some(pos + 1);
        }
        pos += 1 + len as usize;
    }
}

/// DoH JSON endpoints addressed by IP (no hostname to filter). On MCI these
/// returned the ech param while plain UDP to public resolvers had it stripped.
pub const DOH_JSON_IPS: &[&str] = &["8.8.8.8", "8.8.4.4"];
/// Extra UDP resolvers for the parallel bootstrap (Shecan answers on MCI).
pub const EXTRA_BOOTSTRAP_DNS: &[&str] = &["178.22.122.100:53", "185.51.200.2:53"];

/// ECHConfigList the host app fetched for the key request (AETHER_API_ECH,
/// base64). The MASQUE transport keeps using AETHER_ECH; this one only feeds
/// the `doh` key route, so an unset variable changes nothing.
pub fn supplied_api_ech() -> Option<Vec<u8>> {
    use base64::Engine as _;
    let raw = std::env::var("AETHER_API_ECH").ok()?;
    let raw = raw.trim().trim_matches('"');
    if raw.is_empty() {
        return None;
    }
    match base64::engine::general_purpose::STANDARD.decode(raw) {
        Ok(list) if plausible_ech_list(&list) => Some(list),
        Ok(list) => {
            log::warn!(
                "[-] AETHER_API_ECH is not an ECHConfigList ({} bytes); ignoring it",
                list.len()
            );
            None
        }
        Err(e) => {
            log::warn!("[-] bad AETHER_API_ECH: {e}; ignoring it");
            None
        }
    }
}

/// Race DoH-by-IP and several UDP resolvers for the ECHConfigList and keep the
/// first plausible answer. Returns the list and a label naming its source.
pub async fn fetch_ech_config_parallel(budget: Duration) -> Result<(Vec<u8>, String)> {
    let mut set: tokio::task::JoinSet<Option<(Vec<u8>, String)>> = tokio::task::JoinSet::new();
    for host in ECH_HOSTS.iter().copied() {
        for ip in DOH_JSON_IPS.iter().copied() {
            set.spawn(async move {
                match query_doh_json(ip, host).await {
                    Ok(list) => Some((list, format!("doh https://{ip}/resolve ({host})"))),
                    Err(e) => {
                        log::debug!("ech doh {host}@{ip} failed: {e}");
                        None
                    }
                }
            });
        }
        for server in EXTRA_BOOTSTRAP_DNS
            .iter()
            .chain(BOOTSTRAP_DNS.iter())
            .copied()
        {
            set.spawn(async move {
                let addr: SocketAddr = server.parse().ok()?;
                match query_ech(addr, host).await {
                    Ok(list) => Some((list, format!("udp {server} ({host})"))),
                    Err(e) => {
                        log::debug!("ech bootstrap {host}@{server} failed: {e}");
                        None
                    }
                }
            });
        }
    }
    let deadline = tokio::time::Instant::now() + budget;
    loop {
        match timeout_at(deadline, set.join_next()).await {
            Ok(Some(Ok(Some((list, source))))) if plausible_ech_list(&list) => {
                set.abort_all();
                return Ok((list, source));
            }
            Ok(Some(_)) => continue,
            Ok(None) => break,
            Err(_) => {
                set.abort_all();
                break;
            }
        }
    }
    Err(AetherError::Ech(
        "parallel ECH bootstrap found no ECHConfigList".into(),
    ))
}

async fn query_doh_json(ip: &str, host: &str) -> Result<Vec<u8>> {
    use base64::Engine as _;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
        .map_err(|e| AetherError::Ech(e.to_string()))?;
    let url = format!("https://{ip}/resolve?name={host}&type=HTTPS");
    let body: serde_json::Value = client
        .get(&url)
        .header("accept", "application/dns-json")
        .send()
        .await
        .map_err(|e| AetherError::Ech(e.to_string()))?
        .json()
        .await
        .map_err(|e| AetherError::Ech(e.to_string()))?;
    let answers = body
        .get("Answer")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    for answer in answers {
        let Some(data) = answer.get("data").and_then(|v| v.as_str()) else {
            continue;
        };
        if let Some(b64) = ech_from_presentation(data) {
            if let Ok(list) = base64::engine::general_purpose::STANDARD.decode(b64) {
                if plausible_ech_list(&list) {
                    return Ok(list);
                }
            }
        }
    }
    Err(AetherError::Ech("doh answer carried no ech param".into()))
}

/// Cloudflare's published IPv4/IPv6 ranges. A relay host is always proxied by
/// Cloudflare (workers.dev or a custom domain on a Worker route), so an answer
/// outside these ranges is a resolver bending the name (e.g. a proxy-DNS pool).
const CLOUDFLARE_RANGES: &[&str] = &[
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32",
];

pub fn is_cloudflare_ip(ip: std::net::IpAddr) -> bool {
    CLOUDFLARE_RANGES.iter().any(|cidr| {
        cidr.parse::<ipnet::IpNet>()
            .map(|net| net.contains(&ip))
            .unwrap_or(false)
    })
}

const RR_A: u16 = 1;

async fn query_doh_json_a(ip: &str, host: &str) -> Result<Vec<std::net::IpAddr>> {
    query_doh_json_addrs(ip, host, RR_A).await
}

const RR_AAAA: u16 = 28;

/// System resolver (A and AAAA together), capped at `budget`.
///
/// `resolve_a_parallel` stops at the first source with a Cloudflare answer,
/// and DoH-by-IP only asks for A records, so on a network where IPv4 TLS to
/// Cloudflare stalls but IPv6 works (an MCI phone hotspot, 2026-10-22) the
/// relay could end up with IPv4 candidates only. The relay races this too.
pub async fn system_lookup(host: &str, budget: Duration) -> Vec<std::net::IpAddr> {
    let mut out: Vec<std::net::IpAddr> = Vec::new();
    if let Ok(Ok(addrs)) = tokio::time::timeout(budget, tokio::net::lookup_host((host, 443))).await {
        for addr in addrs {
            if !out.contains(&addr.ip()) {
                out.push(addr.ip());
            }
        }
    }
    out
}

/// AAAA over DoH-by-IP (8.8.8.8 / 8.8.4.4); the first non-empty answer wins.
pub async fn resolve_aaaa_doh(host: &str) -> Vec<std::net::IpAddr> {
    let mut set: tokio::task::JoinSet<Vec<std::net::IpAddr>> = tokio::task::JoinSet::new();
    for ip in DOH_JSON_IPS.iter().copied() {
        let host = host.to_string();
        set.spawn(async move { query_doh_json_addrs(ip, &host, RR_AAAA).await.unwrap_or_default() });
    }
    while let Some(joined) = set.join_next().await {
        if let Ok(found) = joined {
            if !found.is_empty() {
                set.abort_all();
                return found;
            }
        }
    }
    Vec::new()
}

async fn query_doh_json_addrs(ip: &str, host: &str, rtype: u16) -> Result<Vec<std::net::IpAddr>> {
    let kind = if rtype == RR_AAAA { "AAAA" } else { "A" };
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
        .map_err(|e| AetherError::Api(e.to_string()))?;
    let url = format!("https://{ip}/resolve?name={host}&type={kind}");
    let body: serde_json::Value = client
        .get(&url)
        .header("accept", "application/dns-json")
        .send()
        .await
        .map_err(|e| AetherError::Api(e.to_string()))?
        .json()
        .await
        .map_err(|e| AetherError::Api(e.to_string()))?;
    let mut out = Vec::new();
    for answer in body
        .get("Answer")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default()
    {
        if answer.get("type").and_then(|v| v.as_u64()) != Some(rtype as u64) {
            continue;
        }
        if let Some(ip) = answer
            .get("data")
            .and_then(|v| v.as_str())
            .and_then(|d| d.trim().parse::<std::net::IpAddr>().ok())
        {
            out.push(ip);
        }
    }
    if out.is_empty() {
        return Err(AetherError::Api(format!("doh {kind} answer was empty")));
    }
    Ok(out)
}

fn parse_a_answers(msg: &[u8]) -> Vec<std::net::IpAddr> {
    let mut out = Vec::new();
    if msg.len() < 12 {
        return out;
    }
    let qd = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let an = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut pos = 12;
    for _ in 0..qd {
        pos = match skip_name(msg, pos).and_then(|p| p.checked_add(4)) {
            Some(p) => p,
            None => return out,
        };
    }
    for _ in 0..an {
        pos = match skip_name(msg, pos) {
            Some(p) => p,
            None => return out,
        };
        if pos + 10 > msg.len() {
            return out;
        }
        let rtype = u16::from_be_bytes([msg[pos], msg[pos + 1]]);
        let rdlen = u16::from_be_bytes([msg[pos + 8], msg[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > msg.len() {
            return out;
        }
        if rtype == RR_A && rdlen == 4 {
            out.push(std::net::IpAddr::V4(std::net::Ipv4Addr::new(
                msg[pos],
                msg[pos + 1],
                msg[pos + 2],
                msg[pos + 3],
            )));
        }
        pos += rdlen;
    }
    out
}

async fn query_a_udp(server: SocketAddr, host: &str) -> Result<Vec<std::net::IpAddr>> {
    let sock = UdpSocket::bind("0.0.0.0:0").await?;
    crate::platform::protect_socket(&sock).map_err(AetherError::Io)?;
    sock.connect(server).await?;
    let (query, id) = build_query(host, RR_A);
    sock.send(&query).await?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    let mut buf = [0u8; 2048];
    loop {
        let n = timeout_at(deadline, sock.recv(&mut buf))
            .await
            .map_err(|_| AetherError::Api("dns timeout".into()))??;
        if !response_matches(&buf[..n], id, host, RR_A) {
            continue;
        }
        let ips = parse_a_answers(&buf[..n]);
        if ips.is_empty() {
            return Err(AetherError::Api("udp A answer was empty".into()));
        }
        return Ok(ips);
    }
}

/// Resolve `host` to addresses by racing DoH-by-IP (8.8.8.8 / 8.8.4.4),
/// Shecan / extra UDP resolvers and the system resolver.
///
/// The system resolver is not trusted alone: a proxy DNS can hand back a pool
/// address for a Cloudflare-proxied name. Answers inside Cloudflare's ranges
/// win; the race returns as soon as one source yields such an answer. If no
/// source does within `budget`, whatever arrived is returned (Cloudflare first).
/// The second element names the source(s) for the log.
pub async fn resolve_a_parallel(
    host: &str,
    budget: Duration,
) -> (Vec<std::net::IpAddr>, String) {
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return (vec![ip], "literal".to_string());
    }
    let mut set: tokio::task::JoinSet<(String, Vec<std::net::IpAddr>)> =
        tokio::task::JoinSet::new();
    for ip in DOH_JSON_IPS.iter().copied() {
        let host = host.to_string();
        set.spawn(async move {
            let found = query_doh_json_a(ip, &host).await.unwrap_or_default();
            (format!("doh https://{ip}/resolve"), found)
        });
    }
    for server in EXTRA_BOOTSTRAP_DNS.iter().copied() {
        let host = host.to_string();
        set.spawn(async move {
            let found = match server.parse::<SocketAddr>() {
                Ok(addr) => query_a_udp(addr, &host).await.unwrap_or_default(),
                Err(_) => Vec::new(),
            };
            (format!("udp {server}"), found)
        });
    }
    {
        let host = host.to_string();
        set.spawn(async move {
            let found = match tokio::time::timeout(
                Duration::from_secs(3),
                tokio::net::lookup_host((host.as_str(), 443)),
            )
            .await
            {
                Ok(Ok(addrs)) => addrs.map(|a| a.ip()).collect(),
                _ => Vec::new(),
            };
            ("system resolver".to_string(), found)
        });
    }

    let deadline = tokio::time::Instant::now() + budget;
    let mut cloudflare: Vec<std::net::IpAddr> = Vec::new();
    let mut other: Vec<std::net::IpAddr> = Vec::new();
    let mut sources: Vec<String> = Vec::new();
    loop {
        match timeout_at(deadline, set.join_next()).await {
            Ok(Some(Ok((source, found)))) => {
                if found.is_empty() {
                    continue;
                }
                let mut useful = false;
                for ip in found {
                    if is_cloudflare_ip(ip) {
                        if !cloudflare.contains(&ip) {
                            cloudflare.push(ip);
                        }
                        useful = true;
                    } else {
                        log::info!("[dns] {source} answered {host} with non-Cloudflare {ip}; ignored unless nothing else answers");
                        if !other.contains(&ip) {
                            other.push(ip);
                        }
                    }
                }
                if useful {
                    sources.push(source);
                    set.abort_all();
                    break;
                }
            }
            Ok(Some(Err(_))) => continue,
            Ok(None) => break,
            Err(_) => {
                set.abort_all();
                break;
            }
        }
    }
    // IPv4 first: many Iranian SIMs break AAAA paths.
    cloudflare.sort_by_key(|ip| if ip.is_ipv4() { 0u8 } else { 1u8 });
    if !cloudflare.is_empty() {
        return (cloudflare, sources.join(", "));
    }
    (other, "non-Cloudflare answers only".to_string())
}

/// Pull the base64 `ech=` value out of an HTTPS record in presentation form.
fn ech_from_presentation(data: &str) -> Option<String> {
    let start = data.find("ech=")? + 4;
    let value: String = data[start..]
        .trim_start_matches('"')
        .chars()
        .take_while(|c| !c.is_whitespace() && *c != '"')
        .collect();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloudflare_ranges_are_recognised_and_proxy_pool_ips_are_not() {
        assert!(is_cloudflare_ip("104.21.60.172".parse().unwrap()));
        assert!(is_cloudflare_ip("172.67.198.113".parse().unwrap()));
        assert!(is_cloudflare_ip("162.159.199.1".parse().unwrap()));
        assert!(!is_cloudflare_ip("50.7.177.12".parse().unwrap()));
        assert!(!is_cloudflare_ip("142.54.178.211".parse().unwrap()));
    }

    #[test]
    fn a_records_are_read_from_a_udp_answer() {
        let (mut msg, _) = build_query("a.example", RR_A);
        msg[2] |= 0x80;
        msg[7] = 1; // ancount
        msg.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 21, 60, 172]);
        let ips = parse_a_answers(&msg);
        assert_eq!(ips, vec!["104.21.60.172".parse::<std::net::IpAddr>().unwrap()]);
    }

    #[test]
    fn ech_value_is_read_from_a_doh_presentation_answer() {
        let data = "1 . alpn=\"h3,h2\" ipv4hint=104.18.10.118 ech=AEX+DQBB ipv6hint=2606:4700::1";
        assert_eq!(ech_from_presentation(data).as_deref(), Some("AEX+DQBB"));
        let quoted = "1 . ech=\"AEX+DQBB\"";
        assert_eq!(ech_from_presentation(quoted).as_deref(), Some("AEX+DQBB"));
        assert_eq!(ech_from_presentation("1 . alpn=h2"), None);
    }

    fn reply(id: u16, name: &str, qtype: u16, qr: bool, qdcount: u16) -> Vec<u8> {
        let mut msg = Vec::new();
        msg.extend_from_slice(&id.to_be_bytes());
        msg.push(if qr { 0x81 } else { 0x01 });
        msg.push(0x80);
        msg.extend_from_slice(&qdcount.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&[0, 0, 0, 0]);
        for label in name.split('.') {
            msg.push(label.len() as u8);
            msg.extend_from_slice(label.as_bytes());
        }
        msg.push(0);
        msg.extend_from_slice(&qtype.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg
    }

    #[test]
    fn build_query_reports_the_id_it_wrote() {
        let (query, id) = build_query("cloudflare-ech.com", RR_HTTPS);
        assert_eq!(u16::from_be_bytes([query[0], query[1]]), id);
    }

    #[test]
    fn accepts_a_reply_that_matches_the_query() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 1);
        assert!(response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_spoofed_reply_with_the_wrong_transaction_id() {
        let msg = reply(0x9999, "cloudflare-ech.com", RR_HTTPS, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_for_a_different_name() {
        let msg = reply(0x1234, "attacker.example", RR_HTTPS, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_for_a_different_record_type() {
        let msg = reply(0x1234, "cloudflare-ech.com", 1, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_message_that_is_not_a_response() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, false, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_with_an_unexpected_question_count() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 2);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_truncated_input_without_panicking() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 1);
        for cut in 0..msg.len() {
            assert!(!response_matches(
                &msg[..cut],
                0x1234,
                "cloudflare-ech.com",
                RR_HTTPS
            ));
        }
    }

    #[test]
    fn name_comparison_is_case_insensitive() {
        let msg = reply(0x1234, "CloudFlare-ECH.com", RR_HTTPS, true, 1);
        assert!(response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }
}
