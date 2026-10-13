package app.mg4dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Position du véhicule. La MG4 expose un fournisseur « fused » précis (vérifié sur la voiture
 * le 2026-10-13), avec vitesse et cap ; repli sur « gps ».
 */
object LocationRepo {
    private const val TAG = "Position"
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (BuildConfig.MOCK) {
            _location.value = Location("mock").apply {
                latitude = 48.8584; longitude = 2.2945; bearing = 120f; speed = 0f; altitude = 35.0
            }
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.allProviders
        val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in providers }
        if (provider == null) { Log.w(TAG, "aucun fournisseur de position"); return }
        try {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { _location.value = it }
            lm.requestLocationUpdates(provider, 2000L, 0f, listener, Looper.getMainLooper())
            Log.i(TAG, "suivi de position via « $provider »")
        } catch (e: Exception) {
            Log.w(TAG, "position indisponible : ${e.message}")
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { _location.value = location }
        @Deprecated("API 28")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun cardinal(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return dirs[(((bearing % 360) + 360 + 22.5f) / 45f).toInt() % 8]
    }
}
