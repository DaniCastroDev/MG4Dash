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
    @Volatile private var fastPoll = true

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
        if (Firmware.family != Firmware.Family.A9) LegacyCar.init(context)
        Log.i(TAG, "firmware ${Firmware.version} (${Firmware.gen}, famille ${Firmware.family})")
        scope.launch { pollLoop() }
    }

    /** Démarrage / arrêt de la charge et du V2L disponibles (service EV de la voie A9). */
    val chargeControl: Boolean get() = BuildConfig.MOCK || ev != null || LegacyCar.chargingReady

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

    /** Ralentit l'interrogation quand l'accueil n'est pas visible. */
    fun setForeground(foreground: Boolean) {
        fastPoll = foreground
    }

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
        if (general?.alive != true) general = query(CODE_GENERAL)?.let { GeneralService(it) }
    }

    private suspend fun pollLoop() {
        var tick = 0L
        while (scope.isActive) {
            try {
                if (adapter == null) {
                    if (tick % 5 == 0L && Firmware.family != Firmware.Family.LEGACY) bind()
                } else if (tick % 5 == 0L) {
                    resolveClients()
                }
                refreshHvac()
                if (tick % 3 == 0L) refreshEnergy()
            } catch (e: Exception) {
                Log.w(TAG, "poll : ${e.message}")
            }
            tick++
            delay(if (fastPoll) 1000L else 5000L)
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    // Valeur réglée à l'instant : la voiture met un moment à l'appliquer, et la relecture (chaque
    // seconde) réaffichait l'ancienne valeur entre-temps (« la valeur hésite », issue #6). On garde
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
        return r
    }

    private fun refreshHvac() {
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
        // Mode de conduite (propriété) : 0 neige, 1 éco, 3 normal, 4 sport, 7 personnalisé → échelle A9
        val dm = when (i(0x2140a17c)) { 0 -> 6; 1 -> 2; 3 -> 3; 4 -> 4; 7 -> 7; else -> null }
        // Sans service EV, les propriétés sont la seule source : on les reprend à chaque passage
        // (avant, la première valeur lue restait affichée : % et autonomie figés, issue #13).
        val noEv = ev == null
        _state.value = s.copy(
            connected = s.connected || soc != null,
            batteryPercent = if (noEv) soc ?: s.batteryPercent else s.batteryPercent ?: soc,
            rangeKm = if (noEv) range ?: s.rangeKm else s.rangeKm ?: range,
            chargeStatus = if (ev == null) status else s.chargeStatus,
            odometerKm = s.odometerKm ?: i(0x21401566)?.takeIf { it > 0 },
            driveMode = if (vs == null) dm ?: s.driveMode else s.driveMode ?: dm,
            regenLevel = s.regenLevel ?: i(0x2140a191)?.takeIf { it in 0..7 },
            onePedal = s.onePedal ?: i(0x2140a193)?.takeIf { it in 0..1 },
        )
    }

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
                } else Log.w(TAG, "$label ignoré : service EV non connecté")
                return@launch
            }
            try { action(e); Log.i(TAG, "EV $label → envoyé") } catch (ex: Exception) { Log.w(TAG, "EV $label → échec : ${ex.message}") }
            delay(1500); refreshEnergy()
            delay(3000); refreshEnergy()
        }
    }

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
        val (target, steps) = when (cur) { 1 -> 0 to 3; 0 -> 2 to 2; else -> 1 to 3 }
        if (useLegacy) { legacy("recirc=$target") { LegacyCar.loop(target) }; return }
        cycleTo("recirc", target, steps, { it.airCirculationStatus.takeIf { v -> v >= 0 } }, { it.switchAirCirculationStatus() })
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
