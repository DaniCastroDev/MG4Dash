package app.mg4dash.system

/**
 * Retour vers MG4Dash depuis l'interface MG : double appui sur le bouton CarPlay de l'accueil SAIC.
 *
 * Sans iPhone, ce bouton (CarPlayAutoCardView, `iv_apple_carplay` / `tv_apple_carplay` dans
 * com.saicmotor.hmi.launcher) n'ouvre rien : il affiche seulement « Connect Mobile Phone to USB… ».
 * Aucune activité ni diffusion n'en sort, d'où le service d'accessibilité ([ReturnService]) qui voit
 * ses clics. Deux appuis rapprochés ramènent MG4Dash ; un seul garde le comportement d'origine.
 */
class CarPlayDoubleTap(private val windowMs: Long = WINDOW_MS) {

    private var lastTapAt: Long? = null

    /** Un clic vu dans l'accueil SAIC. Renvoie true quand il complète un double appui sur CarPlay. */
    fun onClick(viewId: String?, atMs: Long): Boolean {
        if (!isCarPlayButton(viewId)) { lastTapAt = null; return false }
        val previous = lastTapAt
        if (previous != null && atMs - previous in 0..windowMs) {
            lastTapAt = null   // consommé : un troisième appui recommence un double appui
            return true
        }
        lastTapAt = atMs
        return false
    }

    companion object {
        const val WINDOW_MS = 600L
        private val IDS = setOf("iv_apple_carplay", "tv_apple_carplay")

        /** `com.saicmotor.hmi.launcher:id/iv_apple_carplay` → bouton CarPlay de l'accueil SAIC. */
        fun isCarPlayButton(viewId: String?): Boolean = viewId?.substringAfterLast('/') in IDS
    }
}
