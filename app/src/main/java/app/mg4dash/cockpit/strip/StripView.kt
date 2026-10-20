package app.mg4dash.cockpit.strip

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.MainActivity
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.BatteryBar
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.LevelBars
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.PowerGauge
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.fixedWidth
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.screens.MediaCard
import app.mg4dash.cockpit.text
import app.mg4dash.car.CarProps
import java.util.Calendar

/**
 * Contenu de la franja (640 px) : ligne d'état, cinq onglets, cartes, barre de clim compacte en deux
 * rangées. En roulant, les réglages réservés à l'arrêt sont verrouillés. « Más » ouvre la feuille de
 * clim (960 px) par-dessus la projection.
 */
@SuppressLint("ViewConstructor")
class StripView(c: Context, private val onRight: Boolean, private val onExpand: (Boolean) -> Unit) : FrameLayout(c) {
    private val strip = c.col(12).apply { setBackgroundColor(Pal.surface); setPadding(20, 20, 20, 20) }
    private val clock = c.text("--:--", Ty.TITLE, Pal.text, Ty.num)
    private val outside = c.text("", 22f, Pal.text2)
    private val socTop = c.text("", Ty.LABEL, Pal.text, Ty.num)
    private val tabs = ArrayList<Btn>()
    private val pages = FrameLayout(c)
    private val pageViews = ArrayList<View>()
    private var tab = 0
    private var expanded = false
    private var sheet: View? = null
    private var driving = false

    // Inicio
    private val soc = ValueText(c, Ty.BIG, Ty.TITLE)
    private val range = ValueText(c, Ty.TITLE, Ty.CAPTION)
    private val power = PowerGauge(c)
    private val powerText = c.text("", Ty.CAPTION, Pal.text2, Ty.num)
    private val bar = BatteryBar(c)
    private lateinit var modes: Segmented
    private val modeLocked = c.text("Modo y asistentes: solo con el coche parado", Ty.LABEL, Pal.text2).apply { visibility = GONE }
    private var assistsRow: View? = null
    private val adas = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium)
    private val media = MediaCard(c, compact = true)

    // Barra de clima
    private val temp = ValueText(c, Ty.VALUE, Ty.LABEL)
    private val fan = c.text("—", Ty.TITLE, Pal.text, Ty.num)
    private val seatL = Btn(c, null, Icons.seatLeft, 30).apply { look = Look.RAISED; accent = Pal.heat }
    private val seatR = Btn(c, null, Icons.seatRight, 30).apply { look = Look.RAISED; accent = Pal.heat }
    private val seatLBars = LevelBars(c, 3, 14, 5).apply { color = Pal.heat }
    private val seatRBars = LevelBars(c, 3, 14, 5).apply { color = Pal.heat }
    private val wheel = Btn(c, null, Icons.wheel, 30, led = true).apply { look = Look.RAISED; accent = Pal.heat }
    private val ac = Btn(c, "A/C", null, textSize = Ty.LABEL, led = true).apply { look = Look.RAISED; accent = Pal.cold }

    // pages Clima / Energía / Vehículo (déclarées avant init : elles y sont remplies)
    private var recSeg: Segmented? = null
    private var autoBtn: Btn? = null
    private val chargeText = c.text("", Ty.LABEL, Pal.text, Ty.uiMedium)
    private val vehText = c.text("", Ty.LABEL, Pal.text)
    // puissance en direct et heure (déclarées avant init : live() y est appelé)
    private val live = app.mg4dash.cockpit.LivePower()
    private var lastMinute = -1L

    init {
        // ligne d'état : 80 px laissés libres côté gauche pour le bouton antibrouillard MG
        strip.addView(c.row(14).apply {
            addView(clock); addView(outside)
            addView(View(c), lp(0, 1, 1f))
            addView(IconView(c, Icons.battery, 30).apply { color = Pal.energy })
            addView(socTop)
            addView(app.mg4dash.cockpit.Pill(c, "Android Auto", Pal.energy), lp(WRAP, 40))
        }, lp(MATCH, 48))
        strip.addView(c.row(10).apply {
            listOf("Inicio" to Icons.home, "Clima" to Icons.climate, "Energía" to Icons.energy, "Vehículo" to Icons.car, "Medios" to Icons.media).forEachIndexed { i, (l, ic) ->
                val b = Btn(c, l, ic, 26, textSize = Ty.CAPTION).apply { look = Look.GHOST; setOnClickListener { select(i) } }
                tabs.add(b); addView(b, lp(0, 80, 1f))
            }
        }, lp(MATCH, 80))
        pageViews += inicio(); pageViews += climaPage(); pageViews += energiaPage(); pageViews += vehiculoPage(); pageViews += media
        pageViews.forEach { pages.addView(it, LayoutParams(MATCH, MATCH)) }
        strip.addView(pages, lp(MATCH, 0, 1f))
        strip.addView(climateBar(), lp(MATCH, 164))
        addView(strip, LayoutParams(Dim.STRIP, MATCH, if (onRight) Gravity.END else Gravity.START))
        select(0)
        tick()
        live()
    }

    private fun select(i: Int) {
        tab = i
        tabs.forEachIndexed { k, b -> b.look = if (k == i) Look.RAISED else Look.GHOST; b.iconView?.color = if (k == i) Pal.action else Pal.text2 }
        pageViews.forEachIndexed { k, v -> v.visibility = if (k == i) VISIBLE else GONE }
        tick()
    }

    private fun inicio(): View = context.col(12).apply {
        addView(context.card(pad = 14, gap = 6).apply {
            addView(context.row(16).apply { addView(soc); addView(range); addView(View(context), lp(0, 1, 1f)); addView(powerText); powerText.fixedWidth(220) })
            addView(power, lp(MATCH, 24))
            addView(bar, lp(MATCH, 24))
        })
        modes = Segmented(context, listOf("Eco", "Normal", "Sport", "Nieve"), height = Dim.TAP_MIN, textSize = Ty.CAPTION, gap = 8) { i ->
            if (driving) return@Segmented
            CarBridge.setDriveMode(CarBridge.driveModes[i])
        }
        addView(modes, lp(MATCH, Dim.TAP_MIN))
        addView(context.row(12).also { assistsRow = it }.apply {
            addView(Btn(context, null, Icons.shield, 28, vertical = false).apply {
                addView(context.col(2).apply { addView(context.text("Asistentes", Ty.CAPTION, Pal.text2)); addView(adas) }, lp(0, WRAP, 1f).apply { leftMargin = 12 })
                setOnClickListener { if (!driving) Adas.apply(context) }
            }, lp(0, Dim.TAP_MIN, 1f))
            addView(Btn(context, null, Icons.fullscreen, 28).apply {
                setOnClickListener { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, lp(Dim.TAP_MIN, Dim.TAP_MIN))
        })
        addView(modeLocked)
    }

    private fun climaPage(): View = context.col(12).apply {
        addView(context.label("Recirculación"))
        addView(Segmented(context, listOf("Exterior", "Interior", "Auto"), height = Dim.TAP_MIN, textSize = Ty.CAPTION, gap = 8) { i -> CarBridge.setRecirc(listOf(1, 0, 2)[i]) }.also { recSeg = it }, lp(MATCH, Dim.TAP_MIN))
        addView(context.row(12).apply {
            addView(Btn(context, "AUTO", null, textSize = Ty.LABEL, led = true).also { autoBtn = it }.apply { setOnClickListener { CarBridge.toggleAuto() } }, lp(0, Dim.TAP_MIN, 1f))
            addView(Btn(context, "Más clima", Icons.chevronRight, 26, vertical = false, iconEnd = true).apply { setOnClickListener { toggleSheet() } }, lp(0, Dim.TAP_MIN, 1f))
        })
    }

    private fun energiaPage(): View = context.card(pad = 18, gap = 10).apply {
        addView(context.label("Energía"))
        addView(chargeText)
        addView(Btn(context, "Abrir Energía en pantalla completa", Icons.energy, 28, vertical = false).apply {
            setOnClickListener { context.startActivity(Intent(context, MainActivity::class.java).putExtra("screen", "energy").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, lp(MATCH, Dim.TAP_MIN))
    }

    private fun vehiculoPage(): View = context.card(pad = 18, gap = 10).apply {
        addView(context.label("Vehículo"))
        addView(vehText)
        addView(Btn(context, "Abrir Vehículo en pantalla completa", Icons.car, 28, vertical = false).apply {
            setOnClickListener { context.startActivity(Intent(context, MainActivity::class.java).putExtra("screen", "vehicle").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, lp(MATCH, Dim.TAP_MIN))
    }

    private fun climateBar(): View = context.col(12).apply {
        setPadding(0, 12, 0, 0)
        addView(context.row(10).apply {
            addView(Btn(context, null, Icons.minus, 36).apply { look = Look.RAISED; iconView?.color = Pal.cold; setOnClickListener { CarBridge.stepTemp(-1) } }, lp(96, 70))
            temp.gravity = Gravity.CENTER or Gravity.BOTTOM
            addView(temp, lp(0, WRAP, 1f))
            addView(Btn(context, null, Icons.plus, 36).apply { look = Look.RAISED; iconView?.color = Pal.heat; setOnClickListener { CarBridge.stepTemp(1) } }, lp(96, 70))
            addView(Btn(context, null, Icons.minus, 30).apply { look = Look.RAISED; setOnClickListener { CarBridge.stepFan(-1) } }, lp(76, 70))
            addView(context.row(4).apply { gravity = Gravity.CENTER; addView(IconView(context, Icons.fan, 22, 2.8f).apply { color = Pal.text2 }); addView(fan) }, lp(70, WRAP))
            addView(Btn(context, null, Icons.plus, 30).apply { look = Look.RAISED; setOnClickListener { CarBridge.stepFan(1) } }, lp(76, 70))
        })
        // conducteur à gauche (siège + volant), passager à droite ; pas de marche/arrêt ni de dégivrage
        addView(context.row(10).apply {
            seatL.addView(seatLBars, lp(WRAP, WRAP).apply { topMargin = 6 }); seatL.setOnClickListener { CarBridge.cycleSeatHeat(true) }
            seatR.addView(seatRBars, lp(WRAP, WRAP).apply { topMargin = 6 }); seatR.setOnClickListener { CarBridge.cycleSeatHeat(false) }
            wheel.setOnClickListener { CarBridge.toggleSteeringHeat() }
            ac.setOnClickListener { CarBridge.toggleAc() }
            for (b in listOf(seatL, wheel, ac)) addView(b, lp(0, 70, 1f))
            addView(Btn(context, "Más", Icons.chevronRight, 26).apply { look = Look.RAISED; setOnClickListener { toggleSheet() } }, lp(0, 70, 1f))
            addView(seatR, lp(0, 70, 1f))
        })
    }

    private fun toggleSheet() = try { doToggleSheet() } catch (t: Throwable) {
        app.mg4dash.system.Log.e("Strip", "hoja de clima", t)
        sheet?.let { removeView(it) }; sheet = null; expanded = false; onExpand(false)
    }

    private fun doToggleSheet() {
        expanded = !expanded
        if (expanded) {
            val s = ClimateSheet(context) { toggleSheet() }
            sheet = s
            addView(s, LayoutParams(960, MATCH, if (onRight) Gravity.START else Gravity.END))
        } else {
            sheet?.let { removeView(it) }; sheet = null
        }
        onExpand(expanded)
        tick()
    }

    /** Toutes les 0,2 s (service) : jauge de puissance, chiffre deux fois par seconde, heure. */
    fun live() {
        val text = live.sample()
        power.kw = live.gauge
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != lastMinute) {
            lastMinute = minute
            val cal = Calendar.getInstance()
            clock.put(Fmt.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
        }
        if (!text) return
        val kw = live.kw ?: 0f
        powerText.put((if (kw < -0.05f) "Regenerando " else "") + Fmt.dec(Math.abs(kw), 0) + " kW")
        powerText.setTextColor(if (kw < -0.05f) Pal.energy else Pal.text2)
    }

    /** Relecture complète : à chaque changement de l'état du véhicule, des médias ou des asistentes. */
    fun tick() {
        val s = CarBridge.state.value
        outside.put(s.outsideTemp?.let { "${Math.round(it)} °C" } ?: "")
        socTop.put(s.socPrecise?.let { Fmt.dec(it) + " %" } ?: s.batteryPercent?.let { "$it %" } ?: "—")
        driving = (s.speedKmh?.let { Math.abs(it) } ?: 0f) > 2f
        for (k in 2..4) tabs[k].alpha = if (driving && k != 4) 0.55f else 1f

        soc.set(s.socPrecise?.let { Fmt.dec(it) } ?: s.batteryPercent?.toString() ?: "—", "%")
        range.set(s.rangeKm?.toString() ?: "—", "km")
        bar.set(s.socPrecise ?: s.batteryPercent?.toFloat() ?: 0f, (CarProps.targetSocPercent(CarProps.getInt(CarProps.CHRG_TRGT_SOC)) ?: -1).toFloat())
        modes.select(CarBridge.driveModes.indexOf(s.driveMode).takeIf { it >= 0 })
        modes.alpha = if (driving) 0.55f else 1f
        // en marche : le bouton « Asistentes » (refusé à l'arrêt seulement) laisse place à l'avertissement
        modeLocked.visibility = if (driving) VISIBLE else GONE
        assistsRow?.visibility = if (driving) GONE else VISIBLE
        adas.put(when (Adas.matches(context)) { true -> "Mi perfil"; false -> "De serie · aplicar"; null -> "—" })
        media.bind()

        recSeg?.select(when (s.recirc) { 1 -> 0; 0 -> 1; 2 -> 2; else -> null })
        autoBtn?.on = s.autoOn == true

        val plugged = VehicleState.isPlugged(s.chargeStatus)
        chargeText.put(if (plugged) VehicleState.chargeStatusLabel(s.chargeStatus) + (s.chargeRemainMin?.takeIf { it in 1..6000 }?.let { " · " + Fmt.hm(it) } ?: "") else "Sin enchufar")
        vehText.put("Modo " + VehicleState.driveModeLabel(s.driveMode) + " · " + VehicleState.regenLabel(s.regenLevel, s.onePedal))

        val t = s.driverTemp
        Fmt.temp(t).let { (v, u) -> temp.set(v, if (u.isEmpty()) "" else "°") }
        fan.put(when { s.hvacOn == false -> "—"; s.autoOn == true -> "A"; else -> s.fanSpeed?.toString() ?: "—" })
        val heat = CarBridge.heatControl
        for (b in listOf(seatL, wheel, seatR)) b.visibility = if (heat) VISIBLE else GONE
        seatL.on = (s.seatHeatL ?: 0) > 0; seatLBars.level = s.seatHeatL ?: 0
        seatR.on = (s.seatHeatR ?: 0) > 0; seatRBars.level = s.seatHeatR ?: 0
        wheel.on = s.wheelHeat == 1
        ac.on = s.acOn == true
        (sheet as? ClimateSheet)?.bind(s)
    }

    fun dispose() { removeAllViews() }
}

/** Feuille de clim (960 px) au-dessus de la projection : grandes commandes seulement. */
@SuppressLint("ViewConstructor")
class ClimateSheet(c: Context, onClose: () -> Unit) : LinearLayout(c) {
    private val temp = ValueText(c, Ty.HERO, Ty.TITLE)
    private val fan = Segmented(c, (VehicleState.FAN_MIN..VehicleState.FAN_MAX).map { it.toString() }, height = Dim.TAP, textSize = Ty.LABEL, gap = 6) { i -> CarBridge.stepFan(i + 1 - (CarBridge.state.value.fanSpeed ?: 1)) }
    private val air = Segmented(c, listOf("Cara", "Cara y\npies", "Pies", "Pies y\nparabrisas", "Parabrisas"), height = 112, textSize = Ty.CAPTION) { i -> CarBridge.setFanDirection(i) }
    private val seatL = Segmented(c, listOf("Off", "1", "2", "3"), height = Dim.TAP, selectedLook = Look.HEAT) { i -> CarBridge.setSeatHeat(true, i) }
    private val seatR = Segmented(c, listOf("Off", "1", "2", "3"), height = Dim.TAP, selectedLook = Look.HEAT) { i -> CarBridge.setSeatHeat(false, i) }

    init {
        orientation = VERTICAL
        setBackgroundColor(Pal.surface)
        elevation = 24f
        setPadding(24, 24, 24, 24)
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = app.mg4dash.cockpit.Spacer(16)
        addView(c.row().apply {
            addView(c.text("Clima", Ty.TITLE, Pal.text, Ty.uiMedium), lp(0, WRAP, 1f))
            addView(Btn(c, null, Icons.close, 34).apply { setOnClickListener { onClose() } }, lp(Dim.TAP, Dim.TAP))
        })
        addView(c.row(16).apply {
            addView(Btn(c, null, Icons.minus, 44).apply { iconView?.color = Pal.cold; setOnClickListener { CarBridge.stepTemp(-1) } }, lp(136, 120))
            temp.gravity = Gravity.CENTER or Gravity.BOTTOM
            addView(temp, lp(0, WRAP, 1f))
            addView(Btn(c, null, Icons.plus, 44).apply { iconView?.color = Pal.heat; setOnClickListener { CarBridge.stepTemp(1) } }, lp(136, 120))
        })
        addView(fan, lp(MATCH, Dim.TAP))
        addView(air, lp(MATCH, 112))
        addView(c.row(Dim.GAP).apply {
            addView(c.col(8).apply { addView(c.label("Conductor")); addView(seatL, lp(MATCH, Dim.TAP)) }, lp(0, WRAP, 1f))
            addView(c.col(8).apply { addView(c.label("Acompañante")); addView(seatR, lp(MATCH, Dim.TAP)) }, lp(0, WRAP, 1f))
        })
    }

    fun bind(s: VehicleState) {
        val t = s.driverTemp
        Fmt.temp(t).let { (v, u) -> temp.set(v, u) }
        fan.select(s.fanSpeed?.minus(1))
        air.select(s.fanDirection?.takeIf { it in 0..4 })
        seatL.select(s.seatHeatL); seatR.select(s.seatHeatR)
        if (s.seatHeatL == 0) seatL.lookFor(0, Look.SELECTED)
        if (s.seatHeatR == 0) seatR.lookFor(0, Look.SELECTED)
    }
}
