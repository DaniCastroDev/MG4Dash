package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.car.Windows
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
import app.mg4dash.cockpit.header
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
    private lateinit var winSummary: TextView
    private val winCells = ArrayList<TextView>()
    private lateinit var winOpen: Btn
    private lateinit var winClose: Btn
    private var escArmedUntil = 0L

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            addView(c.label("Neumáticos"))
            tyres = TyreView(c); addView(tyres, lp(MATCH, 0, 1f))
            odo = c.text("—", Ty.LABEL, Pal.text2); addView(odo)
        }, w(560))
        addView(c.col(Dim.GAP).apply {
            addView(c.card(gap = 12).apply {
                // réglages de conduite : se règlent dans « Mi perfil », appliqués avec les asistentes
                addView(c.header("Conducción", Btn(c, "Mi perfil", Icons.chevronRight, 24, vertical = false, textSize = Ty.CAPTION, iconEnd = true).apply {
                    setPadding(20, 0, 14, 0); setOnClickListener { host.go("adas") }
                }, 56))
                modeText = setting("Modo de conducción")
                regenText = setting("Regeneración")
                onePedalText = setting("Conducción con un pedal")
            }, lp(MATCH, WRAP))
            addView(c.card(gap = 12).apply {
                // Ventanillas : les quatre à la fois, seules commandes nommées par la voiture (voir Windows)
                winSummary = c.text("—", Ty.CAPTION, Pal.text2).apply { maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
                addView(c.header("Ventanillas", winSummary))
                addView(c.row(8).apply {
                    Windows.NAMES.forEach { n ->
                        addView(c.col(2).apply {
                            addView(c.text(n, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
                            val v = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium).apply { maxLines = 1 }
                            winCells.add(v); addView(v)
                        }, lp(0, WRAP, 1f))
                    }
                })
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(12).apply {
                    winOpen = Btn(c, "Abrir todas", Icons.arrowDown, 30, vertical = false, centered = true).apply { setOnClickListener { windows(true) } }
                    addView(winOpen, lp(0, Dim.TAP_MIN, 1f))
                    winClose = Btn(c, "Cerrar todas", Icons.arrowUp, 30, vertical = false, centered = true).apply { setOnClickListener { windows(false) } }
                    addView(winClose, lp(0, Dim.TAP_MIN, 1f))
                })
            }, lp(MATCH, 0, 1f))
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

    /** Ligne « réglage … valeur » de la carte Conducción ; renvoie la valeur. */
    private fun android.widget.LinearLayout.setting(name: String): TextView {
        val v = c.text("—", Ty.BODY, Pal.text, Ty.uiMedium).apply { maxLines = 1 }
        addView(c.row(12).apply {
            addView(c.text(name, Ty.LABEL, Pal.text2).apply { maxLines = 1 }, lp(0, WRAP, 1f))
            addView(v)
        }, lp(MATCH, WRAP))
        return v
    }

    private fun windows(open: Boolean) {
        when (CarBridge.setWindows(open)) {
            CarBridge.Write.SENT -> host.toast(if (open) "Abriendo las ventanillas" else "Cerrando las ventanillas", "Para pararlas, usa los botones de la puerta")
            else -> host.toast("Ventanillas no disponibles", "El coche no responde a las ventanillas", Pal.alert)
        }
    }

    override fun bind(s: VehicleState) {
        val w = s.windows
        winSummary.put(Windows.summary(w))
        winCells.forEachIndexed { i, t -> t.put(Windows.cell(w?.getOrNull(i))) }
        winOpen.isEnabled = w != null; winClose.isEnabled = w != null
        winOpen.alpha = if (w != null) 1f else 0.5f; winClose.alpha = winOpen.alpha
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
