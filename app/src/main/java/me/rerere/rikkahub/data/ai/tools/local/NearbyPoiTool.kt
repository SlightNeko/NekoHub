package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search nearby POIs (restaurants, shops, attractions, etc.) via the Amap (高德) API.
 * Uses the user's current GPS location or a manually specified coordinate.
 *
 * Requires the Amap Web API key configured in SharedPreferences "api_keys" → "amap_web_key".
 * For best results, use together with the get_location tool to get current coordinates.
 */
internal fun buildNearbyPoiTool(context: Context): Tool = Tool(
    name = "search_nearby_poi",
    description = """
        Search for nearby points of interest (POIs) using Amap (高德) API. Find
        restaurants, cafes, shops, gas stations, attractions, hospitals, and more.
        Provide a center coordinate (from get_location) and optional keywords and
        radius. Returns up to 25 results with name, address, distance, rating, and
        type. Requires an Amap Web API key.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("longitude", buildJsonObject {
                    put("type", "number")
                    put("description", "Center longitude (e.g., from get_location). Required.")
                })
                put("latitude", buildJsonObject {
                    put("type", "number")
                    put("description", "Center latitude. Required.")
                })
                put("keywords", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keywords, e.g. 'restaurant', 'cafe', 'hospital', 'gas station'. Default searches all nearby POIs.")
                })
                put("types", buildJsonObject {
                    put("type", "string")
                    put("description", "Amap POI type code, e.g. '050000' (restaurant), '060000' (shopping), '080000' (scenic). Overrides keywords.")
                })
                put("radius", buildJsonObject {
                    put("type", "integer")
                    put("description", "Search radius in metres. Default 3000, max 50000.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max results to return. Default 10, max 25.")
                })
            },
            required = listOf("longitude", "latitude")
        )
    },
    execute = { args ->
        val amapKey = getAmapApiKey(context)
        if (amapKey == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_API_KEY")
                put("message", "Amap API key not configured. Please set it in the app's API key settings.")
            }.toString()))
        }

        val params = args.jsonObject
        val lon = params["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val lat = params["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

        if (lon == null || lat == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "MISSING_COORDINATES")
                put("message", "Both longitude and latitude are required. Use get_location first to obtain coordinates.")
            }.toString()))
        }

        val keywords = params["keywords"]?.jsonPrimitive?.contentOrNull
        val types = params["types"]?.jsonPrimitive?.contentOrNull
        val radius = params["radius"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(100, 50000) ?: 3000
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 25) ?: 10

        val result = searchAmapPoi(amapKey, lon, lat, keywords, types, radius, limit)
        if (result == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "API_ERROR")
                put("message", "Failed to query Amap POI API. Check your network connection and API key.")
            }.toString()))
        }

        listOf(UIMessagePart.Text(result.toString()))
    }
)

private fun searchAmapPoi(
    key: String,
    lon: Double,
    lat: Double,
    keywords: String?,
    types: String?,
    radius: Int,
    limit: Int,
): org.json.JSONObject? {
    return runCatching {
        val params = StringBuilder()
        params.append("key=$key")
        params.append("&location=$lon,$lat")
        if (!keywords.isNullOrBlank()) params.append("&keywords=${URLEncoder.encode(keywords, "UTF-8")}")
        if (!types.isNullOrBlank()) params.append("&types=$types")
        params.append("&radius=$radius")
        params.append("&offset=$limit")
        params.append("&page=1")
        params.append("&extensions=all")
        params.append("&output=JSON")

        val url = URL("https://restapi.amap.com/v3/place/around?$params")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(body)
        if (root.optString("status") != "1") return@runCatching null

        val pois = root.optJSONArray("pois") ?: return@runCatching null
        val suggestion = root.optJSONObject("suggestion")

        org.json.JSONObject().apply {
            put("count", root.optString("count", "0"))
            put("center", org.json.JSONObject().apply {
                put("longitude", lon)
                put("latitude", lat)
            })
            put("radius_m", radius)
            put("results", org.json.JSONArray().apply {
                for (i in 0 until pois.length()) {
                    val p = pois.getJSONObject(i)
                    put(org.json.JSONObject().apply {
                        put("name", p.optString("name", ""))
                        put("address", p.optString("address", ""))
                        put("distance_m", p.optString("distance", ""))
                        put("type", p.optString("type", ""))
                        put("typecode", p.optString("typecode", ""))
                        put("rating", p.optString("biz_ext", org.json.JSONObject().optString("rating", "N/A")))
                        if (p.has("tel") && !p.isNull("tel")) put("phone", p.optString("tel", ""))
                        if (p.has("biz_ext")) {
                            val ext = p.optJSONObject("biz_ext")
                            if (ext != null && ext.has("rating")) {
                                put("rating", ext.optString("rating", ""))
                                if (ext.has("cost")) put("avg_cost", ext.optString("cost", ""))
                            }
                        }
                    })
                }
            })
            if (suggestion != null) {
                put("suggestion", suggestion)
            }
        }
    }.getOrNull()
}

private fun getAmapApiKey(context: Context): String? {
    val prefs = context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)
    val key = prefs.getString("amap_web_key", null)
    return if (key.isNullOrBlank()) null else key
}
