package app.mg4dash.cockpit

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Cockpit v2 — charte « Cabina nocturna » (lienzo de diseño MG4 cockpit).
 * Tout est dessiné en code (pas de XML de mise en page) : moins d'inflation, moins de mémoire,
 * démarrage plus rapide sur le MT2712. Les dimensions sont en pixels de la dalle 1920 × 720
 * (densité 160 dpi, 1 dp = 1 px) : [px] convertit si l'appareil a une autre densité.
 */
object Pal {
    var dark = true
        private set

    var bg = 0; var surface = 0; var surface2 = 0; var surface3 = 0; var line = 0
    var text = 0; var text2 = 0; var text3 = 0
    var action = 0; var energy = 0; var heat = 0; var cold = 0; var alert = 0; var onFill = 0
    const val AA_BG = 0xFF05080B.toInt()
    const val AA_GRID = 0xFF11171D.toInt()

    init { set(true) }

    fun set(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = 0xFF0B0F13.toInt(); surface = 0xFF131920.toInt(); surface2 = 0xFF1B232C.toInt(); surface3 = 0xFF24303B.toInt()
            line = 0xFF2A3440.toInt(); text = 0xFFEDF1F4.toInt(); text2 = 0xFFAEB9C3.toInt(); text3 = 0xFF8794A0.toInt()
            action = 0xFF5AB4FF.toInt(); energy = 0xFF3DDC97.toInt(); heat = 0xFFFF9F43.toInt(); cold = 0xFF4FD1E8.toInt()
            alert = 0xFFFF5A5F.toInt(); onFill = 0xFF0B0F13.toInt()
        } else {
            bg = 0xFFEEF1F4.toInt(); surface = 0xFFFFFFFF.toInt(); surface2 = 0xFFE3E8ED.toInt(); surface3 = 0xFFD6DDE4.toInt()
            line = 0xFFC7D0D9.toInt(); text = 0xFF0E141A.toInt(); text2 = 0xFF45515D.toInt(); text3 = 0xFF5C6874.toInt()
            action = 0xFF1565C0.toInt(); energy = 0xFF0E7A4F.toInt(); heat = 0xFFB85500.toInt(); cold = 0xFF00798C.toInt()
            alert = 0xFFC62828.toInt(); onFill = 0xFFFFFFFF.toInt()
        }
    }
}

/** Échelle typographique (px) : rien sous 20 px. */
object Ty {
    const val CAPTION = 20f
    const val LABEL = 24f
    const val BODY = 28f
    const val TITLE = 34f
    const val VALUE = 48f
    const val BIG = 72f
    const val HERO = 112f

    val ui: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val uiMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** Chiffres : condensé et gras, à chasse fixe (tnum). */
    val num: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val numLight: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
}

/** Mesures communes. */
object Dim {
    const val RAIL = 112
    const val PAD = 24
    const val GAP = 16
    const val CARD_R = 20f
    const val BTN_R = 16f
    const val TAP = 88
    const val TAP_MIN = 76
    const val PRIMARY = 96
    const val CLIMATE_BAR = 112
    /** Bouton antibrouillard que MG superpose en haut à gauche (≈ x 20–76, y 12–68). */
    const val FOG_SAFE = 80
    const val STRIP = 640
    const val AA_W = 1280
}

fun Context.px(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.px(v: Int): Int = context.px(v)
fun Context.pxf(v: Float): Float = v * resources.displayMetrics.density

/** Style de texte : taille en px de la dalle, couleur et police. */
/** N'affecte le texte que s'il change : évite une remise en page à chaque trame du véhicule. */
fun TextView.put(s: CharSequence) { if (text.toString() != s.toString()) text = s }

fun TextView.style(sizePx: Float, color: Int, face: Typeface = Ty.ui, upper: Boolean = false): TextView {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, context.pxf(sizePx))
    setTextColor(color)
    typeface = face
    includeFontPadding = false
    if (upper) { isAllCaps = true; letterSpacing = 0.08f }
    if (face === Ty.num || face === Ty.numLight) fontFeatureSettings = "tnum"
    return this
}

/** Formats espagnols : virgule décimale, espace avant l'unité. */
object Fmt {
    fun dec(v: Float, d: Int = 1): String = String.format(java.util.Locale.US, "%.${d}f", v).replace('.', ',')
    fun pct(v: Int?): String = v?.let { "$it %" } ?: "— %"
    fun hm(totalMin: Int): String = if (totalMin >= 60) "${totalMin / 60} h ${"%02d".format(totalMin % 60)} min" else "$totalMin min"
    fun clock(h: Int, m: Int) = "%02d:%02d".format(h, m)
    /** Consigne de clim : valeur et unité séparées (LO / HI sans unité). */
    fun temp(t: Float?): Pair<String, String> = when {
        t == null || t.isNaN() || t <= 0f -> "—" to ""
        t <= app.mg4dash.car.VehicleState.TEMP_MIN -> "LO" to ""
        t >= app.mg4dash.car.VehicleState.TEMP_MAX -> "HI" to ""
        else -> (if (t % 1f == 0f) t.toInt().toString() else dec(t)) to "°C"
    }
}
