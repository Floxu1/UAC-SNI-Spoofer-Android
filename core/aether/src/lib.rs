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
mod h2_receive;
mod h3_carrier;
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

#[cfg(feature = "direct-support")]
pub mod direct_support {
    pub use crate::app::{
        apply_runtime_options, certificate_retry_peers, establish_wg, join_outcome, load_clean_h2,
        load_or_provision_masque, load_or_provision_warp, masque_config_path, open_scan_carrier,
        remember_clean_h2, unique_scan_peers, warp_config_path, ForwarderGuard, INNER_MTU,
    };
    pub use crate::ffi::{
        aether_is_ready as is_ready, aether_is_running as is_running, aether_last_error as last_error,
        aether_last_log as last_log, aether_stop as stop, mark_ready, record_log, run_session,
    };
    pub use crate::h3_carrier::Carrier;
    pub use crate::masque_h2::{connect_tcp, use_extended_connect, CarrierStartupStage, CarrierStartupTrace};
    pub use crate::prober::{direct_range_peers, masque_scan_peers, MASQUE_SEEDS};
    pub use crate::quic::MAX_DATAGRAM_SIZE;
    pub use crate::socks::serve;
}
