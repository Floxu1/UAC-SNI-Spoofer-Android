package com.uacspoofer.mobile.engine.pow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class PowPsiphonClient(
    private val vpn: VpnService,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onConnecting()
        fun onConnected()
        fun onExiting()
        fun onListeningSocksProxyPort(port: Int)
        fun onDiagnosticMessage(message: String)
        fun onBytesTransferred(sent: Long, received: Long)
        fun onClientRegion(region: String)
        fun onConnectedServerRegion(region: String)
        fun onAvailableEgressRegions(regions: List<String>)
        fun onClientAddress(address: String)
        fun onStartFailed(message: String)
    }

    private val incomingThread = HandlerThread("uac-pow-psiphon-ipc", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val incoming = Messenger(Handler(incomingThread.looper, ::onIncoming))
    private val started = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val dead = AtomicBoolean(false)

    @Volatile private var outgoing: Messenger? = null
    @Volatile private var bindWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var stopWaiter: CompletableDeferred<Unit>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            outgoing = Messenger(service)
            bound.set(true)
            bindWaiter?.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            outgoing = null
            bound.set(false)
            if (!dead.getAndSet(true)) {
                callbacks.onExiting()
            }
        }
    }

    suspend fun start(configJson: String) {
        dead.set(false)
        ensureBound()
        val payload = Bundle().apply {
            putString(PowPsiphonIpc.KEY_CONFIG, configJson)
        }
        send(PowPsiphonIpc.MSG_START, payload)
    }

    suspend fun stop() {
        val waiter = CompletableDeferred<Unit>()
        stopWaiter = waiter
        runCatching { send(PowPsiphonIpc.MSG_STOP) }
        runCatching {
            withTimeout(STOP_TIMEOUT_MS) { waiter.await() }
        }
        stopWaiter = null
        withContext(Dispatchers.Main.immediate) {
            unbindLocked()
        }
    }

    fun close() {
        incomingThread.quitSafely()
    }

    private suspend fun ensureBound() {
        if (bound.get() && outgoing != null) return
        val waiter = CompletableDeferred<Unit>()
        bindWaiter = waiter
        val intent = serviceIntent()
        withContext(Dispatchers.Main) {
            runCatching { vpn.startService(intent) }
            val ok = vpn.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
            if (!ok) {
                waiter.completeExceptionally(IllegalStateException("Could not bind UAC PoW inner hop"))
            }
        }
        try {
            withTimeout(BIND_TIMEOUT_MS) { waiter.await() }
        } catch (timeout: TimeoutCancellationException) {
            unbindLocked()
            throw IllegalStateException("UAC PoW inner hop did not start")
        } finally {
            bindWaiter = null
        }
        started.set(true)
    }

    private fun unbindLocked() {
        if (bound.get() || started.get()) {
            runCatching { vpn.unbindService(connection) }
            runCatching { vpn.stopService(serviceIntent()) }
        }
        bound.set(false)
        started.set(false)
        outgoing = null
    }

    private fun send(what: Int, extras: Bundle? = null) {
        val target = outgoing ?: throw IllegalStateException("UAC PoW inner hop is not bound")
        val message = Message.obtain(null, what).apply {
            replyTo = incoming
            if (extras != null) data = extras
        }
        try {
            target.send(message)
        } catch (error: android.os.TransactionTooLargeException) {
            throw IllegalStateException("UAC PoW inner hop IPC payload is too large", error)
        } catch (error: RemoteException) {
            throw IllegalStateException("UAC PoW inner hop died", error)
        }
    }

    private fun onIncoming(message: Message): Boolean {
        when (message.what) {
            PowPsiphonIpc.MSG_PROTECT -> protectFd(message)
            PowPsiphonIpc.MSG_CONNECTED -> callbacks.onConnected()
            PowPsiphonIpc.MSG_EXITING -> callbacks.onExiting()
            PowPsiphonIpc.MSG_CONNECTING -> callbacks.onConnecting()
            PowPsiphonIpc.MSG_SOCKS_PORT -> callbacks.onListeningSocksProxyPort(message.arg1)
            PowPsiphonIpc.MSG_DIAGNOSTIC -> {
                val text = message.data.getString(PowPsiphonIpc.KEY_MESSAGE).orEmpty()
                if (text.isNotBlank()) callbacks.onDiagnosticMessage(text)
            }
            PowPsiphonIpc.MSG_BYTES -> callbacks.onBytesTransferred(
                message.data.getLong(PowPsiphonIpc.KEY_SENT),
                message.data.getLong(PowPsiphonIpc.KEY_RECEIVED),
            )
            PowPsiphonIpc.MSG_CLIENT_REGION -> {
                val region = message.data.getString(PowPsiphonIpc.KEY_REGION).orEmpty()
                if (region.isNotBlank()) callbacks.onClientRegion(region)
            }
            PowPsiphonIpc.MSG_SERVER_REGION -> {
                val region = message.data.getString(PowPsiphonIpc.KEY_REGION).orEmpty()
                if (region.isNotBlank()) callbacks.onConnectedServerRegion(region)
            }
            PowPsiphonIpc.MSG_EGRESS_REGIONS -> {
                val regions = message.data.getStringArrayList(PowPsiphonIpc.KEY_REGIONS).orEmpty()
                if (regions.isNotEmpty()) callbacks.onAvailableEgressRegions(regions)
            }
            PowPsiphonIpc.MSG_CLIENT_ADDRESS -> {
                val address = message.data.getString(PowPsiphonIpc.KEY_ADDRESS).orEmpty()
                if (address.isNotBlank()) callbacks.onClientAddress(address)
            }
            PowPsiphonIpc.MSG_START_FAILED -> {
                val error = message.data.getString(PowPsiphonIpc.KEY_ERROR).orEmpty()
                callbacks.onStartFailed(error.ifBlank { "Psiphon failed to start" })
            }
            PowPsiphonIpc.MSG_STOPPED -> stopWaiter?.complete(Unit)
        }
        return true
    }

    private fun protectFd(message: Message) {
        val replyTo = message.replyTo
        val pfd = parcelFd(message.data)
        val ok = try {
            pfd != null && vpn.protect(pfd.fd)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { pfd?.close() }
        }
        if (replyTo == null) return
        runCatching {
            replyTo.send(Message.obtain(null, PowPsiphonIpc.MSG_PROTECT_RESULT).apply { arg1 = if (ok) 1 else 0 })
        }
    }

    private fun parcelFd(data: Bundle): ParcelFileDescriptor? {
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        return if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(PowPsiphonIpc.KEY_FD, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelable(PowPsiphonIpc.KEY_FD)
        }
    }

    private fun serviceIntent(): Intent =
        Intent().setClassName(vpn.packageName, PowPsiphonIpc.SERVICE_CLASS)

    companion object {
        private const val BIND_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 8_000L
    }
}
