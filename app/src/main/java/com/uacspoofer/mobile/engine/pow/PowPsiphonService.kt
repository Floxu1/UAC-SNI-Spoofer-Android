package com.uacspoofer.mobile.engine.pow

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import ca.psiphon.PsiphonTunnel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PowPsiphonService : Service() {
    private val worker = HandlerThread("uac-pow-inner", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val incoming = Messenger(Handler(worker.looper, ::onCommand))
    private val host = Host()
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var callback: Messenger? = null
    @Volatile private var configJson = "{}"
    @Volatile private var tunnel: PsiphonTunnel? = null

    override fun attachBaseContext(newBase: Context) {
        GoJniLoader.forcePowLibrary()
        super.attachBaseContext(newBase)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopRequested.set(true)
        runCatching { tunnel?.stop() }
        tunnel = null
        worker.quitSafely()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun onCommand(message: Message): Boolean {
        when (message.what) {
            PowPsiphonIpc.MSG_START -> {
                callback = message.replyTo
                configJson = message.data.getString(PowPsiphonIpc.KEY_CONFIG) ?: "{}"
                startTunnel()
            }
            PowPsiphonIpc.MSG_STOP -> {
                stopRequested.set(true)
                runCatching { tunnel?.stop() }
                tunnel = null
                emit(PowPsiphonIpc.MSG_STOPPED)
            }
            PowPsiphonIpc.MSG_PROTECT_RESULT -> Unit
        }
        return true
    }

    private fun startTunnel() {
        stopRequested.set(false)
        runCatching { tunnel?.stop() }
        tunnel = null
        try {
            GoJniLoader.forcePowLibrary()
            val serverEntries = readServerEntries()
            val next = PsiphonTunnel.newPsiphonTunnel(host)
            next.setVpnMode(false)
            tunnel = next
            next.startTunneling(serverEntries)
            Log.i(TAG, "Psiphon tunneling started entries=${serverEntries.length}")
        } catch (error: Throwable) {
            tunnel = null
            Log.e(TAG, "Psiphon start failed", error)
            emit(
                PowPsiphonIpc.MSG_START_FAILED,
                Bundle().apply {
                    putString(
                        PowPsiphonIpc.KEY_ERROR,
                        error.message.orEmpty().ifBlank { "Psiphon failed to start" },
                    )
                },
            )
        }
    }

    private fun readServerEntries(): String = runCatching {
        assets.open(PowPsiphonIpc.ASSET_SERVER_ENTRIES).bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")

    private fun emit(what: Int, extras: Bundle? = null, arg1: Int = 0) {
        val target = callback ?: return
        val message = Message.obtain(null, what).apply {
            this.arg1 = arg1
            if (extras != null) data = extras
        }
        try {
            target.send(message)
        } catch (_: RemoteException) {
            callback = null
        }
    }

    private inner class Host : PsiphonTunnel.HostService {
        override fun getContext() = applicationContext

        override fun getPsiphonConfig(): String = configJson

        override fun loadLibrary(name: String?) {
            GoJniLoader.load()
        }

        override fun bindToDevice(fd: Long) {
            val target = callback ?: throw PsiphonTunnel.Exception("protect callback missing")
            val pfd = ParcelFileDescriptor.fromFd(fd.toInt())
            val result = Handler(Looper.getMainLooper())
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val reply = Messenger(Handler(result.looper) { replyMsg ->
                if (replyMsg.what == PowPsiphonIpc.MSG_PROTECT_RESULT) {
                    ok.set(replyMsg.arg1 == 1)
                    latch.countDown()
                }
                true
            })
            val payload = Bundle().apply {
                putParcelable(PowPsiphonIpc.KEY_FD, pfd)
            }
            val message = Message.obtain(null, PowPsiphonIpc.MSG_PROTECT).apply {
                data = payload
                replyTo = reply
            }
            try {
                target.send(message)
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw PsiphonTunnel.Exception("protect(fd=$fd) timed out")
                }
                if (!ok.get()) {
                    throw PsiphonTunnel.Exception("protect(fd=$fd) failed")
                }
            } finally {
                runCatching { pfd.close() }
            }
        }

        override fun onListeningSocksProxyPort(port: Int) {
            emit(PowPsiphonIpc.MSG_SOCKS_PORT, arg1 = port)
        }

        override fun onConnecting() {
            emit(PowPsiphonIpc.MSG_CONNECTING)
        }

        override fun onConnected() {
            emit(PowPsiphonIpc.MSG_CONNECTED)
        }

        override fun onExiting() {
            tunnel = null
            emit(PowPsiphonIpc.MSG_EXITING)
        }

        override fun onDiagnosticMessage(message: String?) {
            val text = message.orEmpty()
            if (text.isBlank()) return
            emit(
                PowPsiphonIpc.MSG_DIAGNOSTIC,
                Bundle().apply { putString(PowPsiphonIpc.KEY_MESSAGE, text) },
            )
        }

        override fun onClientRegion(region: String?) {
            if (region.isNullOrBlank()) return
            emit(
                PowPsiphonIpc.MSG_CLIENT_REGION,
                Bundle().apply { putString(PowPsiphonIpc.KEY_REGION, region) },
            )
        }

        override fun onConnectedServerRegion(region: String?) {
            if (region.isNullOrBlank()) return
            emit(
                PowPsiphonIpc.MSG_SERVER_REGION,
                Bundle().apply { putString(PowPsiphonIpc.KEY_REGION, region) },
            )
        }

        override fun onAvailableEgressRegions(regions: MutableList<String>?) {
            val list = ArrayList(regions?.filterNotNull().orEmpty())
            if (list.isEmpty()) return
            emit(
                PowPsiphonIpc.MSG_EGRESS_REGIONS,
                Bundle().apply { putStringArrayList(PowPsiphonIpc.KEY_REGIONS, ArrayList(list)) },
            )
        }

        override fun onBytesTransferred(sent: Long, received: Long) {
            emit(
                PowPsiphonIpc.MSG_BYTES,
                Bundle().apply {
                    putLong(PowPsiphonIpc.KEY_SENT, sent)
                    putLong(PowPsiphonIpc.KEY_RECEIVED, received)
                },
            )
        }

        override fun onClientAddress(address: String?) {
            if (address.isNullOrBlank()) return
            emit(
                PowPsiphonIpc.MSG_CLIENT_ADDRESS,
                Bundle().apply { putString(PowPsiphonIpc.KEY_ADDRESS, address) },
            )
        }

        override fun onHomepage(homepage: String?) = Unit

        override fun onListeningHttpProxyPort(port: Int) = Unit
    }

    companion object {
        private const val TAG = "UAC-POW-INNER"
    }
}
