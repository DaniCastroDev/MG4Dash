package app.mg4dash.car

import kotlin.math.roundToInt

/**
 * Ventanillas, par le `VehicleControlManager` de l'ancien launcher (SWI68) : une propriété par
 * vitre (0x11603801 conducteur, …02 passager, …03 arrière gauche, …04 arrière droite).
 *
 * Lecture et écriture ne parlent pas la même échelle (ressources du service véhicule) :
 *  - lecture : position 0 (fermée) à 100 (ouverte), `max_vehicle_window_get = 100` ;
 *  - écriture : une commande 0 à 7, `max_vehicle_window_set = 7`, toute autre valeur est jetée
 *    sans erreur.
 *
 * Seules deux commandes sont nommées sur la voiture : `VehicleConstant.MainDrivingWindowValueSend`
 * du launcher SAIC, 3 « tout fermer » et 4 « tout ouvrir », pour la vitre conducteur. On les envoie
 * aux quatre vitres : que la commande vaille pour toutes ou pour la seule vitre visée, les quatre
 * finissent dans le même sens. Non essayé sur la voiture au moment de l'écrire (2026-10-22).
 */
object Windows {
    const val CLOSE_ALL = 3
    const val OPEN_ALL = 4

    /** Dans l'ordre de [VehicleState.windows] : conducteur, passager, arrière gauche, arrière droite. */
    val GETTERS = listOf("getDriveWindow", "getPassengerWindow", "getLeftRearWindow", "getRightRearWindow")
    val SETTERS = listOf("setDriveWindow", "setPassengerWindow", "setLeftRearWindow", "setRightRearWindow")
    val NAMES = listOf("Conductor", "Acompañante", "Trasera izq.", "Trasera der.")

    fun command(open: Boolean) = if (open) OPEN_ALL else CLOSE_ALL

    /** Position lue en %, ou null hors échelle (le service répond une valeur invalide sans capteur). */
    fun percent(raw: Float?): Int? = raw?.takeIf { !it.isNaN() && it in 0f..100f }?.roundToInt()

    /** Toutes les vitres lues et fermées. */
    fun allClosed(p: List<Int?>?): Boolean = p != null && p.size == 4 && p.all { it == 0 }

    /** Résumé pour l'en-tête de la carte. */
    fun summary(p: List<Int?>?): String {
        val known = p?.filterNotNull().orEmpty()
        val open = known.count { it > 0 }
        return when {
            known.isEmpty() -> "Sin lectura"
            open == 0 -> "Todas cerradas"
            known.size == 4 && known.all { it == 100 } -> "Todas abiertas"
            open == 1 -> "1 abierta"
            else -> "$open abiertas"
        }
    }

    fun cell(v: Int?): String = v?.let { if (it == 0) "Cerrada" else "$it %" } ?: "—"
}
