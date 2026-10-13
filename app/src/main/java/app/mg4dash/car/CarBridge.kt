package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.mg4dash.BuildConfig
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
        bind()
        scope.launch { pollLoop() }
    }

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
                    if (tick % 5 == 0L) bind()
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

    private fun refreshHvac() {
        val h = hvac ?: return
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
        )
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
    }

    // ── Commandes clim ────────────────────────────────────────────────────────
    // Exécutées hors thread principal, puis relecture rapide pour que l'UI suive l'état réel.

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
            val e = ev ?: run { Log.w(TAG, "$label ignoré : service EV non connecté"); return@launch }
            try { action(e); Log.i(TAG, "EV $label → envoyé") } catch (ex: Exception) { Log.w(TAG, "EV $label → échec : ${ex.message}") }
            delay(1500); refreshEnergy()
            delay(3000); refreshEnergy()
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

    fun togglePower() = cycleTo("power", toggleTarget(_state.value.hvacOn), 2,
        { it.hvacPowerStatus.asInt() }, { it.switchHvacPowerStatus() })
    fun toggleAc() = cycleTo("ac", toggleTarget(_state.value.acOn), 2,
        { it.acStatus.asInt() }, { it.switchACStatus() })
    fun toggleAuto() = cycleTo("auto", toggleTarget(_state.value.autoOn), 2,
        { it.autoStatus.asInt() }, { it.switchAutoStatus() })
    fun toggleDefrostFront() = cycleTo("defrostFront", toggleTarget(_state.value.defrostFront), 2,
        { it.frontDefrostStatus.asInt() }, { it.switchFrontDefrostStatus() })
    fun toggleDefrostRear() = cycleTo("defrostRear", toggleTarget(_state.value.defrostRear), 2,
        { it.rearDefrostStatus.asInt() }, { it.switchRearDefrostStatus() })

    fun toggleEco() = cycleTo("eco", toggleTarget(_state.value.ecoOn), 2,
        { it.ecoStatus.asInt() }, { it.switchEcoStatus() })

    /** Recyclage : air recyclé (0) ↔ air extérieur (1) ; cycle à 3 états, donc 3 crans au plus. */
    fun toggleRecirc() = cycleTo("recirc", if (_state.value.recirc == 0) 1 else 0, 3,
        { it.airCirculationStatus.takeIf { v -> v >= 0 } }, { it.switchAirCirculationStatus() })

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
        command("temp=$target") { it.setDriverTemperature(target) }
    }

    fun stepFan(delta: Int) {
        val cur = _state.value.fanSpeed ?: return
        val target = (cur + delta).coerceIn(VehicleState.FAN_MIN, VehicleState.FAN_MAX)
        if (target == cur) return
        _state.value = _state.value.copy(fanSpeed = target)
        command("fan=$target") { it.setFanSpeed(target) }
    }

    /** Direction de l'air, échelle 0–6 (table AirFlow de MG4Control). */
    fun setFanDirection(value: Int) {
        if (value !in 0..6) return
        _state.value = _state.value.copy(fanDirection = value)
        command("direction=$value") { it.setFanDirection(value) }
    }
}
