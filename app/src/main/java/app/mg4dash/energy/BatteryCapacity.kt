package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Capacité utile de la batterie et santé (SOH) estimée, sans réglage à la main si possible.
 *
 * Capacité :
 *  1) la valeur annoncée par la voiture (INFO_EV_BATTERY_CAPACITY, AOSP, en Wh ; MG4Control l'a
 *     trouvée à 0 sur SWI133, elle peut répondre ailleurs) ;
 *  2) sinon, la mesure de MG4Dash : à chaque période entre deux recharges, énergie sortie de la
 *     batterie (récupération déduite) ÷ baisse du %, dès 15 % de baisse. Les mesures sont gardées
 *     avec leur date et lissées : moyenne pondérée par la baisse de % (une mesure sur 60 % compte
 *     4 fois plus qu'une sur 15 %), sur les 30 derniers jours.
 * Santé : capacité mesurée ÷ capacité utile d'origine du modèle (51, 64 ou 77 kWh), aussi lissée
 * sur 30 jours, et sur toute la période mesurée pour suivre l'évolution.
 */
object BatteryCapacity {
    private const val TAG = "Capacité"
    private const val PROP_EV_BATTERY_CAPACITY = 0x11600106
    private const val MIN_DROP = 15
    private const val MONTH_MS = 30L * 24 * 3600_000L
    private const val MAX_SAMPLES = 300

    /** Modèles : capacité brute (affichée) → capacité utile d'origine (kWh). */
    val MODELS = listOf(51 to 49.0f, 64 to 61.7f, 77 to 74.4f)

    data class Sample(val at: Long, val kwh: Float, val drop: Int)

    /** Capacité annoncée par la voiture, en kWh, ou null. */
    fun fromCar(): Float? {
        val raw = CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0)?.takeIf { it.isFinite() && it > 0f }
            ?: CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0x1000000)?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val kwh = if (raw > 1000f) raw / 1000f else raw
        return kwh.takeIf { it in 20f..120f }
    }

    fun samples(context: Context): List<Sample> = try {
        val arr = JSONArray(Prefs(context).raw.getString("cap_samples", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Sample(o.getLong("t"), o.getDouble("k").toFloat(), o.getInt("d")) } }
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, list: List<Sample>) {
        val arr = JSONArray()
        list.takeLast(MAX_SAMPLES).forEach { arr.put(JSONObject().put("t", it.at).put("k", it.kwh.toDouble()).put("d", it.drop)) }
        Prefs(context).raw.edit().putString("cap_samples", arr.toString()).apply()
    }

    private fun weighted(list: List<Sample>): Float? {
        val w = list.sumOf { it.drop }
        return if (w == 0) null else list.sumOf { (it.kwh * it.drop).toDouble() }.toFloat() / w
    }

    /** Capacité mesurée, lissée sur 30 jours (sinon toutes les mesures, sinon la période en cours), et nombre de mesures. */
    fun estimate(context: Context): Pair<Float, Int>? {
        val all = samples(context)
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }
        weighted(recent)?.let { return it to recent.size }
        weighted(all)?.let { return it to all.size }
        val sp = Prefs(context).raw
        val cur = sp.getFloat("cap_current", -1f).takeIf { it > 0f } ?: return null
        return cur to 0
    }

    /** Pendant une période : estimation provisoire (affichée tant qu'aucune période n'est terminée). */
    fun learn(context: Context, netKwh: Float, socDrop: Int) {
        val est = valid(netKwh, socDrop) ?: return
        Prefs(context).raw.edit().putFloat("cap_current", est).putInt("cap_current_drop", socDrop).apply()
        if (samples(context).isEmpty()) apply(context)
    }

    /** Fin de période (recharge détectée) : la mesure rejoint l'historique. */
    fun closePeriod(context: Context, netKwh: Float, socDrop: Int) {
        val est = valid(netKwh, socDrop) ?: return
        save(context, samples(context) + Sample(System.currentTimeMillis(), est, socDrop))
        Prefs(context).raw.edit().remove("cap_current").remove("cap_current_drop").apply()
        Log.i(TAG, "mesure ${"%.1f".format(est)} kWh sur $socDrop % ajoutée")
        apply(context)
    }

    private fun valid(netKwh: Float, socDrop: Int): Float? {
        if (socDrop < MIN_DROP || netKwh < 3f) return null
        return (netKwh / socDrop * 100f).takeIf { it in 30f..100f }
    }

    /** Capacité utile d'origine du modèle choisi (kWh). */
    fun nominal(context: Context): Float {
        val gross = Prefs(context).batteryModel
        return MODELS.firstOrNull { it.first == gross }?.second ?: 49.0f
    }

    /** Santé (%) sur 30 jours et depuis le début des mesures, ou null sans mesure terminée. */
    fun health(context: Context): Pair<Int, Int>? {
        val all = samples(context).takeIf { it.isNotEmpty() } ?: return null
        val nom = nominal(context)
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }.ifEmpty { all }
        val m = weighted(recent) ?: return null
        val total = weighted(all) ?: m
        return Math.round(m / nom * 100f).coerceAtMost(100) to Math.round(total / nom * 100f).coerceAtMost(100)
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
