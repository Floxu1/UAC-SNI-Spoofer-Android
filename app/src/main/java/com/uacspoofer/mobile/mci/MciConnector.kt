package com.uacspoofer.mobile.mci

import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.uacspoofer.mobile.vpn.SocketProtector
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel

class MciConnector(
    private val protector: SocketProtector,
) {
    fun connect(
        edge: MciEdge,
        onSocketCreated: (Socket) -> Unit = {},
    ): Socket {
        
        
        
        val channel = SocketChannel.open()
        val socket = channel.socket()
        
        
        try {
            onSocketCreated(socket)
            if (!protector.protect(socket)) {
                error("VpnService.protect returned false")
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = MciConfig.SOCKET_BUFFER_BYTES
            socket.sendBufferSize = MciConfig.SOCKET_BUFFER_BYTES
            socket.connect(
                InetSocketAddress(edge.address, edge.port),
                MciConfig.CONNECT_TIMEOUT_MS,
            )
            tuneKeepAlive(socket)
            socket.soTimeout = 0
            Log.i(TAG, "protected edge connected ${edge.role}=${edge.address}:${edge.port}")
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            runCatching { channel.close() }
            throw error
        }
    }

    private fun tuneKeepAlive(socket: Socket) {
        
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        tuneKeepAliveApi29(socket)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun tuneKeepAliveApi29(socket: Socket) {
        runCatching {
            ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
                Os.setsockoptInt(
                    descriptor.fileDescriptor,
                    OsConstants.IPPROTO_TCP,
                    TCP_KEEPIDLE,
                    MciConfig.XRAY_KEEPALIVE_IDLE_SECONDS,
                )
                Os.setsockoptInt(
                    descriptor.fileDescriptor,
                    OsConstants.IPPROTO_TCP,
                    TCP_KEEPINTVL,
                    MciConfig.XRAY_KEEPALIVE_INTERVAL_SECONDS,
                )
                Os.setsockoptInt(
                    descriptor.fileDescriptor,
                    OsConstants.IPPROTO_TCP,
                    TCP_KEEPCNT,
                    3,
                )
            }
        }.onFailure {
            Log.d(TAG, "platform keepalive tuning unavailable: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "UAC-MCI"

        
        private const val TCP_KEEPIDLE = 4
        private const val TCP_KEEPINTVL = 5
        private const val TCP_KEEPCNT = 6
    }
}
