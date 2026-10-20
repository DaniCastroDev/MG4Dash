package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import app.mg4dash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Point d'accès Wi-Fi de la voiture (Android Auto sans fil, ordinateur…).
 *
 * Android 9 : marche / arrêt par le partage de connexion (IConnectivityManager.startTethering /
 * stopTethering, TETHER_PRIVILEGED), état et nom / mot de passe par WifiManager (getWifiApState,
 * getWifiApConfiguration). Tout est masqué : la version voiture (uid système) y a accès par réflexion.
 */
object Hotspot {
    private const val TAG = "PointAcces"
    private const val TETHERING_WIFI = 0
    private const val AP_DISABLING = 10
    private const val AP_DISABLED = 11
    private const val AP_ENABLING = 12
    private const val AP_ENABLED = 13
    private const val AP_FAILED = 14

    data class State(
        val available: Boolean = false,
        val on: Boolean = false,
        val changing: Boolean = false,
        val failed: Boolean = false,
        val ssid: String = "",
        val password: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var mockOn = true

    fun start(context: Context) {
        app = context.applicationContext
        if (!BuildConfig.MOCK) app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
        refresh()
    }

    fun refresh() {
        if (!::app.isInitialized) return
        if (BuildConfig.MOCK) { _state.value = State(true, mockOn, false, false, "MG4-Daniel", "mg4dash2026"); return }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: run { _state.value = State(); return }
        val apState = try { wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int } catch (e: Exception) {
            Log.w(TAG, "état : ${e.message}"); _state.value = State(); return
        }
        var ssid = ""; var pass = ""
        try {
            val cfg = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm) as? android.net.wifi.WifiConfiguration
            ssid = cfg?.SSID.orEmpty().trim('"'); pass = cfg?.preSharedKey.orEmpty().trim('"')
        } catch (e: Exception) { Log.w(TAG, "configuration : ${e.cause?.message ?: e.message}") }
        _state.value = State(
            available = true,
            on = apState == AP_ENABLED,
            changing = apState == AP_ENABLING || apState == AP_DISABLING,
            failed = apState == AP_FAILED,
            ssid = ssid, password = pass,
        )
    }

    /** Active ou coupe le point d'accès ; false si la voiture refuse (le bouton des réglages Android reste). */
    fun set(on: Boolean): Boolean {
        if (BuildConfig.MOCK) {
            _state.value = _state.value.copy(changing = true)
            main.postDelayed({ mockOn = on; refresh() }, 1_000); return true
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val f = ConnectivityManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
            val svc = f.get(cm) ?: return false
            val m = svc.javaClass.methods.firstOrNull { it.name == if (on) "startTethering" else "stopTethering" } ?: return false
            val receiver = object : ResultReceiver(main) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    Log.i(TAG, "partage Wi-Fi : résultat $code"); refresh()
                }
            }
            // Signature selon la version : (int, ResultReceiver, boolean, String) pour démarrer, (int, String) pour arrêter
            val args = m.parameterTypes.map { t ->
                when (t) {
                    Int::class.javaPrimitiveType -> TETHERING_WIFI
                    Boolean::class.javaPrimitiveType -> false
                    String::class.java -> app.packageName
                    ResultReceiver::class.java -> receiver
                    else -> null
                }
            }
            m.invoke(svc, *args.toTypedArray())
            Log.i(TAG, if (on) "activation demandée" else "arrêt demandé")
            _state.value = _state.value.copy(changing = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "${if (on) "activation" else "arrêt"} refusé : ${e.cause?.message ?: e.message}")
            false
        }
    }
}
