package com.hisbaan.orbit.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
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
import com.hisbaan.orbit.weather.WeatherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Current conditions and forecast for the phone's location or a named place, from the provider
 * chosen in settings ([provider]); Open-Meteo ([geocoder]) finds places and stands in when the
 * chosen provider fails.
 */
class WeatherTool(
    context: Context,
    private val geocoder: OpenMeteo,
    private val provider: suspend () -> WeatherProvider,
) : Tool {
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

    override suspend fun invoke(args: JsonObject): ToolOutcome = coroutineScope {
        val t0 = System.currentTimeMillis()
        val days = args.int("days") ?: 2
        val imperial = Locale.getDefault().country in IMPERIAL_COUNTRIES
        val name = args.string("place")
        val latitude: Double
        val longitude: Double
        val label: Deferred<String>
        if (name != null) {
            val place = geocoder.geocode(name, Locale.getDefault().language)
                ?: return@coroutineScope ToolOutcome("No place called '$name' was found. Ask the user where they mean.")
            latitude = place.latitude
            longitude = place.longitude
            label = CompletableDeferred(place.label)
        } else {
            val here = location.current() ?: return@coroutineScope ToolOutcome(
                if (location.granted) "The phone's location isn't available right now. Ask the user which city."
                else "Error: Orbit has no location permission. Ask the user for a city, or to grant location in Orbit's setup.",
            )
            latitude = here.latitude
            longitude = here.longitude
            // The place name only labels the answer: look it up while the forecast loads.
            label = async { "the user's location" + (location.locality(here)?.let { " (near $it)" } ?: "") }
        }
        val chosen = provider()
        var note = ""
        val forecast = try {
            chosen.forecast(latitude, longitude, days, imperial)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (chosen === geocoder) throw e
            EventLog.log("weather", "${chosen.name} failed ($e); using Open-Meteo")
            note = "\n(${chosen.name} didn't answer, so this is from Open-Meteo.)"
            geocoder.forecast(latitude, longitude, days, imperial)
        }
        val text = forecast.describe(label.await()) + note
        EventLog.log("weather", "${if (note.isEmpty()) chosen.name else geocoder.name} in ${System.currentTimeMillis() - t0}ms")
        ToolOutcome(text)
    }

    private companion object {
        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}

/**
 * Coarse location for weather: a recent known location if there is one, else a quick fresh
 * fix, else the last fix Orbit got (the system may refuse a fix while Orbit isn't visible,
 * e.g. a headset turn with the screen off).
 */
class DeviceLocation(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    val granted: Boolean
        get() = PERMISSIONS.any { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * A recent known location right away (weather doesn't need a fresh fix, and waiting for one
     * took 4 s: the fix often never comes while Orbit isn't in the foreground), refreshed in the
     * background for next time. Otherwise a quick fresh fix, else whatever is known.
     */
    suspend fun current(): Location? {
        if (!granted) return null
        return try {
            val known = lastKnown()
            if (known != null && known.ageMs() < FRESH_MS) {
                refreshInBackground()
                return known.also { last = it }
            }
            val fresh = freshFix(FIX_TIMEOUT_MS)
            (fresh ?: known?.takeIf { it.ageMs() < CACHE_MS })?.also { last = it }
        } catch (e: SecurityException) {
            EventLog.log("location", "Denied: $e")
            last?.takeIf { it.ageMs() < CACHE_MS }
        }
    }

    @Suppress("MissingPermission") // checked by the caller via [granted]
    private fun lastKnown(): Location? =
        (manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) } + listOfNotNull(last)).minByOrNull { it.ageMs() }

    private fun refreshInBackground() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                freshFix(REFRESH_TIMEOUT_MS)?.let { last = it }
            } catch (_: SecurityException) {
            } finally {
                refreshing = false
            }
        }
    }

    private fun Location.ageMs(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

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
    private suspend fun freshFix(timeoutMs: Long): Location? {
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in manager.getProviders(true) } ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { cont.resume(it) }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        /** A known location this recent is used without waiting for a fix. */
        const val FRESH_MS = 15 * 60_000L
        const val FIX_TIMEOUT_MS = 2_000L
        const val REFRESH_TIMEOUT_MS = 30_000L
        const val CACHE_MS = 6 * 60 * 60_000L

        @Volatile
        var last: Location? = null

        @Volatile
        var refreshing = false

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
