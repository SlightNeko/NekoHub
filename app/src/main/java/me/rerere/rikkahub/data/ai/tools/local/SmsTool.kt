package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
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

/**
 * Read SMS messages from the device's inbox.
 * Requires READ_SMS permission. Returns messages sorted by date (newest first).
 */
internal fun buildSmsTool(context: Context): Tool = Tool(
    name = "read_sms",
    description = """
        Read SMS messages from the device. Returns up to 'limit' messages sorted
        by date (newest first). Optionally filter by phone number/address or by
        date range. Requires READ_SMS permission; if not granted, returns an error.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of messages to return. Default 20, max 100.")
                })
                put("address_filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional phone number or contact name to filter messages.")
                })
                put("since_hours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Only return messages from the last N hours. Default no limit.")
                })
                put("include_sent", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include sent messages in addition to received. Default false (received only).")
                })
            }
        )
    },
    execute = { args ->
        if (!hasSmsPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put("message", "SMS read permission is not granted. Please enable it in the app permission settings.")
            }.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val addressFilter = params["address_filter"]?.jsonPrimitive?.contentOrNull
        val sinceHours = params["since_hours"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val includeSent = params["include_sent"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.PERSON,
        )

        val selection = StringBuilder()
        val selectionArgs = mutableListOf<String>()

        // Filter type
        if (includeSent) {
            selection.append("${Telephony.Sms.TYPE} IN (?, ?)")
            selectionArgs.add(Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            selectionArgs.add(Telephony.Sms.MESSAGE_TYPE_SENT.toString())
        } else {
            selection.append("${Telephony.Sms.TYPE} = ?")
            selectionArgs.add(Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
        }

        // Filter by address
        if (!addressFilter.isNullOrBlank()) {
            selection.append(" AND ${Telephony.Sms.ADDRESS} LIKE ?")
            selectionArgs.add("%$addressFilter%")
        }

        // Filter by time
        if (sinceHours != null && sinceHours > 0) {
            val sinceMs = System.currentTimeMillis() - sinceHours * 3600_000L
            selection.append(" AND ${Telephony.Sms.DATE} > ?")
            selectionArgs.add(sinceMs.toString())
        }

        val messages = buildJsonArray {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection.toString(),
                selectionArgs.toTypedArray(),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("address", cursor.getString(1) ?: "")
                        put("body", cursor.getString(2) ?: "")
                        put("date", cursor.getLong(3))
                        put("type", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent")
                        put("read", cursor.getInt(6) == 1)
                    })
                    count++
                }
            }
        }

        val payload = buildJsonObject {
            put("count", messages.size)
            put("messages", messages)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun hasSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
