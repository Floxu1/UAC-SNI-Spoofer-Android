//! Receive-side backpressure for a reliable HTTP/2 capsule stream.
//!
//! This runs in its own task: a full netstack queue must not block the
//! controller's Close/PING/PONG handling or the independent upload task.
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use tokio::sync::{mpsc, watch};

#[derive(Default)]
pub(crate) struct ReceiveStats {
    pub delivered_packets: AtomicU64,
    pub queue_waits: AtomicU64,
    pub released_bytes: AtomicU64,
}

impl Drop for ReceiveStats {
    fn drop(&mut self) {
        log::debug!(
            "[h2] receive summary: {} packets delivered, {} queue waits, {} bytes credited; no queue-full packet drops",
            self.delivered_packets.load(Ordering::Relaxed),
            self.queue_waits.load(Ordering::Relaxed),
            self.released_bytes.load(Ordering::Relaxed),
        );
    }
}

pub(crate) async fn forward<D>(
    mut body: h2::RecvStream,
    inbound: mpsc::Sender<Vec<u8>>,
    mut decode: D,
    progress: watch::Sender<u32>,
    validation_limit: u32,
    stats: Arc<ReceiveStats>,
) -> Result<(), String>
where
    D: FnMut(&[u8]) -> Result<Vec<Vec<u8>>, String>,
{
    let mut validations = 0u32;
    while let Some(chunk) = body.data().await {
        let chunk = chunk.map_err(|error| format!("h2 body: {error}"))?;
        // Decode one DATA chunk at a time. Do not accumulate 32 frames before
        // parsing: that can overrun the capsule parser's bounded reassembly buffer.
        let packets = decode(&chunk)?;
        let got_data = !packets.is_empty();
        for packet in packets {
            match inbound.try_send(packet) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Full(packet)) => {
                    let waits = stats.queue_waits.fetch_add(1, Ordering::Relaxed);
                    if waits == 0 {
                        log::debug!("[h2] inbound queue full: applying receive backpressure, keeping packet");
                    }
                    inbound.send(packet).await.map_err(|_| "h2 inbound queue closed".to_string())?;
                }
                Err(mpsc::error::TrySendError::Closed(_)) => {
                    return Err("h2 inbound queue closed".to_string());
                }
            }
            stats.delivered_packets.fetch_add(1, Ordering::Relaxed);
        }
        // Promise room for more data only after every complete packet decoded
        // from this chunk has entered the bounded netstack queue. A partial
        // capsule stays in the bounded parser and may need the following chunk.
        body.flow_control().release_capacity(chunk.len())
            .map_err(|error| format!("h2 receive credit: {error}"))?;
        stats.released_bytes.fetch_add(chunk.len() as u64, Ordering::Relaxed);
        if got_data && validations < validation_limit {
            validations += 1;
            // Coalesce startup notifications; never add a per-packet control
            // queue or any periodic work once data-plane validation is done.
            progress.send_replace(validations);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::Bytes;
    use std::time::Duration;
    use tokio::sync::oneshot;

    struct Drivers(Vec<tokio::task::JoinHandle<()>>);
    impl Drop for Drivers {
        fn drop(&mut self) {
            for task in &self.0 { task.abort(); }
        }
    }

    async fn session(window: u32) -> (
        h2::RecvStream, h2::SendStream<Bytes>, h2::PingPong,
        h2::SendStream<Bytes>, Drivers,
    ) {
        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        let (send_tx, send_rx) = oneshot::channel();
        let server = tokio::spawn(async move {
            let mut connection = h2::server::handshake(server_io).await.unwrap();
            let (request, mut respond) = connection.accept().await.unwrap().unwrap();
            let _request_body = request.into_body();
            let send = respond.send_response(http::Response::new(()), false).unwrap();
            let _ = send_tx.send(send);
            while let Some(request) = connection.accept().await {
                if request.is_err() { break; }
            }
        });
        let mut builder = h2::client::Builder::new();
        builder.initial_window_size(window);
        let (client, mut connection) = builder.handshake(client_io).await.unwrap();
        let ping = connection.ping_pong().unwrap();
        let driver = tokio::spawn(async move { let _ = connection.await; });
        let mut client = client.ready().await.unwrap();
        let (response, request) = client.send_request(
            http::Request::builder().uri("https://test.invalid/").body(()).unwrap(), false,
        ).unwrap();
        let body = response.await.unwrap().into_body();
        let send = send_rx.await.unwrap();
        (body, send, ping, request, Drivers(vec![server, driver]))
    }

    async fn send(send: &mut h2::SendStream<Bytes>, bytes: Bytes, end: bool) {
        send.reserve_capacity(bytes.len());
        tokio::time::timeout(Duration::from_secs(2), async {
            while send.capacity() < bytes.len() {
                std::future::poll_fn(|cx| send.poll_capacity(cx)).await.unwrap().unwrap();
            }
        }).await.unwrap();
        send.send_data(bytes, end).unwrap();
    }

    async fn wait_for_full_queue(stats: &ReceiveStats) {
        tokio::time::timeout(Duration::from_secs(2), async {
            while stats.queue_waits.load(Ordering::Relaxed) == 0 {
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
    }

    #[tokio::test]
    async fn burst_is_delivered_in_order_without_loss_through_a_one_packet_queue() {
        let (body, mut send_stream, _ping, _request, _drivers) = session(4096).await;
        let (tx, mut rx) = mpsc::channel(1);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, _progress_rx) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(chunk.chunks(32).map(<[u8]>::to_vec).collect()), progress, 2, stats.clone()));
        let data: Vec<u8> = (0..128).flat_map(|index| vec![index as u8; 32]).collect();
        send(&mut send_stream, Bytes::from(data), true).await;
        wait_for_full_queue(&stats).await;
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 0);
        for index in 0..128 {
            let packet = tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.unwrap().unwrap();
            assert_eq!(packet, vec![index as u8; 32]);
        }
        tokio::time::timeout(Duration::from_secs(2), worker).await.unwrap().unwrap().unwrap();
        assert_eq!(stats.delivered_packets.load(Ordering::Relaxed), 128);
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 4096);
        assert!(stats.queue_waits.load(Ordering::Relaxed) > 0);
    }

    #[tokio::test]
    async fn receive_credit_waits_for_handoff_but_ping_pong_keeps_working() {
        let (body, mut send_stream, mut ping, _request, _drivers) = session(96).await;
        let (tx, mut rx) = mpsc::channel(1);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, _progress_rx) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(chunk.chunks(32).map(<[u8]>::to_vec).collect()), progress, 2, stats.clone()));
        send(&mut send_stream, Bytes::from(vec![7; 96]), false).await;
        wait_for_full_queue(&stats).await;
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 0);
        assert_eq!(stats.delivered_packets.load(Ordering::Relaxed), 1);

        ping.send_ping(h2::Ping::opaque()).unwrap();
        tokio::time::timeout(Duration::from_secs(1),
            std::future::poll_fn(|cx| ping.poll_pong(cx))).await.unwrap().unwrap();
        send_stream.reserve_capacity(1);
        let extra_credit = tokio::time::timeout(Duration::from_millis(30), async {
            while send_stream.capacity() == 0 {
                std::future::poll_fn(|cx| send_stream.poll_capacity(cx)).await.unwrap().unwrap();
            }
        }).await;
        assert!(extra_credit.is_err(), "the edge must not get credit while the queue is blocked");
        for _ in 0..3 { assert_eq!(rx.recv().await.unwrap(), vec![7; 32]); }
        tokio::time::timeout(Duration::from_secs(1), async {
            while send_stream.capacity() == 0 {
                std::future::poll_fn(|cx| send_stream.poll_capacity(cx)).await.unwrap().unwrap();
            }
        }).await.unwrap();
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 96);
        worker.abort();
        assert!(worker.await.unwrap_err().is_cancelled());
    }

    #[tokio::test]
    async fn a_saturated_receiver_can_be_cancelled_without_waiting_for_queue_space() {
        let (body, mut send_stream, _ping, _request, _drivers) = session(256).await;
        let (tx, _rx) = mpsc::channel(1);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, _) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(chunk.chunks(32).map(<[u8]>::to_vec).collect()), progress, 2, stats.clone()));
        send(&mut send_stream, Bytes::from(vec![1; 256]), false).await;
        wait_for_full_queue(&stats).await;
        worker.abort();
        let stopped = tokio::time::timeout(Duration::from_secs(1), worker).await.unwrap();
        assert!(stopped.unwrap_err().is_cancelled());
    }

    #[tokio::test]
    async fn a_closed_netstack_queue_ends_the_receiver_instead_of_discarding_more_data() {
        let (body, mut send_stream, _ping, _request, _drivers) = session(256).await;
        let (tx, rx) = mpsc::channel(1);
        drop(rx);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, _) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(vec![chunk.to_vec()]), progress, 2, stats.clone()));
        send(&mut send_stream, Bytes::from_static(b"packet"), false).await;
        let error = tokio::time::timeout(Duration::from_secs(1), worker).await.unwrap().unwrap().unwrap_err();
        assert!(error.contains("inbound queue closed"));
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn validation_notifications_stop_after_the_startup_limit() {
        let (body, mut send_stream, _ping, _request, _drivers) = session(256).await;
        let (tx, mut rx) = mpsc::channel(8);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, progress_rx) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(vec![chunk.to_vec()]), progress, 2, stats));
        for index in 0..8 {
            send(&mut send_stream, Bytes::from(vec![index; 32]), index == 7).await;
            assert_eq!(rx.recv().await.unwrap(), vec![index; 32]);
        }
        tokio::time::timeout(Duration::from_secs(1), worker).await.unwrap().unwrap().unwrap();
        assert_eq!(*progress_rx.borrow(), 2);
    }

    #[tokio::test]
    async fn sustained_transfer_reopens_the_window_without_loss_or_unbounded_buffering() {
        let (body, mut send_stream, _ping, _request, _drivers) = session(4096).await;
        let (tx, mut rx) = mpsc::channel(1);
        let stats = Arc::new(ReceiveStats::default());
        let (progress, progress_rx) = watch::channel(0);
        let worker = tokio::spawn(forward(body, tx,
            |chunk| Ok(chunk.chunks(32).map(<[u8]>::to_vec).collect()), progress, 2, stats.clone()));
        let producer = tokio::spawn(async move {
            for frame in 0..256 {
                send(&mut send_stream, Bytes::from(vec![frame as u8; 4096]), frame == 255).await;
            }
        });
        for frame in 0..256 {
            for _ in 0..128 {
                let packet = tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.unwrap().unwrap();
                assert_eq!(packet, vec![frame as u8; 32]);
            }
        }
        tokio::time::timeout(Duration::from_secs(2), producer).await.unwrap().unwrap();
        tokio::time::timeout(Duration::from_secs(2), worker).await.unwrap().unwrap().unwrap();
        assert_eq!(stats.delivered_packets.load(Ordering::Relaxed), 32768);
        assert_eq!(stats.released_bytes.load(Ordering::Relaxed), 1024 * 1024);
        assert_eq!(*progress_rx.borrow(), 2);
    }
}
