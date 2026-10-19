package app.mg4dash.cockpit

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState

/** Sections du rail. Les écrans de détail (Asistentes, Carga) s'affichent sous leur section. */
enum class Nav { HOME, CLIMATE, ENERGY, VEHICLE, MEDIA, AA, SETTINGS }

/** Ce que l'activité offre aux écrans. */
interface Host {
    val context: Context
    val driving: Boolean
    fun go(screen: String)
    fun toast(title: String, sub: String = "", tone: Int = Pal.text2)
}

/** Un écran du cockpit : construit une fois, mis à jour à chaque état véhicule. */
abstract class Screen(protected val host: Host) {
    protected val c: Context get() = host.context
    abstract val nav: Nav
    open val climateBar: Boolean = true
    /** Écran réservé à l'arrêt : verrouillé en roulant. */
    open val parkedOnly: Boolean = false
    abstract fun build(): View
    open fun bind(s: VehicleState) {}
    /** Écran qui affiche la puissance en direct : [onLive] est appelé toutes les 0,2 s tant qu'il est visible. */
    open val liveRefresh: Boolean = false
    open fun onLive() {}
    open fun onShow() {}
    open fun onHide() {}
    val view: View by lazy { build() }
}

// ───────────────────────────── rail ─────────────────────────────

/**
 * Rail de navigation (112 px). En haut, 80 px laissés libres pour le bouton antibrouillard que
 * MG superpose ; l'heure est en bas. En roulant, les sections réservées à l'arrêt sont verrouillées.
 */
@SuppressLint("ViewConstructor")
class Rail(c: Context, private val onNav: (Nav) -> Unit, private val onLocked: () -> Unit) : LinearLayout(c) {
    private class Item(val nav: Nav, val btn: Btn, val lock: IconView, val parkedOnly: Boolean)
    private val items = ArrayList<Item>()
    private val aaDot = View(c)
    val clock = c.text("--:--", Ty.TITLE, Pal.text, Ty.num).apply { gravity = Gravity.CENTER; maxLines = 1 }
    private var locked = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Pal.surface)
        addView(View(c), lp(MATCH, Dim.FOG_SAFE))
        add(Nav.HOME, "Inicio", Icons.home, false)
        add(Nav.CLIMATE, "Clima", Icons.climate, false)
        add(Nav.ENERGY, "Energía", Icons.energy, true)
        add(Nav.VEHICLE, "Vehículo", Icons.car, true)
        add(Nav.MEDIA, "Medios", Icons.media, true)
        add(Nav.AA, "Android\nAuto", Icons.phone, false)
        add(Nav.SETTINGS, "Ajustes", Icons.settings, true)
        addView(View(c), lp(MATCH, 0, 1f))
        addView(clock, lp(MATCH, WRAP).apply { bottomMargin = 14 })
    }

    private fun add(nav: Nav, label: String, icon: Icon, parkedOnly: Boolean) {
        val frame = FrameLayout(context)
        val b = Btn(context, label, icon, iconSize = 28, textSize = Ty.CAPTION).apply { look = Look.GHOST }
        b.labelView?.setLineSpacing(0f, 0.9f)
        frame.addView(b, FrameLayout.LayoutParams(96, 78))
        val lock = IconView(context, Icons.lock, 20, 3.5f).apply { color = Pal.text2; visibility = GONE }
        frame.addView(lock, FrameLayout.LayoutParams(20, 20).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 6; rightMargin = 10 })
        if (nav == Nav.AA) {
            aaDot.background = rounded(Pal.heat, 7f)
            frame.addView(aaDot, FrameLayout.LayoutParams(14, 14).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 4; rightMargin = 28 })
        }
        b.setOnClickListener { if (locked && parkedOnly) onLocked() else onNav(nav) }
        items.add(Item(nav, b, lock, parkedOnly))
        addView(frame, lp(96, 78).apply { topMargin = if (items.size == 1) 0 else 6 })
    }

    fun select(nav: Nav) {
        for (it in items) {
            val on = it.nav == nav
            it.btn.look = if (on) Look.RAISED else Look.GHOST
            it.btn.iconView?.color = if (on) Pal.action else Pal.text2
            it.btn.labelView?.setTextColor(if (on) Pal.text else Pal.text2)
        }
    }

    fun setLocked(driving: Boolean) {
        if (locked == driving) return
        locked = driving
        for (it in items) it.lock.visibility = if (driving && it.parkedOnly) VISIBLE else GONE
    }

    fun setAaState(color: Int) { aaDot.background = rounded(color, 7f) }
}

// ───────────────────────────── barre de clim ─────────────────────────────

/**
 * Barre de climatisation persistante (112 px) : température, ventilation, AUTO, A/C, recyclage,
 * sièges et volant chauffants, dégivrages, « Más » et marche/arrêt. Commandes de [CarBridge].
 */
@SuppressLint("ViewConstructor")
class ClimateBar(c: Context, private val onMore: () -> Unit) : LinearLayout(c) {
    private val temp = ValueText(c, Ty.VALUE, Ty.LABEL)
    private val fanValue = c.text("—", Ty.TITLE, Pal.text, Ty.num)
    private val fanBars = LevelBars(c, 7, 8, 6, rising = true, gapPx = 3).apply { color = Pal.action }
    private val auto = tile("AUTO", null)
    private val ac = tile("A/C", null).apply { accent = Pal.cold }
    private val recirc = tile(null, Icons.recirc)
    private val seatL = seatTile(Icons.seatLeft)
    private val wheel = tile(null, Icons.wheel).apply { accent = Pal.heat }
    private val seatR = seatTile(Icons.seatRight)
    private val defF = tile(null, Icons.defrostFront).apply { accent = Pal.heat }
    private val defR = tile(null, Icons.defrostRear).apply { accent = Pal.heat }
    private val more = Btn(c, null, Icons.chevronUp, 32).apply { look = Look.NEUTRAL }
    private val power = tile(null, Icons.power).apply { accent = Pal.energy }
    private val dimmable = c.row(10)
    private val seatLBars get() = seatL.getChildAt(seatL.childCount - 1) as LevelBars
    private val seatRBars get() = seatR.getChildAt(seatR.childCount - 1) as LevelBars

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Pal.surface, Dim.CARD_R)
        setPadding(12, 12, 12, 12)

        val minus = Btn(c, null, Icons.minus, 36).apply { setOnClickListener { CarBridge.stepTemp(-1) } }
        val plus = Btn(c, null, Icons.plus, 36).apply { setOnClickListener { CarBridge.stepTemp(1) } }
        minus.iconView?.color = Pal.cold; plus.iconView?.color = Pal.heat
        temp.gravity = Gravity.CENTER or Gravity.BOTTOM
        dimmable.addView(minus, lp(Dim.TAP, Dim.TAP))
        dimmable.addView(temp, lp(150, WRAP))
        dimmable.addView(plus, lp(Dim.TAP, Dim.TAP))
        dimmable.addView(divider())
        val fanDown = Btn(c, null, Icons.minus, 30).apply { setOnClickListener { CarBridge.stepFan(-1) } }
        val fanUp = Btn(c, null, Icons.plus, 30).apply { setOnClickListener { CarBridge.stepFan(1) } }
        val fanMid = c.col(6).apply {
            gravity = Gravity.CENTER
            addView(c.row(6).apply { gravity = Gravity.CENTER; addView(IconView(c, Icons.fan, 24, 2.8f).apply { color = Pal.text2 }); addView(fanValue) })
            addView(fanBars)
        }
        dimmable.addView(fanDown, lp(Dim.TAP_MIN, Dim.TAP))
        dimmable.addView(fanMid, lp(84, WRAP))
        dimmable.addView(fanUp, lp(Dim.TAP_MIN, Dim.TAP))
        dimmable.addView(divider())
        for (t in listOf(auto, ac, recirc, seatL, wheel, seatR, defF, defR)) dimmable.addView(t, lp(0, Dim.TAP, 1f))
        addView(dimmable, lp(0, Dim.TAP, 1f))
        addView(more, lp(Dim.TAP, Dim.TAP).apply { leftMargin = 10 })
        addView(power, lp(Dim.TAP, Dim.TAP).apply { leftMargin = 10 })

        auto.setOnClickListener { CarBridge.toggleAuto() }
        ac.setOnClickListener { CarBridge.toggleAc() }
        recirc.setOnClickListener { CarBridge.toggleRecirc() }
        seatL.setOnClickListener { CarBridge.cycleSeatHeat(left = true) }
        seatR.setOnClickListener { CarBridge.cycleSeatHeat(left = false) }
        wheel.setOnClickListener { CarBridge.toggleSteeringHeat() }
        defF.setOnClickListener { CarBridge.toggleDefrostFront() }
        defR.setOnClickListener { CarBridge.toggleDefrostRear() }
        more.setOnClickListener { onMore() }
        power.setOnClickListener { CarBridge.togglePower() }
    }

    private fun divider() = View(context).apply { setBackgroundColor(Pal.line) }.also { it.layoutParams = lp(1, 56) }

    private fun tile(label: String?, icon: Icon?) = Btn(context, label, icon, 32, textSize = Ty.LABEL, led = true).apply {
        labelView?.typeface = Ty.uiMedium
    }

    private fun seatTile(icon: Icon) = Btn(context, null, icon, 32).apply {
        accent = Pal.heat
        addView(LevelBars(context, 3, 14, 5).also { it.color = Pal.heat }, lp(WRAP, WRAP).apply { topMargin = 8 })
    }

    fun bind(s: VehicleState) {
        val on = s.hvacOn != false
        dimmable.alpha = if (on) 1f else 0.55f
        power.on = on
        val t = s.driverTemp
        Fmt.temp(t).let { (v, u) -> temp.set(v, u) }
        val fan = s.fanSpeed
        val autoOn = s.autoOn == true
        fanValue.text = when { !on -> "OFF"; autoOn -> "A"; fan != null -> fan.toString(); else -> "—" }
        fanBars.level = if (on && !autoOn) fan ?: 0 else 0
        auto.on = autoOn
        ac.on = s.acOn == true
        recirc.on = s.recirc == 0 || s.recirc == 2
        val heat = CarBridge.heatControl
        for (v in listOf(seatL, wheel, seatR)) v.visibility = if (heat) VISIBLE else GONE
        seatL.on = (s.seatHeatL ?: 0) > 0; seatLBars.level = s.seatHeatL ?: 0
        seatR.on = (s.seatHeatR ?: 0) > 0; seatRBars.level = s.seatHeatR ?: 0
        wheel.on = s.wheelHeat == 1
        defF.on = s.defrostFront == true
        defR.on = s.defrostRear == true
    }
}
