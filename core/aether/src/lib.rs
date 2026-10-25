mod account;
mod aethernoize;
mod apifront;
mod cli;
mod config;
mod consts;
mod dns;
pub mod error;
mod exitip;
pub(crate) mod ffi;
mod fragment;
mod lastconn;
mod masque;
mod masque_h2;
mod netstack;
mod noize;
mod oob;
#[cfg(windows)]
mod oob_schannel;
pub mod platform;
mod prober;
mod quic;
mod relay;
mod routing;
mod socks;
#[cfg(unix)]
pub(crate) mod socks_upstream;
/// Windows stub: the TUN-fd bridge is Unix-only by nature (AsyncFd, fcntl).
/// `run_psiphon` rejects non-TUN use before reaching `serve`, so this is
/// unreachable; it only exists so the same core builds on Windows.
#[cfg(not(unix))]
pub(crate) mod socks_upstream {
    use crate::error::{AetherError, Result};
    use std::net::SocketAddr;

    pub async fn serve(_upstream: SocketAddr, _tun_fd: i32) -> Result<()> {
        Err(AetherError::Other("psiphon mode requires a TUN fd".into()))
    }
}
mod sysprofile;
mod tls;
mod tun;
mod tunnelping;
mod wg_prober;
mod wireguard;
mod zerotrust;

#[path = "main.rs"]
mod app;

pub use app::{
    initialize, prepare, run_cli, start, EndpointDiscovery, IpScan, MasqueTransport, Protocol,
    ScanMode, StartOptions, TlsCurvePreset, TunnelAddresses,
};
pub use platform::{set_socket_protector, SocketProtector};
