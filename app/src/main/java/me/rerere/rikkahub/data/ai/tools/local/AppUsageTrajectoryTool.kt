package me.rerere.rikkahub.data.ai.tools.local

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Build the app usage trajectory tool that returns a timeline of app
 * open/close/pause events, showing the user's app-switching behavior.
 *
 * Unlike ScreenTime which aggregates total time per app, this tool returns
 * individual events so the AI can understand usage patterns: what apps were
 * used in what order, how long each session lasted, etc.
 *
 * Requires the 'Usage access' special permission.
 */
internal fun buildAppUsageTrajectoryTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_app_usage_trajectory",
    description = """
        Get the user's app usage timeline (trajectory) — a sequential log of app
        foreground/background events within a time range. Returns individual
        events with timestamps, app names, and estimated session durations so
        the AI can understand the user's app-switching patterns.
        Specify a custom interval with 'begin'/'end', or use a preset 'range'
        (today/week). The device timezone is '${ZoneId.systemDefault()}' (UTC
        offset ${OffsetDateTime.now().offset}); times without an explicit offset
        are interpreted in this timezone. Requires 'Usage access' permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put("description", "Start time (inclusive). ISO-8601 date, date-time, or epoch ms. When provided, 'range' is ignored.")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "End time (exclusive), same formats as 'begin'. Defaults to now.")
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("today"); add("week") })
                    put("description", "Convenience preset when 'begin' is omitted: today or week. Default today.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 50.")
                })
            }
        )
    },
    execute = { args ->
        if (!context.hasUsageStatsPermission()) {
            eventBus.emit(AppEvent.OpenUsageAccessSettings)
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put("message", "Usage access permission is not granted. The system settings page has been opened; please enable it and try again.")
            }.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val now = ZonedDateTime.now()
        val zone = now.zone

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { parseUsageTime(it, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseUsageTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.minusDays(7)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
        } catch (e: Exception) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format.")
            }.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val launcherPackages = resolveLauncherPackages(pm)

        val events = queryUsageEvents(usageStatsManager, startMs, endMs, launcherPackages, limit, pm)

        val payload = org.json.JSONObject().apply {
            put("range", org.json.JSONObject().apply {
                put("start", startTime.withNano(0).toString())
                put("end", endTime.withNano(0).toString())
            })
            put("event_count", events.size)
            put("events", org.json.JSONArray().apply {
                events.forEach { put(it) }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Query usage events and convert to a timeline of app foreground/background transitions
 * with estimated session durations.
 */
private fun queryUsageEvents(
    usageStatsManager: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
    limit: Int,
    pm: PackageManager,
): List<org.json.JSONObject> {
    val events = usageStatsManager.queryEvents(startMs, endMs)
    val event = UsageEvents.Event()
    val timeline = mutableListOf<org.json.JSONObject>()

    // Track current foreground app and its start time for duration calculation
    var currentPkg: String? = null
    var currentStartMs = 0L

    while (events.hasNextEvent() && timeline.size < limit) {
        events.getNextEvent(event)

        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (event.packageName != currentPkg) {
                    // Close previous app session
                    if (currentPkg != null && currentPkg !in excludedPackages) {
                        timeline.add(buildEventJson(
                            type = "session_end",
                            packageName = currentPkg,
                            appName = resolveAppName(pm, currentPkg),
                            timestampMs = event.timeStamp,
                            durationMs = event.timeStamp - currentStartMs,
                        ))
                    }
                    // Start new app session
                    if (event.packageName !in excludedPackages) {
                        timeline.add(buildEventJson(
                            type = "session_start",
                            packageName = event.packageName,
                            appName = resolveAppName(pm, event.packageName),
                            timestampMs = event.timeStamp,
                            durationMs = null,
                        ))
                    }
                    currentPkg = event.packageName
                    currentStartMs = event.timeStamp
                }
            }

            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                if (event.packageName == currentPkg && event.packageName !in excludedPackages) {
                    timeline.add(buildEventJson(
                        type = "session_end",
                        packageName = event.packageName,
                        appName = resolveAppName(pm, event.packageName),
                        timestampMs = event.timeStamp,
                        durationMs = event.timeStamp - currentStartMs,
                    ))
                }
                currentPkg = null
                currentStartMs = 0L
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                // Screen off — end current session
                if (currentPkg != null && currentPkg !in excludedPackages) {
                    timeline.add(buildEventJson(
                        type = "screen_off",
                        packageName = currentPkg,
                        appName = resolveAppName(pm, currentPkg),
                        timestampMs = event.timeStamp,
                        durationMs = event.timeStamp - currentStartMs,
                    ))
                }
                currentPkg = null
                currentStartMs = 0L
            }
        }
    }

    // Close any still-open session at end of range
    if (currentPkg != null && currentPkg !in excludedPackages && timeline.size < limit) {
        timeline.add(buildEventJson(
            type = "session_end",
            packageName = currentPkg,
            appName = resolveAppName(pm, currentPkg),
            timestampMs = endMs,
            durationMs = endMs - currentStartMs,
        ))
    }

    return timeline
}

private fun buildEventJson(
    type: String,
    packageName: String,
    appName: String,
    timestampMs: Long,
    durationMs: Long?,
): org.json.JSONObject = org.json.JSONObject().apply {
    put("type", type)
    put("app", appName)
    put("package", packageName)
    put("time", Instant.ofEpochMilli(timestampMs).toString())
    put("timestamp_ms", timestampMs)
    if (durationMs != null) {
        put("duration_seconds", durationMs / 1000.0)
    }
}

private fun resolveLauncherPackages(pm: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

private fun parseUsageTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'")
}
