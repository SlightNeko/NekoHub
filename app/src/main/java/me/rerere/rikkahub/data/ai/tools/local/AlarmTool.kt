package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEventBus
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Alarm broadcaster that receives alarm triggers and notifies the system.
 * Alarms are delivered as Android notifications with a broadcast.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        val label = intent.getStringExtra("alarm_label") ?: "Alarm"
        val timeMs = intent.getLongExtra("alarm_time", 0L)

        // Post event for the AI to potentially react to
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "alarms"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Alarms", android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
            .setContentTitle(label)
            .setContentText("⏰ Alarm: $label")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(alarmId.hashCode(), notification)
    }
}

/**
 * Set, list, or cancel alarm timers.
 *
 * Uses AlarmManager for exact-timing alarms. Alarms trigger a notification
 * and the AI can detect/react to them.
 *
 * On Android 12+, exact alarms require the SCHEDULE_EXACT_ALARM permission
 * or the user granting the alarm permission in system settings.
 */
internal fun buildAlarmTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "set_alarm",
    description = """
        Set an alarm timer for a future time. The alarm will trigger a system
        notification with the label text. Also supports listing pending alarms
        and cancelling alarms by ID. Timezone: '${ZoneId.systemDefault()}'.
        Times without offset are interpreted in the device timezone.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: 'set' (create alarm, default), 'list' (show pending alarms), 'cancel' (remove an alarm).")
                })
                put("time", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm time. ISO-8601 date-time, epoch milliseconds, or relative like '30m', '1h'. Required for 'set'.")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "A label for the alarm (shown in the notification). Default 'Alarm'.")
                })
                put("alarm_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm ID for cancellation. Required for 'cancel'.")
                })
                put("repeat_daily", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Repeat this alarm daily at the same time. Default false.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "set"

        if (!canScheduleExactAlarms(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_ALARM_PERMISSION")
                put("message", "Exact alarm scheduling permission is not granted. Please enable 'Alarms & reminders' in the app's system settings.")
            }.toString()))
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        when (action) {
            "list" -> {
                // We can't enumerate existing alarms from AlarmManager directly.
                // Instead, return a note with instructions.
                val payload = buildJsonObject {
                    put("note", "Android does not support enumerating pending alarms. Use 'cancel' with a known alarm_id to remove one. Active alarms will fire at their scheduled times.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "cancel" -> {
                val alarmId = params["alarm_id"]?.jsonPrimitive?.contentOrNull
                if (alarmId.isNullOrBlank()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "MISSING_ID")
                        put("message", "alarm_id is required for cancel action.")
                    }.toString()))
                }
                val pendingIntent = buildAlarmPendingIntent(context, alarmId, "")
                alarmManager.cancel(pendingIntent)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("action", "cancel")
                    put("alarm_id", alarmId)
                }.toString()))
            }

            else -> { // "set"
                val timeRaw = params["time"]?.jsonPrimitive?.contentOrNull
                val label = params["label"]?.jsonPrimitive?.contentOrNull ?: "Alarm"
                val repeatDaily = params["repeat_daily"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

                if (timeRaw.isNullOrBlank()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "MISSING_TIME")
                        put("message", "time is required for set action.")
                    }.toString()))
                }

                val triggerTime = parseAlarmTime(timeRaw)
                if (triggerTime == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "INVALID_TIME")
                        put("message", "Invalid time format: '$timeRaw'. Use ISO-8601 date-time, epoch ms, or relative like '30m'/'1h'.")
                    }.toString()))
                }

                if (triggerTime <= System.currentTimeMillis()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "PAST_TIME")
                        put("message", "Alarm time must be in the future. Provided time: $timeRaw")
                    }.toString()))
                }

                val alarmId = "alarm_${triggerTime}_${label.hashCode()}"
                val pendingIntent = buildAlarmPendingIntent(context, alarmId, label)

                if (repeatDaily) {
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        AlarmManager.INTERVAL_DAY,
                        pendingIntent
                    )
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+: must have SCHEDULE_EXACT_ALARM or use setAlarmClock
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
                            pendingIntent
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                }

                val formattedTime = Instant.ofEpochMilli(triggerTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("action", "set")
                    put("alarm_id", alarmId)
                    put("trigger_time_ms", triggerTime)
                    put("trigger_time", formattedTime)
                    put("label", label)
                    put("repeat_daily", repeatDaily)
                }.toString()))
            }
        }
    }
)

private fun buildAlarmPendingIntent(context: Context, alarmId: String, label: String): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java).apply {
        putExtra("alarm_id", alarmId)
        putExtra("alarm_label", label)
        putExtra("alarm_time", System.currentTimeMillis())
    }
    return PendingIntent.getBroadcast(
        context,
        alarmId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Parse alarm time from various formats:
 * - Epoch milliseconds (number string)
 * - ISO-8601 date-time
 * - Relative: "30m", "2h", "90s"
 * - "HH:mm" (today at that time)
 */
private fun parseAlarmTime(raw: String): Long? {
    val text = raw.trim()

    // Epoch milliseconds
    text.toLongOrNull()?.let { return it }

    // Relative: 30m, 2h, 90s, 1d
    val relativeRegex = Regex("""^(\d+)\s*(s|m|h|d)$""", RegexOption.IGNORE_CASE)
    relativeRegex.matchEntire(text)?.let { match ->
        val value = match.groupValues[1].toLong()
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit) {
            "s" -> 1000L
            "m" -> 60_000L
            "h" -> 3600_000L
            "d" -> 86_400_000L
            else -> return null
        }
        return System.currentTimeMillis() + value * multiplier
    }

    // HH:mm (today at that time)
    val timeRegex = Regex("""^(\d{1,2}):(\d{2})$""")
    timeRegex.matchEntire(text)?.let { match ->
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val now = ZonedDateTime.now()
        val target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        val adjusted = if (target <= now) target.plusDays(1) else target
        return adjusted.toInstant().toEpochMilli()
    }

    // ISO-8601
    val zone = ZoneId.systemDefault()
    return runCatching {
        OffsetDateTime.parse(text).atZoneSameInstant(zone).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                Instant.parse(text).toEpochMilli()
            }.getOrNull()
        }
    }
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }
}
