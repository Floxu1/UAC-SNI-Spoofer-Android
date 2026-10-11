use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Copy)]
pub struct Tuning {
    pub tier: Tier,
    pub cpus: usize,
    pub mem_mb: Option<u64>,
    pub scan_concurrency_cap: usize,
    pub udp_socket_buf: usize,
    pub netstack_tcp_buf: usize,
    /// Receive buffer for the few long-lived outbound TCP sockets (the
    /// Psiphon/SSH legs through WARP). Equal to `netstack_tcp_buf` when the
    /// tier does not get large buffers.
    pub netstack_tcp_big_rx: usize,
    pub netstack_tcp_big_tx: usize,
    /// How many outbound sockets may hold large buffers at the same time.
    pub netstack_big_sockets: usize,
    pub netstack_udp_buf: usize,
    pub channel_capacity: usize,
}

static TUNING: OnceLock<Tuning> = OnceLock::new();

fn detected_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
}

#[cfg(target_os = "linux")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "android")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "macos")]
fn total_mem_mb() -> Option<u64> {
    let mut size: u64 = 0;
    let mut len = std::mem::size_of::<u64>();
    let name = b"hw.memsize\0";
    let ret = unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut size as *mut u64 as *mut libc::c_void,
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if ret == 0 {
        Some(size / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(target_os = "windows")]
fn total_mem_mb() -> Option<u64> {
    #[repr(C)]
    struct MemoryStatusEx {
        length: u32,
        memory_load: u32,
        total_phys: u64,
        avail_phys: u64,
        total_page_file: u64,
        avail_page_file: u64,
        total_virtual: u64,
        avail_virtual: u64,
        avail_extended_virtual: u64,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GlobalMemoryStatusEx(buf: *mut MemoryStatusEx) -> i32;
    }

    let mut status = MemoryStatusEx {
        length: std::mem::size_of::<MemoryStatusEx>() as u32,
        memory_load: 0,
        total_phys: 0,
        avail_phys: 0,
        total_page_file: 0,
        avail_page_file: 0,
        total_virtual: 0,
        avail_virtual: 0,
        avail_extended_virtual: 0,
    };

    let ok = unsafe { GlobalMemoryStatusEx(&mut status) };
    if ok != 0 {
        Some(status.total_phys / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(not(any(
    target_os = "linux",
    target_os = "android",
    target_os = "macos",
    target_os = "windows"
)))]
fn total_mem_mb() -> Option<u64> {
    None
}

fn detect_tier(cpus: usize, mem_mb: Option<u64>) -> Tier {
    if let Ok(v) = std::env::var("AETHER_PERF_PROFILE") {
        match v.trim().to_lowercase().as_str() {
            "low" => return Tier::Low,
            "medium" | "mid" => return Tier::Medium,
            "high" => return Tier::High,
            _ => {}
        }
    }

    let mem_low = mem_mb.map(|m| m <= 384).unwrap_or(false);
    let mem_medium = mem_mb.map(|m| m <= 1536).unwrap_or(false);

    if cpus <= 2 || mem_low {
        Tier::Low
    } else if cpus <= 4 || mem_medium {
        Tier::Medium
    } else {
        Tier::High
    }
}

fn build_tuning() -> Tuning {
    let cpus = detected_cpus();
    let mem_mb = total_mem_mb();
    let tier = detect_tier(cpus, mem_mb);

    let (
        scan_concurrency_cap,
        udp_socket_buf,
        netstack_tcp_buf,
        netstack_udp_buf,
        channel_capacity,
    ) = match tier {
        Tier::Low => (4usize, 256 * 1024, 128 * 1024, 32 * 1024, 128usize),
        Tier::Medium => (10usize, 2 * 1024 * 1024, 256 * 1024, 64 * 1024, 512usize),
        Tier::High => (
            usize::MAX,
            7 * 1024 * 1024,
            512 * 1024,
            128 * 1024,
            1024usize,
        ),
    };

    // Large windows only where memory allows; Low/Medium stay as they were.
    // High worst case: 6 x (2 MB + 1 MB) = 18 MB, and pages are only touched
    // as data actually queues.
    let (netstack_tcp_big_rx, netstack_tcp_big_tx, netstack_big_sockets) = match tier {
        Tier::High => (2 * 1024 * 1024, 1024 * 1024, 6usize),
        _ => (netstack_tcp_buf, netstack_tcp_buf, 0usize),
    };

    Tuning {
        tier,
        cpus,
        mem_mb,
        scan_concurrency_cap,
        udp_socket_buf,
        netstack_tcp_buf,
        netstack_tcp_big_rx,
        netstack_tcp_big_tx,
        netstack_big_sockets,
        netstack_udp_buf,
        channel_capacity,
    }
}

pub fn tuning() -> &'static Tuning {
    TUNING.get_or_init(build_tuning)
}

pub fn log_summary() {
    let t = tuning();
    let mem = t
        .mem_mb
        .map(|m| format!("{m}MB"))
        .unwrap_or_else(|| "unknown".to_string());
    let cap = if t.scan_concurrency_cap == usize::MAX {
        "unlimited".to_string()
    } else {
        t.scan_concurrency_cap.to_string()
    };
    log::info!(
        "[*] performance profile: {:?} (cpus={} mem={}); scan concurrency cap={}, udp socket buffer={}KB, netstack buffers={}KB/{}KB, large tcp={}KB/{}KB x{}, channel capacity={}",
        t.tier,
        t.cpus,
        mem,
        cap,
        t.udp_socket_buf / 1024,
        t.netstack_tcp_buf / 1024,
        t.netstack_udp_buf / 1024,
        t.netstack_tcp_big_rx / 1024,
        t.netstack_tcp_big_tx / 1024,
        t.netstack_big_sockets,
        t.channel_capacity,
    );
}

pub fn cap_concurrency(requested: usize) -> usize {
    requested.min(tuning().scan_concurrency_cap)
}

pub fn udp_socket_buf_bytes() -> usize {
    tuning().udp_socket_buf
}

pub fn netstack_tcp_buf_bytes() -> usize {
    tuning().netstack_tcp_buf
}

/// (rx, tx, max concurrent sockets) for large outbound TCP buffers.
pub fn netstack_tcp_big() -> (usize, usize, usize) {
    let t = tuning();
    (t.netstack_tcp_big_rx, t.netstack_tcp_big_tx, t.netstack_big_sockets)
}

pub fn netstack_udp_buf_bytes() -> usize {
    tuning().netstack_udp_buf
}

pub fn channel_capacity() -> usize {
    tuning().channel_capacity
}
