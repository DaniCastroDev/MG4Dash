package app.mg4dash.cockpit.strip

/**
 * État de la franja, lisible dans Ajustes sans ADB : le journal de MG4Dash ne vit qu'en mémoire, et
 * une franja qui n'apparaît pas (essai du 2026-10-22) ne disait pas si le service tournait, ce qu'il
 * voyait au premier plan ou s'il s'était coupé après une erreur.
 */
object StripStatus {
    @Volatile var lastPollMs = 0L
    @Volatile var top: String? = null
    @Volatile var visible = false
    @Volatile var error: String? = null
    @Volatile var failures = 0

    /** Au-delà, la franja reste coupée jusqu'au prochain démarrage : une erreur qui revient à chaque ouverture. */
    const val MAX_FAILURES = 3
    private const val SERVICE_STALE_MS = 3_000L

    fun recordFailure(where: String, t: Throwable) {
        failures++
        error = "$where: ${t.javaClass.simpleName}${t.message?.let { " $it" } ?: ""}"
    }

    /** Ligne affichée sous l'interrupteur « Franja con Android Auto ». */
    fun describe(nowMs: Long, enabled: Boolean, lastPollMs: Long = this.lastPollMs, visible: Boolean = this.visible,
                 top: String? = this.top, error: String? = this.error, failures: Int = this.failures): String = when {
        !enabled -> "Desactivada"
        lastPollMs == 0L || nowMs - lastPollMs > SERVICE_STALE_MS -> "El servicio de la franja no está en marcha"
        failures >= MAX_FAILURES -> "Parada tras $failures errores hasta reiniciar · ${error ?: ""}"
        visible -> "Visible sobre DiAuto"
        error != null -> "Esperando a DiAuto · último error: $error"
        else -> "Esperando a DiAuto · delante: ${top ?: "—"}"
    }
}
