package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Capacité utile de la batterie, sans réglage à la main si possible :
 *  1) la valeur annoncée par la voiture (INFO_EV_BATTERY_CAPACITY, AOSP, en Wh ; MG4Control l'a
 *     trouvée à 0 sur SWI133, elle peut répondre ailleurs) ;
 *  2) sinon, une estimation mesurée par MG4Dash : énergie sortie de la batterie (récupération
 *     déduite) divisée par la baisse du % depuis la dernière recharge, dès 15 % de baisse.
 * Tant que l'utilisateur n'a pas ajusté la valeur à la main, la capacité utilisée suit la
 * meilleure source disponible ; « Auto » dans les réglages y revient.
 */
object BatteryCapacity {
    private const val TAG = "Capacité"
    private const val PROP_EV_BATTERY_CAPACITY = 0x11600106
    private const val MIN_DROP = 15

    /** Capacité annoncée par la voiture, en kWh, ou null. */
    fun fromCar(): Float? {
        val raw = CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0)?.takeIf { it.isFinite() && it > 0f }
            ?: CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0x1000000)?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val kwh = if (raw > 1000f) raw / 1000f else raw
        return kwh.takeIf { it in 20f..120f }
    }

    /** Estimation mesurée (kWh) et baisse de % sur laquelle elle repose, ou null. */
    fun estimate(context: Context): Pair<Float, Int>? {
        val sp = Prefs(context).raw
        val kwh = sp.getFloat("em_cap_est", -1f).takeIf { it > 0f } ?: return null
        return kwh to sp.getInt("em_cap_drop", 0)
    }

    /** Appelé par EnergyMonitor : énergie nette et baisse de % depuis la recharge. */
    fun learn(context: Context, netKwh: Float, socDrop: Int) {
        if (socDrop < MIN_DROP || netKwh < 3f) return
        val est = netKwh / socDrop * 100f
        if (est !in 30f..100f) return
        val sp = Prefs(context).raw
        // Garde l'estimation reposant sur la plus grande baisse (la plus précise : % entiers)
        if (socDrop < sp.getInt("em_cap_drop", 0) && sp.getFloat("em_cap_est", -1f) > 0f) return
        sp.edit().putFloat("em_cap_est", est).putInt("em_cap_drop", socDrop).apply()
        Log.i(TAG, "estimation ${"%.1f".format(est)} kWh (baisse de $socDrop %)")
        apply(context)
    }

    /** Remet la capacité utilisée sur la meilleure source, sauf réglage manuel. */
    fun apply(context: Context) {
        val prefs = Prefs(context)
        if (prefs.batteryKwhManual) return
        val v = fromCar() ?: estimate(context)?.first ?: return
        if (Math.abs(v - prefs.batteryKwh) >= 0.1f) {
            prefs.batteryKwh = Math.round(v * 10f) / 10f
            Log.i(TAG, "capacité utilisée : ${prefs.batteryKwh} kWh")
        }
    }
}
