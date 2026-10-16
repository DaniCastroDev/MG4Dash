package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Limite de charge effective.
 *
 * Sur la MG4 Standard, l'appli Énergie SAIC n'autorise la limite (CHRG_TRGT_SOC) que pour
 * certains types de batterie (code 7 ou 9) : ailleurs, la voiture l'ignore et charge à 100 %.
 * MG4Dash écrit quand même la limite côté voiture, puis surveille le % pendant la charge :
 * à la limite, il envoie « arrêter la charge » (chargeCtrl(2)), la commande du bouton
 * « Arrêter » du launcher d'origine.
 *
 * Limite : la surveillance ne fonctionne que si l'écran de la voiture reste allumé pendant
 * la charge (processus MG4Dash actif).
 *
 * Le même collecteur enregistre l'historique de consommation (un point par km parcouru).
 */
object ChargeGuard {
    private const val TAG = "Limite"
    private const val RETRY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastStopAt = 0L

    private val _history = MutableStateFlow<List<Float>>(emptyList())
    /** Conso récente (kWh/100 km) relevée à chaque km, 60 derniers points. */
    val history: StateFlow<List<Float>> = _history

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        _history.value = (sp.getString("cons_history", "") ?: "").split(',').mapNotNull { it.toFloatOrNull() }
        if (app.mg4dash.BuildConfig.MOCK && _history.value.isEmpty()) {
            _history.value = List(48) { i -> 15f + 3f * kotlin.math.sin(i / 5f) + (i % 7) * 0.3f }
        }
        var lastKm = sp.getInt("cons_history_km", -1)
        scope.launch {
            CarBridge.state.collect { s ->
                enforce(prefs, s)
                // Historique : un point par km
                val km = s.odometerKm ?: return@collect
                if (km != lastKm) {
                    val v = CarProps.getFloat(CarProps.ELEC_CSUMP_PERKM)?.takeIf { it in 3f..60f }
                    if (lastKm >= 0 && v != null) {
                        val list = (_history.value + v).takeLast(60)
                        _history.value = list
                        sp.edit().putString("cons_history", list.joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) })
                            .putInt("cons_history_km", km).apply()
                    } else {
                        sp.edit().putInt("cons_history_km", km).apply()
                    }
                    lastKm = km
                }
            }
        }
    }

    private fun enforce(prefs: Prefs, s: VehicleState) {
        if (!prefs.enforceChargeLimit || app.mg4dash.system.Firmware.nativeChargeLimit) return
        val limit = prefs.chargeLimit
        if (limit >= 100) return
        val soc = s.batteryPercent ?: return
        val charging = s.chargeCtrl == 1 || VehicleState.isCharging(s.chargeStatus)
        if (!charging || soc < limit) return
        val now = System.currentTimeMillis()
        if (now - lastStopAt < RETRY_MS) return
        lastStopAt = now
        Log.i(TAG, "batterie $soc % ≥ limite $limit % : arrêt de la charge demandé")
        CarBridge.stopCharge()
    }

    /** Applique la limite : côté voiture (si elle la gère) et côté MG4Dash. */
    fun setLimit(context: Context, percent: Int) {
        val p = percent.coerceIn(40, 100)
        Prefs(context).chargeLimit = p
        CarProps.setInt(CarProps.CHRG_TRGT_SOC, CarProps.targetSocCode(p), "limite de charge $p %")
    }
}
