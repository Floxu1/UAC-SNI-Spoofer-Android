//! "OOB" key route: TCP urgent-data (MSG_OOB) desyncs of the ClientHello,
//! done natively (no helper process, relay or server).
//!
//! Verified on raw MCI (AS197207, 2026-10-23) with real register + MASQUE
//! enroll + delete (key-test.ps1, C# `OobFirstWriteStream`): three send
//! methods got KEY=YES. They are tried one after another, in this order:
//!
//! 1. `disoob-byte0-ttl1`: TTL=1, `ch[0]` as urgent, TTL restored, 50 ms,
//!    then the full hello (byte 0 again) normally.
//! 2. `oob-insni+5-ttl1`: off = SNI host start + 5. TTL=1, `ch[..off]`
//!    normally, `ch[off]` as urgent, TTL restored, 30 ms, then `ch[off..]`.
//! 3. `oob-byte0-nottl`: `ch[0]` as urgent (TTL untouched), 30 ms, then the
//!    full hello. Flaky (handshake passes, HTTP sometimes hangs), so last.
//!
//! On Android the auto order matches the Windows C# probe that got a key
//! (`disoob-byte0-ttl1`, `oob-insni+5-ttl1`, `oob-byte0-nottl`), then the
//! Android-only plain/andr/split variants. TTL is restored immediately after
//! the urgent byte (C# order); the pause happens before the normal hello.
//! Each HTTP request uses a fresh TLS connection (`Connection: close`).
//!
//! The TTL-1 segments die at the first hop, so the DPI never sees an in-order
//! hello; the kernel retransmits them with the restored TTL and the server's
//! TCP strips the urgent byte, so it reads exactly the hello.
//!
//! Only ordinary socket options are used (no raw sockets, no admin / root),
//! so the same code runs on Windows and on Android (Linux).
//!
//! The connection goes only to real Cloudflare API addresses: the built-in
//! A / AAAA of api.cloudflareclient.com plus fresh A / AAAA answers from
//! DoH-by-IP, all filtered to Cloudflare ranges (a proxy DNS can never bend it).

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::task::{Context, Poll};
use std::time::Duration;

use tokio::io::Interest;
use tokio::net::TcpStream;

use crate::apifront::{ApiRequest, ApiResponse, ReadySession};
use crate::error::{AetherError, Result};

/// api.cloudflareclient.com A / AAAA (2026-10-23, Google and Cloudflare DoH).
pub const API_EDGES: &[&str] = &[
    "104.16.24.84",
    "104.16.192.82",
    "2606:4700::6810:1854",
    "2606:4700::6810:c052",
];
/// Budget for resolve + connect + desynced TLS handshake of one method.
const OOB_CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
/// Budget for the HTTP exchange once the handshake is ready (one variant).
/// OOB without TTL sometimes passes the handshake and then hangs, so this is
/// kept short and the other TLS variant / next method is tried.
/// `AETHER_OOB_METHOD_TIMEOUT_MS`.
#[cfg(not(target_os = "android"))]
const OOB_EXCHANGE_TIMEOUT: Duration = Duration::from_secs(8);
/// Android: fail over faster after handshake+0-byte hang so Worker relay can start.
#[cfg(target_os = "android")]
const OOB_EXCHANGE_TIMEOUT: Duration = Duration::from_secs(4);

/// TLS / HTTP variants of one OOB method, in default try order.
/// - `Modern` (TLS 1.3 allowed, ALPN http/1.1): the Rust default that got a
///   key on raw MCI. On MCI (2026-10-23) the TLS 1.2 variant passed the
///   handshake and then never got the response, so 1.3 goes first.
/// - `Oob` (TLS 1.2 only, no ALPN): mirrors the C# SChannel probe that got a
///   key on AS48944 where the TLS 1.3 exchange hung after the handshake.
/// Both use HTTP/1.1 keep-alive with an exact Content-Length / chunked read,
/// so POST /reg and the following PATCH share one connection. The second
/// variant runs only when the first one's handshake passed but its exchange
/// failed; the winner is tried first for the rest of the process.
const TLS_VARIANTS: [crate::apifront::Fingerprint; 2] = [
    crate::apifront::Fingerprint::Modern,
    crate::apifront::Fingerprint::Oob,
];

/// Index into TLS_VARIANTS of the last variant that got an answer (255 none).
static PREFERRED_VARIANT: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(255);

/// TLS_VARIANTS with the last winner first. `AETHER_OOB_TLS=12` / `13` puts
/// that variant first (override for tests).
fn ordered_variants() -> [crate::apifront::Fingerprint; 2] {
    // Android: TLS 1.2 / no ALPN first (handshake can pass on nottl while the
    // TLS 1.3 exchange returns 0 bytes). Windows / desktop keep Modern first.
    #[cfg(target_os = "android")]
    let mut order = [
        crate::apifront::Fingerprint::Oob,
        crate::apifront::Fingerprint::Modern,
    ];
    #[cfg(not(target_os = "android"))]
    let mut order = TLS_VARIANTS;
    let forced = match std::env::var("AETHER_OOB_TLS").ok().as_deref().map(str::trim) {
        Some("12") | Some("tls12") | Some("1.2") => Some(
            order
                .iter()
                .position(|f| *f == crate::apifront::Fingerprint::Oob)
                .unwrap_or(1),
        ),
        Some("13") | Some("tls13") | Some("1.3") => Some(
            order
                .iter()
                .position(|f| *f == crate::apifront::Fingerprint::Modern)
                .unwrap_or(0),
        ),
        _ => None,
    };
    let first = forced.or_else(|| {
        let p = PREFERRED_VARIANT.load(std::sync::atomic::Ordering::SeqCst) as usize;
        // PREFERRED_VARIANT indexes TLS_VARIANTS (Modern=0, Oob=1).
        let mapped = match p {
            0 => order
                .iter()
                .position(|f| *f == crate::apifront::Fingerprint::Modern),
            1 => order
                .iter()
                .position(|f| *f == crate::apifront::Fingerprint::Oob),
            _ => None,
        };
        mapped
    });
    if let Some(first) = first {
        order.swap(0, first);
    }
    order
}

/// Windows `schannel` variant: OFF by default since patch oob6. On both
/// tested networks (MCI and AS48944, 2026-10-23) the Rust SChannel path never
/// got one HTTP byte back, and each of its attempts cost 8-16 s and extra
/// connections that seem to make the filter throttle the later, working
/// variants. `AETHER_OOB_SCHANNEL=1` turns it back on; it then runs LAST,
/// after plain-tls13 and oob-tls12. `AETHER_OOB_TLS=schannel` runs only it.
#[cfg(windows)]
fn schannel_enabled() -> bool {
    let on = matches!(
        std::env::var("AETHER_OOB_SCHANNEL").ok().as_deref().map(str::trim),
        Some("1") | Some("on") | Some("true") | Some("yes")
    );
    let forced_builtin = matches!(
        std::env::var("AETHER_OOB_TLS").ok().as_deref().map(str::trim),
        Some("12") | Some("tls12") | Some("1.2") | Some("13") | Some("tls13") | Some("1.3")
    );
    (on || schannel_only()) && !forced_builtin
}

/// `AETHER_OOB_TLS=schannel`: only the SChannel variant (tests).
#[cfg(windows)]
fn schannel_only() -> bool {
    matches!(
        std::env::var("AETHER_OOB_TLS").ok().as_deref().map(str::trim),
        Some("schannel")
    )
}

fn exchange_timeout() -> Duration {
    std::env::var("AETHER_OOB_METHOD_TIMEOUT_MS")
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .map(|ms| Duration::from_millis(ms.clamp(1_000, 60_000)))
        .unwrap_or(OOB_EXCHANGE_TIMEOUT)
}

fn note(message: &str) {
    let line = format!("Key: {message}");
    log::info!("{line}");
    crate::ffi::record_log(line);
}

/// The three ClientHello send methods that got a real WARP key + MASQUE
/// certificate on raw MCI (key-test.ps1, 2026-10-23). Byte logic mirrors the
/// proven C# `OobFirstWriteStream.Write` exactly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OobMethod {
    /// TTL=1, `ch[0]` as TCP urgent (MSG_OOB), TTL restored, ~50 ms, then the
    /// FULL hello (byte 0 included) normally.
    DisoobByte0Ttl1,
    /// off = SNI host start + 5. TTL=1, `ch[..off]` normally, `ch[off]` as
    /// urgent, TTL restored, ~30 ms, then `ch[off..]` normally.
    OobInSni5Ttl1,
    /// `ch[0]` as urgent (no TTL change), ~30 ms, then the full hello.
    OobByte0NoTtl,
    /// Android-only: same byte pattern as `DisoobByte0Ttl1`, but waits 50 ms
    /// BEFORE restoring TTL (Linux may still hold the TTL=1 segment) and logs
    /// TTL readback loudly. Tried first on Android.
    DisoobByte0Ttl1Andr,
    /// Android-only: after TTL=1 urgent `ch[0]` + restore, send `ch[0]` as its
    /// own normal segment then `ch[1..]` after the pause (Linux send
    /// coalescing / segment shape). Stream after OOB strip is still the hello.
    DisoobByte0Ttl1Split,
    /// Android-only: TTL=1, send `ch[0]` with a NORMAL send (no MSG_OOB),
    /// restore TTL, pause, then the full hello. Linux urgent+TTL is flaky;
    /// a dying first byte without the urgent pointer may desync DPI better.
    /// On the wire the first byte dies at hop 1; the server sees only the
    /// full hello. (Loopback unit test skips this method: TTL=1 still arrives.)
    DisoobByte0Ttl1Plain,
}

/// Try order on Windows / desktop: the two TTL=1 methods were reliable; no-TTL last.
#[cfg(not(target_os = "android"))]
pub const METHOD_ORDER: [OobMethod; 3] = [
    OobMethod::DisoobByte0Ttl1,
    OobMethod::OobInSni5Ttl1,
    OobMethod::OobByte0NoTtl,
];

/// Try order on Android: Windows C# probe order first (got KEY on open net),
/// then Android-only variants.
#[cfg(target_os = "android")]
pub const METHOD_ORDER: [OobMethod; 6] = [
    OobMethod::DisoobByte0Ttl1,
    OobMethod::OobInSni5Ttl1,
    OobMethod::OobByte0NoTtl,
    OobMethod::DisoobByte0Ttl1Plain,
    OobMethod::DisoobByte0Ttl1Andr,
    OobMethod::DisoobByte0Ttl1Split,
];

impl OobMethod {
    pub fn name(self) -> &'static str {
        match self {
            OobMethod::DisoobByte0Ttl1 => "disoob-byte0-ttl1",
            OobMethod::OobInSni5Ttl1 => "oob-insni+5-ttl1",
            OobMethod::OobByte0NoTtl => "oob-byte0-nottl",
            OobMethod::DisoobByte0Ttl1Andr => "disoob-byte0-ttl1-andr",
            OobMethod::DisoobByte0Ttl1Split => "disoob-byte0-ttl1-split",
            OobMethod::DisoobByte0Ttl1Plain => "disoob-byte0-ttl1-plain",
        }
    }

    fn index(self) -> u8 {
        METHOD_ORDER.iter().position(|m| *m == self).unwrap_or(0) as u8
    }

    fn uses_ttl1(self) -> bool {
        !matches!(self, OobMethod::OobByte0NoTtl)
    }

    /// SNI-relative split (`None` = byte 0).
    fn sni_rel(self) -> Option<usize> {
        match self {
            OobMethod::OobInSni5Ttl1 => Some(5),
            _ => None,
        }
    }

    /// True for the Android variant that sends ch[0] normally before ch[1..].
    fn split_after_oob(self) -> bool {
        matches!(self, OobMethod::DisoobByte0Ttl1Split)
    }

    /// TTL=1 first byte via normal send (no MSG_OOB). Android-only variant.
    fn plain_ttl_desync(self) -> bool {
        matches!(self, OobMethod::DisoobByte0Ttl1Plain)
    }

    /// Pause after the urgent byte before the normal send.
    /// On Android every method uses the Windows-proven 50 ms (was 10/30).
    pub fn pause(self) -> Duration {
        #[cfg(target_os = "android")]
        {
            return if self.uses_ttl1() {
                Duration::from_millis(50)
            } else {
                Duration::from_millis(30)
            };
        }
        #[cfg(not(target_os = "android"))]
        match self {
            OobMethod::DisoobByte0Ttl1 => Duration::from_millis(50),
            _ => Duration::from_millis(30),
        }
    }
}

fn wall_ms() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// Method that last got an answer; tried first next time (e.g. MASQUE enroll
/// right after registration). 255 = none yet.
static PREFERRED: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(255);

/// METHOD_ORDER with the last winner moved to the front.
/// `AETHER_OOB_METHODS` (comma list of method names) overrides it, for tests.
pub fn ordered_methods() -> Vec<OobMethod> {
    if let Ok(raw) = std::env::var("AETHER_OOB_METHODS") {
        let picked: Vec<OobMethod> = raw
            .split(',')
            .filter_map(|name| METHOD_ORDER.iter().copied().find(|m| m.name() == name.trim()))
            .collect();
        if !picked.is_empty() {
            return picked;
        }
    }
    let preferred = PREFERRED.load(std::sync::atomic::Ordering::SeqCst) as usize;
    let mut out = Vec::with_capacity(METHOD_ORDER.len());
    if let Some(first) = METHOD_ORDER.get(preferred) {
        out.push(*first);
    }
    for m in METHOD_ORDER {
        if !out.contains(&m) {
            out.push(m);
        }
    }
    out
}

/// A socket that can send its first bytes with an OOB desync.
pub trait DesyncSocket {
    /// Send the desync prefix of `buf` (everything up to and including the
    /// urgent byte). Ready(Ok(resume)) = offset in `buf` from which the rest
    /// must be sent normally (0 for the byte-0 methods: the full hello again).
    fn poll_oob_prefix(
        &self,
        cx: &mut Context<'_>,
        buf: &[u8],
        method: OobMethod,
        sni_host: &str,
    ) -> Poll<io::Result<usize>>;
}

impl DesyncSocket for TcpStream {
    fn poll_oob_prefix(
        &self,
        cx: &mut Context<'_>,
        buf: &[u8],
        method: OobMethod,
        sni_host: &str,
    ) -> Poll<io::Result<usize>> {
        loop {
            match self.poll_write_ready(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Ready(Ok(())) => {}
            }
            match self.try_io(Interest::WRITABLE, || oob_prefix_send(self, buf, method, sni_host)) {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                other => return Poll::Ready(other),
            }
        }
    }
}

#[cfg(windows)]
const FALLBACK_TTL: u32 = 128;
#[cfg(not(windows))]
const FALLBACK_TTL: u32 = 64;

pub(crate) fn current_ttl(sock: &socket2::SockRef<'_>, v6: bool) -> u32 {
    let value = if v6 { sock.unicast_hops_v6() } else { sock.ttl_v4() };
    match value {
        Ok(ttl) if ttl > 0 && ttl < 256 => ttl,
        _ => FALLBACK_TTL,
    }
}

/// byedpi `setttl`: set both families, fail only when the relevant one fails.
pub(crate) fn set_ttl(sock: &socket2::SockRef<'_>, v6: bool, ttl: u32) -> io::Result<()> {
    if v6 {
        let hops = sock.set_unicast_hops_v6(ttl);
        let _ = sock.set_ttl_v4(ttl); // dual-stack socket: harmless if refused
        hops
    } else {
        sock.set_ttl_v4(ttl)
    }
}

/// First occurrence of the SNI host name bytes in the hello.
fn find_sni(buf: &[u8], host: &str) -> Option<usize> {
    let name = host.as_bytes();
    if name.is_empty() || name.len() > buf.len() {
        return None;
    }
    buf.windows(name.len()).position(|w| w == name)
}

/// Split offset of `method` in `buf` (C# `OobFirstWriteStream`: SNI not found
/// or out of range -> 0).
pub fn split_offset(buf: &[u8], method: OobMethod, sni_host: &str) -> usize {
    match method.sni_rel() {
        None => 0,
        Some(rel) => {
            let start = find_sni(buf, sni_host).unwrap_or(0);
            let off = start + rel;
            if off >= buf.len() {
                0
            } else {
                off
            }
        }
    }
}

/// Plain non-blocking send of all of `data` (the hello prefix is tiny and the
/// socket is fresh, so a short send is reported as an error, not retried).
fn send_all_now(sock: &socket2::SockRef<'_>, data: &[u8]) -> io::Result<()> {
    if data.is_empty() {
        return Ok(());
    }
    let sent = sock.send(data)?;
    if sent != data.len() {
        return Err(io::Error::other(format!(
            "oob: short prefix send ({sent} of {} bytes)",
            data.len()
        )));
    }
    Ok(())
}

/// The desync prefix of one method (see `OobMethod`). Returns the offset in
/// `buf` from which the caller sends the rest normally after `method.pause()`.
fn oob_prefix_send(
    tcp: &TcpStream,
    buf: &[u8],
    method: OobMethod,
    sni_host: &str,
) -> io::Result<usize> {
    if buf.len() < 2 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "oob: hello too short"));
    }
    let sock = socket2::SockRef::from(tcp);
    let v6 = matches!(tcp.peer_addr()?, SocketAddr::V6(_));
    let off = split_offset(buf, method, sni_host);
    let saved = current_ttl(&sock, v6);
    let ttl1 = method.uses_ttl1();

    // Parity with the Windows C# probe: OOB not inlined, Nagle off.
    #[cfg(target_os = "android")]
    {
        let _ = sock.set_out_of_band_inline(false);
        let _ = tcp.set_nodelay(true);
        note(&format!(
            "t={} OOB {} prefix start: hello={} off={off} saved_ttl={saved} ttl1={ttl1} peer={:?}",
            wall_ms(),
            method.name(),
            buf.len(),
            tcp.peer_addr().ok()
        ));
    }

    // Android plain TTL desync: no MSG_OOB. First byte leaves at TTL=1 (dies
    // at hop 1); caller then sends the full hello at the restored TTL.
    if method.plain_ttl_desync() {
        set_ttl(&sock, v6, 1)?;
        #[cfg(target_os = "android")]
        {
            let got = current_ttl(&sock, v6);
            note(&format!(
                "t={} OOB {}: TTL set to 1 (readback={got}) plain-no-MSG_OOB",
                wall_ms(),
                method.name()
            ));
            if got != 1 {
                return Err(io::Error::other(format!(
                    "oob: TTL set to 1 failed (readback {got})"
                )));
            }
        }
        send_all_now(&sock, &buf[0..1])?;
        // C# order: restore TTL immediately; Pause waits before the full hello.
        set_ttl(&sock, v6, saved)?;
        let now = current_ttl(&sock, v6);
        #[cfg(target_os = "android")]
        note(&format!(
            "t={} OOB {}: plain byte0 sent; TTL restored want={saved} readback={now}",
            wall_ms(),
            method.name()
        ));
        if now != saved {
            return Err(io::Error::other(format!(
                "oob: TTL not restored (is {now}, want {saved})"
            )));
        }
        let line = format!(
            "Key: OOB {} hello {} bytes, plain TTL1 byte0 (no MSG_OOB), resume=0, ttl {}",
            method.name(),
            buf.len(),
            current_ttl(&sock, v6)
        );
        log::info!("{line}");
        crate::ffi::record_log(line);
        return Ok(0);
    }

    if ttl1 {
        set_ttl(&sock, v6, 1)?;
        #[cfg(target_os = "android")]
        {
            let got = current_ttl(&sock, v6);
            note(&format!(
                "t={} OOB {}: TTL set to 1 (readback={got})",
                wall_ms(),
                method.name()
            ));
            if got != 1 {
                return Err(io::Error::other(format!(
                    "oob: TTL set to 1 failed (readback {got})"
                )));
            }
        }
    }
    let result = (|| {
        send_all_now(&sock, &buf[..off])?;
        let sent = sock.send_out_of_band(&buf[off..off + 1])?;
        if sent != 1 {
            return Err(io::Error::other("oob: urgent byte was not sent"));
        }
        #[cfg(target_os = "android")]
        note(&format!(
            "t={} OOB {}: sent prefix {} + urgent 1 byte (ttl1={ttl1})",
            wall_ms(),
            method.name(),
            off
        ));
        Ok(())
    })();
    if ttl1 {
        // Linux may still hold the TTL-1 segment unsent; give it a moment
        // before the TTL changes back (byedpi await_int). Windows: at once.
        // Android: 50 ms (same as the Windows post-OOB pause) — 10 ms was too short.
        // C# keyfetch-net: restore TTL at once after urgent send; Pause sleeps
        // before the normal hello. Do not sleep while TTL is still 1.
        #[cfg(all(not(windows), not(target_os = "android")))]
        if result.is_ok() {
            std::thread::sleep(Duration::from_millis(10));
        }
        let restored = set_ttl(&sock, v6, saved);
        result?;
        restored?;
        // Never let a later segment (rest of the hello, the HTTP request)
        // leave at TTL 1: read the option back and fail loudly if it stuck.
        let now = current_ttl(&sock, v6);
        #[cfg(target_os = "android")]
        note(&format!(
            "t={} OOB {}: TTL restored want={saved} readback={now}",
            wall_ms(),
            method.name()
        ));
        if now != saved {
            return Err(io::Error::other(format!(
                "oob: TTL not restored (is {now}, want {saved})"
            )));
        }
    } else {
        result?;
    }

    // Android split variant: push ch[0] as its own normal segment now, then
    // the caller sends ch[1..] after method.pause(). After the server strips
    // the urgent byte, the reassembled stream is exactly the ClientHello.
    let resume = if method.split_after_oob() && off == 0 {
        send_all_now(&sock, &buf[0..1])?;
        #[cfg(target_os = "android")]
        note(&format!(
            "t={} OOB {}: sent normal byte0 (split); resume=1",
            wall_ms(),
            method.name()
        ));
        1
    } else {
        off
    };

    let line = format!(
        "Key: OOB {} hello {} bytes, urgent byte at {off}, resume={resume}, ttl {}",
        method.name(),
        buf.len(),
        current_ttl(&sock, v6)
    );
    log::info!("{line}");
    crate::ffi::record_log(line);
    Ok(resume)
}

/// Built-in API addresses (both families).
pub fn builtin_edges() -> Vec<SocketAddr> {
    API_EDGES
        .iter()
        .filter_map(|raw| raw.parse::<IpAddr>().ok())
        .map(|ip| SocketAddr::new(ip, 443))
        .collect()
}

fn spawn_handshake(
    set: &mut tokio::task::JoinSet<Result<ReadySession>>,
    host: &str,
    address: SocketAddr,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
) {
    let host = host.to_string();
    set.spawn(async move {
        crate::apifront::open_oob_session_at(&host, address, method, fingerprint).await
    });
}

/// Open an OOB-desynced TLS session (one `method`) to the API host. Handshakes
/// start at once on the built-in addresses; DoH-by-IP A / AAAA answers
/// (Cloudflare ranges only) join as they arrive. First ready session wins;
/// nothing is sent yet.
pub async fn open_session(
    host: &str,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
) -> Result<ReadySession> {
    open_session_excluding(host, method, fingerprint, None).await
}

/// `open_session` without `exclude` (an IP already tried with this method and
/// variant in the same fetch: one attempt per IP per variant).
async fn open_session_excluding(
    host: &str,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
    exclude: Option<SocketAddr>,
) -> Result<ReadySession> {
    let mut tried: Vec<SocketAddr> = exclude.into_iter().collect();
    let mut set: tokio::task::JoinSet<Result<ReadySession>> = tokio::task::JoinSet::new();
    note(&format!(
        "OOB method {} / {} started to {host} via {}",
        method.name(),
        fingerprint.label(),
        API_EDGES.join(", ")
    ));
    for address in builtin_edges() {
        if tried.contains(&address) {
            continue;
        }
        tried.push(address);
        spawn_handshake(&mut set, host, address, method, fingerprint);
    }
    let mut resolvers: tokio::task::JoinSet<(Vec<IpAddr>, String)> = tokio::task::JoinSet::new();
    {
        let host = host.to_string();
        resolvers.spawn(async move {
            crate::dns::resolve_a_parallel(&host, Duration::from_secs(4)).await
        });
    }
    {
        let host = host.to_string();
        resolvers.spawn(async move {
            (crate::dns::resolve_aaaa_doh(&host).await, "doh AAAA".to_string())
        });
    }
    let mut last: Option<AetherError> = None;
    let deadline = tokio::time::sleep(OOB_CONNECT_TIMEOUT);
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            joined = resolvers.join_next(), if !resolvers.is_empty() => {
                if let Some(Ok((ips, source))) = joined {
                    for ip in ips.into_iter().filter(|ip| crate::dns::is_cloudflare_ip(*ip)) {
                        let address = SocketAddr::new(ip, 443);
                        if !tried.contains(&address) {
                            note(&format!("OOB: {host} also resolved to {ip} ({source})"));
                            tried.push(address);
                            spawn_handshake(&mut set, host, address, method, fingerprint);
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
                        resolvers.abort_all();
                        return Ok(session);
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
                resolvers.abort_all();
                last = Some(AetherError::Api(format!("OOB {} {host}: handshake timed out", method.name())));
                break;
            }
        }
    }
    Err(last.unwrap_or_else(|| {
        AetherError::Api(format!("OOB {} {host}: no API address answered", method.name()))
    }))
}

/// Pause between two connection attempts (fewer bursts at the filter).
const ATTEMPT_PAUSE: Duration = Duration::from_secs(1);
/// How long an idle kept-alive API connection may be reused.
/// `AETHER_OOB_KEEP_MS` (0 = never reuse).
const KEEP_IDLE: Duration = Duration::from_secs(60);

fn keep_idle() -> Duration {
    std::env::var("AETHER_OOB_KEEP_MS")
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .map(|ms| Duration::from_millis(ms.min(600_000)))
        .unwrap_or(KEEP_IDLE)
}

/// The desynced TLS connection that answered last, kept open (HTTP/1.1
/// keep-alive) so the next API call of the same key fetch (PATCH enroll right
/// after POST /reg, a GET refresh) goes over it without a new hello for the
/// filter to stop.
struct Kept {
    tls: crate::apifront::SessionTls,
    host: String,
    address: SocketAddr,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
    route: &'static str,
    since: std::time::Instant,
}

static KEPT: std::sync::Mutex<Option<Kept>> = std::sync::Mutex::new(None);

/// Exact combination (method + TLS variant + IP) that last got an answer:
/// the first thing tried whenever a new connection is needed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Winner {
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
    address: SocketAddr,
}

static WINNER: std::sync::Mutex<Option<Winner>> = std::sync::Mutex::new(None);

fn take_kept() -> Option<Kept> {
    KEPT.lock().ok().and_then(|mut slot| slot.take())
}

fn park(kept: Kept) {
    if keep_idle().is_zero() {
        return;
    }
    if let Ok(mut slot) = KEPT.lock() {
        *slot = Some(kept);
    }
}

fn winner() -> Option<Winner> {
    WINNER.lock().ok().and_then(|slot| *slot)
}

fn record_winner(method: OobMethod, fingerprint: crate::apifront::Fingerprint, address: SocketAddr) {
    PREFERRED.store(method.index(), std::sync::atomic::Ordering::SeqCst);
    if let Some(index) = TLS_VARIANTS.iter().position(|f| *f == fingerprint) {
        PREFERRED_VARIANT.store(index as u8, std::sync::atomic::Ordering::SeqCst);
    }
    if let Ok(mut slot) = WINNER.lock() {
        *slot = Some(Winner { method, fingerprint, address });
    }
}

/// Open an OOB session to exactly one address (the remembered winner).
async fn open_session_one(
    host: &str,
    address: SocketAddr,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
) -> Result<ReadySession> {
    tokio::time::timeout(
        OOB_CONNECT_TIMEOUT,
        crate::apifront::open_oob_session_at(host, address, method, fingerprint),
    )
    .await
    .map_err(|_| AetherError::Api(format!("OOB {} {address}: handshake timed out", method.name())))?
}

/// One request on an open OOB connection, bounded by the exchange timeout.
/// On an answer the combination is remembered and, when the server keeps the
/// connection open, the connection is parked for the next request.
async fn exchange_and_keep(
    #[allow(unused_mut)] mut tls: crate::apifront::SessionTls,
    host: &str,
    address: SocketAddr,
    method: OobMethod,
    fingerprint: crate::apifront::Fingerprint,
    route: &'static str,
    request: &ApiRequest,
    reused: bool,
) -> Result<ApiResponse> {
    let started = std::time::Instant::now();

    #[cfg(target_os = "android")]
    {
        let _ = (host, reused);
        note(&format!(
            "t={} OOB {} / {} -> {} {} on fresh connection {address} (Connection:close, no keep-alive)",
            wall_ms(),
            method.name(),
            fingerprint.label(),
            request.method,
            request.path
        ));
        let outcome = tokio::time::timeout(
            exchange_timeout(),
            crate::apifront::exchange_close(tls, request, address, fingerprint, route),
        )
        .await;
        return match outcome {
            Ok(Ok(response)) => {
                record_winner(method, fingerprint, address);
                note(&format!(
                    "t={} OOB {} / {} {} {} answered {} in {} ms on a new connection {address} (closed)",
                    wall_ms(),
                    method.name(),
                    fingerprint.label(),
                    request.method,
                    request.path,
                    response.status,
                    started.elapsed().as_millis()
                ));
                Ok(response)
            }
            Ok(Err(error)) => {
                note(&format!(
                    "t={} OOB exchange with {address} failed after {} ms: {error}",
                    wall_ms(),
                    started.elapsed().as_millis()
                ));
                Err(error)
            }
            Err(_) => {
                note(&format!(
                    "t={} OOB exchange with {address} timed out after {} ms (0-byte risk)",
                    wall_ms(),
                    started.elapsed().as_millis()
                ));
                Err(AetherError::Api(format!("OOB exchange with {address} timed out")))
            }
        };
    }

    #[cfg(not(target_os = "android"))]
    {
    let outcome = tokio::time::timeout(
        exchange_timeout(),
        crate::apifront::exchange_keep_alive(&mut tls, request, address, fingerprint, route),
    )
    .await;
    match outcome {
        Ok(Ok((response, reusable))) => {
            record_winner(method, fingerprint, address);
            note(&format!(
                "OOB {} / {} {} {} answered {} in {} ms on {} connection {address}{}",
                method.name(),
                fingerprint.label(),
                request.method,
                request.path,
                response.status,
                started.elapsed().as_millis(),
                if reused { "the kept-alive" } else { "a new" },
                if reusable { " (kept open for the next request)" } else { " (server closed it)" }
            ));
            if reusable {
                park(Kept {
                    tls,
                    host: host.to_string(),
                    address,
                    method,
                    fingerprint,
                    route,
                    since: std::time::Instant::now(),
                });
            }
            Ok(response)
        }
        Ok(Err(error)) => Err(error),
        Err(_) => Err(AetherError::Api(format!("OOB exchange with {address} timed out"))),
    }
    }
}

/// Send `request` on a ready OOB session (keeps the connection when it can).
pub async fn send_on(session: ReadySession, method: OobMethod, request: &ApiRequest) -> Result<ApiResponse> {
    let (tls, address, fingerprint, route) = session.into_parts();
    exchange_and_keep(tls, &request.host, address, method, fingerprint, route, request, false).await
}

/// Step 1 of every fetch: the kept-alive connection of the previous request.
async fn try_kept(request: &ApiRequest) -> Option<Result<(ApiResponse, OobMethod)>> {
    // Android: always a fresh connection per request (POST /reg then PATCH).
    // Keep-alive reuse has been observed to return 0 HTTP bytes after a good
    // handshake (per-connection packet cap).
    #[cfg(target_os = "android")]
    {
        let _ = request;
        if let Some(kept) = take_kept() {
            note(&format!(
                "t={} OOB android: dropping kept connection {} (fresh per request)",
                wall_ms(),
                kept.address
            ));
        }
        return None;
    }
    #[cfg(not(target_os = "android"))]
    {
    let kept = take_kept()?;
    let idle = kept.since.elapsed();
    if kept.host != request.host || idle > keep_idle() {
        note(&format!(
            "OOB: dropping the kept connection {} (idle {} ms)",
            kept.address,
            idle.as_millis()
        ));
        return None;
    }
    note(&format!(
        "OOB {} / {} -> {} {} on the kept-alive connection {} (idle {} ms, no new hello)",
        kept.method.name(),
        kept.fingerprint.label(),
        request.method,
        request.path,
        kept.address,
        idle.as_millis()
    ));
    let method = kept.method;
    let address = kept.address;
    match exchange_and_keep(
        kept.tls,
        &kept.host,
        kept.address,
        kept.method,
        kept.fingerprint,
        kept.route,
        request,
        true,
    )
    .await
    {
        Ok(response) => Some(Ok((response, method))),
        Err(error) => {
            note(&format!(
                "OOB kept connection {address} failed: {error}; opening a new one (winner combination first)"
            ));
            None
        }
    }
    }
}


/// Variant order of one method. Built-in variants (plain-tls13, oob-tls12;
/// last winner first) and, only when enabled, schannel last.
fn variant_plan() -> Vec<Option<crate::apifront::Fingerprint>> {
    #[cfg(windows)]
    if schannel_only() {
        return vec![None];
    }
    let mut plan: Vec<Option<crate::apifront::Fingerprint>> =
        ordered_variants().into_iter().map(Some).collect();
    #[cfg(windows)]
    if schannel_enabled() {
        plan.push(None);
    }
    plan
}

/// One method: each TLS variant in turn, one connection attempt per IP per
/// variant (no same-IP repeat), 1 s between attempts. Any HTTP answer (even
/// 4xx / 429) means the method got through and is returned as is. A failed
/// handshake ends the built-in variants of this method (the DPI stopped the
/// hello); a handshake that passed followed by a failed / hung exchange moves
/// to the next variant. `skip` = a (variant, IP) already tried this fetch.
async fn fetch_with_skip(
    method: OobMethod,
    request: &ApiRequest,
    skip: Option<(crate::apifront::Fingerprint, SocketAddr)>,
    first_attempt: &mut bool,
) -> Result<ApiResponse> {
    let mut last: Option<AetherError> = None;
    let mut builtin_stopped = false;
    for variant in variant_plan() {
        if builtin_stopped && variant.is_some() {
            continue;
        }
        if !*first_attempt {
            tokio::time::sleep(ATTEMPT_PAUSE).await;
        }
        *first_attempt = false;
        match variant {
            Some(fingerprint) => {
                let exclude = skip.filter(|(f, _)| *f == fingerprint).map(|(_, a)| a);
                let session = match open_session_excluding(&request.host, method, fingerprint, exclude).await {
                    Ok(session) => session,
                    Err(error) => {
                        builtin_stopped = true;
                        last = Some(last.unwrap_or(error));
                        continue;
                    }
                };
                note(&format!(
                    "OOB {} / {} -> {} {} via {}",
                    method.name(),
                    fingerprint.label(),
                    request.method,
                    request.path,
                    session.address
                ));
                match send_on(session, method, request).await {
                    Ok(response) => return Ok(response),
                    Err(error) => {
                        note(&format!(
                            "OOB {} / {} exchange failed: {error}",
                            method.name(),
                            fingerprint.label()
                        ));
                        last = Some(error);
                    }
                }
            }
            None => {
                #[cfg(windows)]
                match crate::oob_schannel::fetch(method, request).await {
                    Ok(response) => {
                        note(&format!(
                            "OOB {} / schannel answered {} ({} {})",
                            method.name(),
                            response.status,
                            request.method,
                            request.path
                        ));
                        PREFERRED.store(method.index(), std::sync::atomic::Ordering::SeqCst);
                        return Ok(response);
                    }
                    Err(crate::oob_schannel::Failure::Handshake(error))
                    | Err(crate::oob_schannel::Failure::Exchange(error)) => {
                        note(&format!("OOB {} / schannel failed: {error}", method.name()));
                        last = Some(error);
                    }
                }
            }
        }
    }
    Err(last.unwrap_or_else(|| AetherError::Api(format!("OOB {}: no variant ran", method.name()))))
}

/// One method, fresh connections only (kept for older call sites / tests).
pub async fn fetch_with(method: OobMethod, request: &ApiRequest) -> Result<ApiResponse> {
    let mut first = true;
    fetch_with_skip(method, request, None, &mut first).await
}

/// The OOB key route. In order:
/// 1. the kept-alive connection of the previous API call (same TLS stream,
///    HTTP/1.1 keep-alive: POST /reg and PATCH /reg/{id} share it);
/// 2. a new connection with the exact winner (method + TLS variant + IP);
/// 3. the three proven methods strictly one after another (never in parallel
///    against the API), last winner first, variants plain-tls13 then oob-tls12.
/// The first HTTP answer wins and is remembered. Err only when all failed on
/// the transport (no answer).
pub async fn fetch(request: &ApiRequest) -> Result<(ApiResponse, OobMethod)> {
    if let Some(Ok(done)) = try_kept(request).await {
        return Ok(done);
    }
    let mut failures: Vec<String> = Vec::new();
    let mut skip: Option<Winner> = None;
    let mut first_attempt = true;
    if let Some(w) = winner() {
        if ordered_methods().contains(&w.method) {
            note(&format!(
                "OOB: new connection with the winner first: {} / {} via {}",
                w.method.name(),
                w.fingerprint.label(),
                w.address
            ));
            first_attempt = false;
            match open_session_one(&request.host, w.address, w.method, w.fingerprint).await {
                Ok(session) => {
                    note(&format!(
                        "OOB {} / {} -> {} {} via {}",
                        w.method.name(),
                        w.fingerprint.label(),
                        request.method,
                        request.path,
                        session.address
                    ));
                    match send_on(session, w.method, request).await {
                        Ok(response) => {
                            note(&format!(
                                "OOB method {} won ({} {} answered {} over {})",
                                w.method.name(),
                                request.method,
                                request.path,
                                response.status,
                                response.route
                            ));
                            return Ok((response, w.method));
                        }
                        Err(error) => {
                            note(&format!("OOB winner combination exchange failed: {error}"));
                            failures.push(format!("winner {}: {error}", w.method.name()));
                        }
                    }
                }
                Err(error) => {
                    note(&format!("OOB winner combination handshake failed: {error}"));
                    failures.push(format!("winner {}: {error}", w.method.name()));
                }
            }
            skip = Some(w);
        }
    }
    #[cfg(target_os = "android")]
    let mut skip_ttl1_rest = false;
    for method in ordered_methods() {
        #[cfg(target_os = "android")]
        if skip_ttl1_rest && method.uses_ttl1() {
            note(&format!(
                "OOB android: skipping {} (nottl already hung after handshake; TTL1 burns time)",
                method.name()
            ));
            failures.push(format!("{}: skipped after nottl 0-byte hang", method.name()));
            continue;
        }
        let skip_here = skip
            .filter(|w| w.method == method)
            .map(|w| (w.fingerprint, w.address));
        match fetch_with_skip(method, request, skip_here, &mut first_attempt).await {
            Ok(response) => {
                note(&format!(
                    "OOB method {} won ({} {} answered {} over {})",
                    method.name(),
                    request.method,
                    request.path,
                    response.status,
                    response.route
                ));
                return Ok((response, method));
            }
            Err(error) => {
                note(&format!("OOB method {} failed: {error}", method.name()));
                #[cfg(target_os = "android")]
                {
                    let msg = error.to_string();
                    if matches!(method, OobMethod::OobByte0NoTtl)
                        && (msg.contains("timed out")
                            || msg.contains("0-byte")
                            || msg.contains("exchange")
                            || msg.contains("first_byte"))
                    {
                        skip_ttl1_rest = true;
                        note(
                            "OOB android: nottl hung after handshake; skipping TTL1 methods so caller can use Worker relay",
                        );
                    }
                }
                failures.push(format!("{}: {error}", method.name()));
            }
        }
    }
    Err(AetherError::Api(format!(
        "all OOB methods failed ({})",
        failures.join("; ")
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_builtin_api_edges_cover_both_families_and_are_cloudflare() {
        let edges = builtin_edges();
        assert_eq!(edges.len(), API_EDGES.len());
        assert!(edges.iter().any(|a| a.is_ipv4()));
        assert!(edges.iter().any(|a| a.is_ipv6()));
        assert!(edges.iter().all(|a| crate::dns::is_cloudflare_ip(a.ip()) && a.port() == 443));
    }

    #[test]
    fn methods_are_tried_in_the_proven_order_and_split_where_the_test_did() {
        #[cfg(not(target_os = "android"))]
        assert_eq!(
            METHOD_ORDER.iter().map(|m| m.name()).collect::<Vec<_>>(),
            ["disoob-byte0-ttl1", "oob-insni+5-ttl1", "oob-byte0-nottl"]
        );
        #[cfg(target_os = "android")]
        assert_eq!(
            METHOD_ORDER.iter().map(|m| m.name()).collect::<Vec<_>>(),
            [
                "disoob-byte0-ttl1",
                "oob-insni+5-ttl1",
                "oob-byte0-nottl",
                "disoob-byte0-ttl1-plain",
                "disoob-byte0-ttl1-andr",
                "disoob-byte0-ttl1-split",
            ]
        );
        let hello = b"\x16\x03\x01....\x00\x18api.cloudflareclient.com....";
        let sni = find_sni(hello, "api.cloudflareclient.com").unwrap();
        assert_eq!(split_offset(hello, OobMethod::OobInSni5Ttl1, "api.cloudflareclient.com"), sni + 5);
        assert_eq!(split_offset(hello, OobMethod::DisoobByte0Ttl1, "api.cloudflareclient.com"), 0);
        assert_eq!(split_offset(hello, OobMethod::OobByte0NoTtl, "api.cloudflareclient.com"), 0);
        assert_eq!(split_offset(b"\x16\x03\x01xx", OobMethod::OobInSni5Ttl1, "nope.example"), 0);
    }

    async fn delivered(method: OobMethod) -> Vec<u8> {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut got = Vec::new();
            stream.read_to_end(&mut got).await.unwrap();
            got
        });
        let tcp = TcpStream::connect(address).await.unwrap();
        tcp.set_nodelay(true).unwrap();
        let before = current_ttl(&socket2::SockRef::from(&tcp), false);
        let mut stream = crate::fragment::FragmentingStream::new(
            tcp,
            crate::fragment::FragmentConfig::disabled(),
        )
        .with_oob(method, "api.cloudflareclient.com");
        stream.write_all(HELLO).await.unwrap();
        stream.flush().await.unwrap();
        stream.shutdown().await.unwrap();
        let after = current_ttl(&socket2::SockRef::from(stream.get_ref()), false);
        assert_eq!(after, before, "{} left the TTL changed", method.name());
        drop(stream);
        server.await.unwrap()
    }

    const HELLO: &[u8] = b"\x16\x03\x01\x00\x40hello\x00\x18api.cloudflareclient.com-rest-of-hello";

    #[tokio::test]
    async fn every_method_delivers_the_hello_without_the_urgent_byte() {
        // Loopback never drops the TTL-1 segment; the urgent byte is stripped
        // by the receiver (SO_OOBINLINE off), so the stream is the hello.
        // Skip plain TTL desync: without MSG_OOB the dying byte still arrives
        // on loopback and would duplicate the first byte of the hello.
        for method in METHOD_ORDER {
            if method.plain_ttl_desync() {
                continue;
            }
            assert_eq!(delivered(method).await, HELLO.to_vec(), "{}", method.name());
        }
    }
}
