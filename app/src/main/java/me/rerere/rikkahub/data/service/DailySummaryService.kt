package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

private const val TAG = "DailySummaryService"

/**
 * Schedules and cancels the daily cron alarm via [AlarmManager].
 *
 * The alarm fires [ACTION_DAILY_CRON] at the configured hour/minute each day.
 * After each trigger the service must be called again to re-schedule the next one.
 */
object DailySummaryService {
    const val ACTION_DAILY_CRON = "me.rerere.rikkahub.DAILY_CRON"
    const val PREFS_NAME = "daily_cron_prefs"
    const val DEFAULT_HOUR = 3
    const val DEFAULT_MINUTE = 0
    const val REQUEST_CODE = 10003

    private const val KEY_NEXT_TRIGGER = "next_trigger_time"

    fun scheduleNext(context: Context, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel any existing alarm first
        cancel(context)

        // Calculate the next trigger time
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the time has already passed today, schedule for tomorrow
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent(context, DailySummaryReceiver::class.java).apply {
            action = ACTION_DAILY_CRON
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

        // Persist the next trigger time for debugging / UI
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_NEXT_TRIGGER, calendar.timeInMillis).apply()

        Log.d(TAG, "Daily cron scheduled for ${calendar.time}")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailySummaryReceiver::class.java).apply {
            action = ACTION_DAILY_CRON
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Daily cron cancelled")
        }

        // Clear stored trigger time
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_NEXT_TRIGGER).apply()
    }
}
