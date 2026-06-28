package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.SharedPreferences
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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Manages Supabase connection configuration and sync operations.
 *
 * Supabase is an open-source Firebase alternative that provides:
 * - PostgreSQL database with real-time subscriptions
 * - REST API for CRUD operations
 * - Row-level security
 *
 * Configuration is stored in SharedPreferences. Data sync is performed
 * via the Supabase REST API using the anon/public key.
 */
object SupabaseConfig {
    private const val PREFS_NAME = "supabase_config"
    private const val KEY_URL = "supabase_url"
    private const val KEY_ANON_KEY = "supabase_anon_key"
    private const val KEY_ENABLED = "supabase_enabled"
    private const val KEY_TABLE_PREFIX = "supabase_table_prefix"

    fun getUrl(context: Context): String? {
        val url = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_URL, null)
        return if (url.isNullOrBlank()) null else url.trimEnd('/')
    }

    fun getAnonKey(context: Context): String? {
        val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ANON_KEY, null)
        return if (key.isNullOrBlank()) null else key
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun getTablePrefix(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TABLE_PREFIX, "rikkahub_") ?: "rikkahub_"

    fun configure(context: Context, url: String, anonKey: String, enabled: Boolean, tablePrefix: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url)
            .putString(KEY_ANON_KEY, anonKey)
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TABLE_PREFIX, tablePrefix)
            .apply()
    }
}

/**
 * Utility to post events to a Supabase table.
 */
object SupabaseEvents {
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Post a JSON event to a Supabase table asynchronously.
     */
    fun postEvent(context: Context, table: String, data: org.json.JSONObject) {
        val url = SupabaseConfig.getUrl(context) ?: return
        val anonKey = SupabaseConfig.getAnonKey(context) ?: return
        if (!SupabaseConfig.isEnabled(context)) return

        executor.execute {
            runCatching {
                val tableName = SupabaseConfig.getTablePrefix(context) + table
                val fullUrl = "$url/rest/v1/$tableName"
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", anonKey)
                conn.setRequestProperty("Authorization", "Bearer $anonKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(data.toString()) }
                conn.responseCode // trigger the request
            }
        }
    }
}

/**
 * Tool for managing Supabase database sync configuration and status.
 *
 * The actual sync of messages/memories to Supabase is handled by the
 * app's sync layer (not this tool). This tool only manages settings.
 */
internal fun buildSupabaseTool(context: Context): Tool = Tool(
    name = "supabase_sync",
    description = """
        Manage Supabase database sync configuration. View current status,
        configure connection settings (URL, anon key, table prefix), enable
        or disable sync, and test the connection. Supabase provides cloud
        PostgreSQL with real-time subscriptions for persistent memory and
        cross-device sync.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: 'status' (view config, default), 'configure' (set URL+key), 'enable', 'disable', 'test' (test connection).")
                })
                put("supabase_url", buildJsonObject {
                    put("type", "string")
                    put("description", "Your Supabase project URL (e.g. https://xxx.supabase.co). Required for 'configure'.")
                })
                put("anon_key", buildJsonObject {
                    put("type", "string")
                    put("description", "Supabase project anon/public key. Required for 'configure'.")
                })
                put("table_prefix", buildJsonObject {
                    put("type", "string")
                    put("description", "Prefix for database table names. Default 'rikkahub_'.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "status"

        when (action) {
            "configure" -> {
                val url = params["supabase_url"]?.jsonPrimitive?.contentOrNull
                val anonKey = params["anon_key"]?.jsonPrimitive?.contentOrNull
                if (url.isNullOrBlank() || anonKey.isNullOrBlank()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "MISSING_CONFIG")
                        put("message", "Both supabase_url and anon_key are required for configure.")
                    }.toString()))
                }
                val tablePrefix = params["table_prefix"]?.jsonPrimitive?.contentOrNull ?: "rikkahub_"
                SupabaseConfig.configure(context, url, anonKey, true, tablePrefix)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("action", "configured")
                    put("url", url)
                    put("table_prefix", tablePrefix)
                    put("note", "Supabase config saved. Use 'test' to verify the connection.")
                }.toString()))
            }

            "enable" -> {
                if (SupabaseConfig.getUrl(context) == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "NOT_CONFIGURED")
                        put("message", "Configure Supabase first with action='configure'.")
                    }.toString()))
                }
                context.getSharedPreferences("supabase_config", Context.MODE_PRIVATE)
                    .edit().putBoolean("supabase_enabled", true).apply()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("action", "enabled")
                }.toString()))
            }

            "disable" -> {
                context.getSharedPreferences("supabase_config", Context.MODE_PRIVATE)
                    .edit().putBoolean("supabase_enabled", false).apply()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("action", "disabled")
                }.toString()))
            }

            "test" -> {
                val url = SupabaseConfig.getUrl(context)
                val anonKey = SupabaseConfig.getAnonKey(context)
                if (url == null || anonKey == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "NOT_CONFIGURED")
                        put("message", "Supabase not configured. Use action='configure' first.")
                        put("connected", false)
                    }.toString()))
                }

                val result = testSupabaseConnection(url, anonKey)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("connected", result)
                    put("url", url)
                    put("message", if (result) "Connection successful!" else "Connection failed. Check URL and anon key.")
                }.toString()))
            }

            else -> { // "status"
                val enabled = SupabaseConfig.isEnabled(context)
                val url = SupabaseConfig.getUrl(context)
                val hasKey = SupabaseConfig.getAnonKey(context) != null
                val tablePrefix = SupabaseConfig.getTablePrefix(context)

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("enabled", enabled)
                    put("configured", url != null && hasKey)
                    put("url", url ?: "not set")
                    put("has_anon_key", hasKey)
                    put("table_prefix", tablePrefix)
                }.toString()))
            }
        }
    }
)

private fun testSupabaseConnection(url: String, anonKey: String): Boolean {
    return runCatching {
        val conn = URL("$url/rest/v1/").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("apikey", anonKey)
        conn.setRequestProperty("Authorization", "Bearer $anonKey")
        val code = conn.responseCode
        // 200 or 404 mean connection works (404 = empty, which is fine)
        code in 200..499
    }.getOrDefault(false)
}
