package app.mg4dash.system

import android.content.Context
import android.content.Intent
import app.mg4dash.MainActivity
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.system.WheelKeys.Action

/** Ce que fait chaque action des boutons du volant ; rend le texte du bref avis affiché par-dessus tout. */
object WheelActions {

    /** Cran suivant d'un siège : le même ordre que le bouton d'origine (arrêt → 3 → 2 → 1 → arrêt). */
    fun nextSeatLevel(cur: Int?): Int = when (val c = cur ?: 0) { 0 -> 3; else -> c - 1 }

    /** Régénération suivante : baja → media → alta → adaptativa → baja. */
    fun nextRegen(cur: Int?): Int = ((cur ?: -1) + 1).mod(4)

    /** Mode suivant parmi éco, normal et sport (neige se choisit à l'écran). */
    fun nextDriveMode(cur: Int?): Int = DRIVE_CYCLE[(DRIVE_CYCLE.indexOf(cur) + 1).mod(DRIVE_CYCLE.size)]
    private val DRIVE_CYCLE = listOf(2, 3, 4)

    private val REGEN = listOf("baja", "media", "alta", "adaptativa")

    fun perform(context: Context, action: Action): String {
        val s = CarBridge.state.value
        return when (action) {
            Action.NONE -> ""
            Action.PROFILE -> {
                val changes = Adas.changes(context).size
                when (Adas.apply(context)) {
                    CarBridge.Write.SENT -> if (changes == 0) "Tu perfil ya estaba aplicado" else "Aplicando tu perfil · $changes ${if (changes == 1) "cambio" else "cambios"}"
                    else -> "No se pudo aplicar el perfil"
                }
            }
            Action.WHEEL_HEAT -> {
                val on = (s.wheelHeat ?: 0) == 0
                CarBridge.toggleSteeringHeat()
                "Volante calefactado ${if (on) "encendido" else "apagado"}"
            }
            Action.SEAT_HEAT_DRIVER, Action.SEAT_HEAT_PASSENGER -> {
                val driver = action == Action.SEAT_HEAT_DRIVER
                val next = nextSeatLevel(if (driver) s.seatHeatL else s.seatHeatR)
                CarBridge.cycleSeatHeat(left = driver)
                "Asiento del ${if (driver) "conductor" else "acompañante"}: ${if (next == 0) "apagado" else "nivel $next"}"
            }
            Action.WINDOWS_CLOSE ->
                if (CarBridge.moveAllWindows(open = false) == CarBridge.Write.SENT) "Subiendo las ventanillas" else "Ventanillas no disponibles"
            Action.REAR_FOG -> {
                val on = s.rearFog != true
                if (CarBridge.setRearFog(on) == CarBridge.Write.SENT) "Antiniebla trasera ${if (on) "encendida" else "apagada"}" else "Antiniebla no disponible"
            }
            Action.REGEN_NEXT -> {
                val next = nextRegen(s.regenLevel)
                CarBridge.setRegenLevel(next)
                "Regeneración ${REGEN[next]}"
            }
            Action.DRIVE_MODE_NEXT -> {
                val next = nextDriveMode(s.driveMode)
                if (CarBridge.setDriveMode(next) == CarBridge.Write.SENT) "Modo ${VehicleState.driveModeLabel(next)}" else "No se pudo cambiar el modo"
            }
            Action.OPEN_ENERGY, Action.OPEN_CLIMATE -> {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("screen", if (action == Action.OPEN_ENERGY) "energy" else "climate"))
                ""
            }
        }
    }
}
