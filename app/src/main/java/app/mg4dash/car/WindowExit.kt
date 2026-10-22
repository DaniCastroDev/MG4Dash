package app.mg4dash.car

/**
 * « Cerrar al salir » : ferme les vitres quand le conducteur quitte la voiture, d'après WinClose
 * (Skittle6938/winclose), sans son service à part : MG4Dash, accueil de la voiture, tourne déjà.
 *
 *  - Armé après avoir roulé : au moins [ARM_KMH] une fois depuis la dernière fermeture (un
 *    stationnement sans avoir roulé ne déclenche rien).
 *  - Déclenché en P (ou, marche illisible, à l'arrêt) par l'ouverture de la porte conducteur ou par
 *    le verrouillage : la première des deux.
 *  - Compte à rebours de [COUNTDOWN_MS], annulable à l'écran ; annulé aussi si la voiture quitte P
 *    ou repart. Puis fermeture, et pause de [COOLDOWN_MS] avant un autre déclenchement.
 *
 * Pure : [CarBridge] la nourrit chaque seconde et exécute ce qu'elle répond.
 */
class WindowExit {
    data class Input(
        val nowMs: Long,
        val enabled: Boolean,
        val speedKmh: Float?,
        /** Marche brute du service véhicule ; [PARK] = P. */
        val gear: Int?,
        val driverDoorOpen: Boolean?,
        val locked: Boolean?,
    )

    enum class Action { NONE, COUNTDOWN, CANCEL, CLOSE }

    sealed class State {
        object Off : State()
        object Waiting : State()
        object Armed : State()
        data class Countdown(val untilMs: Long, val reason: String) : State()
    }

    var state: State = State.Off
        private set

    private var armed = false
    private var lastDoorOpen: Boolean? = null
    private var lastLocked: Boolean? = null
    private var lastCloseMs = Long.MIN_VALUE / 2

    fun step(i: Input): Action {
        val door = i.driverDoorOpen
        val doorOpened = door == true && lastDoorOpen == false
        val lockedNow = i.locked == true && lastLocked == false
        if (door != null) lastDoorOpen = door
        if (i.locked != null) lastLocked = i.locked

        if (!i.enabled) {
            val was = state
            armed = false; state = State.Off
            return if (was is State.Countdown) Action.CANCEL else Action.NONE
        }
        val speed = i.speedKmh ?: 0f
        if (speed >= ARM_KMH) armed = true
        val parked = if (i.gear != null) i.gear == PARK else speed < 1f

        val s = state
        if (s is State.Countdown) {
            if (!parked || speed >= 3f) { state = idle(); return Action.CANCEL }
            if (i.nowMs >= s.untilMs) {
                armed = false; lastCloseMs = i.nowMs; state = idle()
                return Action.CLOSE
            }
            return Action.NONE
        }
        state = idle()
        if (!armed || !parked || i.nowMs - lastCloseMs < COOLDOWN_MS) return Action.NONE
        val reason = when {
            doorOpened -> "puerta del conductor abierta"
            lockedNow -> "coche cerrado"
            else -> return Action.NONE
        }
        state = State.Countdown(i.nowMs + COUNTDOWN_MS, reason)
        return Action.COUNTDOWN
    }

    /** Annulation demandée à l'écran : désarmé jusqu'à la prochaine fois qu'on roule. */
    fun cancel(): Boolean {
        if (state !is State.Countdown) return false
        armed = false; state = idle(); return true
    }

    private fun idle(): State = if (armed) State.Armed else State.Waiting

    /** Ligne d'état sous l'interrupteur : ce que voit le déclencheur, pour le vérifier sur la voiture. */
    fun describe(i: Input): String {
        if (!i.enabled) return "Desactivado"
        val gear = when (i.gear) { null -> "marcha —"; PARK -> "en P"; else -> "marcha ${i.gear}" }
        val door = when (i.driverDoorOpen) { null -> "puerta —"; true -> "puerta abierta"; false -> "puerta cerrada" }
        val head = when (val s = state) {
            is State.Countdown -> "Cerrando en ${((s.untilMs - i.nowMs + 999) / 1000).coerceAtLeast(0)} s"
            is State.Armed -> "Listo al salir"
            else -> "Se activa al circular"
        }
        return "$head · $gear · $door"
    }

    companion object {
        /** `getCarGear` du service véhicule : 1 = P (EVHardware, et les applis SAIC qui testent carGear == 1). */
        const val PARK = 1
        const val ARM_KMH = 15f
        const val COUNTDOWN_MS = 5_000L
        const val COOLDOWN_MS = 60_000L
    }
}
