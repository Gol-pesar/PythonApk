package com.example.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RuntimeServiceController {

    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    internal fun markActive(active: Boolean) {
        _isServiceActive.value = active
    }

    internal fun notifyStopRequested() {
        onStopRequested?.invoke()
    }

    fun start(context: Context, scriptName: String) {
        val intent = Intent(context.applicationContext, PythonRuntimeService::class.java).apply {
            action = PythonRuntimeService.ACTION_START
            putExtra(PythonRuntimeService.EXTRA_SCRIPT_NAME, scriptName)
        }
        try {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } catch (_: Exception) {
            // If Android disallows background FGS start in an edge case, script still runs in-process.
        }
    }

    fun stop(context: Context) {
        try {
            context.applicationContext.stopService(
                Intent(context.applicationContext, PythonRuntimeService::class.java)
            )
        } catch (_: Exception) {
        }
        _isServiceActive.value = false
    }
}

class PythonRuntimeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FROM_NOTIFICATION -> {
                RuntimeServiceController.notifyStopRequested()
                releaseLocks()
                RuntimeServiceController.markActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                val scriptName = intent?.getStringExtra(EXTRA_SCRIPT_NAME) ?: "main.py"
                val notification = buildNotification(scriptName)
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
                acquireWakeLock()
                RuntimeServiceController.markActive(true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        RuntimeServiceController.markActive(false)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PyLaunch::CPythonBackgroundRuntime"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Bounded 12-hour lock released immediately when script stops or completes.
                wakeLock?.acquire(12L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(scriptName: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PythonRuntimeService::class.java).apply {
            action = ACTION_STOP_FROM_NOTIFICATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_python)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_text, scriptName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(
                R.drawable.ic_stat_python,
                getString(R.string.action_stop),
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pylaunch_cpython_runtime"
        const val NOTIFICATION_ID = 3110
        const val ACTION_START = "com.example.runtime.ACTION_START"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.example.runtime.ACTION_STOP_FROM_NOTIFICATION"
        const val EXTRA_SCRIPT_NAME = "extra_script_name"
    }
}
