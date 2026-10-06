package com.hisbaan.orbit.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.int
import com.hisbaan.orbit.agent.integerProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ToolSpec
import com.hisbaan.orbit.weather.OpenMeteo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.coroutines.resume

/** Current conditions and forecast for the phone's location or a named place (Open-Meteo). */
class WeatherTool(private val context: Context, private val weather: OpenMeteo) : Tool {
    private val location = DeviceLocation(context)

    override val spec = ToolSpec(
        name = "get_weather",
        description = "Current weather, the next 12 hours (temperature, conditions, chance of rain) and a daily forecast. " +
            "Uses the phone's location unless a place is given. Answer the user's actual question from it, briefly.",
        parameters = objectSchema(
            emptyList(),
            "place" to stringProperty("A city or town, only if the user named one, e.g. 'Paris'"),
            "days" to integerProperty("Days of daily forecast including today (default 2)", minimum = 1, maximum = 7),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val days = args.int("days") ?: 2
        val imperial = Locale.getDefault().country in IMPERIAL_COUNTRIES
        val name = args.string("place")
        val (latitude, longitude, label) = if (name != null) {
            val place = weather.geocode(name, Locale.getDefault().language)
                ?: return ToolOutcome("No place called '$name' was found. Ask the user where they mean.")
            Triple(place.latitude, place.longitude, place.label)
        } else {
            val here = location.current()
                ?: return ToolOutcome(
                    if (location.granted) "The phone's location isn't available right now. Ask the user which city."
                    else "Error: Orbit has no location permission. Ask the user for a city, or to grant location in Orbit's setup.",
                )
            Triple(here.latitude, here.longitude, "the user's location" + (location.locality(here)?.let { " (near $it)" } ?: ""))
        }
        val forecast = weather.forecast(latitude, longitude, days, imperial)
        return ToolOutcome(forecast.describe(label))
    }

    private companion object {
        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}

/**
 * Coarse location for weather. A fresh fix when the provider gives one quickly, else the most
 * recent known one, else the last fix Orbit got (the system may refuse a fix while Orbit isn't
 * visible, e.g. a headset turn with the screen off).
 */
class DeviceLocation(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    val granted: Boolean
        get() = PERMISSIONS.any { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun current(): Location? {
        if (!granted) return null
        return try {
            val fresh = freshFix()
            val known = fresh ?: manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            known?.also { last = it } ?: last?.takeIf { System.currentTimeMillis() - it.time < CACHE_MS }
        } catch (e: SecurityException) {
            EventLog.log("location", "Denied: $e")
            last?.takeIf { System.currentTimeMillis() - it.time < CACHE_MS }
        }
    }

    /** The town or city around [location], from the platform geocoder (null if it has none). */
    suspend fun locality(location: Location): String? {
        if (!Geocoder.isPresent() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                Geocoder(appContext).getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        cont.resume(addresses.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea })
                    }

                    override fun onError(errorMessage: String?) = cont.resume(null)
                })
            }
        }
    }

    @Suppress("MissingPermission") // checked by the caller via [granted]
    private suspend fun freshFix(): Location? {
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in manager.getProviders(true) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { cont.resume(it) }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        const val FIX_TIMEOUT_MS = 4_000L
        const val CACHE_MS = 6 * 60 * 60_000L

        @Volatile
        var last: Location? = null
    }
}
