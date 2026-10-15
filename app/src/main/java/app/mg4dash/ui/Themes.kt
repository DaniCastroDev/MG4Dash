package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.TypedValue
import androidx.core.content.ContextCompat
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Thèmes : fond et couleur des boutons, choisis séparément (5 × 5).
 * Les ressources utilisent des attributs (?attr/mgPanel…) ; on superpose ici les styles choisis
 * au thème de chaque écran, avant son affichage. Les couleurs « qui ont un sens » (batterie,
 * chaud, froid, alerte) ne changent pas.
 *
 * Le choix est aussi publié dans Settings.Global ("mg4dash_theme" = "fond,boutons") pour que
 * Radio Dash, qui tourne dans l'appli radio SAIC, suive le même thème.
 */
object Themes {
    val BACKGROUNDS = listOf(R.style.MgBg_Night, R.style.MgBg_Black, R.style.MgBg_Charcoal, R.style.MgBg_Forest, R.style.MgBg_Burgundy)
    val ACCENTS = listOf(R.style.MgAccent_Blue, R.style.MgAccent_Grey, R.style.MgAccent_Red, R.style.MgAccent_Green, R.style.MgAccent_Orange)

    /** Couleurs des pastilles du sélecteur (même ordre que les listes). */
    val BACKGROUND_SWATCHES = intArrayOf(0xFF141B29.toInt(), 0xFF0E0E10.toInt(), 0xFF2B2F35.toInt(), 0xFF16271F.toInt(), 0xFF29161C.toInt())
    val ACCENT_SWATCHES = intArrayOf(0xFF2979FF.toInt(), 0xFF8E99A8.toInt(), 0xFFE53935.toInt(), 0xFF00C853.toInt(), 0xFFFF8F00.toInt())

    private const val GLOBAL_KEY = "mg4dash_theme"

    /** Signature du thème courant : sert à savoir si un écran doit se redessiner. */
    fun signature(context: Context): Int = Prefs(context).let { it.themeBackground * 10 + it.themeAccent }

    /** À appeler dans onCreate, avant setContentView. */
    fun apply(activity: Activity) {
        val p = Prefs(activity)
        activity.theme.applyStyle(BACKGROUNDS.getOrElse(p.themeBackground) { BACKGROUNDS[0] }, true)
        activity.theme.applyStyle(ACCENTS.getOrElse(p.themeAccent) { ACCENTS[0] }, true)
    }

    /** Publie le thème pour Radio Dash (uid système requis ; sans effet sinon). */
    fun publish(context: Context) {
        val p = Prefs(context)
        try {
            Settings.Global.putString(context.contentResolver, GLOBAL_KEY, "${p.themeBackground},${p.themeAccent}")
        } catch (e: Exception) {
            Log.w("Thème", "publication pour Radio Dash impossible : ${e.message}")
        }
    }

    private val ATTRS = mapOf(
        R.color.bg_top to R.attr.mgBgTop, R.color.bg_bottom to R.attr.mgBgBottom,
        R.color.panel to R.attr.mgPanel, R.color.panel_raised to R.attr.mgPanel, R.color.panel_border to R.attr.mgPanelBorder,
        R.color.button_idle to R.attr.mgButtonIdle, R.color.button_pressed to R.attr.mgButtonPressed,
        R.color.gauge_off to R.attr.mgGaugeOff, R.color.accent_action to R.attr.mgAction,
    )

    /** Couleur d'une ressource, en suivant le thème pour les teintes réglables. */
    fun color(context: Context, res: Int): Int {
        val attr = ATTRS[res] ?: return ContextCompat.getColor(context, res)
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
            tv.data else ContextCompat.getColor(context, res)
    }

    /** Couleur de sélection (puces actives) du thème courant. */
    fun select(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.mgSelect, tv, true)) tv.data else ContextCompat.getColor(context, R.color.accent_cold)
    }
}
