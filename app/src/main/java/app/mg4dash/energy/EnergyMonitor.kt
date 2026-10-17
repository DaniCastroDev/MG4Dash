package app.mg4dash.energy

import android.content.Context
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Consommation mesurée, répartie entre conduite, climatisation et accessoires.
 *
 * Constaté sur la voiture (journal du 2026-10-14) : les compteurs de consommation SAIC restent
 * à zéro, mais tension et courant batterie sont fiables. Donc, chaque seconde :
 *  - P = tension × courant ; P > 0 = énergie consommée, P < 0 en roulant = récupération ;
 *  - à l'arrêt, clim coupée : P = accessoires (12 V, écran…) → appris, par état des feux /
 *    essuie-glaces (différence mesurée en conditions identiques) ;
 *  - à l'arrêt, clim allumée : P − accessoires = clim → appris par réglage (ventilation,
 *    chauffage / froid, dégivrage) ;
 *  - en roulant : clim et accessoires = valeurs apprises, le reste = conduite.
 * Trois périodes : depuis la dernière recharge, depuis le démarrage (pause de plus de 10 min), et les
 * 1 000 derniers km (100 tranches de 10 km gardées, la plus ancienne remplacée : mémoire bornée).
 */
object EnergyMonitor {
    private const val TAG = "EnergyMon"
    private const val SLEEP_GAP_MS = 10 * 60_000L

    data class Totals(
        val outKwh: Float = 0f,      // énergie sortie de la batterie
        val regenKwh: Float = 0f,    // récupérée en roulant
        val climKwh: Float = 0f,
        val accKwh: Float = 0f,
        val km: Int = 0,
    ) {
        val driveKwh: Float get() = (outKwh - climKwh - accKwh).coerceAtLeast(0f)
        val netKwh: Float get() = (outKwh - regenKwh).coerceAtLeast(0f)
        /** kWh/100 km net (récupération déduite), à partir de 2 km. */
        val per100: Float? get() = if (km >= 2 && netKwh > 0.1f) (netKwh / km * 100f).takeIf { it in 3f..60f } else null
    }

    private val _sinceCharge = MutableStateFlow(Totals())
    val sinceCharge: StateFlow<Totals> = _sinceCharge
    private val _sinceStart = MutableStateFlow(Totals())
    val sinceStart: StateFlow<Totals> = _sinceStart
    private val _lastKm = MutableStateFlow(Totals())
    /** Les 1 000 derniers km (tranches terminées + tranche en cours). */
    val lastKm: StateFlow<Totals> = _lastKm
    private const val SEG_KM = 10
    private const val SEG_MAX = 100

    /** Période choisie à l'écran : 0 = depuis la recharge, 1 = depuis le démarrage, 2 = 1 000 derniers km. */
    fun totals(period: Int): Totals = when (period) { 1 -> sinceStart.value; 2 -> lastKm.value; else -> sinceCharge.value }

    /** Puissances apprises (W), pour l'affichage et le journal. */
    @Volatile var accessoriesW = 250f; private set
    @Volatile var climateW = 0f; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        if (BuildConfig.MOCK) {
            _sinceCharge.value = Totals(18.4f, 3.2f, 2.1f, 0.9f, 112)
            _sinceStart.value = Totals(3.6f, 0.7f, 0.5f, 0.2f, 21)
            _lastKm.value = Totals(152.3f, 24.8f, 18.6f, 7.4f, 1000)
            return
        }
        fun load(p: String) = Totals(sp.getFloat("${p}_out", 0f), sp.getFloat("${p}_rgn", 0f), sp.getFloat("${p}_clim", 0f), sp.getFloat("${p}_acc", 0f))
        _sinceCharge.value = load("em_c")
        accessoriesW = sp.getFloat("em_base", 250f)
        // Tranches de 10 km : « out,rgn,clim,acc,km » séparées par « ; »
        val segs = ArrayDeque<Totals>()
        sp.getString("em_l_segs", "")!!.split(';').filter { it.isNotBlank() }.forEach { seg ->
            val f = seg.split(',').mapNotNull { it.toFloatOrNull() }
            if (f.size == 5) segs.addLast(Totals(f[0], f[1], f[2], f[3], f[4].toInt()))
        }
        var segCur = load("em_l")
        fun sumSegs(cur: Totals) = segs.fold(cur) { a, t ->
            Totals(a.outKwh + t.outKwh, a.regenKwh + t.regenKwh, a.climKwh + t.climKwh, a.accKwh + t.accKwh, a.km + t.km)
        }
        _lastKm.value = sumSegs(segCur)

        scope.launch {
            var last = System.currentTimeMillis()
            var chargeKm = sp.getInt("em_c_km0", -1)
            var segKm = sp.getInt("em_l_km0", -1)
            var startKm = -1
            var lastSoc = sp.getInt("em_last_soc", -1)
            var saveTick = 0
            val learned = HashMap<String, Float>()
            fun get(k: String, def: Float) = learned.getOrPut(k) { sp.getFloat(k, def) }
            fun put(k: String, v: Float) { learned[k] = v }
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val dt = now - last
                last = now
                val s = CarBridge.state.value
                val km = s.odometerKm
                val soc = s.batteryPercent

                // Nouveau démarrage : longue pause (voiture en veille) ou premier passage
                if (dt > SLEEP_GAP_MS || startKm < 0) {
                    if (km != null) startKm = km
                    _sinceStart.value = Totals()
                }
                // Recharge : le % remonte → nouvelle période « depuis la recharge »
                if (soc != null) {
                    if (lastSoc >= 0 && soc > lastSoc + 1 || chargeKm < 0 && km != null) {
                        Log.i(TAG, "nouvelle période depuis recharge ($soc %)")
                        // La période qui se termine devient une mesure de capacité (santé batterie)
                        val soc0 = sp.getInt("em_c_soc0", -1)
                        if (soc0 > 0 && lastSoc in 0 until soc0) BatteryCapacity.closePeriod(context, _sinceCharge.value.netKwh, soc0 - lastSoc)
                        _sinceCharge.value = Totals()
                        chargeKm = km ?: -1
                        sp.edit().putInt("em_c_km0", chargeKm).putInt("em_c_soc0", soc).apply()
                    }
                    if (soc != lastSoc) {
                        lastSoc = soc; sp.edit().putInt("em_last_soc", soc).apply()
                        // Capacité : énergie nette mesurée depuis la recharge / baisse du %
                        val soc0 = sp.getInt("em_c_soc0", -1)
                        if (soc0 > soc) _sinceCharge.value.let { BatteryCapacity.learn(context, it.netKwh, soc0 - soc) }
                    }
                }

                val v = CarProps.getFloat(CarProps.BMS_PACK_VOL)
                val i = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
                if (v == null || i == null || v < 100f || v > 900f || dt > 5000) continue
                if (VehicleState.isPlugged(s.chargeStatus)) continue   // en charge ou V2L : hors conso
                val p = v * i
                val h = dt / 3_600_000f
                val stopped = (s.speedKmh ?: 0f) < 0.5f
                val hvacOn = s.hvacOn == true

                // Apprentissage à l'arrêt
                val accKey = "em_acc_" + accessoryKey(s)
                val accW = get(accKey, accessoriesW)
                val climKey = "em_clim_" + climateKey(s)
                if (stopped && p in 30f..8000f) {
                    if (!hvacOn) {
                        val nv = ema(accW, p, 0.02f)
                        put(accKey, nv)
                        if (accKey == "em_acc_off") { accessoriesW = nv; put("em_base", nv) }
                    } else {
                        val c = (p - accW).coerceAtLeast(0f)
                        put(climKey, ema(get(climKey, c), c, 0.05f))
                        put("em_clim_any", ema(get("em_clim_any", c), c, 0.05f))
                    }
                }

                // Répartition
                var out = 0f; var regen = 0f; var clim = 0f; var acc = 0f
                if (p >= 0f) {
                    out = p * h
                    val cw = if (!hvacOn) 0f else if (stopped) (p - accW).coerceAtLeast(0f)
                        else get(climKey, get("em_clim_any", 1000f))
                    climateW = cw
                    val cPart = minOf(cw, p)
                    val aPart = minOf(accW, p - cPart)
                    clim = cPart * h; acc = aPart * h
                } else if (!stopped) {
                    regen = -p * h
                }
                fun add(t: Totals, k: Int?, k0: Int) = t.copy(
                    outKwh = t.outKwh + out / 1000f, regenKwh = t.regenKwh + regen / 1000f,
                    climKwh = t.climKwh + clim / 1000f, accKwh = t.accKwh + acc / 1000f,
                    km = if (k != null && k0 >= 0) (k - k0).coerceAtLeast(0) else t.km,
                )
                _sinceCharge.value = add(_sinceCharge.value, km, chargeKm)
                _sinceStart.value = add(_sinceStart.value, km, startKm)
                if (segKm < 0 && km != null) { segKm = km; sp.edit().putInt("em_l_km0", segKm).apply() }
                segCur = add(segCur, km, segKm)
                if (segCur.km >= SEG_KM && km != null) {
                    segs.addLast(segCur)
                    while (segs.size > SEG_MAX) segs.removeFirst()
                    segCur = Totals(); segKm = km
                    sp.edit().putInt("em_l_km0", segKm)
                        .putString("em_l_segs", segs.joinToString(";") { t -> "${t.outKwh},${t.regenKwh},${t.climKwh},${t.accKwh},${t.km}" }).apply()
                }
                _lastKm.value = sumSegs(segCur)

                if (++saveTick >= 30) {
                    saveTick = 0
                    val c = _sinceCharge.value
                    val e = sp.edit()
                    learned.forEach { (k, v) -> e.putFloat(k, v) }
                    e.putFloat("em_c_out", c.outKwh).putFloat("em_c_rgn", c.regenKwh)
                        .putFloat("em_c_clim", c.climKwh).putFloat("em_c_acc", c.accKwh)
                    e.putFloat("em_l_out", segCur.outKwh).putFloat("em_l_rgn", segCur.regenKwh)
                        .putFloat("em_l_clim", segCur.climKwh).putFloat("em_l_acc", segCur.accKwh).apply()
                }
            }
        }
    }

    /** Réglage de clim : ventilation (tranches), chauffage / froid / ventilation seule, dégivrage. */
    private fun climateKey(s: VehicleState): String {
        val fan = s.fanSpeed ?: 0
        val fanBucket = when { fan >= 15 -> "max"; fan >= 7 -> "hi"; fan >= 4 -> "mid"; else -> "lo" }
        val set = s.driverTemp; val outside = s.outsideTemp
        val mode = when {
            set == null || outside == null -> "x"
            set > outside + 2 -> "heat"
            set < outside - 2 -> "cool"
            else -> "vent"
        }
        val def = (if (s.defrostFront == true) "F" else "") + (if (s.defrostRear == true) "R" else "")
        return "$mode-$fanBucket-$def"
    }

    /** Équipements électriques visibles : feux, essuie-glaces. "off" = rien d'allumé. */
    private fun accessoryKey(s: VehicleState): String {
        val parts = buildList {
            // Feux : VEH_SIDE_LGHT (0 = éteints), sinon mode jour / nuit du système (3 = feux allumés),
            // relevés sur la voiture le 2026-10-14 ; HEADLIGHTS_STATE n'existe pas sur ce modèle.
            val side = CarProps.readAny(557880099)
            val night = CarProps.readAny(289442317)
            if (side.isNotEmpty() && side != "0" && side != "false" || side.isEmpty() && night == "3") add("lights")
            if (CarProps.readAny(557897511).let { it.isNotEmpty() && it != "0" }) add("wiper")
            if (s.defrostRear == true && s.hvacOn != true) add("rear")
        }
        return if (parts.isEmpty()) "off" else parts.joinToString("+")
    }

    private fun ema(old: Float, new: Float, k: Float) = old + (new - old) * k
}
