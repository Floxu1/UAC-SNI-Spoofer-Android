package com.uacspoofer.mobile.mci

import android.util.Log
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext





class MciEdgeBridge(
    private val connector: MciConnector,
) {
    private val lifecycleMutex = Mutex()
    private var worker: BridgeWorker? = null

    suspend fun start(edge: MciEdge) = lifecycleMutex.withLock {
        stopLocked()
        val server = bindLoopbackServer()
        val rootJob = SupervisorJob()
        val nextWorker = BridgeWorker(server, rootJob)
        val scope = CoroutineScope(rootJob + Dispatchers.IO)
        worker = nextWorker
        scope.launch { acceptLoop(nextWorker, edge) }
        Log.i(TAG, "bridge listening for ${edge.role}=${edge.address}:${edge.port}")
    }

    suspend fun stop() = withContext(NonCancellable) {
        lifecycleMutex.withLock { stopLocked() }
    }

    private suspend fun stopLocked() {
        withContext(NonCancellable) {
            val current = worker ?: return@withContext
            worker = null

            
            current.beginStopping()
            current.rootJob.cancel()
            runCatching { current.server.close() }
            current.closeAllSockets()
            current.rootJob.join()
            current.closeAllSockets()
        }
    }

    private suspend fun CoroutineScope.acceptLoop(worker: BridgeWorker, edge: MciEdge) {
        
        
        val sessionGate = Semaphore(MciConfig.BRIDGE_MAX_SESSIONS)
        while (isActive) {
            sessionGate.acquire()
            val client = try {
                worker.server.accept()
            } catch (error: SocketException) {
                sessionGate.release()
                if (isActive && !worker.stopping) Log.w(TAG, "bridge accept stopped", error)
                break
            } catch (error: Throwable) {
                sessionGate.release()
                if (!worker.stopping) Log.w(TAG, "bridge accept failed", error)
                continue
            }

            if (!worker.register(client)) {
                sessionGate.release()
                continue
            }
            launch {
                try {
                    relaySession(client, edge, worker)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isActive && !worker.stopping) {
                        Log.d(TAG, "bridge session ended: ${error.javaClass.simpleName}")
                    }
                } finally {
                    worker.unregister(client)
                    runCatching { client.close() }
                    sessionGate.release()
                }
            }
        }
    }

    private suspend fun relaySession(client: Socket, edge: MciEdge, worker: BridgeWorker) {
        client.tcpNoDelay = true
        client.receiveBufferSize = MciConfig.SOCKET_BUFFER_BYTES
        client.sendBufferSize = MciConfig.SOCKET_BUFFER_BYTES
        var upstream: Socket? = null
        try {
            val connectedUpstream = connector.connect(edge) { socket ->
                upstream = socket
                check(worker.register(socket)) { "bridge route is stopping" }
            }
            upstream = connectedUpstream
            supervisorScope {
                
                
                
                val clientToEdge = launch {
                    relayQuietly(client, connectedUpstream, worker, applyExternalFinalMask = true)
                }
                val edgeToClient = launch { relayQuietly(connectedUpstream, client, worker) }
                clientToEdge.invokeOnCompletion { closePair(client, connectedUpstream) }
                edgeToClient.invokeOnCompletion { closePair(client, connectedUpstream) }
                joinAll(clientToEdge, edgeToClient)
            }
        } finally {
            upstream?.let { socket ->
                worker.unregister(socket)
                closePair(client, socket)
            } ?: runCatching { client.close() }
        }
    }

    private fun relayQuietly(
        source: Socket,
        destination: Socket,
        worker: BridgeWorker,
        applyExternalFinalMask: Boolean = false,
    ) {
        try {
            if (applyExternalFinalMask) {
                relayClientHelloWithFinalMask(source, destination)
            } else {
                relay(source, destination)
            }
        } catch (error: IOException) {
            
            if (!worker.stopping && !source.isClosed && !destination.isClosed) {
                Log.w(TAG, "relay I/O ended: ${error.javaClass.simpleName}")
            }
        }
    }

    




    private fun relayClientHelloWithFinalMask(source: Socket, destination: Socket) {
        source.getInputStream().use { input ->
            destination.getOutputStream().use { output ->
                val header = ByteArray(TLS_RECORD_HEADER_BYTES)
                val headerRead = readUpTo(input, header)
                Log.i(
                    TAG,
                    "edge relay first header bytes=$headerRead hex=" +
                        header.take(headerRead).joinToString("") { byte -> "%02x".format(byte) },
                )
                if (headerRead < header.size) {
                    if (headerRead > 0) output.write(header, 0, headerRead)
                    return
                }

                val payloadLength =
                    ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
                val isTlsHandshake = header[0].toInt() and 0xff == TLS_HANDSHAKE_CONTENT_TYPE
                if (!isTlsHandshake || payloadLength <= 0 || payloadLength > MAX_TLS_RECORD_PAYLOAD_BYTES) {
                    Log.i(TAG, "external finalmask skipped tls=$isTlsHandshake payload=$payloadLength")
                    output.write(header)
                    input.copyTo(output, MciConfig.RELAY_BUFFER_BYTES)
                    return
                }

                val firstPayload = ByteArray(MciConfig.FINALMASK_LENGTH)
                val firstPayloadRead = readUpTo(input, firstPayload)
                if (firstPayloadRead < firstPayload.size) {
                    output.write(header)
                    if (firstPayloadRead > 0) output.write(firstPayload, 0, firstPayloadRead)
                    return
                }

                val remainingPayload = payloadLength - firstPayload.size
                val rewrittenHeadersAndPrefix = byteArrayOf(
                    header[0], header[1], header[2], 0x00, firstPayload.size.toByte(),
                ) + firstPayload + byteArrayOf(
                    header[0],
                    header[1],
                    header[2],
                    ((remainingPayload ushr 8) and 0xff).toByte(),
                    (remainingPayload and 0xff).toByte(),
                )
                output.write(rewrittenHeadersAndPrefix)
                Log.i(
                    TAG,
                    "external finalmask applied tlsPayload=$payloadLength " +
                        "prefixWrite=${rewrittenHeadersAndPrefix.size}",
                )
                copyExactly(input, output, remainingPayload)
                input.copyTo(output, MciConfig.RELAY_BUFFER_BYTES)
            }
        }
    }

    private fun readUpTo(input: java.io.InputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) break
            if (read > 0) offset += read
        }
        return offset
    }

    private fun copyExactly(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        byteCount: Int,
    ) {
        val buffer = ByteArray(minOf(MciConfig.RELAY_BUFFER_BYTES, byteCount.coerceAtLeast(1)))
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) return
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun relay(source: Socket, destination: Socket) {
        source.getInputStream().use { input ->
            destination.getOutputStream().use { output ->
                input.copyTo(output, MciConfig.RELAY_BUFFER_BYTES)
            }
        }
    }

    private fun closePair(first: Socket, second: Socket) {
        runCatching { first.shutdownInput() }
        runCatching { first.shutdownOutput() }
        runCatching { second.shutdownInput() }
        runCatching { second.shutdownOutput() }
        runCatching { first.close() }
        runCatching { second.close() }
    }

    private suspend fun bindLoopbackServer(): ServerSocket {
        val endpoint = InetSocketAddress(
            InetAddress.getByName(MciConfig.LOCAL_BRIDGE_ADDRESS),
            MciConfig.LOCAL_BRIDGE_PORT,
        )
        var lastBindError: BindException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(endpoint)
                return candidate
            } catch (error: BindException) {
                lastBindError = error
                runCatching { candidate.close() }
                if (attempt < BIND_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS)
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                throw error
            }
        }
        throw checkNotNull(lastBindError)
    }

    private class BridgeWorker(
        val server: ServerSocket,
        val rootJob: Job,
    ) {
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()

        @Volatile
        var stopping: Boolean = false
            private set

        fun beginStopping() {
            stopping = true
        }

        fun register(socket: Socket): Boolean {
            if (stopping) {
                runCatching { socket.close() }
                return false
            }
            sockets += socket
            if (stopping) {
                if (sockets.remove(socket)) runCatching { socket.close() }
                return false
            }
            return true
        }

        fun unregister(socket: Socket) {
            sockets.remove(socket)
        }

        fun closeAllSockets() {
            while (true) {
                val snapshot = sockets.toList()
                if (snapshot.isEmpty()) return
                snapshot.forEach { socket ->
                    if (sockets.remove(socket)) runCatching { socket.close() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UAC-MCI"
        private const val TLS_RECORD_HEADER_BYTES = 5
        private const val TLS_HANDSHAKE_CONTENT_TYPE = 0x16
        private const val MAX_TLS_RECORD_PAYLOAD_BYTES = 18 * 1024
        private const val BIND_ATTEMPTS = 10
        private const val BIND_RETRY_DELAY_MS = 50L
    }
}
