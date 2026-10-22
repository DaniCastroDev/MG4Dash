package app.mg4dash.cockpit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Réglages du véhicule qui ne changent pas à chaque trajet (Ajustes › Volante y luces) : feux et mode
 * remorque, par le VehicleSettingManager de l'appli Réglages d'origine, comme [Adas].
 *
 * Valeurs relevées dans LightSettingsViewModel et SafeSettingsViewModel de SWI68 R46 : chaque
 * interrupteur envoie 1 (activé) ou 0 (désactivé) ; « Acompáñame a casa » y est un interrupteur
 * (setHomeLightTime 1/0), pas une durée.
 */
object CarSettings {
    enum class Toggle(val title: String, val sub: String, val getter: String, val setter: String) {
        AUTO_HIGH_BEAM("Luces largas automáticas", "De noche, a más de 40 km/h", "getAutoMainBeamControl", "setAutoMainBeamControl"),
        WELCOME_APPROACH("Luz de bienvenida al acercarte", "Con la llave cerca del coche", "getLampWelcomeOn", "setLampWelcomeOn"),
        WELCOME_UNLOCK("Luz de bienvenida al abrir", "Al desbloquear el coche", "getWelcomeLightTime", "setWelcomeLightTime"),
        FOLLOW_HOME("Acompáñame a casa", "Los faros siguen encendidos al apagar el coche", "getHomeLightTime", "setHomeLightTime"),
        TOWING("Modo remolque", "Desactiva los sensores de aparcamiento y el aviso trasero", "getTowingModeSwitch", "setTowingModeSwitch"),
    }

    val LIGHTS = listOf(Toggle.AUTO_HIGH_BEAM, Toggle.WELCOME_APPROACH, Toggle.WELCOME_UNLOCK, Toggle.FOLLOW_HOME)

    /** 1 activé ; 0 désactivé (2, la convention « désactivé » d'autres réglages SAIC, aussi) ; sinon illisible. */
    fun parse(v: Int?): Boolean? = when (v) { 1 -> true; 0, 2 -> false; else -> null }
    fun command(on: Boolean) = if (on) 1 else 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Map<Toggle, Boolean?>>(emptyMap())
    val state: StateFlow<Map<Toggle, Boolean?>> = _state

    fun refresh() {
        // émulateur : réglages d'usine simulés
        if (app.mg4dash.BuildConfig.MOCK) { if (_state.value.isEmpty()) _state.value = Toggle.entries.associateWith { it != Toggle.TOWING }; return }
        scope.launch { _state.value = Toggle.entries.associateWith { parse(Adas.readInt(it.getter)) } }
    }

    /** Affiche tout de suite la nouvelle valeur, l'écrit, puis relit ce que la voiture a retenu. */
    fun set(t: Toggle, on: Boolean) {
        _state.value = _state.value + (t to on)
        if (app.mg4dash.BuildConfig.MOCK) return
        scope.launch {
            Adas.writeInt(t.setter, command(on))
            delay(1_500)
            _state.value = _state.value + (t to parse(Adas.readInt(t.getter)))
        }
    }
}
