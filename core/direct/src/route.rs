use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::StreamExt;
use route_support::direct_support::{
    apply_runtime_options, certificate_retry_peers, connect_tcp, direct_range_peers, establish_wg,
    join_outcome, load_clean_h2, load_or_provision_masque, load_or_provision_warp, mark_ready,
    masque_config_path, masque_scan_peers, open_scan_carrier, record_log, remember_clean_h2, serve,
    unique_scan_peers, use_extended_connect, warp_config_path, Carrier, CarrierStartupStage,
    CarrierStartupTrace, ForwarderGuard, INNER_MTU, MASQUE_SEEDS, MAX_DATAGRAM_SIZE,
};
use route_support::error::{AetherError as CoreError, Result};
use route_support::{initialize, IpScan, ScanMode, StartOptions};
use tokio::{net::UdpSocket, sync::mpsc};

const MAX_PEERS: usize = 24;
const CHECKS_PER_SOURCE: usize = 2;
const BALANCED_RANGE_PEERS: usize = 192;

struct CandidateDiagnostic {
    peer: SocketAddr,
    source: &'static str,
    trace: Arc<CarrierStartupTrace>,
    started: Instant,
    outcome: &'static str,
    reason: &'static str,
}

impl CandidateDiagnostic {
    fn failure(&mut self, error: &CoreError) {
        self.outcome = "failed";
        self.reason = safe_failure_reason(error);
    }
}

impl Drop for CandidateDiagnostic {
    fn drop(&mut self) {
        record_log(format!(
            "Direct candidate {} {} stage={} result={} elapsed_ms={} http_status={} reason={}",
            self.peer,
            self.source,
            self.trace.stage(),
            self.outcome,
            self.started.elapsed().as_millis(),
            self.trace.http_status(),
            self.reason,
        ));
    }
}

fn safe_failure_reason(error: &CoreError) -> &'static str {
    use std::io::ErrorKind;
    match error {
        CoreError::Io(error) => match error.kind() {
            ErrorKind::TimedOut => "socket_timeout",
            ErrorKind::ConnectionRefused => "connection_refused",
            ErrorKind::ConnectionReset => "connection_reset",
            ErrorKind::PermissionDenied => "permission_denied",
            _ => "socket_error",
        },
        CoreError::Tls(message) if message.contains("timed out") => "tls_timeout",
        CoreError::Tls(_) => "tls_error",
        CoreError::Masque(message) if message == "candidate startup timed out" => "carrier_timeout",
        CoreError::Masque(message) if message.starts_with("h2 connect-ip status ") => {
            "request_rejected"
        }
        CoreError::Masque(_) => "masque_error",
        CoreError::Other(message) if message.contains("dataplane verify timed out") => {
            "wireguard_data_timeout"
        }
        CoreError::Other(message)
            if message.contains("timeout") || message.contains("timed out") =>
        {
            "timeout"
        }
        CoreError::Cancelled => "cancelled",
        _ => "native_error",
    }
}

fn eligible_carriers(
    peers: impl IntoIterator<Item = SocketAddr>,
    skip_ip: Option<IpAddr>,
) -> impl Iterator<Item = SocketAddr> {
    peers
        .into_iter()
        .filter(move |peer| Some(peer.ip()) != skip_ip)
}

fn plan_carriers(
    known: impl IntoIterator<Item = SocketAddr>,
    sampled: impl IntoIterator<Item = SocketAddr>,
) -> (Vec<SocketAddr>, Vec<SocketAddr>) {
    let known = unique_scan_peers(known, IpScan::V4);
    let saved: std::collections::HashSet<_> = known.iter().copied().collect();
    let known: Vec<_> = known.into_iter().take(MAX_PEERS / 2).collect();
    let sampled = unique_scan_peers(sampled, IpScan::V4)
        .into_iter()
        .filter(|peer| !saved.contains(peer))
        .take(MAX_PEERS - known.len())
        .collect();
    (known, sampled)
}

fn record_check(peer: SocketAddr, source: &str, stage: &str) {
    record_log(format!("Direct IP {peer} {source} {stage}"));
}

fn range_candidates(mode: ScanMode) -> Vec<SocketAddr> {
    match mode {
        ScanMode::Balanced => {
            let peers = direct_range_peers();
            let count = peers.len().min(BALANCED_RANGE_PEERS);
            (0..count)
                .map(|index| peers[index * peers.len() / count])
                .collect()
        }
        ScanMode::Thorough => direct_range_peers(),
        _ => Vec::new(),
    }
}

async fn rank_ranges(peers: Vec<SocketAddr>) -> Vec<(SocketAddr, Duration)> {
    let total = peers.len();
    record_log(format!("Direct IP scan {total}"));
    let mut probes = futures::stream::iter(peers)
        .map(|peer| async move {
            let started = Instant::now();
            let result = tokio::time::timeout(Duration::from_millis(650), connect_tcp(peer)).await;
            matches!(result, Ok(Ok(_))).then(|| (peer, started.elapsed()))
        })
        .buffer_unordered(96);
    let mut reachable = Vec::new();
    let mut checked = 0;
    while let Some(result) = probes.next().await {
        reachable.extend(result);
        checked += 1;
        if checked % 128 == 0 || checked == total {
            record_log(format!("Direct IP sweep {checked}"));
        }
    }
    reachable.sort_unstable_by_key(|&(peer, elapsed)| (elapsed, peer));
    reachable
}

pub async fn run(options: StartOptions) -> Result<()> {
    if !options.listen.ip().is_loopback() || options.tun_fd.is_some() {
        return Err(CoreError::Other(
            "Direct WireGuard requires loopback SOCKS".into(),
        ));
    }

    apply_runtime_options(&options);
    initialize();
    record_log("Direct startup stage=masque_identity");
    let masque = load_or_provision_masque(&masque_config_path(&options), false).await?;
    record_log("Direct startup stage=wireguard_identity");
    let wireguard = load_or_provision_warp(&warp_config_path(&options)).await?;
    use_extended_connect(false);
    let wg_peer = wireguard
        .assigned_endpoint
        .parse::<SocketAddr>()
        .ok()
        .filter(|peer| peer.is_ipv4() && peer.port() != 0)
        .unwrap_or_else(|| "162.159.192.1:2408".parse().unwrap());
    let hints = options
        .endpoint_cache_path
        .as_deref()
        .map(load_clean_h2)
        .unwrap_or_default();
    let shared_hints = hints.len();
    let saved_peers = load_clean_h2(&options.config_path)
        .into_iter()
        .chain(hints)
        .chain(certificate_retry_peers().into_iter().map(|(peer, _)| peer))
        .chain(
            MASQUE_SEEDS
                .iter()
                .filter_map(|ip| ip.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, 443))),
        );
    let skip_ip = options.direct_retry_skip_ip;
    let (known, sampled) = plan_carriers(
        eligible_carriers(saved_peers, skip_ip),
        eligible_carriers(
            masque_scan_peers(ScanMode::Turbo, &[443], IpScan::V4),
            skip_ip,
        ),
    );
    let range_peers: Vec<_> =
        eligible_carriers(range_candidates(options.scan_mode), skip_ip).collect();
    let rank_addresses = !range_peers.is_empty();
    record_log(format!(
        "Direct startup stage=carrier_search known={} sampled={} range={} shared_h2_hints={shared_hints}",
        known.len(),
        sampled.len(),
        range_peers.len(),
    ));
    let masque = &masque;
    let wireguard = &wireguard;
    let probe = |(peer, source, ping): (SocketAddr, &'static str, Option<Duration>)| async move {
        let mut diagnostic = CandidateDiagnostic {
            peer,
            source,
            trace: Arc::new(CarrierStartupTrace::default()),
            started: Instant::now(),
            outcome: "interrupted",
            reason: "attempt_ended",
        };
        record_check(peer, source, "https");
        let carrier = open_scan_carrier(masque, peer, Some(diagnostic.trace.clone()))
            .await
            .inspect_err(|error| {
                diagnostic.failure(error);
                record_check(peer, source, "https_failed");
            })?;
        record_check(peer, source, "wireguard");
        diagnostic
            .trace
            .advance(CarrierStartupStage::WireGuardRelay);
        let (local, mut relay) = forward_wireguard(&carrier.stack, wg_peer)
            .await
            .inspect_err(|error| {
                diagnostic.failure(error);
                record_check(peer, source, "wireguard_failed");
            })?;
        diagnostic
            .trace
            .advance(CarrierStartupStage::WireGuardValidation);
        let (stack, tunnel) = establish_wg(wireguard, local, INNER_MTU, false, 20, "Direct", true)
            .await
            .inspect_err(|error| {
                diagnostic.failure(error);
                record_check(peer, source, "wireguard_failed");
            })?;
        diagnostic.trace.advance(CarrierStartupStage::Ready);
        diagnostic.outcome = "passed";
        diagnostic.reason = "none";
        relay.0.push(tunnel.abort_handle());
        Ok::<_, CoreError>((peer, source, ping, carrier, relay, stack, tunnel))
    };
    let mut last_error = None;
    let selected = if rank_addresses {
        let ranked = rank_ranges(range_peers).await;
        let validation_seconds = if options.scan_mode == ScanMode::Thorough {
            24
        } else {
            8
        };
        // Keep validation in ping order, while checking several routes concurrently.
        let checks = futures::stream::iter(
            ranked
                .into_iter()
                .map(|(peer, ping)| (peer, "range", Some(ping))),
        )
        .map(|candidate| tokio::time::timeout(Duration::from_secs(6), probe(candidate)))
        .buffered(8);
        tokio::time::timeout(
            Duration::from_secs(validation_seconds),
            checks
                .filter_map(|result| std::future::ready(result.ok().and_then(Result::ok)))
                .next(),
        )
        .await
        .ok()
        .flatten()
    } else {
        None
    };
    let selected = match selected {
        Some(route) => route,
        None => {
            record_log(format!("Direct IP scan {}", known.len() + sampled.len()));
            let known_checks =
                futures::stream::iter(known.into_iter().map(|peer| (peer, "known", None)))
                    .map(probe)
                    .buffer_unordered(CHECKS_PER_SOURCE);
            let sampled_checks =
                futures::stream::iter(sampled.into_iter().map(|peer| (peer, "range", None)))
                    .map(probe)
                    .buffer_unordered(CHECKS_PER_SOURCE);
            let mut attempts = futures::stream::select(known_checks, sampled_checks);
            loop {
                match attempts.next().await {
                    Some(Ok(route)) => break route,
                    Some(Err(error)) => last_error = Some(error),
                    None => {
                        return Err(last_error.unwrap_or_else(|| {
                            CoreError::Other("No usable Direct WireGuard path".into())
                        }))
                    }
                }
            }
        }
    };
    let (peer, source, ping, mut carrier, _relay, stack, mut tunnel) = selected;
    let ping = if rank_addresses && ping.is_none() {
        let start = Instant::now();
        tokio::time::timeout(Duration::from_millis(650), connect_tcp(peer))
            .await
            .ok()
            .and_then(Result::ok)
            .map(|_| start.elapsed())
    } else {
        ping
    };
    if let Some(ping) = ping {
        record_log(format!(
            "Direct IP {peer} {source} selected {}",
            ping.as_millis()
        ));
    } else {
        record_check(peer, source, "selected");
    }
    remember_clean_h2(&options.config_path, peer);
    record_log(format!(
        "Direct WireGuard handshake and data validated via H2 {peer}"
    ));
    mark_ready();
    let result = tokio::select! {
        result = &mut carrier.task => join_outcome("Direct HTTP/2 carrier", result),
        result = &mut tunnel => join_outcome("Direct WireGuard tunnel", result),
        result = serve(options.listen, stack) => result,
    };
    tunnel.abort();
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn startup_trace_keeps_the_furthest_stage_and_a_safe_failure_reason() {
        let trace = CarrierStartupTrace::default();
        trace.advance(CarrierStartupStage::TlsHandshake);
        trace.advance(CarrierStartupStage::TcpConnect);
        assert_eq!(trace.stage(), "tls_handshake");
        assert_eq!(
            safe_failure_reason(&CoreError::Masque("h2 connect-ip status 400".into())),
            "request_rejected"
        );
    }

    #[test]
    fn retry_excludes_the_carrier_ip_on_every_port() {
        let failed = SocketAddr::from(([192, 0, 2, 1], 443));
        let same_ip = SocketAddr::from(([192, 0, 2, 1], 8443));
        let alternate = SocketAddr::from(([192, 0, 2, 2], 443));
        let peers = [failed, same_ip, alternate];
        assert_eq!(
            eligible_carriers(peers, Some(failed.ip())).collect::<Vec<_>>(),
            [alternate]
        );
        assert_eq!(eligible_carriers(peers, None).collect::<Vec<_>>(), peers);
    }

    #[test]
    fn search_modes_scan_distinct_range_sizes() {
        assert!(range_candidates(ScanMode::Turbo).is_empty());
        let balanced = range_candidates(ScanMode::Balanced);
        let deep = range_candidates(ScanMode::Thorough);
        assert_eq!(balanced.len(), BALANCED_RANGE_PEERS);
        assert!(deep.len() > balanced.len());
        assert!(balanced.iter().all(|peer| deep.contains(peer)));
    }

    #[test]
    fn reserves_half_the_budget_for_distinct_range_addresses() {
        let peers = |prefix, count| {
            (1..=count).map(move |host| SocketAddr::from(([192, 0, prefix, host], 443)))
        };
        let (known, sampled) = plan_carriers(peers(2, 16), peers(2, 16).chain(peers(3, 24)));
        assert_eq!(known, peers(2, 12).collect::<Vec<_>>());
        assert_eq!(sampled, peers(3, 12).collect::<Vec<_>>());

        let (known, sampled) = plan_carriers(peers(2, 4), peers(3, 24));
        assert_eq!(known.len(), 4);
        assert_eq!(sampled.len(), 20);
    }
}

async fn forward_wireguard(
    carrier: &Carrier,
    peer: SocketAddr,
) -> Result<(SocketAddr, ForwarderGuard)> {
    let socket = UdpSocket::bind("127.0.0.1:0").await?;
    let local = socket.local_addr()?;
    let (tx, mut rx) = mpsc::channel(32);
    let route = carrier.open(peer, local, tx)?;
    let task = tokio::spawn(async move {
        let client = std::sync::OnceLock::new();
        let uplink = async {
            let mut packet = [0; MAX_DATAGRAM_SIZE + 1];
            loop {
                let (len, from) = socket.recv_from(&mut packet).await?;
                if len <= MAX_DATAGRAM_SIZE && *client.get_or_init(|| from) == from {
                    route.send(peer, &packet[..len]).await?;
                }
            }
        };
        let downlink = async {
            while let Some((_, _, data)) = rx.recv().await {
                if let Some(client) = client.get() {
                    socket.send_to(&data, client).await?;
                }
            }
            Ok::<_, CoreError>(())
        };
        tokio::select! {
            result = uplink => result,
            result = downlink => result,
        }
    });
    Ok((local, ForwarderGuard(vec![task.abort_handle()])))
}
