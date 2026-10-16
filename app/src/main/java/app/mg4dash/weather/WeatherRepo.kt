package app.mg4dash.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.Tr
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.StatusMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Météo Open-Meteo (choix 4A) : gratuit, sans clé, nécessite internet dans la voiture.
 * Position : dernière localisation connue du système, sinon la ville réglée dans les paramètres.
 */
object WeatherRepo {
    private const val TAG = "Météo"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 2 * 60 * 1000L

    data class Hour(val label: String, val code: Int, val tempC: Int)
    data class Day(val label: String, val code: Int, val minC: Int, val maxC: Int)
    data class Weather(
        val place: String,
        val tempC: Int,
        val feelsC: Int,
        val code: Int,
        val windKmh: Int,
        val humidity: Int,
        val hours: List<Hour>,
        val days: List<Day>,
        val updatedAt: Long,
    )

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val app = context.applicationContext
        if (BuildConfig.MOCK) {
            _weather.value = Weather(
                "Paris", 14, 12, 2, 18, 72,
                listOf(Hour("14h", 2, 14), Hour("15h", 3, 15), Hour("16h", 61, 13), Hour("17h", 61, 12), Hour("18h", 3, 11)),
                listOf(Day("Mer", 3, 8, 15), Day("Jeu", 0, 7, 17), Day("Ven", 61, 9, 13)),
                System.currentTimeMillis(),
            )
            return
        }
        scope.launch {
            delay(5_000)   // laisser le réseau T-box monter après le démarrage
            while (true) {
                val ok = try { refresh(app) } catch (e: Exception) {
                    Log.w(TAG, "échec : ${e.javaClass.simpleName} ${e.message}"); false
                }
                delay(if (ok) REFRESH_MS else RETRY_MS)
            }
        }
    }

    @Volatile private var usedFallback = false

    /**
     * Au démarrage, la météo est demandée avant le premier relevé GPS : elle partait alors sur la
     * ville de repli jusqu'au rafraîchissement suivant. Dès qu'une position arrive, on la redemande.
     */
    fun onLocation(context: Context) {
        if (usedFallback) { usedFallback = false; refreshNow(context) }
    }

    fun refreshNow(context: Context) {
        scope.launch {
            try { refresh(context.applicationContext) } catch (e: Exception) {
                Log.w(TAG, "échec : ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    // Pas de test préalable de connectivité : sur la voiture, internet passe par l'interface
    // Ethernet de la T-box, et la requête elle-même est le seul test fiable.
    private fun refresh(context: Context): Boolean {
        Log.i(TAG, "requête Open-Meteo (internet : ${StatusMonitor.hasInternet(context)})")
        val prefs = Prefs(context)
        val loc = app.mg4dash.map.LocationRepo.location.value?.let { it.latitude to it.longitude } ?: lastLocation(context)
        usedFallback = loc == null
        val lat = loc?.first ?: prefs.weatherLat.toDouble()
        val lon = loc?.second ?: prefs.weatherLon.toDouble()
        val place = if (loc != null) Tr.s(R.string.weather_here) else prefs.weatherCity
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=4&timezone=auto",
            lat, lon,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            if (conn.responseCode != 200) { Log.w(TAG, "HTTP ${conn.responseCode}"); return false }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        _weather.value = parse(JSONObject(body), place)
        Log.i(TAG, "mise à jour : ${_weather.value?.tempC}° à $place")
        return true
    }

    private fun parse(json: JSONObject, place: String): Weather {
        val cur = json.getJSONObject("current")
        val curTime = cur.getString("time")             // "2026-10-13T15:00"
        val hourly = json.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")
        var start = 0
        for (i in 0 until hTimes.length()) if (hTimes.getString(i) <= curTime) start = i
        val hours = (1..5).mapNotNull { k ->
            val i = start + k
            if (i >= hTimes.length()) null
            else Hour(hourLabel(hTimes.getString(i)), hCodes.getInt(i), Math.round(hTemps.getDouble(i)).toInt())
        }
        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val days = (1 until minOf(4, dTimes.length())).map { i ->
            Day(
                dayLabel(dTimes.getString(i)),
                daily.getJSONArray("weather_code").getInt(i),
                Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
            )
        }
        return Weather(
            place = place,
            tempC = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feelsC = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            code = cur.getInt("weather_code"),
            windKmh = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
            humidity = cur.getInt("relative_humidity_2m"),
            hours = hours,
            days = days,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun dayLabel(iso: String): String = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(d!!).trimEnd('.').replaceFirstChar { it.uppercase() }
    } catch (_: Exception) { iso.takeLast(5) }

    /** « 14h » en français, « 14:00 » ailleurs. */
    private fun hourLabel(iso: String): String =
        if (Locale.getDefault().language == "fr") iso.substring(11, 13).trimStart('0').ifEmpty { "0" } + "h"
        else iso.substring(11, 16)

    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Pair<Double, Double>? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }

    /** Codes WMO → libellé français. */
    fun label(code: Int): String = when (code) {
        0 -> Tr.s(R.string.wx_sunny)
        1 -> Tr.s(R.string.wx_mostly_clear)
        2 -> Tr.s(R.string.wx_partly)
        3 -> Tr.s(R.string.wx_overcast)
        45, 48 -> Tr.s(R.string.wx_fog)
        51, 53, 55, 56, 57 -> Tr.s(R.string.wx_drizzle)
        61, 63, 65, 66, 67, 80, 81, 82 -> Tr.s(R.string.wx_rain)
        71, 73, 75, 77, 85, 86 -> Tr.s(R.string.wx_snow)
        95, 96, 99 -> Tr.s(R.string.wx_storm)
        else -> "—"
    }

    fun icon(code: Int): Int = when (code) {
        0, 1 -> R.drawable.ic_sun
        2, 3, 45, 48 -> R.drawable.ic_cloud
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
        95, 96, 99 -> R.drawable.ic_bolt
        else -> if (code >= 51) R.drawable.ic_rain else R.drawable.ic_cloud
    }
}
