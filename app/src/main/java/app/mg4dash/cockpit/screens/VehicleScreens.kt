package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.TyreView
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w

/** Vehículo (lienzo : Vehicle.dc.html). */
class VehicleScreen(host: Host) : Screen(host) {
    override val nav = Nav.VEHICLE

    private lateinit var tyres: TyreView
    private lateinit var modeText: TextView
    private lateinit var regenText: TextView
    private lateinit var onePedalText: TextView
    private lateinit var esc: Btn
    private lateinit var adas: TextView
    private lateinit var odo: TextView
    private var escArmedUntil = 0L

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            addView(c.label("Neumáticos"))
            tyres = TyreView(c); addView(tyres, lp(MATCH, 0, 1f))
            odo = c.text("—", Ty.LABEL, Pal.text2); addView(odo)
        }, w(560))
        addView(c.card().apply {
            // réglages de conduite : se règlent dans « Mi perfil », appliqués avec les asistentes
            addView(c.label("Modo de conducción"))
            modeText = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium); addView(modeText)
            addView(c.label("Regeneración"))
            regenText = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium); addView(regenText)
            addView(c.label("Conducción con un pedal"))
            onePedalText = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium); addView(onePedalText)
            addView(View(c), lp(MATCH, 0, 1f))
            addView(Btn(c, "Cambiar en Mi perfil", Icons.chevronRight, 28, vertical = false, iconEnd = true).apply {
                setOnClickListener { host.go("adas") }
            }, lp(MATCH, Dim.TAP))
        }, w(640))
        addView(c.col(Dim.GAP).apply {
            addView(c.card().apply {
                addView(c.label("Seguridad"))
                esc = Btn(c, "Control de estabilidad (ESC)", Icons.esc, 30, vertical = false, sub = "Activado", led = true).apply {
                    accent = Pal.energy
                    setOnClickListener {
                        val now = System.currentTimeMillis()
                        if (now < escArmedUntil) { CarBridge.toggleEsc(); escArmedUntil = 0 }
                        else { escArmedUntil = now + 4000; host.toast("Pulsa otra vez para cambiar el ESC", "Por seguridad, se pide confirmación", Pal.heat) }
                    }
                }
                addView(esc, lp(MATCH, Dim.TAP))
                adas = c.text("—", Ty.CAPTION, Pal.text2)
                addView(Btn(c, "Asistentes a la conducción", Icons.shield, 30, vertical = false, iconEnd = false).apply {
                    addView(adas, lp(WRAP, WRAP).apply { leftMargin = 12 })
                    addView(IconView(c, Icons.chevronRight, 26).apply { color = Pal.text2 }, lp(WRAP, WRAP).apply { leftMargin = 12 })
                    setOnClickListener { host.go("adas") }
                }, lp(MATCH, Dim.TAP))
            }, lp(MATCH, WRAP))
            addView(c.card(gap = 12).apply {
                addView(c.label("Utilidades"))
                addView(c.row(12).apply {
                    addView(Btn(c, "Cámara 360°", Icons.camera, 32, textSize = Ty.CAPTION).apply { setOnClickListener { Launch.open(c, Launch.AROUND_VIEW) } }, lp(0, MATCH, 1f))
                    addView(Btn(c, "Ajustes MG", Icons.settings, 32, textSize = Ty.CAPTION).apply { setOnClickListener { Launch.open(c, Launch.SETTINGS) } }, lp(0, MATCH, 1f))
                    addView(Btn(c, "Apps", Icons.apps, 32, textSize = Ty.CAPTION).apply { setOnClickListener { c.startActivity(android.content.Intent(c, app.mg4dash.apps.AppsActivity::class.java)) } }, lp(0, MATCH, 1f))
                }, lp(MATCH, 0, 1f))
            }, lp(MATCH, 0, 1f))
        }, w(528))
    }

    override fun bind(s: VehicleState) {
        tyres.wheels = (0..3).map { i ->
            val kpa = CarProps.getInt(0x21401553 + i)?.takeIf { it in 50..600 }
            val t = CarProps.getInt(0x2140155b + i)?.takeIf { it in -40..120 }
            val st = CarProps.getInt(0x21401557 + i)
            Triple(kpa?.div(100f), t, st != null && st != 0)
        }
        odo.put(s.odometerKm?.let { "Cuentakilómetros  " + String.format("%,d", it).replace(',', ' ') + " km" } ?: "Cuentakilómetros —")
        modeText.put(VehicleState.driveModeLabel(s.driveMode))
        regenText.put(Adas.Drive.REGEN.options.firstOrNull { it.first == s.regenLevel }?.second ?: "—")
        onePedalText.put(when (s.onePedal) { 1 -> "Activada"; 0 -> "Desactivada"; else -> "—" })
        val e = CarBridge.escActive()
        esc.on = e == true
        esc.setSub(when (e) { true -> "Activado"; false -> "Desactivado"; null -> "—" })
        adas.put(when (Adas.matches(c)) { true -> "Mi perfil"; false -> "De serie"; null -> "—" })
    }
}
