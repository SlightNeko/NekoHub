package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Get the device's current GPS/network location and (optionally) convert
 * it to a human-readable address via the Amap (高德) reverse-geocoding API.
 *
 * Permissions required: ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
 * Amap API key: the user should configure this via app settings. If not set,
 * only raw coordinates are returned.
 */
internal fun buildLocationTool(context: Context, eventBus: me.rerere.rikkahub.data.event.AppEventBus): Tool = Tool(
    name = "get_location",
    description = """
        Get the device's current location. Returns latitude, longitude, accuracy
        (metres), provider (gps/network), and timestamp. If an Amap (高德) API
        key is configured, also returns a formatted address and nearby POIs via
        the Amap reverse-geocoding API. Requires location permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("force_fresh", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Force a fresh GPS fix instead of using cached location. May take a few seconds. Default false.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasLocationPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put("message", "Location permission is not granted. Please enable it in the app permission settings.")
            }.toString()))
        }

        val params = args.jsonObject
        val forceFresh = params["force_fresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "LOCATION_DISABLED")
                put("message", "Location services are disabled. Please turn on GPS or network location in device settings.")
            }.toString()))
        }

        val location = if (forceFresh) {
            getFreshLocation(locationManager)
        } else {
            getLastKnownLocation(locationManager)
        }

        if (location == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "LOCATION_UNAVAILABLE")
                put("message", "Could not get a location fix. Try force_fresh=true or move to an open area.")
            }.toString()))
        }

        // Try Amap reverse geocoding
        val amapKey = getAmapApiKey(context)
        val addressJson = if (amapKey != null) {
            reverseGeocodeAmap(amapKey, location.latitude, location.longitude)
        } else null

        val payload = buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
            put("provider", location.provider ?: "unknown")
            put("timestamp", location.time)
            if (addressJson != null) {
                put("address", addressJson)
            } else {
                put("address_note", "Amap API key not configured. Set it in app settings for address lookup.")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun getLastKnownLocation(locationManager: LocationManager): Location? {
    var best: Location? = null
    for (provider in locationManager.getProviders(true)) {
        @Suppress("MissingPermission")
        val loc = locationManager.getLastKnownLocation(provider) ?: continue
        if (best == null || loc.accuracy < best.accuracy) {
            best = loc
        }
    }
    return best
}

private fun getFreshLocation(locationManager: LocationManager): Location? {
    val providers = locationManager.getProviders(true)
    if (providers.isEmpty()) return null

    var result: Location? = null
    val latch = CountDownLatch(1)

    for (provider in providers) {
        @Suppress("MissingPermission")
        locationManager.requestSingleUpdate(provider, { loc ->
            if (result == null || loc.accuracy < result!!.accuracy) {
                result = loc
            }
            latch.countDown()
        }, null)
    }

    latch.await(15, TimeUnit.SECONDS)
    return result
}

/**
 * Read the Amap API key from a simple SharedPreferences store.
 * Users set this in the app's API key settings page (to be added).
 */
private fun getAmapApiKey(context: Context): String? {
    val prefs = context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)
    val key = prefs.getString("amap_web_key", null)
    return if (key.isNullOrBlank()) null else key
}

/**
 * Call Amap reverse-geocoding API synchronously.
 * Returns the parsed address JSON, or null on failure.
 */
private fun reverseGeocodeAmap(key: String, lat: Double, lon: Double): org.json.JSONObject? {
    return runCatching {
        val urlStr = "https://restapi.amap.com/v3/geocode/regeo" +
            "?key=$key" +
            "&location=$lon,$lat" +
            "&extensions=all" +
            "&radius=1000" +
            "&output=JSON"

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(body)
        if (root.optString("status") != "1") return@runCatching null

        val regeo = root.getJSONObject("regeocode")
        val addrComp = regeo.getJSONObject("addressComponent")

        org.json.JSONObject().apply {
            put("formatted", regeo.optString("formatted_address", ""))
            put("country", addrComp.optString("country", ""))
            put("province", addrComp.optString("province", ""))
            val city = addrComp.optString("city", "")
            put("city", if (city.isEmpty()) addrComp.optString("province", "") else city)
            put("district", addrComp.optString("district", ""))
            put("township", addrComp.optString("township", ""))
            put("street", addrComp.optJSONObject("streetNumber")?.let { sn ->
                org.json.JSONObject().apply {
                    put("street", sn.optString("street", ""))
                    put("number", sn.optString("number", ""))
                }
            } ?: org.json.JSONObject())

            put("building", addrComp.optJSONObject("building") ?: org.json.JSONObject())

            val pois = regeo.optJSONArray("pois")
            if (pois != null && pois.length() > 0) {
                val nearby = org.json.JSONArray()
                for (i in 0 until minOf(pois.length(), 5)) {
                    val poi = pois.getJSONObject(i)
                    nearby.put(org.json.JSONObject().apply {
                        put("name", poi.optString("name", ""))
                        put("type", poi.optString("type", ""))
                        put("distance_m", poi.optString("distance", ""))
                    })
                }
                put("nearby_pois", nearby)
            }
        }
    }.getOrNull()
}
