package com.avalon.cwm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the in-process Kotlin server available after MainActivity goes to the background.
 *
 * The native server remains owned by [EmbeddedServer]. This service raises the process priority,
 * publishes the localhost/LAN addresses, and requests process recreation after ordinary Android
 * background reclamation. Force-stop still intentionally wins.
 */
class ServerForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "cwm_local_server"
        private const val NOTIFICATION_ID = 8080
        private const val ACTION_REFRESH = "com.avalon.cwm.action.REFRESH_SERVER_NOTIFICATION"

        /** Safe to call repeatedly; Android reuses the existing service instance. */
        fun start(context: Context) {
            EmbeddedServer.prepareForStart()
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ServerForegroundService::class.java),
            )
        }

        /** Refreshes the address lines after permission or connectivity changes. */
        fun refresh(context: Context) {
            if (EmbeddedServer.isExplicitlyStopped()) return
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ServerForegroundService::class.java).setAction(ACTION_REFRESH),
            )
        }

        /** Fully closes the local server and removes its foreground service. */
        fun stop(context: Context) {
            val appContext = context.applicationContext
            EmbeddedServer.stop()
            appContext.stopService(Intent(appContext, ServerForegroundService::class.java))
        }
    }

    private val serviceStartedAt = System.currentTimeMillis()
    private val refreshWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cwm-notification-addresses")
    }
    private val refreshLock = Any()
    private var pendingRefresh: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        if (EmbeddedServer.isExplicitlyStopped()) {
            stopSelf()
            return
        }
        createNotificationChannel()
        enterForeground(buildNotification(emptyList(), resolving = true))
        EmbeddedServer.ensureStarted(applicationContext)
        registerNetworkUpdates()
        scheduleAddressRefresh(delayMillis = 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (EmbeddedServer.isExplicitlyStopped()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        EmbeddedServer.ensureStarted(applicationContext)
        scheduleAddressRefresh(delayMillis = if (intent?.action == ACTION_REFRESH) 0 else 250)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkUpdates()
        synchronized(refreshLock) {
            pendingRefresh?.cancel(false)
            pendingRefresh = null
        }
        refreshWorker.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地网页服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示本机与局域网地址，并保持 127.0.0.1:8080 后台可访问"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun enterForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun buildNotification(lanUrls: List<String>, resolving: Boolean): Notification {
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val lanSummary = when {
            resolving -> "正在读取…"
            lanUrls.isEmpty() -> "未连接"
            else -> lanUrls.joinToString("  ") { it.removePrefix("http://") }
        }
        val addressText = buildString {
            append("本机 ").append(ServerAddressProvider.LOCAL_URL.removePrefix("http://"))
            append("\n局域网 ").append(lanSummary)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openBrowser = PendingIntent.getActivity(
            this,
            1,
            Intent(Intent.ACTION_VIEW, Uri.parse(ServerAddressProvider.LOCAL_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val refreshIntent = Intent(this, ServerForegroundService::class.java)
            .setAction(ACTION_REFRESH)
        val refresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                2,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                2,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("本地网页服务运行中")
            .setContentText(addressText)
            .setSubText(appLabel)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("本地网页服务运行中")
                    .bigText(addressText)
                    .setSummaryText(appLabel),
            )
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_server_notification, "打开网页", openBrowser)
            .addAction(R.drawable.ic_server_notification, "刷新地址", refresh)
            .setWhen(serviceStartedAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun scheduleAddressRefresh(delayMillis: Long = 300) {
        synchronized(refreshLock) {
            if (refreshWorker.isShutdown) return
            pendingRefresh?.cancel(false)
            pendingRefresh = try {
                refreshWorker.schedule(
                    {
                        val notification = buildNotification(
                            ServerAddressProvider.lanUrls(),
                            resolving = false,
                        )
                        try {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification)
                        } catch (_: SecurityException) {
                            // Android 13+ may hide notifications when permission is denied.
                            // The foreground service itself remains active.
                        }
                    },
                    delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    private fun registerNetworkUpdates() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleAddressRefresh()
            override fun onLost(network: Network) = scheduleAddressRefresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = scheduleAddressRefresh()
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Throwable) {
            // Manual refresh remains available when a vendor system blocks callbacks.
        }
    }

    private fun unregisterNetworkUpdates() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }
}
