package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
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
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Read health data from an imported Gadgetbridge database.
 *
 * NOTE: Android security prevents reading another app's database directly.
 * The user must export the Gadgetbridge database and place it at the configured
 * path. Default search path: app's files/gadgetbridge/Gadgetbridge
 *
 * Gadgetbridge is an open-source smartwatch companion app:
 * https://codeberg.org/Freeyourgadget/Gadgetbridge
 *
 * Supported data: daily steps, heart rate samples, sleep data (if available).
 */
internal fun buildGadgetbridgeTool(context: Context): Tool = Tool(
    name = "get_health_data",
    description = """
        Read health data from a Gadgetbridge database export. Get daily step
        counts, heart rate readings, and sleep data from a connected smartwatch
        via Gadgetbridge. The Gadgetbridge database must be exported and placed
        in the app's file directory first. Requires the Gadgetbridge app to be
        installed and synced with a compatible smartwatch.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("data_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Type of data to query: 'steps' (daily step counts), 'heartrate' (heart rate samples), 'sleep' (sleep sessions), 'all' (all available). Default 'all'.")
                })
                put("days", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of recent days to query. Default 7, max 30.")
                })
                put("db_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional custom path to the Gadgetbridge database file. Default auto-detects.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val dataType = params["data_type"]?.jsonPrimitive?.contentOrNull ?: "all"
        val days = params["days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 30) ?: 7
        val customPath = params["db_path"]?.jsonPrimitive?.contentOrNull

        val dbPath = customPath ?: findGadgetbridgeDb(context)
        if (dbPath == null || !File(dbPath).exists()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "DB_NOT_FOUND")
                put("message", "Gadgetbridge database not found. Please export the database from the Gadgetbridge app and place it in the app's file directory, or provide a custom db_path.")
                put("help", "In Gadgetbridge: Settings → Debug → Database export. Then copy to: ${context.filesDir}/gadgetbridge/")
            }.toString()))
        }

        val db: SQLiteDatabase
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "DB_OPEN_FAILED")
                put("message", "Failed to open database: ${e.message}")
            }.toString()))
        }

        val result = try {
            val payload = buildJsonObject {
                put("source", "Gadgetbridge")
                put("db_path", dbPath)
                put("days_queried", days)
                put("timezone", ZoneId.systemDefault().id)

                if (dataType == "steps" || dataType == "all") {
                    put("steps", Json.parseToJsonElement(querySteps(db, days).toString()))
                }
                if (dataType == "heartrate" || dataType == "all") {
                    put("heart_rate", Json.parseToJsonElement(queryHeartRate(db, days).toString()))
                }
                if (dataType == "sleep" || dataType == "all") {
                    put("sleep", Json.parseToJsonElement(querySleep(db, days).toString()))
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "QUERY_FAILED")
                put("message", "Failed to query health data: ${e.message}")
            }.toString()))
        }

        db.close()
        result
    }
)

private fun findGadgetbridgeDb(context: Context): String? {
    // Search paths in priority order
    val searchPaths = listOf(
        "${context.filesDir}/gadgetbridge/Gadgetbridge",
        "${context.filesDir}/gadgetbridge.db",
        "${context.getExternalFilesDir(null)}/gadgetbridge/Gadgetbridge",
        "${context.getExternalFilesDir(null)}/gadgetbridge.db",
    )
    return searchPaths.firstOrNull { File(it).exists() }
}

/**
 * Query daily step counts from ACTIVITY_SAMPLES table.
 */
private fun querySteps(db: SQLiteDatabase, days: Int): org.json.JSONArray {
    val cutoffSec = ZonedDateTime.now().minusDays(days.toLong()).toEpochSecond()
    val results = org.json.JSONArray()

    // Try Gadgetbridge's standard schema
    val columns = arrayOf(
        "SUM(RAW_INTENSITY) as total_steps",
        "date(TRUNC(TIMESTAMP/1000, 'unixepoch')) as day"
    )
    val tablesToTry = listOf("ACTIVITY_SAMPLE", "ACTIVITY_SAMPLES")
    for (table in tablesToTry) {
        try {
            val cursor = db.rawQuery(
                "SELECT CAST(SUM(RAW_INTENSITY) AS INTEGER) as total_steps, " +
                    "date(TRUNC(TIMESTAMP/1000, 'unixepoch'), 'unixepoch') as day " +
                    "FROM $table WHERE RAW_KIND = 1 AND TIMESTAMP/1000 > $cutoffSec " +
                    "GROUP BY day ORDER BY day DESC LIMIT $days",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val day = it.getString(1) ?: continue
                    val steps = it.getInt(0)
                    results.put(org.json.JSONObject().apply {
                        put("date", day)
                        put("steps", steps)
                    })
                }
            }
            break // success, don't try other tables
        } catch (e: Exception) {
            // Try next table
            continue
        }
    }

    return results
}

/**
 * Query heart rate samples from ACTIVITY_SAMPLES table.
 */
private fun queryHeartRate(db: SQLiteDatabase, days: Int): org.json.JSONArray {
    val cutoffMs = System.currentTimeMillis() - days * 86_400_000L

    return try {
        val results = org.json.JSONArray()
        val cursor = db.rawQuery(
            "SELECT CAST(RAW_INTENSITY AS INTEGER) as bpm, CAST(TIMESTAMP/1000 AS INTEGER) as ts " +
                "FROM ACTIVITY_SAMPLE WHERE RAW_KIND = 2 AND TIMESTAMP > $cutoffMs " +
                "ORDER BY TIMESTAMP DESC LIMIT 200",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val bpm = it.getInt(0)
                if (bpm in 30..250) {
                    results.put(org.json.JSONObject().apply {
                        put("bpm", bpm)
                        put("time", Instant.ofEpochSecond(it.getLong(1)).toString())
                    })
                }
            }
        }
        results
    } catch (e: Exception) {
        org.json.JSONArray()
    }
}

/**
 * Query sleep data. Gadgetbridge stores sleep in different ways depending on
 * the device. Try common patterns.
 */
private fun querySleep(db: SQLiteDatabase, days: Int): org.json.JSONArray {
    val cutoffMs = System.currentTimeMillis() - days * 86_400_000L
    val results = org.json.JSONArray()

    try {
        // Try SLEEP_SESSIONS table (Mi Band style)
        val cursor = db.rawQuery(
            "SELECT CAST(TIMESTAMP_FROM/1000 AS INTEGER) as start_ts, " +
                "CAST(TIMESTAMP_TO/1000 AS INTEGER) as end_ts, " +
                "SLEEP_TYPE as type " +
                "FROM SLEEP_SESSIONS WHERE TIMESTAMP_FROM > $cutoffMs " +
                "ORDER BY TIMESTAMP_FROM DESC LIMIT 30",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val startSec = it.getLong(0)
                val endSec = it.getLong(1)
                val durationMin = (endSec - startSec) / 60
                val typeCode = it.getInt(2)
                val typeStr = when (typeCode) {
                    0 -> "light"
                    1 -> "deep"
                    2 -> "rem"
                    else -> "unknown"
                }
                results.put(org.json.JSONObject().apply {
                    put("start", Instant.ofEpochSecond(startSec).toString())
                    put("end", Instant.ofEpochSecond(endSec).toString())
                    put("duration_minutes", durationMin)
                    put("type", typeStr)
                })
            }
        }
    } catch (e: Exception) {
        // Sleep data not available with this structure
    }

    return results
}
