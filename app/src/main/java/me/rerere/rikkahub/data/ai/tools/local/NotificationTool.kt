package me.rerere.rikkahub.data.ai.tools.local

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory store for recent notifications captured by [NotificationCaptureService].
 * Keyed by a unique key string (package+id+tag), capped to avoid memory bloat.
 */
object NotificationCache {
    private const val MAX_ENTRIES = 500
    private val store = ConcurrentHashMap<String, CachedNotification>()

    @Synchronized
    fun put(sbn: StatusBarNotification) {
        val key = "${sbn.packageName}/${sbn.id}/${sbn.tag ?: ""}"
        store[key] = CachedNotification(
            packageName = sbn.packageName,
            appName = "",
            title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: "",
            text = sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: "",
            bigText = sbn.notification.extras.getString(Notification.EXTRA_BIG_TEXT) ?: "",
            summaryText = sbn.notification.extras.getString(Notification.EXTRA_SUMMARY_TEXT) ?: "",
            category = sbn.notification.category ?: "",
            channelId = sbn.notification.channelId ?: "",
            postTime = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            groupKey = sbn.groupKey ?: "",
        )
        // Evict oldest if over capacity
        if (store.size > MAX_ENTRIES) {
            val oldest = store.entries.minByOrNull { it.value.postTime }
            oldest?.let { store.remove(it.key) }
        }
    }

    @Synchronized
    fun remove(sbn: StatusBarNotification) {
        val key = "${sbn.packageName}/${sbn.id}/${sbn.tag ?: ""}"
        store.remove(key)
    }

    fun getAll(): List<CachedNotification> = store.values.toList()

    fun clear() = store.clear()
}

data class CachedNotification(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val bigText: String,
    val summaryText: String,
    val category: String,
    val channelId: String,
    val postTime: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val groupKey: String,
)

/**
 * NotificationListenerService that captures all status-bar notifications
 * and stores them in [NotificationCache] for the AI to read.
 *
 * IMPORTANT: The user MUST manually enable this notification listener in
 * Settings → Apps → Special app access → Notification access.
 */
class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationCache.put(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationCache.remove(sbn)
    }
}

/**
 * Build the notification tool that lets the AI read recent notifications.
 *
 * Requires the NotificationListenerService to be enabled by the user in system
 * settings. If not enabled, returns an error with instructions.
 */
internal fun buildNotificationTool(context: Context, eventBus: me.rerere.rikkahub.data.event.AppEventBus): Tool = Tool(
    name = "read_notifications",
    description = """
        Read recent notification messages from the device's status bar.
        Returns up to 'limit' notifications sorted by post time (newest first).
        Optionally filter by package name. Requires notification listener access
        to be enabled in system settings; if not, returns an error with setup
        instructions.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of notifications to return. Default 20.")
                })
                put("package_filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional package name filter (e.g. 'com.tencent.mm' for WeChat). Only notifications from this app will be returned.")
                })
                put("include_ongoing", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include ongoing (non-dismissible) notifications like music players. Default false.")
                })
            }
        )
    },
    execute = { args ->
        if (!isNotificationListenerEnabled(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NOTIFICATION_LISTENER_DISABLED")
                put("message", "Notification access is not enabled. Please go to Settings → Apps → Special app access → Notification access and enable it for this app.")
            }.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val packageFilter = params["package_filter"]?.jsonPrimitive?.contentOrNull
        val includeOngoing = params["include_ongoing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val allNotifications = NotificationCache.getAll()
        if (allNotifications.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("count", 0)
                put("notifications", buildJsonArray { })
                put("note", "No notifications captured yet. The notification listener may have just been enabled — new notifications will appear as they arrive.")
            }.toString()))
        }

        val pm: PackageManager = context.packageManager
        val filtered = allNotifications
            .asSequence()
            .filter { includeOngoing || !it.isOngoing }
            .filter { packageFilter == null || it.packageName == packageFilter }
            .sortedByDescending { it.postTime }
            .take(limit)
            .map { notif ->
                buildJsonObject {
                    put("app", resolveAppName(pm, notif.packageName))
                    put("package", notif.packageName)
                    put("title", notif.title)
                    put("text", notif.text)
                    if (notif.bigText.isNotEmpty()) put("big_text", notif.bigText)
                    if (notif.summaryText.isNotEmpty()) put("summary", notif.summaryText)
                    if (notif.category.isNotEmpty()) put("category", notif.category)
                    put("post_time", notif.postTime)
                    put("ongoing", notif.isOngoing)
                    put("clearable", notif.isClearable)
                }
            }
            .toList()

        val payload = buildJsonObject {
            put("count", filtered.size)
            put("total_cached", allNotifications.size)
            put("notifications", buildJsonArray { filtered.forEach { add(it) } })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Check if the notification listener service is enabled for this app.
 */
private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = android.provider.Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    return flat?.contains(context.packageName) == true
}

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
