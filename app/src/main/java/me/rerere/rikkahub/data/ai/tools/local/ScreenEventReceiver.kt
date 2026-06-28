package me.rerere.rikkahub.data.ai.tools.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory store for screen on/off and boot events.
 * Capped to the most recent 200 events to avoid memory bloat.
 */
object ScreenEventCache {
    private const val MAX_EVENTS = 200
    private val events = ConcurrentLinkedQueue<ScreenEvent>()

    fun add(event: ScreenEvent) {
        events.add(event)
        while (events.size > MAX_EVENTS) events.poll()
    }

    fun getAll(): List<ScreenEvent> = events.toList()

    fun query(sinceMs: Long, limit: Int): List<ScreenEvent> =
        events.filter { it.timestampMs >= sinceMs }.take(limit)
}

data class ScreenEvent(
    val type: String,       // screen_on, screen_off, user_present, boot_completed
    val timestampMs: Long,
    val label: String,
)

/**
 * Receives screen on/off, user present, and boot completed broadcasts.
 * Stores them in [ScreenEventCache] for the AI to read via the screen_events tool.
 *
 * Screen on/off must be registered dynamically (not in manifest) on Android 7+.
 * Boot completed is registered in AndroidManifest.
 */
class ScreenEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                ScreenEventCache.add(ScreenEvent("screen_on", now, "Screen turned on"))
            }
            Intent.ACTION_SCREEN_OFF -> {
                ScreenEventCache.add(ScreenEvent("screen_off", now, "Screen turned off"))
            }
            Intent.ACTION_USER_PRESENT -> {
                ScreenEventCache.add(ScreenEvent("user_present", now, "Device unlocked"))
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                ScreenEventCache.add(ScreenEvent("boot_completed", now, "Device booted"))
            }
        }
    }

    companion object {
        /**
         * Register screen on/off receiver dynamically. Call from Application.onCreate().
         */
        fun register(context: Context): ScreenEventReceiver {
            val receiver = ScreenEventReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(receiver, filter)
            return receiver
        }

        fun unregister(context: Context, receiver: ScreenEventReceiver) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
