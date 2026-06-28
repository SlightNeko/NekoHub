package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Read the device's battery level, charging status, and health info.
 * No special permissions required — uses sticky broadcast intent.
 */
internal fun buildBatteryTool(context: Context): Tool = Tool(
    name = "get_battery_info",
    description = """
        Get the device's current battery information: charge level (percentage),
        charging status (charging/discharging/full), power source (AC/USB/wireless),
        battery health, temperature (Celsius), voltage, and estimated remaining time
        if available.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (batteryStatus == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "UNAVAILABLE")
                put("message", "Could not read battery information.")
            }.toString()))
        }

        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (scale > 0) (level * 100f / scale) else -1f

        val status = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

        val plugged = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unplugged"
        }

        val health = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

        val payload = buildJsonObject {
            put("level_percent", String.format("%.0f", percent))
            put("level_raw", level)
            put("scale", scale)
            put("status", status)
            put("plugged", plugged)
            put("health", health)
            if (temp > 0) put("temperature_c", String.format("%.1f", temp / 10f))
            if (voltage > 0) put("voltage_mv", voltage)
            if (!technology.isNullOrBlank()) put("technology", technology)
            if (status == "charging" || status == "full") {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (bm != null) {
                    val chargeTimeRemaining = bm.computeChargeTimeRemaining()
                    if (chargeTimeRemaining > 0) {
                        put("charge_time_remaining_seconds", chargeTimeRemaining / 1000L)
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
