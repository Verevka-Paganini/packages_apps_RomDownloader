package org.lineageos.romdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap

sealed class DownloadStatus {
    data object Preparing : DownloadStatus()
    data class Running(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val mirrorHost: String = ""
    ) : DownloadStatus()
    data object Verifying : DownloadStatus()
    data class Completed(val sha256: String, val mirrorHost: String = "") : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

object DownloadStateHolder {
    private val states = ConcurrentHashMap<String, DownloadStatus>()
    private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getStatus(fileName: String): DownloadStatus? = states[fileName]

    fun update(fileName: String, status: DownloadStatus?) {
        if (status == null) {
            states.remove(fileName)
        } else {
            states[fileName] = status
        }
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "org.lineageos.romdownloader.ACTION_START"
        const val ACTION_CANCEL = "org.lineageos.romdownloader.ACTION_CANCEL"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_THREADS = "extra_threads"
        const val EXTRA_MIRROR = "extra_mirror"
        const val DEFAULT_THREADS = 6
        const val DEFAULT_MIRROR = "auto"

        private const val CHANNEL_ID = "rom_downloads_channel"
        private const val FOREGROUND_NOTIF_ID = 1001

        fun startDownload(
            context: Context,
            url: String,
            fileName: String,
            threadCount: Int = DEFAULT_THREADS,
            mirror: String = DEFAULT_MIRROR
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_THREADS, threadCount)
                putExtra(EXTRA_MIRROR, mirror)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, fileName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            context.startService(intent)
        }
    }

    private val activeDownloaders = ConcurrentHashMap<String, MultiThreadDownloader>()
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val threads = intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS).coerceIn(1, 16)
                val mirror = intent.getStringExtra(EXTRA_MIRROR) ?: DEFAULT_MIRROR
                if (activeDownloaders.containsKey(fileName)) return START_NOT_STICKY

                acquireWakeLock()
                DownloadStateHolder.update(fileName, DownloadStatus.Preparing)

                val initialNotif = buildProgressNotification(
                    fileName = fileName,
                    progressPercent = 0,
                    indeterminate = true,
                    statusText = getString(R.string.download_preparing)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_NOTIF_ID,
                        initialNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(FOREGROUND_NOTIF_ID, initialNotif)
                }

                val downloader = MultiThreadDownloader(
                    applicationContext,
                    threadCount = threads,
                    mirror = mirror
                )
                activeDownloaders[fileName] = downloader

                downloader.download(url, fileName, object : MultiThreadDownloader.Listener {
                    override fun onProgress(
                        downloadedBytes: Long,
                        totalBytes: Long,
                        speedBytesPerSec: Long,
                        mirrorHost: String
                    ) {
                        DownloadStateHolder.update(
                            fileName,
                            DownloadStatus.Running(downloadedBytes, totalBytes, speedBytesPerSec, mirrorHost)
                        )
                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val subText = if (totalBytes <= 0) getString(R.string.download_preparing) else "$percent%"
                        notificationManager.notify(
                            FOREGROUND_NOTIF_ID,
                            buildProgressNotification(fileName, percent, totalBytes <= 0, subText)
                        )
                    }

                    override fun onVerifying() {
                        DownloadStateHolder.update(fileName, DownloadStatus.Verifying)
                        notificationManager.notify(
                            FOREGROUND_NOTIF_ID,
                            buildProgressNotification(
                                fileName = fileName,
                                progressPercent = 100,
                                indeterminate = true,
                                statusText = getString(R.string.download_verifying)
                            )
                        )
                    }

                    override fun onCompleted(fileName: String, sha256: String, mirrorHost: String) {
                        activeDownloaders.remove(fileName)
                        DownloadStateHolder.update(fileName, DownloadStatus.Completed(sha256, mirrorHost))
                        showResultNotification(
                            fileName.hashCode(),
                            getString(R.string.notif_completed_title),
                            "$fileName\nSHA-256: ${sha256.take(16)}…"
                        )
                        stopIfIdle()
                    }

                    override fun onError(error: String) {
                        activeDownloaders.remove(fileName)
                        DownloadStateHolder.update(fileName, DownloadStatus.Failed(error))
                        showResultNotification(
                            fileName.hashCode(),
                            getString(R.string.notif_failed_title),
                            "$fileName: $error"
                        )
                        stopIfIdle()
                    }
                })
            }

            ACTION_CANCEL -> {
                activeDownloaders.remove(fileName)?.cancel()
                DownloadStateHolder.update(fileName, null)
                stopIfIdle()
            }
        }

        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (activeDownloaders.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "13TFirmware::DownloadWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        fileName: String,
        progressPercent: Int,
        indeterminate: Boolean,
        statusText: String
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            fileName.hashCode(),
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_FILE_NAME, fileName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_downloading_title, fileName))
            .setContentText(statusText)
            .setProgress(100, progressPercent, indeterminate)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.btn_cancel),
                    cancelIntent
                ).build()
            )
            .build()
    }

    private fun showResultNotification(id: Int, title: String, text: String) {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notif = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
