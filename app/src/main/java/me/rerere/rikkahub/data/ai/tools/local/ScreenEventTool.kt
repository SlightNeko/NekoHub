package me.rerere.rikkahub.data.ai.tools.local

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
import java.time.Instant
import java.time.ZoneId

/**
 * Tool for the AI to read screen on/off/unlock/boot events.
 * Events are collected by [ScreenEventReceiver] which must be registered
 * in the Application class for screen events and in the manifest for boot.
 */
internal fun buildScreenEventTool(): Tool = Tool(
    name = "get_screen_events",
    description = """
        Get recent screen on/off, unlock, and boot events. Returns a timeline
        of when the device screen was turned on, off, when the user unlocked it,
        and when the device booted. Useful for understanding the user's device
        usage patterns and availability.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 30, max 100.")
                })
                put("since_hours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Only return events from the last N hours. Default 24.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 30
        val sinceHours = params["since_hours"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 24
        val sinceMs = System.currentTimeMillis() - sinceHours * 3600_000L

        val events = ScreenEventCache.query(sinceMs, limit)

        val payload = buildJsonObject {
            put("timezone", ZoneId.systemDefault().id)
            put("total_cached", ScreenEventCache.getAll().size)
            put("since_hours", sinceHours)
            put("count", events.size)
            put("events", buildJsonArray {
                events.forEach { e ->
                    add(buildJsonObject {
                        put("type", e.type)
                        put("time", Instant.ofEpochMilli(e.timestampMs).toString())
                        put("label", e.label)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
