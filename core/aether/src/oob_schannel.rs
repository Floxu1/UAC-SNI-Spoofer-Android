//! Windows-only OOB TLS variant `schannel`: the OS TLS stack (SChannel, via
//! `native-tls`) over a blocking std `TcpStream` whose FIRST write (the
//! ClientHello) leaves with the same OOB desync as the C# probe
//! `OobFirstWriteStream.Write` in key-test.ps1, which got register 200 /
//! PATCH 200 / DELETE 204 on AS48944 where every rustls/boring variant passed
//! the handshake and then never got the HTTP answer.
//!
//! Like `KeyProbe.Http1`: one fresh TCP connection per HTTP request, TLS 1.2
//! only, no ALPN, HTTP/1.1 with the C# header set (headers, then body as a
//! second TLS write), read until Content-Length is satisfied. Runs blocking,
//! so callers use `tokio::task::spawn_blocking`.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::apifront::{ApiRequest, ApiResponse};
use crate::error::AetherError;
use crate::oob::OobMethod;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
/// Budget for the HTTP exchange after the handshake (C# used 10 s socket
/// timeouts; the task asks for 8 s).
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(8);

fn note(message: &str) {
    let line = format!("Key: {message}");
    log::info!("{line}");
    crate::ffi::record_log(line);
}

/// C# `OobFirstWriteStream`: reads pass through; the first write is split.
#[derive(Debug)]
struct OobFirstWrite {
    sock: TcpStream,
    method: OobMethod,
    sni_host: String,
    first: bool,
    /// Raw TCP bytes received (TLS records), for the timeout log.
    rx: Arc<AtomicUsize>,
}

impl Read for OobFirstWrite {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.sock.read(buf)?;
        self.rx.fetch_add(n, Ordering::SeqCst);
        Ok(n)
    }
}

impl Write for OobFirstWrite {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if !self.first || buf.is_empty() {
            return self.sock.write(buf);
        }
        self.first = false;
        first_write(&self.sock, buf, self.method, &self.sni_host)?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.sock.flush()
    }
}

fn send_all(sock: &socket2::SockRef<'_>, mut data: &[u8]) -> io::Result<()> {
    while !data.is_empty() {
        let n = sock.send(data)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "schannel oob: send returned 0"));
        }
        data = &data[n..];
    }
    Ok(())
}

/// Byte-for-byte the C# `Write` (first call):
/// - disoob: TTL=1, OOB `ch[0]`, TTL restored, sleep 50 ms, send all of `ch`.
/// - oob:    send `ch[..off]`, OOB `ch[off]`, sleep 30 ms, send `ch[off..]`.
/// - oobttl: TTL=1, send `ch[..off]`, OOB `ch[off]`, TTL restored, sleep
///   30 ms, send `ch[off..]`.
/// off = SNI host start (0 if not found) + rel, 0 if out of range.
fn first_write(tcp: &TcpStream, ch: &[u8], method: OobMethod, sni_host: &str) -> io::Result<()> {
    let sock = socket2::SockRef::from(tcp);
    let v6 = matches!(tcp.peer_addr()?, SocketAddr::V6(_));
    let off = crate::oob::split_offset(ch, method, sni_host);
    let saved = crate::oob::current_ttl(&sock, v6);
    let restore = |sock: &socket2::SockRef<'_>| -> io::Result<()> {
        crate::oob::set_ttl(sock, v6, saved)?;
        let now = crate::oob::current_ttl(sock, v6);
        if now != saved {
            return Err(io::Error::other(format!(
                "schannel oob: TTL not restored (is {now}, want {saved})"
            )));
        }
        Ok(())
    };
    match method {
        OobMethod::DisoobByte0Ttl1
        | OobMethod::DisoobByte0Ttl1Andr
        | OobMethod::DisoobByte0Ttl1Split => {
            // Android-only variants share the Windows disoob byte pattern here
            // (schannel is Windows-only; these arms keep the match exhaustive).
            crate::oob::set_ttl(&sock, v6, 1)?;
            let sent = sock.send_out_of_band(&ch[..1]);
            restore(&sock)?;
            if sent? != 1 {
                return Err(io::Error::other("schannel oob: urgent byte was not sent"));
            }
            std::thread::sleep(Duration::from_millis(50));
            if matches!(method, OobMethod::DisoobByte0Ttl1Split) {
                send_all(&sock, &ch[..1])?;
                std::thread::sleep(Duration::from_millis(1));
                send_all(&sock, &ch[1..])?;
            } else {
                send_all(&sock, ch)?;
            }
        }
        OobMethod::DisoobByte0Ttl1Plain => {
            // Exhaustive arm only: schannel is Windows-only and never selects
            // this Android method. Same wire shape as plain TTL desync.
            crate::oob::set_ttl(&sock, v6, 1)?;
            send_all(&sock, &ch[..1])?;
            restore(&sock)?;
            std::thread::sleep(Duration::from_millis(50));
            send_all(&sock, ch)?;
        }
        OobMethod::OobByte0NoTtl => {
            send_all(&sock, &ch[..off])?;
            if sock.send_out_of_band(&ch[off..off + 1])? != 1 {
                return Err(io::Error::other("schannel oob: urgent byte was not sent"));
            }
            std::thread::sleep(Duration::from_millis(30));
            send_all(&sock, &ch[off..])?;
        }
        OobMethod::OobInSni5Ttl1 => {
            crate::oob::set_ttl(&sock, v6, 1)?;
            let sent = (|| {
                send_all(&sock, &ch[..off])?;
                sock.send_out_of_band(&ch[off..off + 1])
            })();
            restore(&sock)?;
            if sent? != 1 {
                return Err(io::Error::other("schannel oob: urgent byte was not sent"));
            }
            std::thread::sleep(Duration::from_millis(30));
            send_all(&sock, &ch[off..])?;
        }
    }
    log::info!(
        "Key: OOB {} / schannel hello {} bytes, urgent byte at {off}, ttl {}",
        method.name(),
        ch.len(),
        crate::oob::current_ttl(&sock, v6)
    );
    Ok(())
}

/// Request head exactly like `KeyProbe.Http1` (order and set of headers);
/// values come from the request (CF-Access-Jwt-Assertion kept when present).
fn render_head(request: &ApiRequest, body_len: usize) -> String {
    let get = |name: &str| {
        request
            .headers
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.clone())
    };
    let mut head = format!("{} {} HTTP/1.1\r\n", request.method, request.path);
    head.push_str(&format!("Host: {}\r\n", request.host));
    head.push_str(&format!(
        "CF-Client-Version: {}\r\n",
        get("CF-Client-Version").unwrap_or_else(|| crate::consts::CF_CLIENT_VERSION.to_string())
    ));
    head.push_str(&format!(
        "User-Agent: {}\r\n",
        get("User-Agent").unwrap_or_else(|| crate::consts::UA_REGISTER.to_string())
    ));
    head.push_str(&format!(
        "Content-Type: {}\r\n",
        get("Content-Type").unwrap_or_else(|| "application/json; charset=UTF-8".to_string())
    ));
    head.push_str("Connection: close\r\n");
    if let Some(auth) = get("Authorization") {
        head.push_str(&format!("Authorization: {auth}\r\n"));
    }
    if let Some(jwt) = get("CF-Access-Jwt-Assertion") {
        head.push_str(&format!("CF-Access-Jwt-Assertion: {jwt}\r\n"));
    }
    head.push_str(&format!("Content-Length: {body_len}\r\n\r\n"));
    head
}

/// Content-Length satisfied (C# loop condition), or a body-less status.
fn complete(raw: &[u8]) -> bool {
    let Some(head_end) = raw.windows(4).position(|w| w == b"\r\n\r\n") else {
        return false;
    };
    let head = String::from_utf8_lossy(&raw[..head_end]).to_ascii_lowercase();
    for line in head.split("\r\n").skip(1) {
        if let Some(v) = line.strip_prefix("content-length:") {
            if let Ok(len) = v.trim().parse::<usize>() {
                return raw.len() >= head_end + 4 + len;
            }
        }
    }
    let status = head.split_whitespace().nth(1).and_then(|s| s.parse::<u16>().ok()).unwrap_or(0);
    matches!(status, 100..=199 | 204 | 304)
}

pub enum Failure {
    /// TCP connect or TLS handshake did not finish (DPI stopped the hello, or
    /// SChannel refused).
    Handshake(AetherError),
    /// Handshake passed, the HTTP answer did not come.
    Exchange(AetherError),
}

/// One request over one fresh connection (blocking).
pub fn http1_blocking(
    address: SocketAddr,
    method: OobMethod,
    request: &ApiRequest,
) -> std::result::Result<ApiResponse, Failure> {
    let host = request.host.as_str();
    let tcp = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT).map_err(|e| {
        Failure::Handshake(AetherError::Api(format!("schannel: connect to {address}: {e}")))
    })?;
    let _ = tcp.set_nodelay(true);
    let _ = tcp.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    let _ = tcp.set_write_timeout(Some(HANDSHAKE_TIMEOUT));
    let rx = Arc::new(AtomicUsize::new(0));
    let rx_at_request = Arc::new(AtomicUsize::new(0));
    let sock_for_timeouts = tcp.try_clone().map_err(|e| {
        Failure::Handshake(AetherError::Api(format!("schannel: socket clone: {e}")))
    })?;
    let stream = OobFirstWrite {
        sock: tcp,
        method,
        sni_host: host.to_string(),
        first: true,
        rx: rx.clone(),
    };
    let cred = schannel::schannel_cred::SchannelCred::builder()
        .enabled_protocols(&[schannel::schannel_cred::Protocol::Tls12])
        .acquire(schannel::schannel_cred::Direction::Outbound)
        .map_err(|e| Failure::Handshake(AetherError::Tls(format!("schannel: credential: {e}"))))?;
    let started = Instant::now();
    let mut builder = schannel::tls_stream::Builder::new();
    builder
        .domain(host)
        .use_sni(true)
        .accept_invalid_hostnames(true)
        .verify_callback(|_| Ok(()));
    let mut tls = builder.connect(cred, stream).map_err(|e| {
        Failure::Handshake(AetherError::Api(format!(
            "schannel handshake with {address} failed after {} ms: {e}",
            started.elapsed().as_millis()
        )))
    })?;
    let resumed = tls.session_resumed().unwrap_or(false);
    note(&format!(
        "{address} OOB {} / schannel handshake ready ({} ms, resumed={resumed})",
        method.name(),
        started.elapsed().as_millis()
    ));

    let body: &[u8] = request.body.as_deref().unwrap_or(&[]);
    let head = render_head(request, body.len());
    let exchange_start = Instant::now();
    let deadline = exchange_start + EXCHANGE_TIMEOUT;
    let _ = sock_for_timeouts.set_write_timeout(Some(EXCHANGE_TIMEOUT));
    rx_at_request.store(rx.load(Ordering::SeqCst), Ordering::SeqCst);
    let wrote = (|| -> io::Result<()> {
        tls.write_all(head.as_bytes())?;
        if !body.is_empty() {
            tls.write_all(body)?;
        }
        tls.flush()
    })();
    if let Err(e) = wrote {
        return Err(Failure::Exchange(AetherError::Api(format!(
            "schannel: write to {address}: {e}"
        ))));
    }

    let mut raw: Vec<u8> = Vec::new();
    let mut buf = [0u8; 8192];
    let mut read_error: Option<String> = None;
    loop {
        let now = Instant::now();
        if now >= deadline {
            read_error = Some("timed out".into());
            break;
        }
        let _ = sock_for_timeouts.set_read_timeout(Some((deadline - now).max(Duration::from_millis(10))));
        match tls.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                raw.extend_from_slice(&buf[..n]);
                if complete(&raw) {
                    break;
                }
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => {
                read_error = Some(
                    if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) {
                        "timed out".to_string()
                    } else {
                        e.to_string()
                    },
                );
                break;
            }
        }
    }
    let tcp_after = rx.load(Ordering::SeqCst).saturating_sub(rx_at_request.load(Ordering::SeqCst));
    if raw.is_empty() || !raw.windows(4).any(|w| w == b"\r\n\r\n") {
        // Abort: do not SSL_shutdown. A graceful close after a hung exchange
        // makes SChannel cache a resumed ClientHello (~180 bytes) that then
        // hangs again; a hard drop forces a full hello next time (C# probe).
        drop(tls);
        return Err(Failure::Exchange(AetherError::Api(format!(
            "OOB schannel exchange with {address} {} after {} ms: {} response bytes (HTTP), {} TCP bytes after the request",
            read_error.as_deref().unwrap_or("closed"),
            exchange_start.elapsed().as_millis(),
            raw.len(),
            tcp_after
        ))));
    }
    let _ = tls.shutdown();
    if let Some(e) = &read_error {
        if !complete(&raw) {
            note(&format!(
                "OOB schannel {address}: response incomplete ({e}), {} bytes; using what arrived",
                raw.len()
            ));
        }
    }
    let text = String::from_utf8_lossy(&raw).to_string();
    let split = text.find("\r\n\r\n").unwrap_or(text.len());
    let status_line = text[..split].lines().next().unwrap_or("");
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .ok_or_else(|| Failure::Exchange(AetherError::Api(format!("schannel: bad status line: {status_line}"))))?;
    let mut body_text = text.get(split + 4..).unwrap_or("").to_string();
    if text[..split].to_ascii_lowercase().contains("transfer-encoding: chunked") {
        body_text = crate::apifront::dechunk(&body_text);
    }
    Ok(ApiResponse {
        status,
        body: body_text,
        route: format!("{address} / schannel / oob"),
    })
}

/// The schannel variant of one OOB method: IPv4 API edges first (the C#
/// probe used IPv4), then IPv6. A failed handshake moves to the next address;
/// a passed handshake whose exchange failed moves to the next address too
/// (one attempt per IP; the old same-IP retry is gone since patch oob6).
pub async fn fetch(method: OobMethod, request: &ApiRequest) -> std::result::Result<ApiResponse, Failure> {
    let mut edges = crate::oob::builtin_edges();
    edges.sort_by_key(|a| a.is_ipv6());
    note(&format!(
        "OOB method {} / schannel started to {} ({} TLS 1.2, one connection per request)",
        method.name(),
        request.host,
        "SChannel"
    ));
    let mut last: Option<Failure> = None;
    let mut exchange_failures = 0;
    for (index, address) in edges.into_iter().enumerate() {
        // patch oob6: one attempt per IP (no same-IP retry), 1 s apart.
        if index > 0 && !address.is_ipv6() {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        note(&format!(
            "OOB {} / schannel -> {} {} via {address}",
            method.name(),
            request.method,
            request.path
        ));
        let req = request.clone();
        let joined = tokio::time::timeout(
            CONNECT_TIMEOUT + HANDSHAKE_TIMEOUT + EXCHANGE_TIMEOUT + Duration::from_secs(4),
            tokio::task::spawn_blocking(move || http1_blocking(address, method, &req)),
        )
        .await;
        let outcome = match joined {
            Ok(Ok(outcome)) => outcome,
            Ok(Err(join)) => Err(Failure::Handshake(AetherError::Api(format!("schannel task: {join}")))),
            Err(_) => Err(Failure::Exchange(AetherError::Api(format!(
                "OOB schannel with {address} timed out (blocking task)"
            )))),
        };
        match outcome {
            Ok(response) => return Ok(response),
            Err(Failure::Handshake(e)) => {
                note(&format!("{address} OOB {} / schannel handshake failed: {e}", method.name()));
                if !matches!(last, Some(Failure::Exchange(_))) {
                    last = Some(Failure::Handshake(e));
                }
            }
            Err(Failure::Exchange(e)) => {
                note(&format!("OOB {} / schannel exchange failed: {e}", method.name()));
                last = Some(Failure::Exchange(e));
                exchange_failures += 1;
                if exchange_failures >= 4 {
                    break;
                }
            }
        }
    }
    Err(last.unwrap_or_else(|| Failure::Handshake(AetherError::Api("schannel: no API address".into()))))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn head_matches_the_csharp_probe() {
        let request = ApiRequest {
            method: "PATCH".into(),
            host: "api.cloudflareclient.com".into(),
            path: "/v0a4471/reg/x".into(),
            headers: vec![
                ("Content-Type".into(), "application/json; charset=UTF-8".into()),
                ("User-Agent".into(), "WARP for Android".into()),
                ("CF-Client-Version".into(), "a-6.35-4471".into()),
                ("Accept".into(), "application/json".into()),
                ("Authorization".into(), "Bearer t".into()),
            ],
            body: Some(b"{}".to_vec()),
        };
        assert_eq!(
            render_head(&request, 2),
            "PATCH /v0a4471/reg/x HTTP/1.1\r\nHost: api.cloudflareclient.com\r\nCF-Client-Version: a-6.35-4471\r\nUser-Agent: WARP for Android\r\nContent-Type: application/json; charset=UTF-8\r\nConnection: close\r\nAuthorization: Bearer t\r\nContent-Length: 2\r\n\r\n"
        );
    }

    fn delivered(method: OobMethod) -> Vec<u8> {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut got = Vec::new();
            s.read_to_end(&mut got).unwrap();
            got
        });
        let tcp = TcpStream::connect(address).unwrap();
        tcp.set_nodelay(true).unwrap();
        let before = crate::oob::current_ttl(&socket2::SockRef::from(&tcp), false);
        first_write(&tcp, HELLO, method, "api.cloudflareclient.com").unwrap();
        let after = crate::oob::current_ttl(&socket2::SockRef::from(&tcp), false);
        assert_eq!(before, after);
        tcp.shutdown(std::net::Shutdown::Write).unwrap();
        drop(tcp);
        server.join().unwrap()
    }

    const HELLO: &[u8] = b"\x16\x03\x01\x00\x40hello\x00\x18api.cloudflareclient.com-rest-of-hello";

    #[test]
    fn every_method_delivers_the_hello_the_csharp_way() {
        // Receiver strips the urgent byte; byte-0 / in-SNI resend from off.
        for method in crate::oob::METHOD_ORDER {
            assert_eq!(delivered(method), HELLO.to_vec(), "{}", method.name());
        }
    }
}
