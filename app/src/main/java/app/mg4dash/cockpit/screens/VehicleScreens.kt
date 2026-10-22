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
    private lateinit var esc: Btn
    private lateinit var drive: Btn
    private lateinit var adas: TextView
    private lateinit var odo: TextView
    private lateinit var winSummary: TextView
    private val winCells = ArrayList<TextView>()
    private val winButtons = ArrayList<Btn>()
    private lateinit var winExit: Btn
    private var escArmedUntil = 0L

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            addView(c.label("Neumáticos"))
            tyres = TyreView(c); addView(tyres, lp(MATCH, 0, 1f))
            odo = c.text("—", Ty.LABEL, Pal.text2); addView(odo)
        }, w(560))
        addView(c.card(gap = 12).apply {
            // Ventanillas, une par une comme l'interrupteur de porte : un toque va au bout, un appui
            // long bouge tant que le doigt reste posé (voir Windows et CarBridge.moveWindow).
            winSummary = c.text("—", Ty.CAPTION, Pal.text2).apply { maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
            addView(c.header("Ventanillas", winSummary))
            // rangées comme dans la voiture : avant, puis arrière
            listOf(listOf(0, 1), listOf(2, 3)).forEach { pair ->
                addView(c.row(12).apply { pair.forEach { i -> addView(windowTile(i), lp(0, MATCH, 1f)) } }, lp(MATCH, 0, 1f))
            }
            addView(c.row(12).apply {
                addView(Btn(c, "Abrir todas", Icons.arrowDown, 30, vertical = false, centered = true).apply { setOnClickListener { all(true) } }.also { winButtons.add(it) }, lp(0, Dim.TAP_MIN, 1f))
                addView(Btn(c, "Cerrar todas", Icons.arrowUp, 30, vertical = false, centered = true).apply { setOnClickListener { all(false) } }.also { winButtons.add(it) }, lp(0, Dim.TAP_MIN, 1f))
            })
            winExit = Btn(c, "Cerrar al salir del coche", null, vertical = false, sub = "—", led = true).apply {
                accent = Pal.action
                setOnClickListener {
                    val p = app.mg4dash.system.Prefs(c)
                    p.windowsAutoClose = !p.windowsAutoClose; on = p.windowsAutoClose
                    if (on) host.toast("Cerrar al salir activado", "Tras circular: en P, al abrir la puerta del conductor o cerrar el coche")
                }
            }
            addView(winExit, lp(MATCH, Dim.TAP))
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
                // modo de conducción, regeneración y un pedal : se cambian en « Mi perfil », con los asistentes
                adas = c.text("—", Ty.CAPTION, Pal.text2)
                drive = Btn(c, "Conducción y asistentes", Icons.shield, 30, vertical = false, sub = "—").apply {
                    addView(adas, lp(WRAP, WRAP).apply { leftMargin = 12 })
                    addView(IconView(c, Icons.chevronRight, 26).apply { color = Pal.text2 }, lp(WRAP, WRAP).apply { leftMargin = 12 })
                    setOnClickListener { host.go("adas") }
                }
                addView(drive, lp(MATCH, Dim.TAP))
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

    /** Une vitre : nom et position, puis ses deux boutons (bajar, subir). */
    private fun windowTile(i: Int): View = c.col(8).apply {
        background = rounded(Pal.surface2, Dim.BTN_R)
        setPadding(16, 10, 16, 10)
        addView(c.row(8).apply {
            addView(c.text(Windows.NAMES[i], Ty.LABEL, Pal.text2).apply { maxLines = 1 }, lp(0, WRAP, 1f))
            val v = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium).apply { maxLines = 1 }
            winCells.add(v); addView(v)
        })
        addView(c.row(8).apply {
            addView(windowButton(i, open = true), lp(0, MATCH, 1f))
            addView(windowButton(i, open = false), lp(0, MATCH, 1f))
        }, lp(MATCH, 0, 1f))
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun windowButton(i: Int, open: Boolean) = Btn(c, null, if (open) Icons.arrowDown else Icons.arrowUp, 32).apply {
        look = Look.RAISED
        contentDescription = "${if (open) "Bajar" else "Subir"} ${Windows.NAMES[i].lowercase()}"
        var downAt = 0L
        var stopping = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; downAt = e.eventTime
                    // comme l'interrupteur de porte : un appui pendant la course l'arrête
                    stopping = CarBridge.windowMoving(i)
                    if (stopping) CarBridge.stopWindow(i)
                    else if (CarBridge.moveWindow(i, open, Windows.HOLD_MAX_MS) != CarBridge.Write.SENT) unavailable()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (!stopping) CarBridge.releaseWindow(i, e.eventTime - downAt)
                }
            }
            true
        }
        winButtons.add(this)
    }

    private fun all(open: Boolean) {
        if (CarBridge.moveAllWindows(open) == CarBridge.Write.SENT)
            host.toast(if (open) "Bajando las ventanillas" else "Subiendo las ventanillas", "Toca una flecha para parar esa ventanilla")
        else unavailable()
    }

    private fun unavailable() = host.toast("Ventanillas no disponibles", "El coche no responde a las ventanillas", Pal.alert)

    override fun bind(s: VehicleState) {
        val w = s.windows
        winSummary.put(Windows.summary(w))
        winCells.forEachIndexed { i, t -> t.put(Windows.cell(w?.getOrNull(i))) }
        winButtons.forEach { it.isEnabled = w != null; it.alpha = if (w != null) 1f else 0.5f }
        winExit.on = app.mg4dash.system.Prefs(c).windowsAutoClose
        winExit.setSub(s.windowExit.ifEmpty { "En P, al abrir la puerta o cerrar el coche" })
        tyres.wheels = (0..3).map { i ->
            val kpa = CarProps.getInt(0x21401553 + i)?.takeIf { it in 50..600 }
            val t = CarProps.getInt(0x2140155b + i)?.takeIf { it in -40..120 }
            val st = CarProps.getInt(0x21401557 + i)
            Triple(kpa?.div(100f), t, st != null && st != 0)
        }
        odo.put(s.odometerKm?.let { "Cuentakilómetros  " + String.format("%,d", it).replace(',', ' ') + " km" } ?: "Cuentakilómetros —")
        val regen = Adas.Drive.REGEN.options.firstOrNull { it.first == s.regenLevel }?.second
        drive.setSub(listOfNotNull(
            VehicleState.driveModeLabel(s.driveMode).takeIf { it != "—" },
            regen?.let { "regeneración ${it.lowercase()}" },
            when (s.onePedal) { 1 -> "un pedal"; else -> null },
        ).joinToString(" · ").ifEmpty { "—" })
        val e = CarBridge.escActive()
        esc.on = e == true
        esc.setSub(when (e) { true -> "Activado"; false -> "Desactivado"; null -> "—" })
        adas.put(when (Adas.matches(c)) { true -> "Mi perfil"; false -> "De serie"; null -> "—" })
    }
}
