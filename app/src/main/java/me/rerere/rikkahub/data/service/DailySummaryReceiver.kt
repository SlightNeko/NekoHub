package me.rerere.rikkahub.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "DailySummaryReceiver"

/**
 * BroadcastReceiver that handles both BOOT_COMPLETED (to re-schedule) and
 * [DailySummaryService.ACTION_DAILY_CRON] (to start the trigger service).
 */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed — re-scheduling daily cron")
                DailySummaryService.scheduleNext(context)
            }

            DailySummaryService.ACTION_DAILY_CRON -> {
                Log.d(TAG, "Daily cron fired — starting trigger service")
                val serviceIntent = Intent(context, DailySummaryTriggerService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            else -> {
                Log.w(TAG, "Unexpected action: ${intent.action}")
            }
        }
    }
}
