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
import app.mg4dash.cockpit.fixedWidth
import app.mg4dash.cockpit.LivePower
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
import app.mg4dash.cockpit.w
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
    private lateinit var trip: Pair<ValueText, android.widget.TextView>
    private lateinit var sinceCharge: Pair<ValueText, android.widget.TextView>
    private lateinit var adas: android.widget.TextView
    private lateinit var aa: Pill

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        media = MediaCard(c, compact = true, big = true)
        addView(media, w(640))
        addView(c.card().apply {
            addView(c.row().apply {
                addView(c.label("Energía"), lp(0, WRAP, 1f))
                powerValue = ValueText(c, Ty.VALUE, Ty.LABEL); addView(powerValue); powerValue.value.fixedWidth(110)
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
        }, w(560))
        addView(c.col(Dim.GAP).apply {
            // énergie consommée : depuis le démarrage et depuis la dernière charge (EnergyMonitor)
            addView(energyCard("Este viaje").also { trip = it.second }.first, lp(MATCH, 0, 1f))
            addView(energyCard("Desde la última carga").also { sinceCharge = it.second }.first, lp(MATCH, 0, 1f))
            addView(profileCard("Asistentes").also { adas = it.second }.first, lp(MATCH, 0, 1f))
            aa = Pill(c, "Android Auto: buscando teléfono…", Pal.heat)
            addView(Btn(c, null, Icons.phone, 30, vertical = false).apply {
                addView(aa, lp(0, 44, 1f).apply { leftMargin = 12 })
                setOnClickListener { host.go("aa") }
            }, lp(MATCH, Dim.TAP))
        }, w(528))
    }

    /** kWh nets consommés (sortie − régénération) et km parcourus sur la période. */
    private fun energyCard(title: String): Pair<View, Pair<ValueText, android.widget.TextView>> {
        val value = ValueText(c, Ty.VALUE, Ty.LABEL)
        val sub = c.text("", Ty.LABEL, Pal.text2).apply { maxLines = 2; gravity = Gravity.END }
        val card = c.card(gap = 4).apply {
            addView(c.label(title))
            addView(c.row(16).apply {
                gravity = Gravity.BOTTOM
                addView(value); addView(sub, lp(0, WRAP, 1f))
            })
        }
        return card to (value to sub)
    }

    private fun showTotals(v: Pair<ValueText, android.widget.TextView>, t: app.mg4dash.energy.EnergyMonitor.Totals) {
        v.first.set(Fmt.dec(t.netKwh), "kWh")
        v.second.put("${t.km} km" + (t.per100?.let { " · " + Fmt.dec(it) + " kWh/100" } ?: "") +
            (if (t.regenKwh >= 0.05f) "\nrecuperados " + Fmt.dec(t.regenKwh) + " kWh" else ""))
    }

    /** Carte d'état ; la toucher ouvre « Mi perfil », où se règlent conduite et asistentes. */
    private fun profileCard(title: String): Pair<View, android.widget.TextView> {
        val value = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium)
        val card = c.card(gap = 6).apply {
            addView(c.row().apply {
                addView(c.label(title), lp(0, WRAP, 1f))
                addView(IconView(c, Icons.chevronRight, 24, 3f).apply { color = Pal.text2 })
            })
            addView(value.apply { maxLines = 1 })
            isClickable = true
            setOnClickListener { host.go("adas") }
        }
        return card to value
    }

    private val live = LivePower()
    override val liveRefresh = true

    override fun onLive() {
        val text = live.sample()
        power.kw = live.gauge
        if (!text) return
        val kw = live.kw ?: 0f
        powerValue.set((if (kw > 0.05f) "+" else if (kw < -0.05f) "−" else "") + Fmt.dec(Math.abs(kw), 0), "kW", if (kw < -0.05f) Pal.energy else Pal.text)
    }

    override fun bind(s: VehicleState) {
        media.bind()
        soc.set(s.socPrecise?.let { Fmt.dec(it) } ?: s.batteryPercent?.toString() ?: "—", "%")
        range.set(s.rangeKm?.toString() ?: "—", "km")
        val h = ChargeGuard.history.value.takeLast(10)
        cons.set(h, emptyList(), h.takeIf { it.isNotEmpty() }?.average()?.toFloat())
        showTotals(trip, app.mg4dash.energy.EnergyMonitor.sinceStart.value)
        showTotals(sinceCharge, app.mg4dash.energy.EnergyMonitor.sinceCharge.value)
        adas.put(when (Adas.matches(c)) { true -> "Mi perfil"; false -> "De serie"; null -> "—" })
        val connected = MediaHub.androidAutoAvailable()
        aa.set(if (connected) "Android Auto: conectado" else "Android Auto: buscando teléfono…", if (connected) Pal.energy else Pal.heat)
    }
}
