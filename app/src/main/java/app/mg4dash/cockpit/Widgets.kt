package app.mg4dash.cockpit

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser

// ───────────────────────────── mise en page ─────────────────────────────

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
/**
 * Colonne d'écran proportionnelle : la largeur de la maquette (écran de 1920 px) sert de poids,
 * pour que tout tienne si la voiture laisse un peu moins de place (barre système, police plus large).
 */
fun w(designPx: Int) = LinearLayout.LayoutParams(0, MATCH, designPx.toFloat())
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.col(gap: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = Spacer(gap) }
}

fun Context.row(gap: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = Spacer(gap) }
}

/** Espacement entre enfants (diviseur transparent) : équivalent du `gap` du lienzo. */
class Spacer(private val size: Int) : android.graphics.drawable.ColorDrawable(0) {
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** Panneau du lienzo : fond surface, coins 20 px, marge intérieure 24 px. */
fun Context.card(pad: Int = Dim.PAD, gap: Int = Dim.GAP): LinearLayout = col(gap).apply {
    background = rounded(Pal.surface, Dim.CARD_R)
    setPadding(pad, pad, pad, pad)
}

fun Context.text(s: String = "", size: Float = Ty.BODY, color: Int = Pal.text, face: android.graphics.Typeface = Ty.ui, upper: Boolean = false) =
    TextView(this).apply { text = s; style(size, color, face, upper); maxLines = 2 }

/** En-tête de carte : libellé entier à gauche, pastille d'état calée à droite (abrégée si besoin). */
fun Context.header(title: String, pill: View, h: Int = 40): LinearLayout = row().apply {
    addView(label(title), lp(WRAP, WRAP))
    addView(android.widget.FrameLayout(context).apply {
        addView(pill, android.widget.FrameLayout.LayoutParams(WRAP, h, Gravity.END or Gravity.CENTER_VERTICAL))
    }, lp(0, h, 1f).apply { leftMargin = 12 })
}

/** Étiquette de section en capitales (20 px, text-2). */
fun Context.label(s: String) = text(s, Ty.CAPTION, Pal.text2, Ty.uiMedium, upper = true).apply { maxLines = 1 }

/** Valeur chiffrée + unité alignées sur la ligne de base. */
class ValueText(c: Context, size: Float, unitSize: Float, private val unitColor: Int = Pal.text2) : LinearLayout(c) {
    val value = c.text("—", size, Pal.text, Ty.num).apply { maxLines = 1 }
    val unit = c.text("", unitSize, unitColor, Ty.uiMedium).apply { maxLines = 1 }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(value)
        addView(unit, lp(WRAP, WRAP).apply { leftMargin = (unitSize / 4).toInt(); bottomMargin = (size * 0.08f).toInt() })
    }
    private val baseValuePx = value.textSize
    private val probe = android.graphics.Paint(value.paint)

    /**
     * Si la place manque (police plus large, écran plus étroit), le chiffre rétrécit jusqu'à 60 %
     * au lieu de pousser les boutons voisins hors de la carte.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            probe.textSize = baseValuePx; probe.typeface = value.typeface
            val valueW = probe.measureText(value.text.toString())
            val unitText = unit.text.toString()
            val unitW = if (unitText.isEmpty()) 0f else unit.paint.measureText(unitText) + (unit.layoutParams as MarginLayoutParams).leftMargin
            val target = if (valueW > 0 && valueW + unitW > avail)
                (baseValuePx * (avail - unitW) / valueW).coerceIn(baseValuePx * 0.6f, baseValuePx) else baseValuePx
            if (Math.abs(value.textSize - target) > 0.5f) value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun set(v: String, u: String = unit.text.toString(), color: Int = Pal.text) {
        if (value.text != v) value.text = v
        if (unit.text != u) unit.text = u
        value.setTextColor(color)
    }
}

// ───────────────────────────── icônes ─────────────────────────────

/** Dessine une [Icon] (viewBox 32) au trait arrondi, à la couleur du texte. */
class IconView(c: Context, icon: Icon, private val sizePx: Int = 32, private val strokeVb: Float = 2.5f) : View(c) {
    private val paths = ArrayList<Pair<Path, Boolean>>()
    private val m = Matrix()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    var icon: Icon = icon
        set(v) { field = v; build(); invalidate() }
    var color: Int = Pal.text
        set(v) { if (field != v) { field = v; invalidate() } }

    init { build() }

    private fun build() {
        paths.clear()
        for (part in icon.parts) {
            val p = cache.getOrPut(part.d) { PathParser.createPathFromPathData(part.d) }
            val copy = Path(p)
            if (part.rotate != 0f) copy.transform(Matrix().apply { setRotate(part.rotate, 16f, 16f) })
            if (icon.mirror) copy.transform(Matrix().apply { setScale(-1f, 1f, 16f, 16f) })
            paths.add(copy to part.fill)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val s = width / 32f
        stroke.color = color; fill.color = color
        stroke.strokeWidth = strokeVb
        canvas.save()
        canvas.scale(s, s)
        for ((p, f) in paths) canvas.drawPath(p, if (f) fill else stroke)
        canvas.restore()
    }

    companion object { private val cache = HashMap<String, Path>() }
}

// ───────────────────────────── boutons ─────────────────────────────

enum class Look { NEUTRAL, RAISED, PRIMARY, SELECTED, HEAT, ALERT, GHOST, DISABLED }

/**
 * Bouton du lienzo : icône facultative, libellé, sous-libellé, voyant (LED). Les styles suivent
 * le vocabulaire « sélectionné = surface-3 + texte bleu + anneau 2 px », « primaire = fond bleu ».
 */
@SuppressLint("ViewConstructor")
open class Btn(
    c: Context,
    label: String? = null,
    icon: Icon? = null,
    iconSize: Int = 32,
    vertical: Boolean = true,
    textSize: Float = Ty.LABEL,
    sub: String? = null,
    led: Boolean = false,
    radius: Float = Dim.BTN_R,
    iconEnd: Boolean = false,
    /** Horizontal : icône et texte centrés (boutons principaux du lienzo). */
    centered: Boolean = false,
    /** Chevron « › » au bout : le bouton ouvre une autre vue. */
    chevron: Boolean = false,
) : LinearLayout(c) {
    val iconView: IconView? = icon?.let { IconView(c, it, iconSize) }
    val labelView: TextView? = label?.let { c.text(it, textSize, Pal.text, Ty.uiMedium).apply { maxLines = 2; gravity = if (vertical) Gravity.CENTER else Gravity.START } }
    val subView: TextView? = sub?.let { c.text(it, Ty.CAPTION, Pal.text2).apply { maxLines = 1 } }
    private val ledView: View? = if (led) View(c) else null
    private val chevronView: IconView? = if (chevron) IconView(c, Icons.chevronRight, 26, 3f) else null
    private val bg = GradientDrawable().apply { cornerRadius = radius }
    var look: Look = Look.NEUTRAL
        set(v) { field = v; paint() }
    /** Couleur « allumé » de l'icône et du voyant (chaleur, froid, énergie…). */
    var accent: Int = Pal.action
        set(v) { field = v; paint() }
    var on: Boolean = false
        set(v) { if (field != v) { field = v; paint() } }

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
        val inner = if (vertical) 8 else 14
        if (!iconEnd) iconView?.let { addView(it) }
        if (labelView != null || subView != null) {
            val texts = c.col(2).apply { gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START }
            labelView?.let { texts.addView(it) }
            subView?.let { texts.addView(it) }
            val grow = !vertical && !centered
            addView(texts, lp(if (grow) 0 else WRAP, WRAP, if (grow) 1f else 0f).apply {
                if (iconView != null && !iconEnd) { if (vertical) topMargin = inner else leftMargin = inner }
            })
        }
        if (iconEnd) iconView?.let { addView(it, lp(WRAP, WRAP).apply { leftMargin = inner }) }
        ledView?.let { addView(it, lp(28, 5).apply { if (vertical) topMargin = 8 else leftMargin = inner }) }
        chevronView?.let { addView(it, lp(WRAP, WRAP).apply { leftMargin = inner }) }
        if (!vertical) setPadding(20, 0, 20, 0)
        paint()
    }

    fun setLabel(s: String) { if (labelView?.text != s) labelView?.text = s }
    fun setSub(s: String) { if (subView?.text != s) subView?.text = s }

    protected fun paint() {
        val (fill, ink, ring) = when (look) {
            Look.NEUTRAL -> Triple(Pal.surface2, Pal.text, 0)
            Look.RAISED -> Triple(Pal.surface3, Pal.text, 0)
            Look.PRIMARY -> Triple(Pal.action, Pal.onFill, 0)
            Look.SELECTED -> Triple(Pal.surface3, Pal.action, Pal.action)
            Look.HEAT -> Triple(Pal.surface3, Pal.heat, Pal.heat)
            Look.ALERT -> Triple(Pal.alert, Pal.onFill, 0)
            Look.GHOST -> Triple(0, Pal.text2, 0)
            Look.DISABLED -> Triple(Pal.surface2, Pal.text3, 0)
        }
        bg.setColor(fill)
        if (ring != 0) bg.setStroke(2, ring) else bg.setStroke(0, 0)
        val iconInk = if (on && look == Look.NEUTRAL) accent else if (look == Look.NEUTRAL) Pal.text2 else ink
        iconView?.color = iconInk
        labelView?.setTextColor(ink)
        subView?.setTextColor(if (look == Look.PRIMARY || look == Look.ALERT) ink else Pal.text2)
        ledView?.background = rounded(if (on) accent else Pal.line, 3f)
        chevronView?.color = if (look == Look.PRIMARY || look == Look.ALERT) ink else Pal.text2
        isEnabled = look != Look.DISABLED
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { scaleX = 0.97f; scaleY = 0.97f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { scaleX = 1f; scaleY = 1f }
        }
        return super.onTouchEvent(e)
    }
}

/** Sélecteur à choix unique ; [select] met à jour l'affichage sans rappeler [onPick]. */
class Segmented(
    c: Context,
    labels: List<String>,
    height: Int = Dim.TAP,
    textSize: Float = Ty.LABEL,
    gap: Int = 12,
    private val selectedLook: Look = Look.SELECTED,
    private val onPick: (Int) -> Unit,
) : LinearLayout(c) {
    val buttons = labels.mapIndexed { i, l ->
        Btn(c, l, textSize = textSize).also { b ->
            b.labelView?.typeface = Ty.num
            b.labelView?.fontFeatureSettings = "tnum"
            b.setOnClickListener { onPick(i) }
            addView(b, lp(0, height, 1f).apply { if (i > 0) leftMargin = gap })
        }
    }
    var selected = -1
        private set

    init { orientation = HORIZONTAL }

    fun select(i: Int?) {
        selected = i ?: -1
        buttons.forEachIndexed { k, b -> b.look = if (k == selected) selectedLook else Look.NEUTRAL }
    }
    fun lookFor(i: Int, look: Look) { buttons.getOrNull(i)?.look = look }
}

/** Pastille d'état : point coloré + texte, fond surface-2. */
class Pill(c: Context, s: String = "", dot: Int = Pal.text2) : LinearLayout(c) {
    private val dotView = View(c)
    val textView = c.text(s, 22f, Pal.text, Ty.uiMedium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Pal.surface2, 999f)
        setPadding(14, 0, 18, 0)
        addView(dotView, lp(12, 12))
        addView(textView, lp(WRAP, WRAP).apply { leftMargin = 10 })
        set(s, dot)
    }
    fun set(s: String, dot: Int) {
        if (textView.text != s) textView.text = s
        dotView.background = rounded(dot, 6f)
    }
}

// ───────────────────────────── jauges ─────────────────────────────

/** Barres de niveau (sièges 0–3, ventilateur 1–7). */
class LevelBars(c: Context, private val count: Int, private val w: Int, private val h: Int, private val rising: Boolean = false, private val gapPx: Int = 4) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var level = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var color = Pal.heat
        set(v) { if (field != v) { field = v; invalidate() } }
    override fun onMeasure(a: Int, b: Int) = setMeasuredDimension(count * w + (count - 1) * gapPx, if (rising) h + count * 2 else h)
    override fun onDraw(canvas: Canvas) {
        for (i in 1..count) {
            val bh = if (rising) h + i * 2f else h.toFloat()
            val x = (i - 1) * (w + gapPx).toFloat()
            r.set(x, height - bh, x + w, height.toFloat())
            paint.color = if (i <= level) color else Pal.line
            canvas.drawRoundRect(r, 3f, 3f, paint)
        }
    }
}

/** Barre de batterie : remplissage, segment « à venir » (charge ou V2L) et repère de limite. */
class BatteryBar(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var level = 0f; var pendingTo = -1f; var marker = -1f
    var fillColor = Pal.energy; var pendingColor = Pal.energy
    fun set(level: Float, marker: Float = -1f, pendingTo: Float = -1f) {
        if (this.level == level && this.marker == marker && this.pendingTo == pendingTo) return
        this.level = level; this.marker = marker; this.pendingTo = pendingTo; invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val top = 10f; val bottom = height - 10f; val w = width.toFloat()
        r.set(0f, top, w, bottom); paint.color = Pal.surface3; canvas.drawRoundRect(r, 12f, 12f, paint)
        canvas.save(); canvas.clipRect(0f, top, w, bottom)
        if (pendingTo > level) {
            paint.color = pendingColor; paint.alpha = 72
            canvas.drawRect(w * level / 100f, top, w * pendingTo / 100f, bottom, paint)
            paint.alpha = 255
        }
        paint.color = fillColor
        r.set(0f, top, w * level.coerceIn(0f, 100f) / 100f, bottom); canvas.drawRoundRect(r, 12f, 12f, paint)
        canvas.restore()
        if (marker in 0f..100f) {
            paint.color = Pal.text
            val x = (w * marker / 100f).coerceIn(2f, w - 2f)
            canvas.drawRoundRect(x - 2f, 0f, x + 2f, height.toFloat(), 2f, 2f, paint)
        }
    }
}

/**
 * Puissance batterie en direct (tension × courant du pack, kW ; négatif = régénération), lue toutes
 * les 0,2 s par les vues qui l'affichent. La jauge suit chaque lecture en lissant ; le chiffre ne
 * change que deux fois par seconde pour rester lisible.
 */
class LivePower {
    /** Valeur lissée pour la jauge. */
    var gauge = 0f; private set
    /** Dernière valeur brute (null : pas de mesure). */
    var kw: Float? = null; private set
    private var lastText = 0L

    /** Une lecture ; renvoie true quand le chiffre doit être réaffiché. */
    fun sample(): Boolean {
        val v = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_VOL)
        val a = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_CRNT)
        val now = if (v != null && a != null) v * a / 1000f else null
        kw = now
        val target = now ?: 0f
        gauge = if (Math.abs(target - gauge) < 0.05f) target else gauge + (target - gauge) * 0.5f
        val t = android.os.SystemClock.uptimeMillis()
        if (t - lastText < TEXT_MS) return false
        lastText = t
        return true
    }

    companion object {
        const val SAMPLE_MS = 200L
        const val TEXT_MS = 500L
    }
}

/** Puissance instantanée : −60 kW (régénération, vert) … +150 kW (consommation). */
class PowerGauge(c: Context, private val minKw: Float = -60f, private val maxKw: Float = 150f) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var drawnKw = 0f
    /** Ne redessine que si la barre bouge d'au moins 1 px (lue 5 fois par seconde). */
    var kw = 0f
        set(v) {
            field = v
            if (width == 0 || Math.abs(v - drawnKw) * width / (maxKw - minKw) >= 1f) invalidate()
        }
    override fun onDraw(canvas: Canvas) {
        drawnKw = kw
        val w = width.toFloat(); val h = height.toFloat()
        val zero = w * (-minKw) / (maxKw - minKw)
        val top = h * 0.3f; val bottom = h * 0.7f
        r.set(0f, top, w, bottom); paint.color = Pal.surface3; canvas.drawRoundRect(r, h, h, paint)
        if (kw > 0) { paint.color = Pal.text; r.set(zero, top, zero + (w - zero) * (kw / maxKw).coerceAtMost(1f), bottom) }
        else { paint.color = Pal.energy; r.set(zero - zero * (kw / minKw).coerceAtMost(1f), top, zero, bottom) }
        canvas.drawRoundRect(r, h, h, paint)
        paint.color = Pal.text; canvas.drawRoundRect(zero - 1.5f, 0f, zero + 1.5f, h, 2f, 2f, paint)
    }
}

/**
 * Graphique tactile : le doigt choisit un point (toucher ou glisser) ; au lâcher, la sélection reste
 * [HOLD_MS] puis revient d'elle-même au résumé. [onSelect] reçoit l'indice, ou −1 au retour.
 */
abstract class TouchChart(c: Context) : View(c) {
    var selected = -1
        private set
    var onSelect: ((Int) -> Unit)? = null
    protected val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(22f) }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipRect = RectF()
    private val clear = Runnable { pick(-1) }

    /** Indice sous le doigt, −1 sans données. */
    protected abstract fun indexAt(x: Float): Int

    private fun pick(i: Int) { if (i != selected) { selected = i; invalidate(); onSelect?.invoke(i) } }

    /** Données remplacées : l'indice choisi peut ne plus exister. */
    protected fun dataChanged(size: Int) { if (selected >= size) { selected = -1; onSelect?.invoke(-1) } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); removeCallbacks(clear); pick(indexAt(e.x)) }
            MotionEvent.ACTION_MOVE -> pick(indexAt(e.x))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> postDelayed(clear, HOLD_MS)
        }
        return true
    }

    override fun onDetachedFromWindow() { removeCallbacks(clear); super.onDetachedFromWindow() }

    /** Bulle de lecture centrée sur [cx], en haut du graphique. */
    protected fun tip(canvas: Canvas, s: String, cx: Float, top: Float = 0f) {
        val w = tipText.measureText(s) + 28f; val h = tipText.textSize + 18f
        val l = (cx - w / 2).coerceIn(0f, (width - w).coerceAtLeast(0f))
        tipRect.set(l, top, l + w, top + h)
        tipBg.color = Pal.surface3; canvas.drawRoundRect(tipRect, 10f, 10f, tipBg)
        tipText.color = Pal.text; canvas.drawText(s, l + 14f, top + h - 13f, tipText)
    }

    companion object { const val HOLD_MS = 4_000L }
}

/** Histogramme avec ligne de moyenne facultative (consommation par km, énergie par jour). Tactile. */
class BarChart(c: Context) : TouchChart(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(20f) }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f) }
    var values: List<Float> = emptyList()
    var labels: List<String> = emptyList()
    var average: Float? = null
    var highlightLast = true
    /** Couleur de la dernière barre (lienzo : blanc en conduite, bleu ailleurs). */
    var lastColor = Pal.action
    var unitTop = ""
    fun set(values: List<Float>, labels: List<String> = emptyList(), average: Float? = null) {
        this.values = values; this.labels = labels; this.average = average; dataChanged(values.size); invalidate()
    }
    override fun indexAt(x: Float): Int = if (values.isEmpty() || width == 0) -1 else (x / (width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val bottomPad = if (labels.isNotEmpty()) 30f else 4f
        val h = height - bottomPad; val w = width.toFloat()
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.1f
        val slot = w / values.size; val bw = (slot * 0.62f).coerceAtLeast(2f)
        paint.color = Pal.line; canvas.drawRect(0f, h - 1f, w, h, paint)
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - bw) / 2
            paint.color = when {
                selected >= 0 -> if (i == selected) Pal.action else Pal.text3
                highlightLast && i == values.lastIndex -> lastColor
                else -> Pal.text3
            }
            canvas.drawRoundRect(x, h - h * v / max, x + bw, h - 1f, 3f, 3f, paint)
        }
        average?.let { a ->
            val y = h - h * a / max
            dash.color = Pal.text2
            canvas.drawLine(0f, y, w, y, dash)
        }
        labels.forEachIndexed { i, l ->
            if (l.isEmpty()) return@forEachIndexed
            txt.color = if (i == selected) Pal.action else Pal.text2
            val tw = txt.measureText(l)
            canvas.drawText(l, (i * slot + slot / 2 - tw / 2).coerceIn(0f, w - tw), height - 4f, txt)
        }
    }
}

/**
 * Courbe de charge : batterie (%) et puissance (kW) dans le même graphique, sur le même axe de temps.
 * Axe de gauche en kW (couleur de la puissance), axe de droite en % (couleur de la batterie).
 * Au doigt : curseur et bulle « heure · % · kW » du point choisi.
 */
class ChargeCurve(c: Context) : TouchChart(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(20f) }
    private val path = Path()
    /** Échantillons (minutes depuis le début, % batterie, kW). */
    var samples: List<Triple<Float, Float, Float>> = emptyList()
    var spanMin = 60f
    var startLabel = ""; var endLabel = ""
    var maxKw = 8f
    /** Début de la session (heure de la bulle), 0 = inconnu. */
    var startAt = 0L
    private val left = 76f
    fun set(samples: List<Triple<Float, Float, Float>>, spanMin: Float, startLabel: String, endLabel: String, maxKw: Float) {
        this.samples = samples; this.spanMin = spanMin; this.startLabel = startLabel; this.endLabel = endLabel; this.maxKw = maxKw
        dataChanged(samples.size); invalidate()
    }
    private fun xOf(t: Float) = left + (width - 2 * left) * (t / spanMin).coerceIn(0f, 1f)
    override fun indexAt(x: Float): Int = if (samples.isEmpty()) -1 else samples.indices.minByOrNull { Math.abs(xOf(samples[it].first) - x) } ?: -1
    override fun onDraw(canvas: Canvas) {
        val right = width - left
        val top = 14f; val bot = height - 38f; val h = bot - top
        fun kwLabel(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else Fmt.dec(v)) + " kW"
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = bot - h * f
            grid.color = Pal.line; canvas.drawLine(left, y, right, y, grid)
            txt.color = Pal.energy
            val kl = kwLabel(maxKw * f); canvas.drawText(kl, left - 12f - txt.measureText(kl), y + 7f, txt)
            txt.color = Pal.text2
            canvas.drawText("${(100 * f).toInt()} %", right + 12f, y + 7f, txt)
        }
        txt.color = Pal.text2
        canvas.drawText(startLabel, left, height - 6f, txt)
        canvas.drawText(endLabel, right - txt.measureText(endLabel), height - 6f, txt)
        if (samples.size < 2) return
        fun yKw(k: Float) = bot - h * (k / maxKw).coerceIn(0f, 1f)
        fun ySoc(s: Float) = bot - h * (s / 100f).coerceIn(0f, 1f)
        path.reset(); path.moveTo(xOf(samples[0].first), bot)
        samples.forEach { (t, _, k) -> path.lineTo(xOf(t), yKw(k)) }
        path.lineTo(xOf(samples.last().first), bot); path.close()
        area.color = Pal.energy; area.alpha = 36; canvas.drawPath(path, area)
        path.reset()
        samples.forEachIndexed { i, (t, _, k) -> if (i == 0) path.moveTo(xOf(t), yKw(k)) else path.lineTo(xOf(t), yKw(k)) }
        line.color = Pal.energy; canvas.drawPath(path, line)
        path.reset()
        samples.forEachIndexed { i, (t, soc, _) -> if (i == 0) path.moveTo(xOf(t), ySoc(soc)) else path.lineTo(xOf(t), ySoc(soc)) }
        line.color = Pal.text; canvas.drawPath(path, line)
        val p = samples.getOrNull(selected) ?: samples.last()
        val px = xOf(p.first)
        if (selected >= 0) { grid.color = Pal.text2; canvas.drawRect(px - 1f, top, px + 1f, bot, grid) }
        area.alpha = 255
        area.color = Pal.energy; canvas.drawCircle(px, yKw(p.third), 7f, area)
        area.color = Pal.text; canvas.drawCircle(px, ySoc(p.second), 7f, area)
        if (selected >= 0) {
            val clock = if (startAt > 0) java.util.Calendar.getInstance().apply { timeInMillis = startAt + (p.first * 60_000L).toLong() }
                .let { Fmt.clock(it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE)) + " · " } else ""
            tip(canvas, clock + "${Math.round(p.second)} % · ${Fmt.dec(p.third)} kW", px, top)
        }
    }
}

/** Mini courbe (consommation récente, sortie V2L). Au doigt : point et bulle [label]. */
class Sparkline(c: Context) : TouchChart(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 6f), 0f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var values: List<Float> = emptyList()
    var color = Pal.text
    var average: Float? = null
    /** Texte de la bulle pour un indice ; null : pas de bulle. */
    var label: ((Int) -> String)? = null
    fun set(v: List<Float>, avg: Float? = null) { values = v; average = avg; dataChanged(v.size); invalidate() }
    private fun xOf(i: Int) = 6f + (width - 12f) * i / (values.size - 1).coerceAtLeast(1)
    override fun indexAt(x: Float): Int = if (values.size < 2) -1 else Math.round((x - 6f) / (width - 12f) * (values.size - 1)).coerceIn(0, values.lastIndex)
    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val tipH = if (label != null) 44f else 0f
        val min = values.minOrNull()!! * 0.9f; val max = values.maxOrNull()!! * 1.1f
        val span = (max - min).coerceAtLeast(0.1f)
        fun y(v: Float) = height - 6f - (height - 12f - tipH) * (v - min) / span
        average?.let { dash.color = Pal.text3; canvas.drawLine(0f, y(it), width.toFloat(), y(it), dash) }
        path.reset()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(xOf(i), y(v)) else path.lineTo(xOf(i), y(v)) }
        line.color = color; canvas.drawPath(path, line)
        val i = if (selected >= 0) selected else values.lastIndex
        if (selected >= 0) { dot.color = Pal.text2; canvas.drawRect(xOf(i) - 1f, tipH, xOf(i) + 1f, height.toFloat(), dot) }
        dot.color = color; canvas.drawCircle(xOf(i), y(values[i]), if (selected >= 0) 7f else 5f, dot)
        if (selected >= 0) label?.let { tip(canvas, it(i), xOf(i)) }
    }
}

/** Vue de dessus de la voiture avec les quatre pressions (bar) et températures. */
class TyreView(c: Context) : View(c) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val tyre = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(34f) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.ui; textSize = c.pxf(20f) }
    private val r = RectF()
    /** FL, FR, RL, RR : presión (bar), temperatura (°C), alerta. */
    var wheels: List<Triple<Float?, Int?, Boolean>> = List(4) { Triple(null, null, false) }
        set(v) { field = v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val bw = width * 0.22f; val bh = height * 0.86f; val top = (height - bh) / 2
        body.color = Pal.text3
        r.set(cx - bw / 2, top, cx + bw / 2, top + bh); canvas.drawRoundRect(r, bw * 0.4f, bw * 0.4f, body)
        val tw = bw * 0.18f; val th = bh * 0.2f
        val pos = listOf(cx - bw / 2 - tw * 0.6f to top + bh * 0.16f, cx + bw / 2 - tw * 0.4f to top + bh * 0.16f,
            cx - bw / 2 - tw * 0.6f to top + bh * 0.64f, cx + bw / 2 - tw * 0.4f to top + bh * 0.64f)
        wheels.forEachIndexed { i, (bar, temp, warn) ->
            val (x, y) = pos[i]
            tyre.color = if (warn) Pal.alert else Pal.energy
            r.set(x, y, x + tw, y + th); canvas.drawRoundRect(r, 6f, 6f, tyre)
            val left = i % 2 == 0
            big.color = if (warn) Pal.alert else Pal.text; small.color = Pal.text2
            val v = bar?.let { Fmt.dec(it, 2) + " bar" } ?: "—"
            val t = temp?.let { "$it °C" } ?: ""
            val tx = if (left) x - 16f - big.measureText(v) else x + tw + 16f
            canvas.drawText(v, tx, y + th / 2, big)
            val sx = if (left) x - 16f - small.measureText(t) else x + tw + 16f
            canvas.drawText(t, sx, y + th / 2 + 30f, small)
        }
    }
}

