package app.mg4dash.cockpit

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.car.LegacyCar
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
 * Aides à la conduite et « Mi perfil » (cockpit v2).
 *
 * Valeurs relevées dans l'appli Réglages d'origine de SWI68 (SafeSettingsViewModel) :
 *  - avertissement de limite de vitesse (son) : setSpeedAsstSlifWarning, 1 = activé, 0 = désactivé ;
 *  - maintien de voie : setLaneKeepingAsstMode, 1 désactivé, 2 alerte, 3 assistance, 5 urgence ;
 *  - surveillance du conducteur, conduite instable, trafic arrière : 1 = activé, 2 = désactivé.
 * Le VehicleSettingManager du launcher d'origine n'a pas toutes ces méthodes : on charge celui de
 * l'appli Réglages (com.saicmotor.hmi.vehiclesettings), même service véhicule derrière.
 * Freinage d'urgence et alerte de collision ne sont jamais touchés. Écritures à l'arrêt seulement,
 * par un appui du conducteur (le règlement UE les réactive à chaque démarrage).
 */
object Adas {
    private const val TAG = "Adas"
    private const val SETTING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Item(val title: String, val getter: String, val setter: String, val options: List<Pair<Int, String>>) {
        SPEED_SOUND("Aviso de límite de velocidad (sonido)", "getSpeedAsstSlifWarning", "setSpeedAsstSlifWarning", listOf(1 to "Activado", 0 to "Desactivado")),
        LANE("Mantenimiento de carril", "getLaneKeepingAsstMode", "setLaneKeepingAsstMode", listOf(1 to "Desactivado", 2 to "Solo aviso", 3 to "Asistencia", 5 to "Emergencia")),
        DRIVER_MONITOR("Monitorización del conductor", "getDriverMonitorSys", "setDriverMonitorSys", listOf(1 to "Activado", 2 to "Desactivado")),
        UNSTEADY("Aviso de conducción inestable", "getUnsteadyDrivingWarning", "setUnsteadyDrivingWarning", listOf(1 to "Activado", 2 to "Desactivado")),
        REAR_TRAFFIC("Alerta de tráfico trasero", "getRearCrossTrafficSys", "setRearCrossTrafficSys", listOf(1 to "Activado", 2 to "Desactivado")),
    }

    /** Profil par défaut : sons et assistances gênants coupés, sécurité passive conservée. */
    private val DEFAULT = mapOf(Item.SPEED_SOUND to 0, Item.LANE to 2, Item.DRIVER_MONITOR to 2, Item.UNSTEADY to 2, Item.REAR_TRAFFIC to 1)

    /**
     * Réglages de conduite du profil (appliqués avec les aides, à l'arrêt). -1 = ne pas toucher.
     * Mode : échelle A9 (2 éco, 3 normal, 4 sport, 6 neige) ; régénération 0–3 ; un pedal 0/1.
     */
    enum class Drive(val title: String, val key: String, val options: List<Pair<Int, String>>) {
        MODE("Modo de conducción", "drive_mode", listOf(-1 to "Sin cambiar", 2 to "Eco", 3 to "Normal", 4 to "Sport", 6 to "Nieve")),
        REGEN("Regeneración", "drive_regen", listOf(-1 to "Sin cambiar", 0 to "Baja", 1 to "Media", 2 to "Alta", 3 to "Adaptativa")),
        ONE_PEDAL("Conducción con un pedal", "drive_one_pedal", listOf(-1 to "Sin cambiar", 1 to "Activada", 0 to "Desactivada")),
        // setSteeringLevel de l'appli Réglages d'origine (DrivingSettingsViewModel) : 1 confort, 2 normal, 3 sport
        STEERING("Dirección", "drive_steering", listOf(-1 to "Sin cambiar", 1 to "Ligera", 2 to "Normal", 3 to "Deportiva")),
    }

    fun driveProfile(context: Context): Map<Drive, Int> {
        val sp = Prefs(context).raw
        return Drive.values().associateWith { sp.getInt(key(context, it.key), -1) }
    }

    fun setDrive(context: Context, d: Drive, value: Int) { Prefs(context).raw.edit().putInt(key(context, d.key), value).apply() }

    /** Valeur actuelle de la voiture pour un réglage de conduite. */
    fun driveNow(d: Drive): Int? {
        val s = CarBridge.state.value
        return when (d) {
            Drive.MODE -> s.driveMode; Drive.REGEN -> s.regenLevel?.takeIf { it in 0..3 }; Drive.ONE_PEDAL -> s.onePedal
            Drive.STEERING -> _state.value.steering
        }
    }

    data class State(val current: Map<Item, Int?> = emptyMap(), val appliedAt: Long = 0L, val busy: Boolean = false, val steering: Int? = null)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var manager: Any? = null
    private var appContext: Context? = null

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        connect()
        scope.launch { while (true) { refresh(); delay(15_000) } }
    }

    private fun connect() {
        val ctx = appContext ?: return
        if (manager != null) return
        manager = LegacyCar.sdkManager(ctx, SETTING_CLASS, listOf("com.saicmotor.hmi.vehiclesettings", app.mg4dash.system.Firmware.LEGACY_LAUNCHER)) {
            manager = it ?: manager; scope.launch { refresh() }
        }
    }

    // ── Deux perfiles : le 1 garde les clés d'origine, le 2 les mêmes préfixées « p2_ » ──
    const val PROFILES = 2
    fun active(context: Context): Int = Prefs(context).raw.getInt("adas_active", 0).coerceIn(0, PROFILES - 1)
    fun setActive(context: Context, i: Int) { Prefs(context).raw.edit().putInt("adas_active", i.coerceIn(0, PROFILES - 1)).apply() }
    private fun key(context: Context, base: String) = if (active(context) == 0) base else "p2_$base"

    fun profile(context: Context): Map<Item, Int> {
        val sp = Prefs(context).raw
        return Item.values().associateWith { sp.getInt(key(context, "adas_" + it.name), DEFAULT.getValue(it)) }
    }

    fun setProfile(context: Context, item: Item, value: Int) {
        Prefs(context).raw.edit().putInt(key(context, "adas_" + item.name), value).apply()
    }

    /** Le profil est-il celui de la voiture en ce moment ? (null si illisible) */
    fun matches(context: Context): Boolean? {
        val cur = _state.value.current
        val driveOk = driveProfile(context).all { (d, v) -> v == -1 || driveNow(d) == null || driveNow(d) == v }
        if (cur.values.all { it == null }) return if (driveProfile(context).values.all { it == -1 }) null else driveOk
        return driveOk && profile(context).all { (k, v) -> cur[k] == null || cur[k] == v }
    }

    /** Lecture d'un réglage du même VehicleSettingManager (voir CarSettings). Hors du fil principal. */
    fun readInt(name: String): Int? { if (manager == null) connect(); return call(name) }

    /** Écriture d'un réglage du même VehicleSettingManager ; false s'il ne répond pas. */
    fun writeInt(name: String, v: Int): Boolean { if (manager == null) connect(); return call(name, v) != null }

    private fun call(name: String, vararg args: Int): Int? {
        val m = manager ?: return null
        return try {
            if (args.isEmpty()) m.javaClass.getMethod(name).invoke(m) as? Int
            else { m.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(m, args[0]); 0 }
        } catch (e: Exception) { Log.w(TAG, "$name : ${(e.cause ?: e).message}"); null }
    }

    fun refresh() {
        if (manager == null) connect()
        val cur = Item.values().associateWith { call(it.getter)?.takeIf { v -> v >= 0 } }
        _state.value = _state.value.copy(current = cur, steering = call("getSteeringLevel")?.takeIf { it in 1..3 })
    }

    /** État de la voiture juste avant la dernière application, pour « Deshacer ». */
    @Volatile private var undoDrive: Map<Drive, Int> = emptyMap()
    @Volatile private var undoAdas: Map<Item, Int> = emptyMap()
    val canUndo: Boolean get() = undoDrive.isNotEmpty() || undoAdas.isNotEmpty()

    /** Applique le profil actif, à l'arrêt comme en roulant (choix du propriétaire). */
    fun apply(context: Context): CarBridge.Write {
        if (manager == null) connect()
        val drive = driveProfile(context).filterValues { it != -1 }
        if (manager == null && drive.isEmpty()) return CarBridge.Write.UNAVAILABLE
        val wanted = if (manager != null) profile(context) else emptyMap()
        undoDrive = drive.keys.mapNotNull { d -> driveNow(d)?.let { d to it } }.toMap()
        undoAdas = wanted.keys.mapNotNull { k -> _state.value.current[k]?.let { k to it } }.toMap()
        return write(drive, wanted)
    }

    /** Remet la voiture comme avant la dernière application. */
    fun undo(): CarBridge.Write {
        if (!canUndo) return CarBridge.Write.UNAVAILABLE
        val d = undoDrive; val a = undoAdas
        undoDrive = emptyMap(); undoAdas = emptyMap()
        return write(d, a, mark = false)
    }

    private fun write(drive: Map<Drive, Int>, wanted: Map<Item, Int>, mark: Boolean = true): CarBridge.Write {
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            // conduite d'abord (mode, régénération, un pedal), puis les aides
            drive[Drive.MODE]?.let { if (driveNow(Drive.MODE) != it) { CarBridge.setDriveMode(it); delay(600) } }
            drive[Drive.REGEN]?.let { if (driveNow(Drive.REGEN) != it) { CarBridge.setRegenLevel(it); delay(600) } }
            drive[Drive.ONE_PEDAL]?.let { if (driveNow(Drive.ONE_PEDAL) != it) { CarBridge.toggleOnePedal(); delay(600) } }
            drive[Drive.STEERING]?.let { if (driveNow(Drive.STEERING) != it) { call("setSteeringLevel", it); Log.i(TAG, "STEERING → $it"); delay(400) } }
            for ((item, v) in wanted) {
                if (_state.value.current[item] == v) continue
                call(item.setter, v)
                Log.i(TAG, "${item.name} → $v")
                delay(250)
            }
            delay(800)
            refresh()
            _state.value = _state.value.copy(busy = false, appliedAt = if (mark) System.currentTimeMillis() else 0L)
        }
        return CarBridge.Write.SENT
    }

    /** Écarts entre l'état de la voiture et le profil, pour l'accueil et la bienvenue. */
    fun changes(context: Context): List<String> {
        val cur = _state.value.current
        val drive = driveProfile(context).mapNotNull { (d, v) ->
            if (v == -1 || driveNow(d) == v) null else "${d.title} → ${d.options.firstOrNull { it.first == v }?.second ?: v}"
        }
        return drive + profile(context).mapNotNull { (k, v) ->
            if (cur[k] == v) null else "${k.title} → ${k.options.firstOrNull { it.first == v }?.second ?: v}"
        }
    }
}
