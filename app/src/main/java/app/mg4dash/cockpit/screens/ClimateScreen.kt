package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w

/**
 * Clima · pantalla completa (lienzo : Climate.dc.html). Sans barre de clim : l'écran entier l'est.
 * La direction de l'air suit la table de MG4Control (0 visage … 4 pare-brise, 1/3 combinés).
 */
class ClimateScreen(host: Host) : Screen(host) {
    override val nav = Nav.CLIMATE
    override val climateBar = false

    private lateinit var temp: ValueText
    private lateinit var outside: android.widget.TextView
    private lateinit var ac: Btn
    private lateinit var auto: Btn
    private lateinit var fan: Segmented
    private lateinit var fanTitle: android.widget.TextView
    private lateinit var air: Segmented
    private lateinit var rec: Segmented
    private lateinit var seatL: Segmented
    private lateinit var seatR: Segmented
    private lateinit var wheel: Btn
    private lateinit var heatCard: View
    private val airValues = listOf(0, 1, 2, 3, 4)

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            // marche / arrêt et dégivrage : boutons physiques de la voiture (retour d'essai)
            addView(c.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.label("Conductor"), lp(0, WRAP, 1f))
                outside = c.text("", Ty.LABEL, Pal.text2); addView(outside)
            })
            addView(View(c), lp(MATCH, 0, 1f))
            // Consigne en colonne (+ en haut, − en bas, à toute la largeur) : rien ne peut sortir de la
            // carte, quelle que soit la largeur de la fenêtre (le + était coupé dans la voiture).
            addView(Btn(c, null, Icons.plus, 44).apply {
                iconView?.color = Pal.heat; contentDescription = "Subir temperatura"; setOnClickListener { CarBridge.stepTemp(1) }
            }, lp(MATCH, Dim.PRIMARY))
            temp = ValueText(c, Ty.HERO, Ty.TITLE).apply { gravity = Gravity.CENTER }
            addView(temp, lp(MATCH, WRAP))
            addView(Btn(c, null, Icons.minus, 44).apply {
                iconView?.color = Pal.cold; contentDescription = "Bajar temperatura"; setOnClickListener { CarBridge.stepTemp(-1) }
            }, lp(MATCH, Dim.PRIMARY))
            addView(c.text("LO · 17 … 32 · HI", Ty.CAPTION, Pal.text2).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(c.row(12).apply {
                ac = Btn(c, "A/C", null, textSize = Ty.TITLE, led = true).apply { accent = Pal.cold; setOnClickListener { CarBridge.toggleAc() } }
                auto = Btn(c, "AUTO", null, textSize = Ty.TITLE, led = true).apply { setOnClickListener { CarBridge.toggleAuto() } }
                addView(ac, lp(0, Dim.PRIMARY, 1f)); addView(auto, lp(0, Dim.PRIMARY, 1f))
            })
        }, w(560))

        addView(c.card(gap = 24).apply {
            fanTitle = c.label("Ventilador"); addView(fanTitle)
            fan = Segmented(c, (VehicleState.FAN_MIN..VehicleState.FAN_MAX).map { it.toString() }, textSize = Ty.LABEL, gap = 6) { i ->
                val cur = CarBridge.state.value.fanSpeed ?: 1
                CarBridge.stepFan(i + 1 - cur)
            }
            addView(fan, lp(MATCH, Dim.TAP))
            addView(c.label("Dirección del aire"))
            air = Segmented(c, listOf("Cara", "Cara y\npies", "Pies", "Pies y\nparabrisas", "Parabrisas"), height = 136, textSize = Ty.CAPTION) { i -> CarBridge.setFanDirection(airValues[i]) }
            addView(air, lp(MATCH, 136))
            addView(c.label("Recirculación"))
            rec = Segmented(c, listOf("Exterior", "Interior", "Auto")) { i -> CarBridge.setRecirc(listOf(1, 0, 2)[i]) }
            addView(rec, lp(MATCH, Dim.TAP))
        }, w(640))

        heatCard = c.card().apply {
            // conducteur : siège + volant ensemble ; passager à part
            addView(c.label("Conductor"))
            seatL = Segmented(c, listOf("Off", "1", "2", "3"), selectedLook = Look.HEAT) { i -> stepSeat(true, i) }
            addView(seatL, lp(MATCH, Dim.TAP))
            wheel = Btn(c, "Volante", Icons.wheel, 34, vertical = false, led = true).apply { accent = Pal.heat; setOnClickListener { CarBridge.toggleSteeringHeat() } }
            addView(wheel, lp(MATCH, Dim.TAP))
            addView(c.label("Acompañante"))
            seatR = Segmented(c, listOf("Off", "1", "2", "3"), selectedLook = Look.HEAT) { i -> stepSeat(false, i) }
            addView(seatR, lp(MATCH, Dim.TAP))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(Btn(c, "App de clima original", Icons.chevronRight, 28, vertical = false, iconEnd = true).apply {
                setOnClickListener { Launch.open(c, Launch.HVAC) }
            }, lp(MATCH, Dim.TAP_MIN))
        }
        addView(heatCard, w(528))
    }

    private fun stepSeat(left: Boolean, target: Int) = CarBridge.setSeatHeat(left, target)

    override fun bind(s: VehicleState) {
        val t = s.driverTemp
        Fmt.temp(t).let { (v, u) -> temp.set(v, if (u.isEmpty()) "" else "°") }
        outside.put(s.outsideTemp?.let { "Fuera ${Math.round(it)} °C" } ?: "")
        ac.on = s.acOn == true
        auto.on = s.autoOn == true
        fan.select(s.fanSpeed?.minus(1))
        fanTitle.put(if (s.autoOn == true) "Ventilador · automático" else "Ventilador")
        air.select(airValues.indexOf(s.fanDirection).takeIf { it >= 0 })
        rec.select(when (s.recirc) { 1 -> 0; 0 -> 1; 2 -> 2; else -> null })
        heatCard.alpha = if (CarBridge.heatControl) 1f else 0.4f
        seatL.select(s.seatHeatL); seatR.select(s.seatHeatR)
        if (s.seatHeatL == 0) seatL.lookFor(0, Look.SELECTED)
        if (s.seatHeatR == 0) seatR.lookFor(0, Look.SELECTED)
        wheel.on = s.wheelHeat == 1
    }
}
