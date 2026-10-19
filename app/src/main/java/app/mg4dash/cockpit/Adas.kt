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

    data class State(val current: Map<Item, Int?> = emptyMap(), val appliedAt: Long = 0L, val busy: Boolean = false)
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
        manager = LegacyCar.sdkManager(ctx, SETTING_CLASS, { manager = it ?: manager; scope.launch { refresh() } },
            listOf("com.saicmotor.hmi.vehiclesettings", app.mg4dash.system.Firmware.LEGACY_LAUNCHER))
    }

    fun profile(context: Context): Map<Item, Int> {
        val sp = Prefs(context).raw
        return Item.values().associateWith { sp.getInt("adas_" + it.name, DEFAULT.getValue(it)) }
    }

    fun setProfile(context: Context, item: Item, value: Int) {
        Prefs(context).raw.edit().putInt("adas_" + item.name, value).apply()
    }

    /** Le profil est-il celui de la voiture en ce moment ? (null si illisible) */
    fun matches(context: Context): Boolean? {
        val cur = _state.value.current
        if (cur.values.all { it == null }) return null
        return profile(context).all { (k, v) -> cur[k] == null || cur[k] == v }
    }

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
        _state.value = _state.value.copy(current = cur)
    }

    /** Applique le profil. Refusé en roulant (vitesse lue ≠ 0) ou si la vitesse est illisible. */
    fun apply(context: Context): CarBridge.Write {
        val speed = CarBridge.state.value.speedKmh ?: return CarBridge.Write.SPEED_UNKNOWN
        if (Math.abs(speed) >= 0.5f) return CarBridge.Write.MOVING
        if (manager == null) { connect(); if (manager == null) return CarBridge.Write.UNAVAILABLE }
        val wanted = profile(context)
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            for ((item, v) in wanted) {
                if (_state.value.current[item] == v) continue
                call(item.setter, v)
                Log.i(TAG, "${item.name} → $v")
                delay(250)
            }
            delay(800)
            refresh()
            _state.value = _state.value.copy(busy = false, appliedAt = System.currentTimeMillis())
        }
        return CarBridge.Write.SENT
    }

    /** Écarts entre l'état de la voiture et le profil, pour l'accueil et la bienvenue. */
    fun changes(context: Context): List<String> {
        val cur = _state.value.current
        return profile(context).mapNotNull { (k, v) ->
            if (cur[k] == v) null else "${k.title} → ${k.options.firstOrNull { it.first == v }?.second ?: v}"
        }
    }
}
