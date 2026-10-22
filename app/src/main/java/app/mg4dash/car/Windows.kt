package app.mg4dash.car

import kotlin.math.roundToInt

/**
 * Ventanillas, par le `VehicleControlManager` de l'ancien launcher (SWI68), le même que l'ESC :
 * une propriété par vitre (0x11603801 conducteur, …02 passager, …03 arrière gauche, …04 arrière
 * droite), derrière `IVehicleControlService`.
 *
 * Lecture et écriture ne parlent pas la même échelle (ressources du service véhicule) :
 *  - lecture : position 0 (fermée) à 100 (ouverte), `max_vehicle_window_get = 100` ;
 *  - écriture : une commande 0 à 7, `max_vehicle_window_set = 7`, toute autre valeur est jetée
 *    sans erreur.
 *
 * Commandes relevées par WinClose (Skittle6938/winclose) sur ce même service : 0 arrêt, 1 monter,
 * 2 descendre, 3 montée automatique, 4 descente automatique ; le launcher SAIC nomme aussi 3 et 4
 * (`VehicleConstant.MainDrivingWindowValueSend`). Monter et descendre sont un interrupteur tenu :
 * la vitre ne bouge que tant que la commande arrive, d'où un envoi toutes les [PULSE_MS]. Sur
 * SWI133, seule la vitre conducteur obéit à la montée automatique ; les autres, seulement au
 * maintien. Rien de cela n'est encore essayé sur la voiture du propriétaire (2026-10-22).
 */
object Windows {
    const val STOP = 0
    const val UP = 1
    const val DOWN = 2
    const val AUTO_UP = 3
    const val AUTO_DOWN = 4

    /** Rythme du maintien (celui de WinClose et d'EVHardware). */
    const val PULSE_MS = 120L
    /** Course complète d'une vitre, avec de la marge : un toque la mène au bout. */
    const val TRAVEL_MS = 6_000L
    /** En dessous, un appui est un toque (course complète) ; au-dessus, la vitre suit le doigt. */
    const val TAP_MS = 350L
    /** Au plus, même doigt posé : la vitre s'arrête si le relâchement n'arrive jamais. */
    const val HOLD_MAX_MS = 10_000L

    /** Dans l'ordre de [VehicleState.windows] : conducteur, passager, arrière gauche, arrière droite. */
    val GETTERS = listOf("getDriveWindow", "getPassengerWindow", "getLeftRearWindow", "getRightRearWindow")
    val SETTERS = listOf("setDriveWindow", "setPassengerWindow", "setLeftRearWindow", "setRightRearWindow")
    val NAMES = listOf("Conductor", "Acompañante", "Trasera izq.", "Trasera der.")
    val ALL = 0..3

    /** Commande tenue pour un sens. */
    fun hold(open: Boolean) = if (open) DOWN else UP

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
