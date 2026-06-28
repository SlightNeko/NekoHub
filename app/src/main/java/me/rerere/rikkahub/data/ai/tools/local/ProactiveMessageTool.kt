package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Manages the proactive messaging scheduler. The AI can enable/disable/configure
 * a random-interval timer that triggers the app to send a proactive message.
 *
 * NOTE: The actual message sending is handled by the app's notification/chat logic
 * when the broadcast is received. This tool only controls the timer.
 */
object ProactiveMessageScheduler {
    private const val PREFS_NAME = "proactive_msg"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MIN_MINUTES = "min_minutes"
    private const val KEY_MAX_MINUTES = "max_minutes"
    private const val KEY_NEXT_TRIGGER_MS = "next_trigger_ms"
    private const val DEFAULT_MIN = 60   // 1 hour
    private const val DEFAULT_MAX = 180  // 3 hours

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ENABLED, false)

    fun getConfig(context: Context): Pair<Int, Int> {
        val prefs = getPrefs(context)
        return Pair(
            prefs.getInt(KEY_MIN_MINUTES, DEFAULT_MIN),
            prefs.getInt(KEY_MAX_MINUTES, DEFAULT_MAX),
        )
    }

    fun getNextTrigger(context: Context): Long =
        getPrefs(context).getLong(KEY_NEXT_TRIGGER_MS, 0L)

    fun schedule(context: Context) {
        val (min, max) = getConfig(context)
        val intervalMs = Random.nextLong(min * 60_000L, (max + 1) * 60_000L)
        val triggerMs = System.currentTimeMillis() + intervalMs

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ProactiveMessageReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        getPrefs(context).edit().putLong(KEY_NEXT_TRIGGER_MS, triggerMs).apply()
    }

    fun enable(context: Context, minMinutes: Int, maxMinutes: Int) {
        getPrefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_MIN_MINUTES, minMinutes.coerceAtLeast(5))
            .putInt(KEY_MAX_MINUTES, maxMinutes.coerceAtLeast(minMinutes + 1))
            .apply()
        schedule(context)
    }

    fun disable(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        // Cancel pending
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ProactiveMessageReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
        getPrefs(context).edit().putLong(KEY_NEXT_TRIGGER_MS, 0L).apply()
    }
}

/**
 * Receiver for proactive message triggers. Re-schedules the next trigger after firing.
 */
class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ProactiveMessageScheduler.isEnabled(context)) return

        // Store the trigger event for the AI to detect
        ProactiveMessageEventCache.add(
            System.currentTimeMillis(),
            "Proactive message trigger — AI should send a message now."
        )

        // Show a notification to alert the user (AI will also see this via notification tool)
        showProactiveNotification(context)

        // Schedule the next trigger
        ProactiveMessageScheduler.schedule(context)
    }

    private fun showProactiveNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "proactive_messages"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "Proactive Messages", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val n = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
            .setContentTitle("Time to check in 💬")
            .setContentText("Your AI companion has something to say.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        nm.notify(9001, n)
    }
}

/**
 * In-memory event cache for proactive message triggers.
 */
object ProactiveMessageEventCache {
    private data class Event(val timestampMs: Long, val note: String)

    private val events = java.util.concurrent.ConcurrentLinkedQueue<Event>()
    private const val MAX = 50

    fun add(timestampMs: Long, note: String) {
        events.add(Event(timestampMs, note))
        while (events.size > MAX) events.poll()
    }

    fun recent(limit: Int): List<Event> =
        events.toList().sortedByDescending { it.timestampMs }.take(limit)
}

/**
 * Tool for controlling the proactive messaging scheduler.
 */
internal fun buildProactiveMessageTool(context: Context): Tool = Tool(
    name = "proactive_messaging",
    description = """
        Control the proactive messaging scheduler. The AI can send messages
        proactively at random intervals between a min and max delay. Use
        'status' to view current config, 'enable' to turn it on, 'disable'
        to turn it off. When enabled, the app will randomly trigger between
        min_minutes and max_minutes, at which point the AI should send a
        proactive message.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: 'status' (view config, default), 'enable' (turn on), 'disable' (turn off).")
                })
                put("min_minutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minimum interval in minutes between proactive messages (min 5). Only needed for 'enable'.")
                })
                put("max_minutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum interval in minutes (must be > min_minutes). Only needed for 'enable'.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "status"

        when (action) {
            "enable" -> {
                val min = params["min_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60
                val max = params["max_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 180
                if (min < 5) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "INTERVAL_TOO_SMALL")
                        put("message", "min_minutes must be at least 5.")
                    }.toString()))
                }
                if (max <= min) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "INVALID_RANGE")
                        put("message", "max_minutes must be greater than min_minutes.")
                    }.toString()))
                }
                ProactiveMessageScheduler.enable(context, min, max)
                val nextMs = ProactiveMessageScheduler.getNextTrigger(context)
                val nextTime = java.time.Instant.ofEpochMilli(nextMs).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("action", "enabled")
                    put("min_minutes", min)
                    put("max_minutes", max)
                    put("next_trigger_ms", nextMs)
                    put("next_trigger_time", nextTime)
                }.toString()))
            }

            "disable" -> {
                ProactiveMessageScheduler.disable(context)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("action", "disabled")
                }.toString()))
            }

            else -> { // "status"
                val enabled = ProactiveMessageScheduler.isEnabled(context)
                val (min, max) = ProactiveMessageScheduler.getConfig(context)
                val nextMs = ProactiveMessageScheduler.getNextTrigger(context)
                val recentTriggers = ProactiveMessageEventCache.recent(5)

                val payload = buildJsonObject {
                    put("enabled", enabled)
                    put("min_minutes", min)
                    put("max_minutes", max)
                    if (enabled && nextMs > 0) {
                        val nextTime = java.time.Instant.ofEpochMilli(nextMs)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        put("next_trigger_time", nextTime)
                    }
                    put("recent_triggers", buildJsonObject {
                        // kotlinx limitation — build inline
                    })
                }
                // Actually let me just serialize with org.json for simplicity here
                val result = org.json.JSONObject().apply {
                    put("enabled", enabled)
                    put("min_minutes", min)
                    put("max_minutes", max)
                    if (enabled && nextMs > 0) {
                        put("next_trigger_time", java.time.Instant.ofEpochMilli(nextMs)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    }
                    put("recent_triggers", org.json.JSONArray().apply {
                        recentTriggers.forEach { t ->
                            put(org.json.JSONObject().apply {
                                put("time", java.time.Instant.ofEpochMilli(t.timestampMs).toString())
                                put("note", t.note)
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        }
    }
)
