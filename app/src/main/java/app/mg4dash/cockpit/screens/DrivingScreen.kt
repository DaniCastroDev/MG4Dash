package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.BarChart
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.PowerGauge
import app.mg4dash.cockpit.Screen
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
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.media.MediaHub

/**
 * En marcha · sin Android Auto (lienzo : Driving.dc.html). Moins d'éléments, plus grands :
 * média à gauche (côté conducteur), énergie au centre, réglages en lecture seule à droite.
 */
class DrivingScreen(host: Host) : Screen(host) {
    override val nav = Nav.HOME

    private lateinit var media: MediaCard
    private lateinit var power: PowerGauge
    private lateinit var powerValue: ValueText
    private lateinit var soc: ValueText
    private lateinit var range: ValueText
    private lateinit var cons: BarChart
    private lateinit var mode: android.widget.TextView
    private lateinit var regen: android.widget.TextView
    private lateinit var adas: android.widget.TextView
    private lateinit var aa: Pill

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        media = MediaCard(c, compact = true, big = true)
        addView(media, lp(640, MATCH))
        addView(c.card().apply {
            addView(c.row().apply {
                addView(c.label("Energía"), lp(0, WRAP, 1f))
                powerValue = ValueText(c, Ty.VALUE, Ty.LABEL); addView(powerValue)
            })
            power = PowerGauge(c); addView(power, lp(MATCH, 48))
            addView(c.row().apply {
                addView(c.text("Regeneración", Ty.CAPTION, Pal.energy), lp(0, WRAP, 1f))
                addView(c.text("Consumo", Ty.CAPTION, Pal.text2))
            })
            addView(c.row(28).apply {
                soc = ValueText(c, Ty.HERO, Ty.VALUE); addView(soc)
                range = ValueText(c, Ty.VALUE, Ty.LABEL); addView(range)
            })
            addView(View(c), lp(MATCH, 0, 1f))
            addView(c.label("Consumo · últimos 10 km"))
            cons = BarChart(c); addView(cons, lp(MATCH, 120))
        }, lp(560, MATCH))
        addView(c.col(Dim.GAP).apply {
            addView(locked("Modo de conducción").also { mode = it.second }.first, lp(MATCH, 0, 1f))
            addView(locked("Regeneración").also { regen = it.second }.first, lp(MATCH, 0, 1f))
            addView(locked("Asistentes").also { adas = it.second }.first, lp(MATCH, 0, 1f))
            aa = Pill(c, "Android Auto: buscando teléfono…", Pal.heat)
            addView(Btn(c, null, Icons.phone, 30, vertical = false).apply {
                addView(aa, lp(0, 44, 1f).apply { leftMargin = 12 })
                setOnClickListener { host.go("aa") }
            }, lp(MATCH, Dim.TAP))
        }, lp(0, MATCH, 1f))
    }

    /** Carte en lecture seule avec cadenas : « Solo con el coche parado ». */
    private fun locked(title: String): Pair<View, android.widget.TextView> {
        val value = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium)
        // le cadenas suffit : « solo con el coche parado » s'affiche en avis si on touche le rail
        val card = c.card(gap = 6).apply {
            addView(c.row().apply {
                addView(c.label(title), lp(0, WRAP, 1f))
                addView(IconView(c, Icons.lock, 24, 3f).apply { color = Pal.text2 })
            })
            addView(value.apply { maxLines = 1 })
        }
        return card to value
    }

    override fun bind(s: VehicleState) {
        media.bind()
        val v = CarProps.getFloat(CarProps.BMS_PACK_VOL); val a = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
        val kw = if (v != null && a != null) v * a / 1000f else 0f
        power.kw = kw
        powerValue.set((if (kw > 0.05f) "+" else if (kw < -0.05f) "−" else "") + Fmt.dec(Math.abs(kw), 0), "kW", if (kw < -0.05f) Pal.energy else Pal.text)
        soc.set(s.batteryPercent?.toString() ?: "—", "%")
        range.set(s.rangeKm?.toString() ?: "—", "km")
        val h = ChargeGuard.history.value.takeLast(10)
        cons.set(h, emptyList(), h.takeIf { it.isNotEmpty() }?.average()?.toFloat())
        mode.put(VehicleState.driveModeLabel(s.driveMode))
        regen.put(VehicleState.regenLabel(s.regenLevel, s.onePedal))
        adas.put(when (Adas.matches(c)) { true -> "Mi perfil"; false -> "De serie"; null -> "—" })
        val connected = MediaHub.androidAutoAvailable()
        aa.set(if (connected) "Android Auto: conectado" else "Android Auto: buscando teléfono…", if (connected) Pal.energy else Pal.heat)
    }
}
