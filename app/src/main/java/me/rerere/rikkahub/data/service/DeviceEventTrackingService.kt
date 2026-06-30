package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceEventTracking"

/**
 * Foreground service that tracks screen on/off events and uploads them to Supabase.
 *
 * Dynamically registers a broadcast receiver for [Intent.ACTION_SCREEN_ON] and
 * [Intent.ACTION_SCREEN_OFF] because these actions cannot be registered in the
 * manifest on Android 7+ (Nougat).
 */
class DeviceEventTrackingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 0x4e24  // 20004
        const val CHANNEL_ID = "device_event_tracking"

        private const val PREFS_NAME = "rikkahub_integrations"
        private const val KEY_URL = "supabase_url"
        private const val KEY_KEY = "supabase_key"
        private const val KEY_ENABLED = "supabase_enabled"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Screen event receiver ─────────────────────────────────────────

    private fun registerScreenReceiver() {
        val receiver = ScreenEventReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Screen event receiver registered")
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Screen event receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
        }
        screenReceiver = null
    }

    /**
     * Inner receiver that fires on screen on/off and launches a coroutine
     * to upload the event to Supabase.
     */
    private inner class ScreenEventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val eventType = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                else -> return
            }
            Log.d(TAG, "Screen event: $eventType")
            serviceScope.launch {
                insertDeviceEvent(context, eventType)
            }
        }
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
            .setContentTitle("设备状态同步运行中")
            .setContentText("正在追踪设备事件…")
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ── Supabase upload helper ────────────────────────────────────────

    /**
     * Builds a [SupabaseSyncData] payload with the given [eventType] and
     * uploads it to the configured Supabase table via [SupabaseService].
     *
     * Silently returns if Supabase is not configured or disabled.
     */
    private suspend fun insertDeviceEvent(context: Context, eventType: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null) ?: return
        val key = prefs.getString(KEY_KEY, null) ?: return
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val data = SupabaseSyncData(
            timestamp = timestamp,
            deviceEvent = eventType
        )
        val service = SupabaseService(url, key, "device_sync")
        service.insertRow(data)
        Log.d(TAG, "Device event uploaded: $eventType")
    }
}
