//! CONNECT-IP carrier for the bound QUIC peers used by H3 New Method.
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use parking_lot::{Mutex, RwLock};
use smoltcp::phy::ChecksumCapabilities;
use smoltcp::wire::{IpProtocol, Ipv4Packet, Ipv4Repr, Ipv6Packet, Ipv6Repr, UdpPacket, UdpRepr};
use tokio::sync::mpsc;

use crate::error::{AetherError, Result};
use crate::quic::{AssignedAddr, NetPacket, MAX_DATAGRAM_SIZE};

const FIRST_PORT: u16 = 49152;

#[derive(Clone)]
pub struct Carrier {
    state: Arc<State>,
    outbound: mpsc::Sender<Vec<u8>>,
}

struct State {
    addresses: RwLock<(Option<Ipv4Addr>, Option<Ipv6Addr>)>,
    routes: Mutex<Routes>,
}

struct Routes {
    ports: HashMap<u16, Route>,
    next_port: u16,
}

struct Route {
    peer: SocketAddr,
    quic_local: SocketAddr,
    inbound: mpsc::Sender<NetPacket>,
}

pub struct CarrierSocket {
    carrier: Carrier,
    source_port: u16,
    peer: SocketAddr,
}

impl Carrier {
    pub(crate) fn new(ipv4: &str, ipv6: &str, outbound: mpsc::Sender<Vec<u8>>) -> Self {
        let addresses = (
            ipv4.split('/').next().and_then(|ip| ip.parse().ok()),
            ipv6.split('/').next().and_then(|ip| ip.parse().ok()),
        );
        Self {
            state: Arc::new(State {
                addresses: RwLock::new(addresses),
                routes: Mutex::new(Routes {
                    ports: HashMap::new(),
                    next_port: FIRST_PORT,
                }),
            }),
            outbound,
        }
    }

    pub fn open(
        &self,
        peer: SocketAddr,
        quic_local: SocketAddr,
        inbound: mpsc::Sender<NetPacket>,
    ) -> Result<CarrierSocket> {
        let mut routes = self.state.routes.lock();
        for _ in FIRST_PORT..=u16::MAX {
            let port = routes.next_port;
            routes.next_port = port.checked_add(1).unwrap_or(FIRST_PORT);
            if let std::collections::hash_map::Entry::Vacant(entry) = routes.ports.entry(port) {
                entry.insert(Route {
                    peer,
                    quic_local,
                    inbound,
                });
                return Ok(CarrierSocket {
                    carrier: self.clone(),
                    source_port: port,
                    peer,
                });
            }
        }
        Err(AetherError::Masque("carrier UDP ports exhausted".into()))
    }

    fn address(&self, ipv4: bool) -> Option<IpAddr> {
        let addrs = self.state.addresses.read();
        if ipv4 {
            addrs.0.map(IpAddr::V4)
        } else {
            addrs.1.map(IpAddr::V6)
        }
    }

    pub(crate) async fn receive(
        &self,
        mut packets: mpsc::Receiver<Vec<u8>>,
        mut assignments: mpsc::Receiver<AssignedAddr>,
    ) -> Result<()> {
        let mut updates_open = true;
        loop {
            tokio::select! {
                biased;
                address = assignments.recv(), if updates_open => match address {
                    Some(address) => {
                        let mut addrs = self.state.addresses.write();
                        match address.ip {
                            IpAddr::V4(ip) => addrs.0 = Some(ip),
                            IpAddr::V6(ip) => addrs.1 = Some(ip),
                        }
                    }
                    None => updates_open = false,
                },
                packet = packets.recv() => match packet {
                    Some(packet) => self.forward(packet).await,
                    None => return Ok(()),
                },
            }
        }
    }

    async fn forward(&self, mut packet: Vec<u8>) {
        let Some((peer, local, payload)) = decode_udp(&packet) else {
            return;
        };
        if self.address(local.is_ipv4()) != Some(local.ip()) {
            return;
        }
        let route = self
            .state
            .routes
            .lock()
            .ports
            .get(&local.port())
            .filter(|entry| entry.peer == peer)
            .map(|entry| (entry.quic_local, entry.inbound.clone()));
        let Some((quic_local, sender)) = route else {
            return;
        };
        let len = payload.len();
        packet.copy_within(payload, 0);
        packet.truncate(len);
        // Backpressure reaches the H2 receive worker through its bounded queue.
        let _ = sender.send((quic_local, peer, packet)).await;
    }
}

impl CarrierSocket {
    pub async fn send(&self, peer: SocketAddr, data: &[u8]) -> Result<()> {
        if peer != self.peer || data.len() > MAX_DATAGRAM_SIZE {
            return Err(AetherError::Masque("invalid carried QUIC send".into()));
        }
        let source = self
            .carrier
            .address(peer.is_ipv4())
            .ok_or_else(|| AetherError::Masque("carrier missing address for QUIC family".into()))?;
        let permit = self
            .carrier
            .outbound
            .reserve()
            .await
            .map_err(|_| AetherError::Masque("carrier send stream closed".into()))?;
        permit.send(encode_udp(
            SocketAddr::new(source, self.source_port),
            peer,
            data,
        ));
        Ok(())
    }
}

impl Drop for CarrierSocket {
    fn drop(&mut self) {
        self.carrier
            .state
            .routes
            .lock()
            .ports
            .remove(&self.source_port);
    }
}

fn encode_udp(source: SocketAddr, dest: SocketAddr, data: &[u8]) -> Vec<u8> {
    let caps = ChecksumCapabilities::default();
    let ip_header_len = if source.is_ipv4() { 20 } else { 40 };
    let udp_len = 8 + data.len();
    let mut packet = vec![0; ip_header_len + udp_len];
    let (header, udp) = packet.split_at_mut(ip_header_len);
    match (source.ip(), dest.ip()) {
        (IpAddr::V4(src_addr), IpAddr::V4(dst_addr)) => Ipv4Repr {
            src_addr,
            dst_addr,
            next_header: IpProtocol::Udp,
            payload_len: udp_len,
            hop_limit: 64,
        }
        .emit(&mut Ipv4Packet::new_unchecked(header), &caps),
        (IpAddr::V6(src_addr), IpAddr::V6(dst_addr)) => Ipv6Repr {
            src_addr,
            dst_addr,
            next_header: IpProtocol::Udp,
            payload_len: udp_len,
            hop_limit: 64,
        }
        .emit(&mut Ipv6Packet::new_unchecked(header)),
        _ => unreachable!("bound QUIC peer and source have the same IP family"),
    }
    UdpRepr {
        src_port: source.port(),
        dst_port: dest.port(),
    }
    .emit(
        &mut UdpPacket::new_unchecked(udp),
        &source.ip().into(),
        &dest.ip().into(),
        data.len(),
        |out| out.copy_from_slice(data),
        &caps,
    );
    packet
}

fn decode_udp(packet: &[u8]) -> Option<(SocketAddr, SocketAddr, std::ops::Range<usize>)> {
    let (source, dest, payload, header_len) = match packet.first()? >> 4 {
        4 => {
            let ip = Ipv4Packet::new_checked(packet).ok()?;
            if ip.next_header() != IpProtocol::Udp
                || ip.more_frags()
                || ip.frag_offset() != 0
                || !ip.verify_checksum()
            {
                return None;
            }
            (
                IpAddr::V4(ip.src_addr()),
                IpAddr::V4(ip.dst_addr()),
                ip.payload(),
                ip.header_len() as usize,
            )
        }
        6 => {
            let ip = Ipv6Packet::new_checked(packet).ok()?;
            if ip.next_header() != IpProtocol::Udp {
                return None;
            }
            (
                IpAddr::V6(ip.src_addr()),
                IpAddr::V6(ip.dst_addr()),
                ip.payload(),
                40,
            )
        }
        _ => return None,
    };
    let udp = UdpPacket::new_checked(payload).ok()?;
    // IPv4 permits an omitted checksum. IPv6 UDP always requires one.
    if (udp.checksum() == 0 && source.is_ipv6())
        || (udp.checksum() != 0 && !udp.verify_checksum(&source.into(), &dest.into()))
    {
        return None;
    }
    let end = header_len + usize::from(udp.len());
    Some((
        SocketAddr::new(source, udp.src_port()),
        SocketAddr::new(dest, udp.dst_port()),
        header_len + 8..end,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn ipv6_carrier_applies_send_backpressure_and_checks_its_receive_address() {
        let (outbound, mut sent) = mpsc::channel(1);
        let carrier = Carrier::new("", "fd00::2", outbound);
        let peer = "[fd00::3]:1701".parse().unwrap();
        let quic_local = "[::]:0".parse().unwrap();
        let (inbound, mut received) = mpsc::channel(1);
        let socket = carrier.open(peer, quic_local, inbound).unwrap();

        socket.send(peer, b"one").await.unwrap();
        let second = socket.send(peer, b"two");
        tokio::pin!(second);
        assert!(futures::poll!(&mut second).is_pending());

        let first = sent.recv().await.unwrap();
        let (source, dest, data) = decode_udp(&first).unwrap();
        assert_eq!(source.ip(), "fd00::2".parse::<IpAddr>().unwrap());
        assert_eq!(dest, peer);
        assert_eq!(&first[data], b"one");
        second.await.unwrap();
        let second = sent.recv().await.unwrap();
        assert_eq!(&second[decode_udp(&second).unwrap().2], b"two");

        let other = SocketAddr::new("fd00::4".parse().unwrap(), source.port());
        carrier.forward(encode_udp(peer, other, b"wrong IP")).await;
        assert!(received.try_recv().is_err());
        let mut invalid = encode_udp(peer, source, b"wrong checksum");
        *invalid.last_mut().unwrap() ^= 1;
        carrier.forward(invalid).await;
        assert!(received.try_recv().is_err());
        carrier.forward(encode_udp(peer, source, b"reply")).await;
        assert_eq!(
            received.try_recv().unwrap(),
            (quic_local, peer, b"reply".to_vec())
        );
    }
}
