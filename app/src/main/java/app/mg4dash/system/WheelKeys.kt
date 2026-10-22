package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Boutons étoile du volant, assignables à des actions de MG4Dash (Ajustes › Volante y luces).
 *
 * La SystemUI SAIC diffuse chaque appui (`com.saic.keyevent.hardkey.report`) : code 17 étoile
 * gauche, 286 (ou 18) étoile droite ; un appui envoie down puis up, un appui long ajoute un down
 * avec `longpress` (codes et séquence relevés par EVHardware). La fonction d'origine de l'étoile
 * (Ajustes MG : modes de conduite, récupération, clim, source ou « Désactiver ») s'exécute quand
 * même : MG4Dash écoute, il ne peut pas l'empêcher. D'où le conseil de la régler sur Désactiver.
 *
 * Un toque simple n'est rendu qu'une fois la fenêtre du double passée ([DOUBLE_MS]) quand le double
 * de ce bouton a une action : sinon un double toque déclencherait aussi l'action du simple.
 */
object WheelKeys {
    private const val TAG = "Volant"
    const val ACTION = "com.saic.keyevent.hardkey.report"
    private const val EXTRA_CODE = "android.intent.extra.hardkey.keycode"
    private const val EXTRA_DOWN = "android.intent.extra.hardkey.down"
    private const val EXTRA_LONG = "android.intent.extra.hardkey.longpress"
    /** Plus large que les 300 ms d'EVHardware : en roulant, deux appuis voulus prennent plus de temps. */
    const val DOUBLE_MS = 400L

    enum class Star(val title: String, val codes: Set<Int>) {
        LEFT("Estrella izquierda", setOf(17)),
        RIGHT("Estrella derecha", setOf(286, 18)),
    }

    enum class Press(val title: String) { SHORT("Un toque"), DOUBLE("Doble toque"), LONG("Pulsación larga") }

    enum class Action(val title: String) {
        NONE("Nada"),
        PROFILE("Aplicar mi perfil"),
        WHEEL_HEAT("Volante calefactado"),
        SEAT_HEAT_DRIVER("Asiento del conductor"),
        SEAT_HEAT_PASSENGER("Asiento del acompañante"),
        WINDOWS_CLOSE("Cerrar ventanillas"),
        REAR_FOG("Antiniebla trasera"),
        REGEN_NEXT("Siguiente regeneración"),
        DRIVE_MODE_NEXT("Siguiente modo de conducción"),
        OPEN_ENERGY("Abrir Energía"),
        OPEN_CLIMATE("Abrir Clima"),
    }

    /** Assignation par défaut : appliquer le profil d'un double toque sur l'étoile droite. */
    fun default(star: Star, press: Press): Action =
        if (star == Star.RIGHT && press == Press.DOUBLE) Action.PROFILE else Action.NONE

    private fun key(star: Star, press: Press) = "wheel_${star.name.lowercase()}_${press.name.lowercase()}"

    fun binding(context: Context, star: Star, press: Press): Action {
        val name = Prefs(context).raw.getString(key(star, press), null) ?: return default(star, press)
        return Action.entries.firstOrNull { it.name == name } ?: default(star, press)
    }

    fun bind(context: Context, star: Star, press: Press, action: Action) {
        Prefs(context).raw.edit().putString(key(star, press), action.name).apply()
    }

    /**
     * Décodeur pur : reçoit les down/up/long de la SystemUI et rend l'appui reconnu. [doubleBound]
     * dit si le double de ce bouton a une action (sinon le simple part tout de suite).
     */
    class Decoder(private val doubleBound: (Star) -> Boolean) {
        private val down = HashMap<Star, Boolean>()      // true = long déjà rendu
        private val pendingShort = HashMap<Star, Long>()  // simple en attente d'un éventuel second appui

        /** Appui reconnu à l'instant [atMs], ou null. */
        fun accept(code: Int, isDown: Boolean, isLong: Boolean, atMs: Long): Pair<Star, Press>? {
            val star = Star.entries.firstOrNull { code in it.codes } ?: return null
            if (isDown) {
                if (isLong && down[star] != true) { down[star] = true; pendingShort.remove(star); return star to Press.LONG }
                down.putIfAbsent(star, false)
                return null
            }
            val wasLong = down.remove(star) ?: return null
            if (wasLong) return null
            val first = pendingShort.remove(star)
            if (first != null && atMs - first <= DOUBLE_MS) return star to Press.DOUBLE
            if (!doubleBound(star)) return star to Press.SHORT
            pendingShort[star] = atMs
            return null
        }

        /** Simple dont la fenêtre du double est passée ; à appeler [DOUBLE_MS] après chaque relâchement. */
        fun expire(atMs: Long): List<Pair<Star, Press>> {
            val due = pendingShort.filterValues { atMs - it > DOUBLE_MS }.keys.toList()
            due.forEach { pendingShort.remove(it) }
            return due.map { it to Press.SHORT }
        }
    }

    private var appContext: Context? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val decoder = Decoder { star -> appContext?.let { binding(it, star, Press.DOUBLE) != Action.NONE } ?: false }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getIntExtra(EXTRA_CODE, -1)
            val now = android.os.SystemClock.uptimeMillis()
            decoder.accept(code, intent.getBooleanExtra(EXTRA_DOWN, false), intent.getBooleanExtra(EXTRA_LONG, false), now)
                ?.let { (s, p) -> run(context, s, p) }
            if (!intent.getBooleanExtra(EXTRA_DOWN, false)) main.postDelayed({
                decoder.expire(android.os.SystemClock.uptimeMillis()).forEach { (s, p) -> run(context, s, p) }
            }, DOUBLE_MS + 20)
        }
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        try {
            appContext!!.registerReceiver(receiver, IntentFilter(ACTION))
            Log.i(TAG, "boutons du volant écoutés")
        } catch (e: Exception) { Log.w(TAG, "écoute impossible : ${e.message}") }
    }

    private fun run(context: Context, star: Star, press: Press) {
        val action = binding(context, star, press)
        Log.i(TAG, "${star.name} ${press.name} → ${action.name}")
        if (action == Action.NONE) return
        val text = try { WheelActions.perform(context, action) } catch (e: Exception) { Log.w(TAG, "${action.name} : ${e.message}"); "" }
        if (text.isNotEmpty()) android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
