package com.uacspoofer.mobile.vpn

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.NetworkGuardSettings
import java.io.FileDescriptor

/**
 * Userspace TUN splice that sits in front of Xray / Tor tun2socks / PoW tun2socks.
 *
 * Cached flags are the only values the packet loop reads. SharedPreferences / DataStore
 * are never touched on the hot path — [applyFlags] updates the volatiles from the
 * service thread when settings change.
 */
internal object TunPacketFilter {
    @Volatile
    var isSinkholeEnabled: Boolean = true
        private set

    @Volatile
    var isWebRtcBlockerEnabled: Boolean = true
        private set

    @Volatile
    var isQuicBlockerEnabled: Boolean = false
        private set

    @Volatile
    var isIpv6BlockerEnabled: Boolean = true
        private set

    private val lock = Any()
    private var realTun: ParcelFileDescriptor? = null
    private var filterPeer: ParcelFileDescriptor? = null
    private var upThread: Thread? = null
    private var downThread: Thread? = null

    @Volatile
    private var running = false

    fun applyFlags(settings: NetworkGuardSettings) {
        isSinkholeEnabled = settings.dnsSinkhole
        isWebRtcBlockerEnabled = settings.webRtcBlock
        isQuicBlockerEnabled = settings.quicBlock
        isIpv6BlockerEnabled = settings.ipv6Block
    }

    fun attach(tun: ParcelFileDescriptor, mtu: Int): ParcelFileDescriptor {
        synchronized(lock) {
            stopLocked()
            val pair = createPacketPair()
            val enginePfd = pair.first
            val filterPfd = pair.second
            runCatching { setBlocking(tun.fileDescriptor, blocking = true) }
            runCatching { setBlocking(filterPfd.fileDescriptor, blocking = true) }
            bumpSocketBuffers(filterPfd.fileDescriptor)
            bumpSocketBuffers(enginePfd.fileDescriptor)
            realTun = tun
            filterPeer = filterPfd
            running = true
            val bufferSize = (mtu + 64).coerceIn(2048, 65535)
            upThread = startLoop("uac-tun-up") {
                forwardDeviceToEngine(tun.fileDescriptor, filterPfd.fileDescriptor, bufferSize)
            }
            downThread = startLoop("uac-tun-down") {
                splice(filterPfd.fileDescriptor, tun.fileDescriptor, bufferSize)
            }
            AppLogRepository.info(
                LogSource.TUN,
                "Packet filter attached sinkhole=$isSinkholeEnabled webrtc=$isWebRtcBlockerEnabled " +
                    "quic=$isQuicBlockerEnabled ipv6=$isIpv6BlockerEnabled mtu=$mtu",
            )
            return enginePfd
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        runCatching { filterPeer?.close() }
        runCatching { realTun?.close() }
        filterPeer = null
        realTun = null
        val up = upThread
        val down = downThread
        upThread = null
        downThread = null
        up?.join(750L)
        down?.join(750L)
        if (up?.isAlive == true) up.interrupt()
        if (down?.isAlive == true) down.interrupt()
    }

    private fun startLoop(name: String, body: () -> Unit): Thread {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                body()
            } catch (_: InterruptedException) {
            } catch (error: Throwable) {
                if (running) {
                    AppLogRepository.warning(LogSource.TUN, "Packet filter $name stopped", error)
                }
            }
        }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Device → engine. IPv6 leak, QUIC, DNS sinkhole, and Anti-WebRTC run here
     * before Xray / WARP / Tor. Cached volatiles only — no preference I/O.
     */
    private fun forwardDeviceToEngine(tunFd: FileDescriptor, engineFd: FileDescriptor, bufferSize: Int) {
        val packet = ByteArray(bufferSize)
        val reply = ByteArray(bufferSize)
        val qnameScratch = CharArray(256)
        while (running) {
            val n = readPacket(tunFd, packet) ?: break
            if (n <= 0) break
            val sinkhole = isSinkholeEnabled
            val webrtc = isWebRtcBlockerEnabled
            val quic = isQuicBlockerEnabled
            val ipv6 = isIpv6BlockerEnabled
            if (sinkhole || webrtc || quic || ipv6) {
                when (
                    TunPacketPolicy.inspect(
                        packet,
                        n,
                        isSinkholeEnabled = sinkhole,
                        isWebRtcBlockerEnabled = webrtc,
                        isQuicBlockerEnabled = quic,
                        isIpv6BlockerEnabled = ipv6,
                        qnameScratch = qnameScratch,
                    )
                ) {
                    TunPacketAction.SINKHOLE -> {
                        val out = TunPacketPolicy.buildDnsSinkholeReply(packet, n, reply)
                        if (out > 0) writePacket(tunFd, reply, out)
                        continue
                    }
                    TunPacketAction.DROP -> continue
                    TunPacketAction.FORWARD -> Unit
                }
            }
            writePacket(engineFd, packet, n)
        }
    }

    private fun splice(from: FileDescriptor, to: FileDescriptor, bufferSize: Int) {
        val packet = ByteArray(bufferSize)
        while (running) {
            val n = readPacket(from, packet) ?: break
            if (n <= 0) break
            writePacket(to, packet, n)
        }
    }

    private fun readPacket(fd: FileDescriptor, buffer: ByteArray): Int? {
        while (running) {
            try {
                val n = Os.read(fd, buffer, 0, buffer.size)
                return if (n <= 0) null else n
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EINTR -> continue
                    OsConstants.EAGAIN -> {
                        waitReadable(fd)
                        continue
                    }
                    OsConstants.EPIPE, OsConstants.EBADF, OsConstants.ECONNRESET -> return null
                    else -> {
                        if (running) throw error
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun writePacket(fd: FileDescriptor, buffer: ByteArray, length: Int) {
        var offset = 0
        while (running && offset < length) {
            try {
                val n = Os.write(fd, buffer, offset, length - offset)
                if (n <= 0) return
                offset += n
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EINTR -> continue
                    OsConstants.EAGAIN -> {
                        waitWritable(fd)
                        continue
                    }
                    OsConstants.EPIPE, OsConstants.EBADF, OsConstants.ECONNRESET -> return
                    else -> {
                        if (running) throw error
                        return
                    }
                }
            }
        }
    }

    private fun waitReadable(fd: FileDescriptor) {
        val poll = android.system.StructPollfd()
        poll.fd = fd
        poll.events = OsConstants.POLLIN.toShort()
        runCatching { Os.poll(arrayOf(poll), 250) }
    }

    private fun waitWritable(fd: FileDescriptor) {
        val poll = android.system.StructPollfd()
        poll.fd = fd
        poll.events = OsConstants.POLLOUT.toShort()
        runCatching { Os.poll(arrayOf(poll), 250) }
    }

    private fun createPacketPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val left = FileDescriptor()
        val right = FileDescriptor()
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, left, right)
        } catch (_: ErrnoException) {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, left, right)
        }
        val engine = ParcelFileDescriptor.dup(left)
        val filter = ParcelFileDescriptor.dup(right)
        runCatching { Os.close(left) }
        runCatching { Os.close(right) }
        return engine to filter
    }

    private fun setBlocking(fd: FileDescriptor, blocking: Boolean) {
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        val next = if (blocking) {
            flags and OsConstants.O_NONBLOCK.inv()
        } else {
            flags or OsConstants.O_NONBLOCK
        }
        if (next != flags) Os.fcntlInt(fd, OsConstants.F_SETFL, next)
    }

    private fun bumpSocketBuffers(fd: FileDescriptor) {
        runCatching { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 256 * 1024) }
        runCatching { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 256 * 1024) }
    }
}
