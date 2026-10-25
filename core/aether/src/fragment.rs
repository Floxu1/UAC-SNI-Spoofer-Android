use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use rand::Rng;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

#[derive(Debug, Clone, Copy)]
pub struct FragmentConfig {
    pub enabled: bool,
    pub size_min: usize,
    pub size_max: usize,
    pub delay_min_ms: u64,
    pub delay_max_ms: u64,
}

impl FragmentConfig {
    pub fn disabled() -> Self {
        Self {
            enabled: false,
            size_min: 1,
            size_max: 1,
            delay_min_ms: 0,
            delay_max_ms: 0,
        }
    }

    pub fn from_env() -> Self {
        let enabled = std::env::var("AETHER_MASQUE_H2_FRAGMENT")
            .map(|v| is_truthy(&v))
            .unwrap_or(false);

        let (size_min, size_max) = parse_range(
            &std::env::var("AETHER_MASQUE_H2_FRAGMENT_SIZE").unwrap_or_default(),
            (16, 32),
        );
        let (delay_min_ms, delay_max_ms) = parse_range(
            &std::env::var("AETHER_MASQUE_H2_FRAGMENT_DELAY").unwrap_or_default(),
            (2, 10),
        );

        let size_min = size_min.max(1) as usize;
        let size_max = (size_max.max(size_min as u64)) as usize;

        Self {
            enabled,
            size_min,
            size_max,
            delay_min_ms,
            delay_max_ms: delay_max_ms.max(delay_min_ms),
        }
    }

    fn pick_chunk_len(&self, remaining: usize) -> usize {
        let hi = self.size_max.max(1).min(remaining);
        let lo = self.size_min.max(1).min(hi);
        if lo >= hi {
            hi
        } else {
            rand::thread_rng().gen_range(lo..=hi)
        }
    }

    fn pick_delay(&self) -> Duration {
        if self.delay_max_ms == 0 {
            return Duration::ZERO;
        }
        let ms = if self.delay_max_ms <= self.delay_min_ms {
            self.delay_min_ms
        } else {
            rand::thread_rng().gen_range(self.delay_min_ms..=self.delay_max_ms)
        };
        Duration::from_millis(ms)
    }
}

fn is_truthy(v: &str) -> bool {
    matches!(
        v.trim().to_lowercase().as_str(),
        "1" | "true" | "yes" | "on"
    )
}

fn parse_range(spec: &str, default: (u64, u64)) -> (u64, u64) {
    let spec = spec.trim();
    if spec.is_empty() {
        return default;
    }
    match spec.split_once('-') {
        Some((a, b)) => {
            let lo = a.trim().parse().unwrap_or(default.0);
            let hi = b.trim().parse().unwrap_or(default.1);
            if hi < lo {
                (hi, lo)
            } else {
                (lo, hi)
            }
        }
        None => {
            let v = spec.parse().unwrap_or(default.0);
            (v, v)
        }
    }
}

pub struct FragmentingStream<S> {
    inner: S,
    cfg: FragmentConfig,
    fragmenting: bool,
    pending_delay: Option<Pin<Box<tokio::time::Sleep>>>,
    /// OOB desync of the first write (see `crate::oob`).
    oob: OobState,
}

enum OobState {
    Off,
    /// Next non-trivial write is the ClientHello: send its desync prefix.
    Prefix(crate::oob::OobMethod, String),
    /// Prefix sent; wait, then send `buf[resume..]` normally.
    Pause(Pin<Box<tokio::time::Sleep>>, usize),
}

impl<S> FragmentingStream<S> {
    pub fn new(inner: S, cfg: FragmentConfig) -> Self {
        Self {
            inner,
            fragmenting: cfg.enabled,
            cfg,
            pending_delay: None,
            oob: OobState::Off,
        }
    }

    /// The first write goes out with an OOB desync `method`; no fragmenting.
    pub fn with_oob(mut self, method: crate::oob::OobMethod, sni_host: &str) -> Self {
        self.oob = OobState::Prefix(method, sni_host.to_string());
        self.fragmenting = false;
        self
    }

    pub fn get_ref(&self) -> &S {
        &self.inner
    }
}

impl<S> AsyncRead for FragmentingStream<S>
where
    S: AsyncRead + Unpin,
{
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        this.fragmenting = false;
        Pin::new(&mut this.inner).poll_read(cx, buf)
    }
}

impl<S> AsyncWrite for FragmentingStream<S>
where
    S: AsyncWrite + Unpin + crate::oob::DesyncSocket,
{
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();

        if let OobState::Prefix(method, host) = &this.oob {
            if buf.len() > 1 {
                let method = *method;
                match this.inner.poll_oob_prefix(cx, buf, method, host) {
                    Poll::Pending => return Poll::Pending,
                    Poll::Ready(Err(error)) => {
                        this.oob = OobState::Off;
                        return Poll::Ready(Err(error));
                    }
                    Poll::Ready(Ok(resume)) => {
                        this.oob =
                            OobState::Pause(Box::pin(tokio::time::sleep(method.pause())), resume);
                    }
                }
            }
        }
        if let OobState::Pause(sleep, resume) = &mut this.oob {
            // The caller retries the same hello buffer until bytes are taken.
            if sleep.as_mut().poll(cx).is_pending() {
                return Poll::Pending;
            }
            let resume = (*resume).min(buf.len());
            this.oob = OobState::Off;
            return match Pin::new(&mut this.inner).poll_write(cx, &buf[resume..]) {
                Poll::Ready(Ok(n)) => Poll::Ready(Ok(resume + n)),
                Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
                // Bytes before `resume` already left; report them. With
                // resume 0 nothing was taken yet: the retry writes normally.
                Poll::Pending if resume > 0 => Poll::Ready(Ok(resume)),
                Poll::Pending => Poll::Pending,
            };
        }

        if buf.is_empty() || !this.fragmenting {
            return Pin::new(&mut this.inner).poll_write(cx, buf);
        }

        if let Some(sleep) = this.pending_delay.as_mut() {
            match sleep.as_mut().poll(cx) {
                Poll::Ready(()) => this.pending_delay = None,
                Poll::Pending => return Poll::Pending,
            }
        }

        let chunk_len = this.cfg.pick_chunk_len(buf.len());
        match Pin::new(&mut this.inner).poll_write(cx, &buf[..chunk_len]) {
            Poll::Ready(Ok(n)) => {
                if n > 0 {
                    let delay = this.cfg.pick_delay();
                    if !delay.is_zero() {
                        this.pending_delay = Some(Box::pin(tokio::time::sleep(delay)));
                    }
                }
                Poll::Ready(Ok(n))
            }
            other => other,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}
