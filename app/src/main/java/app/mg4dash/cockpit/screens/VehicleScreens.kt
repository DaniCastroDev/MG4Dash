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

/** Asistentes · perfil al arrancar (lienzo : ADAS.dc.html). */
class AdasScreen(host: Host) : Screen(host) {
    override val nav = Nav.VEHICLE
    override val climateBar = false
    private val driveRows = HashMap<Adas.Drive, Pair<TextView, Segmented>>()

    private val rows = HashMap<Adas.Item, Pair<TextView, Segmented>>()
    private lateinit var apply: Btn

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card(gap = 8).apply {
            addView(c.row().apply {
                addView(c.label("Conducción y asistentes"), lp(0, WRAP, 1f))
                addView(c.label("Ahora"), lp(200, WRAP))
                addView(c.label("Mi perfil"), lp(620, WRAP))
            })
            for (d in Adas.Drive.values()) {
                val now = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium)
                val seg = Segmented(c, d.options.map { it.second }, height = ROW_H, textSize = Ty.CAPTION, gap = 8) { i ->
                    Adas.setDrive(c, d, d.options[i].first); bind(CarBridge.state.value)
                }
                driveRows[d] = now to seg
                addView(c.row(16).apply {
                    addView(c.text(d.title, Ty.LABEL, Pal.text).apply { maxLines = 2 }, lp(0, WRAP, 1f))
                    addView(now, lp(184, WRAP))
                    addView(seg, lp(620, ROW_H))
                }, lp(MATCH, WRAP))
            }
            for (item in Adas.Item.values()) {
                val now = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium)
                val seg = Segmented(c, item.options.map { it.second }, height = ROW_H, textSize = Ty.CAPTION, gap = 8) { i ->
                    Adas.setProfile(c, item, item.options[i].first); bind(CarBridge.state.value)
                }
                rows[item] = now to seg
                addView(c.row(16).apply {
                    addView(c.text(item.title, Ty.LABEL, Pal.text).apply { maxLines = 2 }, lp(0, WRAP, 1f))
                    addView(now, lp(184, WRAP))
                    addView(seg, lp(620, ROW_H))
                }, lp(MATCH, WRAP))
            }
        }, w(1300))
        addView(c.card().apply {
            addView(c.label("Al arrancar"))
            addView(c.text("La normativa de la UE vuelve a activar estos asistentes en cada arranque.", Ty.LABEL, Pal.text2).apply { maxLines = 4 })
            addView(c.text("Tu perfil (modo, regeneración, un pedal y asistentes) se aplica con un toque, con el coche parado.", Ty.LABEL, Pal.text2).apply { maxLines = 3 })
            addView(c.row(10).apply {
                addView(IconView(c, Icons.shield, 26).apply { color = Pal.energy })
                addView(c.text("Frenada de emergencia y aviso de colisión: siempre activos", Ty.LABEL, Pal.text).apply { maxLines = 3 }, lp(0, WRAP, 1f))
            })
            addView(View(c), lp(MATCH, 0, 1f))
            apply = Btn(c, "Aplicar mi perfil ahora", Icons.shield, 32, vertical = false, textSize = Ty.BODY).apply {
                look = Look.PRIMARY
                setOnClickListener {
                    when (Adas.apply(c)) {
                        CarBridge.Write.SENT -> host.toast("Aplicando mi perfil")
                        CarBridge.Write.MOVING -> host.toast("Detén el coche para aplicar el perfil", tone = Pal.heat)
                        CarBridge.Write.SPEED_UNKNOWN -> host.toast("No se puede leer la velocidad", tone = Pal.heat)
                        CarBridge.Write.UNAVAILABLE -> host.toast("Ajustes del coche no disponibles", tone = Pal.alert)
                    }
                }
            }
            addView(apply, lp(MATCH, Dim.PRIMARY))
        }, w(444))
    }

    override fun bind(s: VehicleState) {
        val st = Adas.state.value
        val prof = Adas.profile(c)
        val drive = Adas.driveProfile(c)
        for ((d, pair) in driveRows) {
            val cur = Adas.driveNow(d)
            pair.first.put(d.options.firstOrNull { it.first == cur && it.first != -1 }?.second ?: "—")
            pair.second.select(d.options.indexOfFirst { it.first == drive[d] }.takeIf { it >= 0 })
        }
        for ((item, pair) in rows) {
            val cur = st.current[item]
            pair.first.put(item.options.firstOrNull { it.first == cur }?.second ?: "—")
            pair.second.select(item.options.indexOfFirst { it.first == prof[item] }.takeIf { it >= 0 })
        }
        apply.setLabel(if (st.busy) "Aplicando…" else if (Adas.matches(c) == true) "Perfil aplicado" else "Aplicar mi perfil ahora")
    }
}

/** Rangées du profil : 8 réglages tiennent dans la page sans barre de clim. */
private const val ROW_H = 66
