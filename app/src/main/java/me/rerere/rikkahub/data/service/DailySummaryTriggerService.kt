package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DailySummaryTrigger"

/**
 * Foreground service that runs the daily cron task.
 *
 * Shows a notification while executing, runs a coroutine to perform the
 * daily summary logic, then re-schedules the next alarm and stops itself.
 */
class DailySummaryTriggerService : Service() {

    companion object {
        const val NOTIFICATION_ID = 20003
        const val CHANNEL_ID = "daily_summary"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        serviceScope.launch {
            runDailyTask()
            // Re-schedule for tomorrow
            DailySummaryService.scheduleNext(this@DailySummaryTriggerService)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Foreground notification ───────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setContentTitle("定时任务")
            .setContentText("正在执行定时任务…")
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ── Daily task ────────────────────────────────────────────────────

    private suspend fun runDailyTask() {
        try {
            val now = Date()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            val calendar = java.util.Calendar.getInstance().apply { time = now }

            val eventJson = buildString {
                append("{")
                append("\"timestamp\": \"${timestampFormat.format(now)}\", ")
                append("\"date\": \"${dateFormat.format(now)}\", ")
                append("\"hour\": ${calendar.get(java.util.Calendar.HOUR_OF_DAY)}, ")
                append("\"minute\": ${calendar.get(java.util.Calendar.MINUTE)}")
                append("}")
            }

            Log.i(TAG, "Daily cron task executed — event: $eventJson")

            // TODO: Dispatch to plugins registered for "daily_cron" hook
            // when the plugin system is ready:
            //   PluginManager.dispatch("daily_cron", eventJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error during daily cron task", e)
        }
    }
}
