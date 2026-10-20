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
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.media.MediaHub
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

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
    private var period = 0   // 0 = desde la última carga, 1 = desde el arranque
    private lateinit var periods: Segmented
    private lateinit var usedTotal: ValueText
    private lateinit var usedKm: ValueText
    private lateinit var split: android.widget.LinearLayout
    private lateinit var driveV: ValueText
    private lateinit var climV: ValueText
    private lateinit var accV: ValueText
    private lateinit var regenV: ValueText
    private lateinit var regenKm: android.widget.TextView
    private val profileBtns = ArrayList<Btn>()
    private var last = VehicleState()

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        media = MediaCard(c, compact = true, big = true, favourites = true)
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
            // « Energía usada » du lienzo (Energy.dc.html) : par période, répartition et régénération
            addView(usedCard(), lp(MATCH, 0, 1f))
            // Perfiles : un toque activa y aplica ; mantener pulsado abre la pantalla para editarlo
            addView(c.row(Dim.GAP).apply {
                for (i in 0 until Adas.PROFILES) {
                    val b = Btn(c, "Perfil ${i + 1}", Icons.shield, 28, vertical = false, sub = "", led = true).apply {
                        accent = Pal.energy
                        setOnClickListener {
                            Adas.setActive(c, i)
                            when (Adas.apply(c)) {
                                app.mg4dash.car.CarBridge.Write.SENT -> host.toast("Aplicando perfil ${i + 1}")
                                else -> host.toast("Ajustes del coche no disponibles", tone = Pal.alert)
                            }
                            bind(last)
                        }
                        setOnLongClickListener { Adas.setActive(c, i); host.go("adas"); true }
                    }
                    profileBtns += b
                    addView(b, lp(0, Dim.TAP, 1f))
                }
            }, lp(MATCH, Dim.TAP))
        }, w(528))
    }

    private fun usedCard() = c.card(gap = 14).apply {
        periods = Segmented(c, listOf("Desde la carga", "Desde el arranque"), height = 56, textSize = Ty.CAPTION, gap = 10) { i -> period = i; bindUsed() }
        addView(periods, lp(MATCH, 56))
        addView(c.row(10).apply {
            gravity = Gravity.BOTTOM
            usedTotal = ValueText(c, Ty.VALUE, Ty.LABEL); addView(usedTotal)
            usedKm = ValueText(c, Ty.TITLE, Ty.LABEL); addView(usedKm)
        })
        split = c.row(3); addView(split, lp(MATCH, 32))
        addView(c.row(12).apply {
            driveV = part(this, "Conducción", Pal.text)
            climV = part(this, "Climatización", Pal.cold)
            accV = part(this, "Accesorios", Pal.text3)
        })
        addView(View(c), lp(MATCH, 0, 1f))
        addView(c.row(12).apply {
            background = rounded(Pal.surface2, Dim.BTN_R)
            setPadding(18, 0, 18, 0)
            addView(c.text("Regenerado", Ty.LABEL, Pal.text2))
            regenV = ValueText(c, Ty.TITLE, Ty.CAPTION); addView(regenV)
            addView(View(c), lp(0, 1, 1f))
            regenKm = c.text("", Ty.TITLE, Pal.energy, Ty.num); addView(regenKm)
        }, lp(MATCH, 56))
    }

    private fun part(row: android.widget.LinearLayout, title: String, color: Int): ValueText {
        val v = ValueText(c, Ty.TITLE, Ty.CAPTION)
        row.addView(c.col(2).apply {
            addView(c.row(6).apply {
                addView(View(c).apply { background = rounded(color, 4f) }, lp(14, 14))
                addView(c.text(title, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
            })
            addView(v)
        }, lp(0, WRAP, 1f))
        return v
    }

    private fun bindUsed() {
        if (!::periods.isInitialized) return
        periods.select(period)
        val t = if (period == 0) app.mg4dash.energy.EnergyMonitor.sinceCharge.value else app.mg4dash.energy.EnergyMonitor.sinceStart.value
        val used = t.driveKwh + t.climKwh + t.accKwh
        usedTotal.set(Fmt.dec(used), "kWh usados en")
        usedKm.set(t.km.toString(), "km")
        driveV.set(Fmt.dec(t.driveKwh), "kWh"); climV.set(Fmt.dec(t.climKwh), "kWh"); accV.set(Fmt.dec(t.accKwh), "kWh")
        regenV.set(Fmt.dec(t.regenKwh), "kWh")
        // autonomie récupérée : kWh régénérés au rythme de consommation de la période
        regenKm.put(t.per100?.takeIf { it > 0.5f }?.let { "+" + Math.round(t.regenKwh / it * 100f) + " km" } ?: "")
        split.removeAllViews()
        val total = used.coerceAtLeast(0.001f)
        for ((v, col) in listOf(t.driveKwh to Pal.text, t.climKwh to Pal.cold, t.accKwh to Pal.text3)) {
            if (v <= 0f) continue
            split.addView(View(c).apply { background = rounded(col, 6f) }, lp(0, MATCH, v / total))
        }
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

    // Radio : relue toutes les 3 s tant que l'écran est visible et que la radio est la source
    private var radioJob: kotlinx.coroutines.Job? = null
    private var shown = false

    override fun onShow() { shown = true; syncRadio() }
    override fun onHide() { shown = false; syncRadio() }

    private fun syncRadio() {
        val want = shown && MediaHub.radioActive
        if (want == (radioJob != null)) return
        if (!want) { radioJob?.cancel(); radioJob = null; app.mg4dash.media.Radio.stop(); return }
        val owner = c as? androidx.lifecycle.LifecycleOwner ?: return
        radioJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                app.mg4dash.media.Radio.start(c, 3_000L)
                try { app.mg4dash.media.Radio.state.collect { media.bind() } } finally { app.mg4dash.media.Radio.stop() }
            }
        }
    }

    override fun bind(s: VehicleState) {
        syncRadio()
        media.bind()
        soc.set(s.socPrecise?.let { Fmt.dec(it) } ?: s.batteryPercent?.toString() ?: "—", "%")
        range.set(s.rangeKm?.toString() ?: "—", "km")
        val h = ChargeGuard.history.value.takeLast(10)
        cons.set(h, emptyList(), h.takeIf { it.isNotEmpty() }?.average()?.toFloat())
        bindUsed()
        last = s
        val active = Adas.active(c)
        val st = Adas.state.value
        val match = Adas.matches(c) == true
        profileBtns.forEachIndexed { i, b ->
            val on = i == active
            b.look = if (on) Look.SELECTED else Look.NEUTRAL
            b.on = on && match
            b.setSub(when {
                !on -> "Aplicar"
                st.busy -> "Aplicando…"
                match -> "Aplicado"
                else -> Adas.changes(c).size.let { "$it cambio" + if (it == 1) "" else "s" }
            })
        }
    }
}
