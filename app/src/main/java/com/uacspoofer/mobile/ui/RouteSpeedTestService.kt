package com.uacspoofer.mobile.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.uacspoofer.mobile.R

internal class RouteSpeedTestService : Service() {
    private lateinit var controller: RouteSpeedTestController

    override fun onCreate() {
        super.onCreate()
        controller = RouteSpeedTestController.get(applicationContext)
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                controller.pauseTest()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> controller.resumePersistedIfRequested()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Route Speed Test",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the adaptive Route Tournament running in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RouteSpeedTestService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_uac)
            .setContentTitle("Route Tournament is running")
            .setContentText("Champion and backup results are saved continuously")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(R.drawable.ic_notification_disconnect, "Pause", pauseIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.uacspoofer.mobile.action.ROUTE_TEST_START"
        const val ACTION_PAUSE = "com.uacspoofer.mobile.action.ROUTE_TEST_PAUSE"
        private const val CHANNEL_ID = "uac_route_speed_test"
        private const val NOTIFICATION_ID = 2107
    }
}
