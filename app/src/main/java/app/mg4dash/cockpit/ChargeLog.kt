package app.mg4dash.cockpit

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Session de charge en cours (ou la dernière) : un point par minute (minutes depuis le
 * branchement, % batterie, kW) pour la courbe du cockpit, gardé dans les préférences pour
 * survivre à un redémarrage de l'appli.
 */
object ChargeLog {
    data class Session(val startedAt: Long = 0L, val startSoc: Float = 0f, val points: List<Triple<Float, Float, Float>> = emptyList(), val endedAt: Long = 0L) {
        val active get() = startedAt > 0 && endedAt == 0L
        val addedKwh get() = points.zipWithNext { a, b -> (b.first - a.first) / 60f * (a.third + b.third) / 2f }.sum()
    }

    /** Sortie V2L en cours (ou la dernière) : un point par minute (minutes depuis le début, kW). */
    data class V2l(val startedAt: Long = 0L, val startSoc: Float = 0f, val points: List<Pair<Float, Float>> = emptyList(), val endedAt: Long = 0L) {
        val active get() = startedAt > 0 && endedAt == 0L
        val deliveredKwh get() = points.zipWithNext { a, b -> (b.first - a.first) / 60f * (a.second + b.second) / 2f }.sum()
    }
    private val _v2l = MutableStateFlow(V2l())
    val v2l: StateFlow<V2l> = _v2l

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val sp = Prefs(context).raw
        var saved = sp.getString("charge_session", null)
        _session.value = load(saved)
        if (app.mg4dash.BuildConfig.MOCK) {
            // Démo : 2 h 30 de charge AC (31 → 58 %), puis 40 min de V2L
            val now = System.currentTimeMillis()
            _session.value = Session(startedAt = now - 150 * 60_000L, startSoc = 31f,
                points = (0..150 step 5).map { t -> Triple(t.toFloat(), 31f + t * 0.18f, if (t < 3) 2f else 7.2f - (t / 150f) * 0.6f) })
            _v2l.value = V2l(startedAt = now - 40 * 60_000L, startSoc = 66f,
                points = (0..40).map { t -> t.toFloat() to (0.9f + 0.6f * kotlin.math.sin(t / 4f) + if (t in 18..24) 0.9f else 0f).coerceAtLeast(0.2f) })
        }
        scope.launch {
            while (true) {
                sample(CarBridge.state.value)
                // n'écrire en flash que si la session a changé (hors charge : jamais)
                val json = save(_session.value)
                if (json != saved) { sp.edit().putString("charge_session", json).apply(); saved = json }
                delay(60_000)
            }
        }
    }

    /** Puissance de charge : entrée AC si dispo, sinon tension × courant du pack (positif en charge). */
    fun powerKw(): Float? {
        val v = CarProps.getFloat(CarProps.ONBD_CHRG_AC_VOL); val a = CarProps.getFloat(CarProps.ONBD_CHRG_AC_CRNT)
        if (v != null && a != null && v > 50f && a > 0.2f) return v * a / 1000f
        val pv = CarProps.getFloat(CarProps.BMS_PACK_VOL); val pa = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
        return if (pv != null && pa != null) Math.abs(pv * pa) / 1000f else null
    }

    private fun sampleV2l(s: VehicleState) {
        val on = VehicleState.isDischarging(s)
        val now = System.currentTimeMillis()
        var cur = _v2l.value
        if (!on) { if (cur.active) _v2l.value = cur.copy(endedAt = now); return }
        if (!cur.active) cur = V2l(startedAt = now, startSoc = s.socPrecise ?: s.batteryPercent?.toFloat() ?: 0f)
        val kw = powerKw() ?: 0f
        _v2l.value = cur.copy(points = (cur.points + ((now - cur.startedAt) / 60_000f to kw)).takeLast(12 * 60))
    }

    private fun sample(s: VehicleState) {
        sampleV2l(s)
        val plugged = VehicleState.isPlugged(s.chargeStatus) && !VehicleState.isDischargeMode(s.chargeStatus)
        val soc = s.socPrecise ?: s.batteryPercent?.toFloat() ?: return
        val now = System.currentTimeMillis()
        var cur = _session.value
        if (plugged && !cur.active) cur = Session(startedAt = now, startSoc = soc)
        if (!plugged) { if (cur.active) _session.value = cur.copy(endedAt = now); return }
        val charging = VehicleState.isCharging(s.chargeStatus) || s.chargeCtrl == 1
        val kw = if (charging) powerKw() ?: 0f else 0f
        val t = (now - cur.startedAt) / 60_000f
        _session.value = cur.copy(points = (cur.points + Triple(t, soc, kw)).takeLast(24 * 60))
    }

    private fun save(s: Session): String = JSONArray().apply {
        put(s.startedAt); put(s.startSoc.toDouble()); put(s.endedAt)
        s.points.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble()).put(it.third.toDouble())) }
    }.toString()

    private fun load(raw: String?): Session = try {
        val a = JSONArray(raw ?: "[]")
        if (a.length() < 3) Session() else Session(
            startedAt = a.getLong(0), startSoc = a.getDouble(1).toFloat(), endedAt = a.getLong(2),
            points = (3 until a.length()).map { val p = a.getJSONArray(it); Triple(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat()) },
        )
    } catch (_: Exception) { Session() }
}
