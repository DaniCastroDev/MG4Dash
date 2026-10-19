package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.mg4dash.BuildConfig
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accès véhicule par la voie « carapi » de la famille A9 (SWI69) :
 * CarAdapterService (com.saicmotor.caradapter) → queryClient(code) → un binder par domaine.
 *
 * Les états sont lus par interrogation périodique plutôt que par callbacks : moins de code
 * AIDL à maintenir, et la reprise est automatique si un service SAIC redémarre.
 * Les commandes clim suivent le choix 2A : une bascule par appui, l'affichage suit la lecture.
 * Aucune écriture n'est faite sur le mode de conduite ni la récupération : lecture seule.
 */
object CarBridge {
    private const val TAG = "Car"
    private const val ADAPTER_PKG = "com.saicmotor.caradapter"
    private const val ADAPTER_ACTION = "com.saicmotor.caradapter.CarAdapterService"
    private const val CODE_GENERAL = 1
    private const val CODE_EV = 4
    private const val CODE_HVAC = 7
    private const val CODE_VS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    @Volatile private var adapter: AdapterService? = null
    @Volatile private var hvac: HvacService? = null
    @Volatile private var ev: EvService? = null
    @Volatile private var vs: VehicleSettingService? = null
    @Volatile private var general: GeneralService? = null
    @Volatile private var bound = false

    private var appContext: Context? = null
    /** Vues du cockpit à l'écran (activité, franja sur Android Auto) ; vide → relecture lente. */
    private val visible: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Émulateur : commande simulée (adb : --es mock drive|unplug|startCharge…). */
    fun mock(label: String) { if (BuildConfig.MOCK) MockCar.command(label, _state) }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (BuildConfig.MOCK) {
            MockCar.start(scope, _state)
            return
        }
        // Firmware A9 (SWI69/131/132) : service caradapter. Ancien SDK (SWI68/165/133) : gestionnaires
        // SAIC du launcher d'origine. Firmware inconnu : on tente les deux.
        if (Firmware.family != Firmware.Family.LEGACY) bind()
        HvacOverlay.connect(context)
        if (Firmware.family != Firmware.Family.A9) LegacyCar.init(context)
        Log.i(TAG, "firmware ${Firmware.version} (${Firmware.gen}, famille ${Firmware.family})")
        scope.launch { pollLoop() }
    }

    /** Démarrage / arrêt de la charge et du V2L disponibles (service EV de la voie A9). */
    val chargeControl: Boolean get() = BuildConfig.MOCK || ev != null || LegacyCar.chargingReady ||
        CarProps.readIntArea(CHRG_CTRL, 0x1000000) != null   // secours : propriété CHRG_CTRL (testée sur SWI69)

    private const val CHRG_CTRL = 0x2140f412          // 1 = démarrer, 2 = arrêter la charge (testé le 2026-10-18)
    private const val ELEC_CLCK_CTRL = 0x2140f413     // pistolet : 1 = verrouillé, 2 = libre
    private const val VEHICLE_LOCK = 289421312        // 0x11403800 : portes ; écriture 1 = verrouiller, 2 = déverrouiller

    /** Pistolet de charge verrouillé ? null si la voiture ne le dit pas. */
    fun chargeLockLocked(): Boolean? = CarProps.readIntArea(ELEC_CLCK_CTRL, 0x1000000)?.let { if (it == 1) true else if (it == 2 || it == 0) false else null }

    /** Verrouille / déverrouille le pistolet : service EV (A9), sinon la propriété directement. */
    fun toggleChargeLock() {
        val target = if (chargeLockLocked() == true) 2 else 1
        if (BuildConfig.MOCK) return
        scope.launch {
            val e = ev
            if (e != null) try { e.elecClckCtrl(target); Log.i(TAG, "pistolet ← $target (service EV)") } catch (ex: Exception) { Log.w(TAG, "pistolet : ${ex.message}") }
            else CarProps.setIntArea(ELEC_CLCK_CTRL, 0x1000000, target, "pistolet")
        }
    }

    private const val POWER_MODE = 557897487   // 0x2140d70f : 2 = éteindre (SystemUI d'origine, CarComfortableController)

    /** Éteint la voiture, comme le bouton « Éteindre » du panneau rapide d'origine. */
    fun powerOffCar(): Boolean {
        // Jamais en roulant : vitesse inconnue ou au-dessus de 1 km/h = refus
        val v = state.value.speedKmh
        if (v == null || v > 1f) { Log.w(TAG, "extinction refusée, vitesse $v"); return false }
        if (BuildConfig.MOCK) return true
        CarProps.setIntArea(POWER_MODE, 0, 2, "extinction de la voiture")
        return true
    }

    /** Portes verrouillées ? (SystemUI d'origine : 0 = ouvertes, 1 à 4 = verrouillées). */
    fun doorsLocked(): Boolean? = CarProps.readIntArea(VEHICLE_LOCK, 0x1000000)?.let { it in 1..4 }

    /** Verrouille ou déverrouille les portes, comme la tuile du panneau rapide d'origine. Rend l'action faite. */
    fun toggleDoors(): Boolean? {
        val locked = doorsLocked() ?: return null
        if (BuildConfig.MOCK) return !locked
        CarProps.setIntArea(VEHICLE_LOCK, 0x1000000, if (locked) 2 else 1, if (locked) "portes : déverrouiller" else "portes : verrouiller")
        return !locked
    }

    /** Services qui répondent, pour l'écran Compatibilité. */
    fun probe(): List<Pair<String, String>> = listOf(
        "caradapter" to if (adapter != null) "ok" else "absent",
        "hvac (7)" to if (hvac != null) "ok" else "absent",
        "ev (4)" to if (ev != null) "ok" else "absent",
        "vehiclesetting (8)" to if (vs != null) "ok" else "absent",
        "general (1)" to if (general != null) "ok" else "absent",
        "AirConditionManager" to LegacyCar.probe(),
        "VehicleChargingManager" to LegacyCar.probeCharging(),
    )

    /** Une activité du launcher passe au premier plan ou le quitte. */
    fun setForeground(foreground: Boolean) = setVisible("activity", foreground)

    /**
     * Une vue du cockpit est à l'écran : l'accueil, une page ou la franja posée sur Android Auto
     * (avant, Android Auto au premier plan faisait passer toute la relecture au rythme lent).
     */
    fun setVisible(who: String, on: Boolean) { if (on) visible.add(who) else visible.remove(who) }

    private fun bind() {
        val ctx = appContext ?: return
        if (bound) return
        val intent = Intent(ADAPTER_ACTION).setPackage(ADAPTER_PKG)
        try {
            bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService CarAdapter → $bound")
        } catch (e: Exception) {
            Log.w(TAG, "bindService CarAdapter impossible : ${e.message}")
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            adapter = binder?.let { AdapterService(it) }
            Log.i(TAG, "CarAdapter connecté")
            scope.launch { resolveClients() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CarAdapter déconnecté")
            adapter = null; hvac = null; ev = null; vs = null; general = null
            _state.value = _state.value.copy(connected = false)
        }
    }

    private fun query(code: Int): IBinder? = try {
        adapter?.queryClient(code)
    } catch (e: Exception) {
        Log.w(TAG, "queryClient($code) : ${e.message}"); null
    }

    private fun resolveClients() {
        if (hvac?.alive != true) hvac = query(CODE_HVAC)?.let { HvacService(it) }
        if (ev?.alive != true) ev = query(CODE_EV)?.let { EvService(it) }
        if (vs?.alive != true) vs = query(CODE_VS)?.let { VehicleSettingService(it) }
        if (general?.alive != true) {
            general = query(CODE_GENERAL)?.let { GeneralService(it) }
            // Au démarrage, écran rallumé : si MG4Dash l'avait éteint puis a été relancé, rien d'autre ne le rallume
            if (general != null && !displayChecked) { displayChecked = true; setDisplay(true) }
        }
    }

    // Rythme de relecture. Cockpit à l'écran : clim 0,25 s ; batterie, autonomie, charge, vitesse 3 s.
    // Rien du cockpit à l'écran : clim 5 s, le reste 15 s. Une valeur inchangée ne réveille pas l'écran
    // (le StateFlow ne publie que les changements). La puissance instantanée est lue à part, 0,2 s,
    // par les vues qui l'affichent.
    private const val HVAC_FAST_MS = 250L
    private const val HVAC_SLOW_MS = 5_000L
    private const val ENERGY_FAST_MS = 3_000L
    private const val ENERGY_SLOW_MS = 15_000L
    private const val RESOLVE_MS = 5_000L

    private suspend fun pollLoop() {
        var lastHvac = 0L; var lastEnergy = 0L; var lastResolve = -RESOLVE_MS
        while (scope.isActive) {
            val fast = visible.isNotEmpty()
            val now = android.os.SystemClock.elapsedRealtime()
            try {
                if (now - lastResolve >= RESOLVE_MS) {
                    lastResolve = now
                    if (adapter == null) { if (Firmware.family != Firmware.Family.LEGACY) bind() } else resolveClients()
                }
                if (now - lastHvac >= if (fast) HVAC_FAST_MS else HVAC_SLOW_MS) { lastHvac = now; refreshHvac(); refreshHeating() }
                if (now - lastEnergy >= if (fast) ENERGY_FAST_MS else ENERGY_SLOW_MS) { lastEnergy = now; refreshEnergy() }
            } catch (e: Exception) {
                Log.w(TAG, "poll : ${e.message}")
            }
            // Au rythme lent on se réveille quand même chaque seconde : le retour à l'écran est pris en compte vite.
            delay(if (fast) HVAC_FAST_MS else 1_000L)
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    // Valeur réglée à l'instant : la voiture met un moment à l'appliquer, et la relecture (chaque
    // fraction de seconde) réaffichait l'ancienne valeur entre-temps (« la valeur hésite », issue #6). On garde
    // l'affichage sur la nouvelle valeur jusqu'à ce que la voiture la confirme, 3 s au plus.
    @Volatile private var holdTemp: Pair<Float, Long>? = null
    @Volatile private var holdFan: Pair<Int, Long>? = null
    private const val HOLD_MS = 3000L

    private fun held(s: VehicleState): VehicleState {
        val now = System.currentTimeMillis()
        var r = s
        holdTemp?.let { (v, until) ->
            if (now > until || s.driverTemp == v) holdTemp = null else r = r.copy(driverTemp = v)
        }
        holdFan?.let { (v, until) ->
            if (now > until || s.fanSpeed == v) holdFan = null else r = r.copy(fanSpeed = v)
        }
        holdDrive?.let { (v, until) ->
            if (now > until || s.driveMode == v) holdDrive = null else r = r.copy(driveMode = v)
        }
        return r
    }
    // Sièges et volant : la voiture peut avancer cran par cran (plusieurs secondes), d'où une tenue plus longue.
    @Volatile private var holdDrive: Pair<Int, Long>? = null

    // Mise en marche de la clim (par n'importe quelle commande) : la SystemUI ouvre son panneau plein écran
    // juste après ; on le referme si l'option est active. Repli quand le rappel du SDK (LegacyCar) manque.
    @Volatile private var lastHvacOn: Boolean? = null

    private fun watchHvacPower() {
        val on = _state.value.hvacOn ?: return
        val was = lastHvacOn
        lastHvacOn = on
        if (was != false || !on || HvacOverlay.eventDriven) return
        HvacOverlay.suppress("relecture")
    }

    // ── Sièges et volant chauffants (propriétés clim, zone 0x75, relevées par MG4Control) ──
    private const val SEAT_HEAT_L = 0x15402513
    private const val SEAT_HEAT_R = 0x15402514
    private const val WHEEL_HEAT = 0x1540253a
    private const val HEAT_AREA = 0x75
    @Volatile private var heatHoldUntil = 0L

    private fun refreshHeating() {
        if (BuildConfig.MOCK || System.currentTimeMillis() < heatHoldUntil) return
        val s = _state.value
        val l = CarProps.readIntArea(SEAT_HEAT_L, HEAT_AREA)?.takeIf { it in 0..3 }
        val r = CarProps.readIntArea(SEAT_HEAT_R, HEAT_AREA)?.takeIf { it in 0..3 }
        val w = CarProps.readIntArea(WHEEL_HEAT, HEAT_AREA)?.takeIf { it >= 0 }?.let { if (it > 0) 1 else 0 }
        if (l != s.seatHeatL || r != s.seatHeatR || w != s.wheelHeat) _state.value = s.copy(seatHeatL = l, seatHeatR = r, wheelHeat = w)
        // Une lecture ratée (démarrage, voiture en veille) ne doit plus faire disparaître les boutons (#19)
        if (l != null) appContext?.let { c ->
            val sp = app.mg4dash.system.Prefs(c).raw
            if (!sp.getBoolean("heat_seen", false)) sp.edit().putBoolean("heat_seen", true).apply()
        }
    }

    /**
     * Siège chauffant : la voiture ne connaît qu'un « cran » (valeur 1) qui fait DESCENDRE
     * (éteint → 3 → 2 → 1 → éteint, établi sur véhicule par MG4Control). Affichage anticipé, puis relecture.
     */
    fun stepSeatHeat(left: Boolean) {
        val cur = (if (left) _state.value.seatHeatL else _state.value.seatHeatR) ?: 0
        val next = (cur + 3) % 4
        _state.value = if (left) _state.value.copy(seatHeatL = next) else _state.value.copy(seatHeatR = next)
        heatHoldUntil = System.currentTimeMillis() + 2_500
        if (BuildConfig.MOCK) return
        scope.launch {
            CarProps.setIntArea(if (left) SEAT_HEAT_L else SEAT_HEAT_R, HEAT_AREA, 1, "siège chauffant ${if (left) "G" else "D"}")
            delay(2_600); refreshHeating()
        }
    }

    /** Volant chauffant : une bascule (valeur 1), comme le bouton d'origine. */
    fun toggleWheelHeat() {
        val on = _state.value.wheelHeat == 1
        _state.value = _state.value.copy(wheelHeat = if (on) 0 else 1)
        heatHoldUntil = System.currentTimeMillis() + 2_500
        if (BuildConfig.MOCK) return
        scope.launch {
            CarProps.setIntArea(WHEEL_HEAT, HEAT_AREA, 1, "volant chauffant")
            delay(2_600); refreshHeating()
        }
    }

    /** Sièges / volant chauffants à afficher : réglage, sinon versions 64 / 77 kWh dont la voiture les expose. */
    fun heatingShown(context: Context): Boolean {
        val p = app.mg4dash.system.Prefs(context)
        return when (p.heatedSeats) {
            1 -> true
            2 -> false
            else -> BuildConfig.MOCK && p.batteryModel != 51 || p.batteryModel in listOf(64, 77) && (_state.value.seatHeatL != null || p.raw.getBoolean("heat_seen", false))
        }
    }

    private fun refreshHvac() {
        refreshHvacState()
        watchHvacPower()
    }

    private fun refreshHvacState() {
        val h = hvac ?: run { if (Firmware.family != Firmware.Family.A9) _state.value = held(LegacyCar.readHvac(_state.value)); return }
        _state.value = _state.value.copy(
            connected = true,
            hvacOn = safe { h.hvacPowerStatus },
            acOn = safe { h.acStatus },
            autoOn = safe { h.autoStatus },
            recirc = safe { h.airCirculationStatus }?.takeIf { it >= 0 },
            fanSpeed = safe { h.fanSpeed }?.takeIf { it >= 0 },
            fanDirection = safe { h.fanDirection }?.takeIf { it in 0..7 },
            driverTemp = safe { h.driverTemperature }?.takeIf { !it.isNaN() && it > 0f },
            outsideTemp = safe { h.outSideTemperature }?.takeIf { !it.isNaN() && it > -60f && it < 70f },
            defrostFront = safe { h.frontDefrostStatus },
            defrostRear = safe { h.rearDefrostStatus },
            ecoOn = safe { h.ecoStatus },
        ).let { held(it) }
    }

    private fun refreshEnergy() {
        val s = _state.value
        val e = ev
        val v = vs
        val g = general
        _state.value = s.copy(
            connected = adapter != null,
            batteryPercent = e?.let { safe { it.chargePercentChange } }?.takeIf { it in 0..100 } ?: s.batteryPercent,
            rangeKm = e?.let { safe { it.currentRange } }?.takeIf { it in 0..2000 } ?: s.rangeKm,
            chargeStatus = e?.let { safe { it.chargeStatus } } ?: s.chargeStatus,
            chargeRemainMin = e?.let { safe { it.chargingRemainTime } } ?: s.chargeRemainMin,
            chargeCtrl = e?.let { safe { it.chargeCtrlStatus } } ?: s.chargeCtrl,
            dischargeCtrl = e?.let { safe { it.disChargeCtrl } } ?: s.dischargeCtrl,
            dischargeRemainMin = e?.let { safe { it.disChargingRemainTime } } ?: s.dischargeRemainMin,
            driveMode = v?.let { safe { it.drivingMode } } ?: s.driveMode,
            regenLevel = v?.let { safe { it.regenerativeLevel } } ?: s.regenLevel,
            onePedal = v?.let { safe { it.signalPedalState } } ?: s.onePedal,
            steeringMode = v?.let { safe { it.steeringMode } } ?: s.steeringMode,
            brakePedalMode = v?.let { safe { it.brakePedalMode } } ?: s.brakePedalMode,
            enduranceMode = v?.let { safe { it.enduranceMode } } ?: s.enduranceMode,
            lasMode = v?.let { safe { it.lasMode } } ?: s.lasMode,
            fcwState = v?.let { safe { it.fcwState } } ?: s.fcwState,
            fcwBrakeMode = v?.let { safe { it.fcwAutoBrakeMode } } ?: s.fcwBrakeMode,
            overSpeedSound = v?.let { safe { it.overSpeedSoundMode } } ?: s.overSpeedSound,
            speedLimitSound = v?.let { safe { it.speedLimitSoundMode } } ?: s.speedLimitSound,
            odometerKm = g?.let { safe { it.totalMileage } }?.takeIf { it > 0 } ?: s.odometerKm,
            speedKmh = g?.let { safe { it.latestSpeed } } ?: s.speedKmh,
        )
        // Dixième de % (BMS) : lu à part, sur toutes les voies d'accès
        if (!BuildConfig.MOCK) {
            val precise = (CarProps.getFloat(0x2160f404) ?: CarProps.readFloatArea(0x2160f404, 0x1000000))?.takeIf { it in 0.1f..100f }
            if (precise != _state.value.socPrecise) _state.value = _state.value.copy(socPrecise = precise)
        }
        if (e == null || v == null || g == null) refreshFromProps()
        if (e == null && Firmware.family != Firmware.Family.A9) _state.value = LegacyCar.readCharging(_state.value)
    }

    /**
     * Repli commun à tous les firmwares : mesures du CarPropertyManager, mêmes identifiants sur
     * SWI133 (relevés MG4Control) et SWI69. Ne remplit que ce que les services n'ont pas donné.
     */
    private fun refreshFromProps() {
        val s = _state.value
        val area = 0x1000000
        fun i(id: Int) = CarProps.readIntArea(id, area)
        fun f(id: Int) = CarProps.readFloatArea(id, area)?.takeIf { it.isFinite() && it != 82.3f && it != 511.5f }
        val soc = f(0x2160f404)?.takeIf { it in 0f..100f }?.let { Math.round(it) }
        val range = i(0x2140f41c)?.takeIf { it in 1..1500 }
        // État de charge BMS : même échelle que le service EV (10 = charge rapide, relevé sur SWI133 en
        // charge DC, issue #13) ; 0 câble branché = connecté sans charger.
        val raw = i(0x2140f409)
        val dc = i(0x2140f43f)?.takeIf { it != 0 && it != 1023 } != null
        val ac = i(0x2140f43e)?.takeIf { it != 0 && it != 1023 } != null
        val status = when { raw != null && raw in 1..15 -> raw; dc || ac -> 7; else -> 0 }
        // Mode de conduite (propriété) → échelle A9 (2 éco, 3 normal, 4 sport, 6 neige, 7 personnalisé).
        // SWI68 donne directement cette échelle (appli Réglages d'origine : 6, 2, 3, 4, 7) ; la table
        // 0 neige / 1 éco reste pour les firmwares qui l'utilisent. Les valeurs ne se chevauchent pas.
        val dm = when (val m = i(0x2140a17c)) { 2, 3, 4, 6, 7 -> m; 0 -> 6; 1 -> 2; else -> null }
        // Sans service EV, les propriétés sont la seule source : on les reprend à chaque passage
        // (avant, la première valeur lue restait affichée : % et autonomie figés, issue #13).
        val noEv = ev == null
        /** Valeur de la propriété si le service réglages manque, sinon celle du service (propriété en repli). */
        fun fresh(prop: Int?, cur: Int?): Int? = if (vs == null) prop ?: cur else cur ?: prop
        _state.value = s.copy(
            connected = s.connected || soc != null,
            batteryPercent = if (noEv) soc ?: s.batteryPercent else s.batteryPercent ?: soc,
            rangeKm = if (noEv) range ?: s.rangeKm else s.rangeKm ?: range,
            chargeStatus = if (ev == null) status else s.chargeStatus,
            odometerKm = s.odometerKm ?: i(0x21401566)?.takeIf { it > 0 },
            driveMode = if (vs == null) dm ?: s.driveMode else s.driveMode ?: dm,
            // Sans service réglages (SWI68, #19), la propriété est relue à chaque passage : avant, la
            // première valeur restait affichée et un changement de régénération ne se voyait pas.
            regenLevel = fresh(i(0x2140a191)?.takeIf { it in 0..7 }, s.regenLevel),
            // Aides à la conduite (relevé sur SWI69 : mêmes échelles que le service réglages)
            lasMode = fresh(i(0x2140a104)?.takeIf { it in 1..5 }, s.lasMode),
            fcwState = fresh(i(0x2140a108)?.takeIf { it in 1..2 }, s.fcwState),
            overSpeedSound = fresh(i(0x2140a16c)?.takeIf { it in 0..1 }, s.overSpeedSound),
            // Vitesse sans service général (SWI68, #19) : PERF_VEHICLE_SPEED, comme MG4Control sur
            // tous les firmwares ; déjà en km/h chez SAIC malgré la spec AOSP, négative en marche arrière.
            speedKmh = if (general != null) s.speedKmh else
                (CarProps.readFloatArea(0x11600207, 0x1000000) ?: CarProps.readFloatArea(0x11600207, 0))
                    ?.takeIf { !it.isNaN() }?.let { Math.abs(it) }?.takeIf { it < 300f } ?: s.speedKmh,
            onePedal = fresh(i(0x2140a193)?.takeIf { it in 0..1 } ?: i(0x2140a191)?.let { if (it == 6) 1 else null }, s.onePedal),
            // Verrou du pistolet (0x2140f413, zone globale : même lecture que chargeLockLocked)
            chargeLock = CarProps.readIntArea(CarProps.CHRG_LOCK, 0x1000000)?.takeIf { it in 0..2 } ?: s.chargeLock,
        ).let { held(it) }
    }

    /** Vitesse (PERF_VEHICLE_SPEED, en km/h sur SWI68 d'après evhardware) ; null si illisible. */
    private fun speedFromProps(): Float? =
        (CarProps.readFloatArea(PERF_VEHICLE_SPEED, 0x1000000) ?: CarProps.readFloatArea(PERF_VEHICLE_SPEED, 0))
            ?.takeIf { it.isFinite() }?.let { Math.abs(it) }
    private const val PERF_VEHICLE_SPEED = 0x11600207

    // ── Commandes clim ────────────────────────────────────────────────────────
    // Exécutées hors thread principal, puis relecture rapide pour que l'UI suive l'état réel.

    /** Commande clim des firmwares ancien SDK (bloquante, donc hors du fil principal). */
    private fun legacy(label: String, action: () -> Boolean) {
        scope.launch {
            val ok = try { action() } catch (e: Exception) { Log.w(TAG, "SET $label : ${e.message}"); false }
            Log.i(TAG, "SET $label (ancien SDK) → $ok")
            delay(300); refreshHvac()
            delay(700); refreshHvac()
        }
    }

    private val useLegacy: Boolean get() = !BuildConfig.MOCK && hvac == null && LegacyCar.ready

    private fun command(label: String, action: (HvacService) -> Unit) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        scope.launch {
            val h = hvac ?: run { Log.w(TAG, "$label ignoré : clim non connectée"); return@launch }
            try {
                action(h)
                Log.i(TAG, "SET $label → ok")
            } catch (e: Exception) {
                Log.w(TAG, "SET $label → échec : ${e.message}")
            }
            delay(300); refreshHvac()
            delay(700); refreshHvac()
        }
    }

    // ── Recharge et V2L : mêmes commandes que les boutons du launcher d'origine ──
    // chargeCtrl(1) = démarrer, chargeCtrl(2) = arrêter ; idem disChargeCtrl pour le V2L.

    private fun evCommand(label: String, action: (EvService) -> Unit) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        scope.launch {
            val e = ev ?: run {
                if (LegacyCar.chargingReady) {
                    val ok = when (label) {
                        "startCharge" -> LegacyCar.chargeCtrl(1)
                        "stopCharge" -> LegacyCar.chargeCtrl(2)
                        "startV2L" -> LegacyCar.dischargeCtrl(1)
                        else -> LegacyCar.dischargeCtrl(2)
                    }
                    Log.i(TAG, "EV $label (ancien SDK) → $ok")
                    delay(1500); refreshEnergy(); delay(3000); refreshEnergy()
                } else if (label == "startCharge" || label == "stopCharge") {
                    // Secours sans service EV ni SDK : la propriété CHRG_CTRL (même effet, testé sur SWI69)
                    CarProps.setIntArea(CHRG_CTRL, 0x1000000, if (label == "startCharge") 1 else 2, label)
                    delay(1500); refreshEnergy(); delay(3000); refreshEnergy()
                } else Log.w(TAG, "$label ignoré : service EV non connecté")
                return@launch
            }
            try { action(e); Log.i(TAG, "EV $label → envoyé") } catch (ex: Exception) { Log.w(TAG, "EV $label → échec : ${ex.message}") }
            delay(1500); refreshEnergy()
            delay(3000); refreshEnergy()
        }
    }

    // ── Écran éteint et ESC (gestes de la barre verticale, issue #12) ──

    @Volatile private var displayChecked = false

    /** Équipement chauffant déclaré par la voiture (A9) : sièges, volant ; null si inconnu. */
    fun heatingConfig(): Pair<Int?, Int?> {
        val b = try { adapter?.queryClient(2) } catch (_: Exception) { null } ?: return null to null
        val c = ConfigService(b)
        return (try { c.seatHeatingConfig } catch (_: Exception) { null }) to (try { c.steeringWheelHeatConfig } catch (_: Exception) { null })
    }

    /** Allume ou éteint l'écran central par le service véhicule ; false si le service ne répond pas. */
    fun setDisplay(on: Boolean): Boolean {
        if (BuildConfig.MOCK) return true
        val g = general ?: run { Log.w(TAG, "écran : service général absent"); return false }
        return try { g.setDisplayState(on); Log.i(TAG, "écran ${if (on) "allumé" else "éteint"}"); true }
        catch (e: Exception) { Log.w(TAG, "écran : ${e.message}"); false }
    }

    /**
     * ESC actif ? 1 = actif, 0 = coupé (VEHICLE_ESP, relevé sur la voiture le 2026-10-18).
     * null si la voiture ne le dit pas.
     */
    fun escActive(): Boolean? {
        if (BuildConfig.MOCK) return mockEsc
        // Relevé sur la voiture : l'état suit VEHICLE_ESP lui-même (1 = actif, 0 = coupé), comme TCS_OPNG_MD
        val v = CarProps.readIntArea(VEHICLE_ESP, 0x1000000) ?: CarProps.readIntArea(TCS_OPNG_MD, 0x1000000)
            ?: return if (Firmware.family == Firmware.Family.LEGACY) LegacyCar.escActive() else null
        return v != 0
    }
    private var mockEsc = true

    /** Bascule l'ESC : la commande SAIC est un appui (valeur 1), comme l'interrupteur du menu MG. */
    fun toggleEsc() {
        if (BuildConfig.MOCK) { mockEsc = !mockEsc; return }
        // Ancien SDK sans la propriété : VehicleControlManager (SWI68/165)
        if (CarProps.readIntArea(VEHICLE_ESP, 0x1000000) == null && Firmware.family == Firmware.Family.LEGACY) {
            Log.i(TAG, "ESC (ancien SDK) : ${LegacyCar.toggleEsc()}"); return
        }
        val ok = CarProps.setIntArea(VEHICLE_ESP, 0x1000000, 1, "ESC (appui)")
        Log.i(TAG, "ESC : commande envoyée → $ok, état avant : ${escActive()}")
    }
    private const val VEHICLE_ESP = 289421317       // 0x11403805 : commande ESC (setDrivingEpsMode du menu MG)
    private const val TCS_OPNG_MD = 289421343       // 0x1140381f : 1 = ESC actif, 0 = coupé (repli)

    // ── Régénération : touchée depuis la jauge KERS (accueil, page Véhicule) ──

    /** Niveau de régénération 0..3 ; la conduite à une pédale est d'abord coupée si elle est active. */
    fun setRegenLevel(level: Int) {
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(regenLevel = level, onePedal = 0); return }
        scope.launch {
            val v = vs
            try {
                if (_state.value.onePedal == 1) {
                    if (v != null) v.setSignalPedalState(0) else CarProps.setInt(0x2140a193, 0, "une pédale")
                    delay(300)
                }
                if (v != null) { v.setRegenerativeLevel(level); Log.i(TAG, "SET régénération = $level") }
                else CarProps.setInt(0x2140a191, level, "régénération")
            } catch (e: Exception) { Log.w(TAG, "SET régénération : ${e.message}") }
            delay(500); refreshEnergy()
            delay(1500); refreshEnergy()
        }
    }

    /** Conduite à une pédale : bascule (appui long sur la jauge KERS). */
    fun toggleOnePedal() {
        val target = if (_state.value.onePedal == 1) 0 else 1
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(onePedal = target); return }
        scope.launch {
            try {
                val v = vs
                if (v != null) { v.setSignalPedalState(target); Log.i(TAG, "SET une pédale = $target") }
                else CarProps.setInt(0x2140a193, target, "une pédale")
            } catch (e: Exception) { Log.w(TAG, "SET une pédale : ${e.message}") }
            delay(500); refreshEnergy()
            delay(1500); refreshEnergy()
        }
    }

    // ── Verrou du câble de charge (bouton cadenas du launcher d'origine) ──
    // Même règle que ChargingViewModel d'origine : 0 = indisponible (rien n'est envoyé),
    // 2 déverrouillé → verrouiller (1), sinon → déverrouiller (2). La trappe ne s'ouvre pas par logiciel.

    /** Bouton proposé : la voiture dit où en est le verrou. */
    val cableLockControl: Boolean get() = _state.value.chargeLock.let { it == 1 || it == 2 }

    fun toggleCableLock() {
        val cur = _state.value.chargeLock
        if (cur != 1 && cur != 2) { Log.w(TAG, "verrou câble ignoré : état $cur"); return }
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(chargeLock = if (cur == 2) 1 else 2); return }
        toggleChargeLock()
        scope.launch { delay(1500); refreshEnergy(); delay(3000); refreshEnergy() }
    }

    // ── Mode de conduite : écrit seulement à l'arrêt (vitesse lue = 0), refusé si la vitesse est illisible ──

    enum class Write { SENT, MOVING, SPEED_UNKNOWN, UNAVAILABLE }

    val driveModes = listOf(2, 3, 4, 6)   // éco, normal, sport, neige (personnalisé : réglé dans l'appli d'origine)

    fun setDriveMode(mode: Int): Write {
        if (mode !in driveModes && mode != 7) return Write.UNAVAILABLE
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(driveMode = mode); return Write.SENT }
        val speed = _state.value.speedKmh?.let { Math.abs(it) } ?: speedFromProps() ?: return Write.SPEED_UNKNOWN
        if (speed >= 0.5f) return Write.MOVING
        holdDrive = mode to System.currentTimeMillis() + HOLD_MS
        _state.value = _state.value.copy(driveMode = mode)
        scope.launch {
            val ok = (Firmware.family != Firmware.Family.A9 && LegacyCar.driveMode(mode)) ||
                CarProps.setIntArea(0x2140a17c, 0x1000000, mode, "mode de conduite")
            Log.i(TAG, "SET mode de conduite = $mode → $ok")
            delay(800); refreshEnergy(); delay(1500); refreshEnergy()
        }
        return Write.SENT
    }

    // ── Sièges et volant chauffants : voie des propriétés du dépôt d'origine (stepSeatHeat /
    // toggleWheelHeat, testée sur SWI68). Ici, seulement ce que le cockpit ajoute par-dessus. ──

    /** Boutons proposés : même règle que l'écran classique (réglage, sinon versions équipées détectées). */
    val heatControl: Boolean get() = appContext?.let { heatingShown(it) } ?: BuildConfig.MOCK

    /** Appui sur un siège, comme le bouton d'origine : un cran vers le bas (arrêt → 3 → 2 → 1 → arrêt). */
    fun cycleSeatHeat(left: Boolean) = stepSeatHeat(left)

    /**
     * Niveau choisi directement (0–3). La voiture ne connaît que le cran descendant : on en envoie
     * autant qu'il faut, espacés, en affichant tout de suite le niveau visé.
     */
    fun setSeatHeat(left: Boolean, level: Int) {
        val target = level.coerceIn(0, 3)
        val cur = (if (left) _state.value.seatHeatL else _state.value.seatHeatR) ?: 0
        val presses = (cur - target + 4) % 4
        if (presses == 0) return
        _state.value = if (left) _state.value.copy(seatHeatL = target) else _state.value.copy(seatHeatR = target)
        heatHoldUntil = System.currentTimeMillis() + presses * SEAT_STEP_MS + 2_500
        if (BuildConfig.MOCK) return
        scope.launch {
            repeat(presses) { k ->
                if (k > 0) delay(SEAT_STEP_MS)
                CarProps.setIntArea(if (left) SEAT_HEAT_L else SEAT_HEAT_R, HEAT_AREA, 1, "siège chauffant ${if (left) "G" else "D"} (cran ${k + 1}/$presses)")
            }
            delay(2_600); refreshHeating()
        }
    }
    private const val SEAT_STEP_MS = 600L

    fun toggleSteeringHeat() = toggleWheelHeat()

    fun startCharge() = evCommand("startCharge") { it.chargeCtrl(1) }
    fun stopCharge() = evCommand("stopCharge") { it.chargeCtrl(2) }
    fun startV2L() = evCommand("startV2L") { it.disChargeCtrl(1) }
    fun stopV2L() = evCommand("stopV2L") { it.disChargeCtrl(2) }

    /** Relecture immédiate de l'énergie (après un réglage de recharge). */
    fun refreshEnergyNow() { scope.launch { refreshEnergy() } }

    // ── Bascules : boucle « lire, basculer, relire » jusqu'à l'état visé (choix du propriétaire) ──
    // Les commandes switch…() du service SAIC n'ont pas d'argument : elles avancent d'un cran.
    // Un appui fixe donc un état CIBLE (l'inverse de l'état lu), puis on bascule et on relit
    // jusqu'à l'atteindre, avec un nombre de crans borné pour ne jamais tourner en rond.

    private val cycleLock = Mutex()

    private fun cycleTo(
        label: String,
        target: Int,
        maxSteps: Int,
        read: (HvacService) -> Int?,
        advance: (HvacService) -> Unit,
    ) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        scope.launch {
            cycleLock.withLock {
                val h = hvac ?: run { Log.w(TAG, "$label ignoré : clim non connectée"); return@launch }
                var steps = 0
                while (true) {
                    val cur = safe { read(h) }
                    if (cur == target) { Log.i(TAG, "SET $label=$target atteint ($steps cran(s))"); break }
                    if (cur == null) { Log.w(TAG, "SET $label=$target : état illisible"); break }
                    if (steps >= maxSteps) { Log.w(TAG, "SET $label=$target NON atteint (lu $cur)"); break }
                    try { advance(h) } catch (e: Exception) { Log.w(TAG, "SET $label : ${e.message}"); break }
                    steps++
                    delay(400)
                }
                refreshHvac()
            }
        }
    }

    private fun Boolean?.asInt(): Int? = this?.let { if (it) 1 else 0 }
    private fun toggleTarget(cur: Boolean?): Int = if (cur == true) 0 else 1

    fun togglePower() = if (useLegacy) legacy("power") { LegacyCar.power(_state.value.hvacOn != true) } else cycleTo("power", toggleTarget(_state.value.hvacOn), 2,
        { it.hvacPowerStatus.asInt() }, { it.switchHvacPowerStatus() })
    fun toggleAc() = if (useLegacy) legacy("ac") { LegacyCar.ac(_state.value.acOn != true) } else cycleTo("ac", toggleTarget(_state.value.acOn), 2,
        { it.acStatus.asInt() }, { it.switchACStatus() })
    fun toggleAuto() = if (useLegacy) legacy("auto") { LegacyCar.auto(_state.value.autoOn != true) } else cycleTo("auto", toggleTarget(_state.value.autoOn), 2,
        { it.autoStatus.asInt() }, { it.switchAutoStatus() })
    fun toggleDefrostFront() = if (useLegacy) legacy("defrostFront") { LegacyCar.defrostFront(_state.value.defrostFront != true) } else cycleTo("defrostFront", toggleTarget(_state.value.defrostFront), 2,
        { it.frontDefrostStatus.asInt() }, { it.switchFrontDefrostStatus() })
    fun toggleDefrostRear() = if (useLegacy) legacy("defrostRear") { LegacyCar.defrostRear(_state.value.defrostRear != true) } else cycleTo("defrostRear", toggleTarget(_state.value.defrostRear), 2,
        { it.rearDefrostStatus.asInt() }, { it.switchRearDefrostStatus() })

    fun toggleEco() = cycleTo("eco", toggleTarget(_state.value.ecoOn), 2,
        { it.ecoStatus.asInt() }, { it.switchEcoStatus() })

    /** Recyclage : air recyclé (0) ↔ air extérieur (1) ; cycle à 3 états, donc 3 crans au plus. */
    /**
     * Recyclage en 3 états : extérieur (1) → recyclé (0) → auto (2) → extérieur.
     * La commande SAIC n'a pas d'argument et avance d'un cran ; si la voiture ne propose pas
     * l'état auto par ce bouton, deux crans ramènent à l'état de départ (aucun changement).
     */
    fun toggleRecirc() {
        val cur = _state.value.recirc
        setRecirc(when (cur) { 1 -> 0; 0 -> 2; else -> 1 })
    }

    /** Recyclage choisi directement : 0 intérieur, 1 extérieur, 2 auto (la commande avance d'un cran). */
    fun setRecirc(target: Int) {
        if (_state.value.recirc == target) return
        if (useLegacy) { legacy("recirc=$target") { LegacyCar.loop(target) }; return }
        cycleTo("recirc", target, 3, { it.airCirculationStatus.takeIf { v -> v >= 0 } }, { it.switchAirCirculationStatus() })
    }

    /**
     * Température au pas de 1° : LO, 18 à 32, HI (bornes mesurées par MG4Control sur A9).
     * Depuis une valeur en demi-degré, le premier appui arrondit dans le sens demandé.
     */
    fun stepTemp(direction: Int) {
        val cur = _state.value.driverTemp ?: return
        val min = VehicleState.TEMP_MIN; val max = VehicleState.TEMP_MAX   // LO / HI
        val target = when {
            direction > 0 && cur <= min -> 18f
            direction < 0 && cur >= max -> 32f
            direction > 0 -> (Math.floor(cur.toDouble()) + 1).toFloat()
            else -> (Math.ceil(cur.toDouble()) - 1).toFloat()
        }.let { t -> if (t < 18f) min else if (t > 32f) max else t }
        if (target == cur) return
        _state.value = _state.value.copy(driverTemp = target)   // retour visuel immédiat
        holdTemp = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("temp=$target") { LegacyCar.temp(Math.round(target)) }
        else command("temp=$target") { it.setDriverTemperature(target) }
    }

    fun stepFan(delta: Int) {
        val cur = _state.value.fanSpeed ?: return
        val target = (cur + delta).coerceIn(VehicleState.FAN_MIN, VehicleState.FAN_MAX)
        if (target == cur) return
        _state.value = _state.value.copy(fanSpeed = target)
        holdFan = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("fan=$target") { LegacyCar.fan(target) }
        else command("fan=$target") { it.setFanSpeed(target) }
    }

    /** Direction de l'air, échelle 0–6 (table AirFlow de MG4Control). */
    fun setFanDirection(value: Int) {
        if (value !in 0..6) return
        _state.value = _state.value.copy(fanDirection = value)
        if (useLegacy) legacy("direction=$value") { LegacyCar.direction(value) }
        else command("direction=$value") { it.setFanDirection(value) }
    }
}
