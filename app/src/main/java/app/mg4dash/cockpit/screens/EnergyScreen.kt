package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.BarChart
import app.mg4dash.cockpit.BatteryBar
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.ChargeCurve
import app.mg4dash.cockpit.ChargeLog
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.energy.EnergyMonitor
import app.mg4dash.system.Prefs
import java.util.Calendar

/**
 * Energía (lienzo : Energy, Charging, Charge-Plan, Charge-Done, Charge-Error, V2L).
 * Trois onglets : Consumo, Carga (s'adapte : en charge, terminée, interrompue, V2L) et Programar.
 * Enchufado, l'écran s'ouvre sur Carga.
 */
class EnergyScreen(host: Host) : Screen(host) {
    override val nav = Nav.ENERGY
    override val parkedOnly = true

    private lateinit var tabs: Segmented
    private lateinit var pages: FrameLayout
    private lateinit var consumo: View
    private lateinit var carga: View
    private lateinit var programar: View
    private var tab = 0
    private var autoTabDone = false
    private val prefs by lazy { Prefs(c) }

    // Consumo
    private lateinit var cSoc: ValueText; private lateinit var cRange: ValueText; private lateinit var cAvg: ValueText
    private lateinit var perKm: BarChart; private lateinit var months: BarChart
    private lateinit var periodSel: Segmented; private var period = 0
    private lateinit var split: LinearLayout; private lateinit var splitText: TextView; private lateinit var regenText: TextView
    private lateinit var packV: TextView; private lateinit var packA: TextView; private lateinit var packKw: TextView

    // Carga
    private lateinit var status: Pill; private lateinit var cable: Pill
    private lateinit var soc: ValueText; private lateinit var target: ValueText
    private lateinit var eta: TextView; private lateinit var bar: BatteryBar
    private lateinit var limits: Segmented
    private lateinit var primary: Btn; private lateinit var cableBtn: Btn
    private lateinit var kw: ValueText; private lateinit var va: TextView; private lateinit var added: TextView; private lateinit var cost: TextView
    private lateinit var curve: ChargeCurve
    private lateinit var amps: Segmented; private lateinit var preheat: Btn
    private lateinit var reason: TextView; private lateinit var reasonCard: View
    private lateinit var v2lCard: View; private lateinit var v2lSel: Segmented

    // Programar
    private lateinit var start: TextView; private lateinit var end: TextView
    private lateinit var mode: Segmented
    private var stH = 1; private var stM = 0; private var spH = 7; private var spM = 0

    override fun build(): View = c.col(Dim.GAP).apply {
        tabs = Segmented(c, listOf("Consumo", "Carga", "Programar"), height = Dim.TAP_MIN) { i -> show(i) }
        addView(c.row().apply { addView(tabs, lp(720, Dim.TAP_MIN)) })
        pages = FrameLayout(c)
        consumo = buildConsumo(); carga = buildCarga(); programar = buildProgramar()
        for (v in listOf(consumo, carga, programar)) pages.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(pages, lp(MATCH, 0, 1f))
        show(0)
    }

    private fun show(i: Int) {
        tab = i
        tabs.select(i)
        consumo.visibility = if (i == 0) View.VISIBLE else View.GONE
        carga.visibility = if (i == 1) View.VISIBLE else View.GONE
        programar.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 2) readSchedule()
    }

    override fun onShow() { autoTabDone = false }

    // ───────────── Consumo ─────────────

    private fun buildConsumo(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            addView(c.row(32).apply {
                cSoc = ValueText(c, Ty.BIG, Ty.TITLE); addView(cSoc)
                cRange = ValueText(c, Ty.VALUE, Ty.LABEL); addView(cRange)
                addView(View(c), lp(0, 1, 1f))
                cAvg = ValueText(c, Ty.VALUE, Ty.CAPTION); addView(cAvg)
            })
            addView(c.label("Consumo por km · últimos 50 km"))
            perKm = BarChart(c); addView(perKm, lp(MATCH, 0, 1f))
        }, lp(760, MATCH))
        addView(c.card().apply {
            addView(c.label("Reparto de la energía"))
            periodSel = Segmented(c, listOf("Desde la carga", "Desde el arranque"), height = Dim.TAP_MIN, textSize = Ty.CAPTION) { i -> period = i; periodSel.select(i) }
            periodSel.select(0)
            addView(periodSel, lp(MATCH, Dim.TAP_MIN))
            split = c.row().apply { background = rounded(Pal.surface3, 12f) }
            addView(split, lp(MATCH, 44))
            splitText = c.text("", Ty.LABEL, Pal.text2).apply { maxLines = 4 }; addView(splitText)
            regenText = c.text("", Ty.LABEL, Pal.energy); addView(regenText)
        }, lp(520, MATCH))
        addView(c.card().apply {
            addView(c.label("Batería ahora"))
            packV = c.text("—", Ty.TITLE, Pal.text, Ty.num); addView(c.statRow("Tensión", packV))
            packA = c.text("—", Ty.TITLE, Pal.text, Ty.num); addView(c.statRow("Corriente", packA))
            packKw = c.text("—", Ty.TITLE, Pal.text, Ty.num); addView(c.statRow("Potencia", packKw))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(c.label("kWh/100 km por mes"))
            months = BarChart(c); addView(months, lp(MATCH, 140))
        }, lp(0, MATCH, 1f))
    }

    private fun bindConsumo(s: VehicleState) {
        cSoc.set(s.socPrecise?.let { Fmt.dec(it) } ?: s.batteryPercent?.toString() ?: "—", "%")
        cRange.set(s.rangeKm?.toString() ?: "—", "km")
        val hist = ChargeGuard.history.value.takeLast(50)
        val avg = EnergyMonitor.sinceCharge.value.per100 ?: hist.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        cAvg.set(avg?.let { Fmt.dec(it) } ?: "—", "kWh/100 km")
        perKm.set(hist, emptyList(), avg)
        val t = EnergyMonitor.totals(period)
        val total = (t.driveKwh + t.climKwh + t.accKwh).coerceAtLeast(0.01f)
        split.removeAllViews()
        for ((v, col) in listOf(t.driveKwh to Pal.action, t.climKwh to Pal.cold, t.accKwh to Pal.heat)) {
            if (v <= 0f) continue
            split.addView(View(c).apply { background = rounded(col, 10f) }, lp(0, MATCH, v / total))
        }
        splitText.text = "Conducción ${Fmt.dec(t.driveKwh)} kWh\nClimatización ${Fmt.dec(t.climKwh)} kWh\nAccesorios ${Fmt.dec(t.accKwh)} kWh · ${t.km} km"
        regenText.text = "Regenerado ${Fmt.dec(t.regenKwh)} kWh"
        val v = CarProps.getFloat(CarProps.BMS_PACK_VOL); val a = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
        packV.text = v?.let { "${Math.round(it)} V" } ?: "—"
        packA.text = a?.let { "${Fmt.dec(it)} A" } ?: "—"
        packKw.text = if (v != null && a != null) "${Fmt.dec(v * a / 1000f)} kW" else "—"
        months.set(EnergyMonitor.monthlyPer100().takeLast(12), emptyList(), null)
    }

    // ───────────── Carga ─────────────

    private fun buildCarga(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            addView(c.row(12).apply {
                status = Pill(c, "—", Pal.text3); addView(status, lp(WRAP, 40))
                addView(View(c), lp(0, 1, 1f))
                cable = Pill(c, "—", Pal.text3); addView(cable, lp(WRAP, 40))
            })
            addView(c.row(20).apply {
                gravity = Gravity.BOTTOM
                soc = ValueText(c, Ty.BIG, Ty.TITLE); addView(soc)
                target = ValueText(c, Ty.VALUE, Ty.LABEL); addView(target)
                addView(View(c), lp(0, 1, 1f))
                eta = c.text("", Ty.TITLE, Pal.text, Ty.uiMedium).apply { gravity = Gravity.END }; addView(eta, lp(WRAP, WRAP))
            })
            bar = BatteryBar(c); addView(bar, lp(MATCH, 32))
            addView(c.row(14).apply {
                addView(c.label("Límite"), lp(WRAP, WRAP))
                limits = Segmented(c, (4..10).map { "${it * 10}" }, height = Dim.TAP_MIN, gap = 8) { i -> ChargeGuard.setLimit(c, (i + 4) * 10); limits.select(i) }
                addView(limits, lp(0, Dim.TAP_MIN, 1f))
            })
            addView(c.row(12).apply {
                primary = Btn(c, "Detener carga", Icons.stop, 30, vertical = false, textSize = Ty.BODY).apply { look = Look.PRIMARY; setOnClickListener { onPrimary() } }
                cableBtn = Btn(c, "Desbloquear cable", Icons.unlock, 30, vertical = false, sub = "La tapa se abre a mano").apply { setOnClickListener { CarBridge.toggleCableLock() } }
                addView(primary, lp(0, Dim.TAP, 1f)); addView(cableBtn, lp(0, Dim.TAP, 1f))
            })
        }, lp(652, MATCH))
        addView(c.card().apply {
            addView(c.label("Sesión de carga"))
            addView(c.row(24).apply {
                kw = ValueText(c, Ty.VALUE, Ty.LABEL); addView(kw)
                va = c.text("", Ty.LABEL, Pal.text2, Ty.num); addView(va)
                added = c.text("", Ty.LABEL, Pal.text2, Ty.num); addView(added)
                cost = c.text("", Ty.LABEL, Pal.text2, Ty.num); addView(cost)
            })
            curve = ChargeCurve(c); addView(curve, lp(MATCH, 0, 1f))
        }, lp(628, MATCH))
        addView(c.card().apply {
            reasonCard = c.row(14).apply {
                background = rounded(Pal.surface2, 16f); setPadding(18, 14, 18, 14)
                addView(app.mg4dash.cockpit.IconView(c, Icons.alert, 36).apply { color = Pal.alert })
                reason = c.text("", Ty.LABEL, Pal.text).apply { maxLines = 3 }; addView(reason, lp(0, WRAP, 1f))
            }
            addView(reasonCard)
            addView(c.label("Corriente de carga"))
            amps = Segmented(c, listOf("6 A", "8 A", "16 A", "Máx"), textSize = Ty.LABEL) { i -> CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "courant AC"); amps.select(i) }
            addView(amps, lp(MATCH, Dim.TAP))
            preheat = Btn(c, "Precalentar batería", Icons.climate, 30, vertical = false, led = true).apply {
                accent = Pal.heat
                setOnClickListener { CarProps.setInt(CarProps.DRVNG_PTC_HEAT, if (on) 2 else 1, "réchauffage batterie"); on = !on }
            }
            addView(preheat, lp(MATCH, Dim.TAP))
            v2lCard = c.col(10).apply {
                addView(c.label("V2L · detener al llegar a"))
                v2lSel = Segmented(c, (2..8).map { "${it * 10}" }, height = 80, gap = 6) { i -> CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode((i + 2) * 10), "seuil V2L"); v2lSel.select(i) }
                addView(v2lSel, lp(MATCH, 80))
            }
            addView(v2lCard)
            addView(View(c), lp(MATCH, 0, 1f))
            addView(Btn(c, "Programar carga", Icons.calendar, 30, vertical = false).apply { setOnClickListener { show(2) } }, lp(MATCH, Dim.TAP))
        }, lp(0, MATCH, 1f))
    }

    private fun onPrimary() {
        val s = CarBridge.state.value
        when {
            VehicleState.isDischargeMode(s.chargeStatus) -> if (s.dischargeCtrl == 1) CarBridge.stopV2L() else CarBridge.startV2L()
            s.chargeCtrl == 1 || VehicleState.isCharging(s.chargeStatus) -> CarBridge.stopCharge()
            else -> CarBridge.startCharge()
        }
    }

    private fun bindCarga(s: VehicleState) {
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        val v2l = VehicleState.isDischargeMode(s.chargeStatus)
        val charging = VehicleState.isCharging(s.chargeStatus) || s.chargeCtrl == 1
        val socV = s.socPrecise ?: s.batteryPercent?.toFloat() ?: 0f
        val limit = CarProps.targetSocPercent(CarProps.getInt(CarProps.CHRG_TRGT_SOC)) ?: prefs.chargeLimit
        val stopReason = CarProps.getInt(CarProps.BMS_CHRG_SP_RSN)?.takeIf { it in 1..5 }
        val done = plugged && !charging && !v2l && socV >= limit - 0.5f
        status.set(when {
            !plugged -> "Sin enchufar"
            v2l -> if (s.dischargeCtrl == 1) "V2L en marcha" else "Adaptador V2L conectado"
            charging -> "Cargando · " + VehicleState.chargeStatusLabel(s.chargeStatus)
            done -> "Carga completada"
            stopReason != null -> "Carga interrumpida"
            else -> VehicleState.chargeStatusLabel(s.chargeStatus)
        }, when { charging -> Pal.energy; v2l -> Pal.heat; stopReason != null && !done -> Pal.alert; plugged -> Pal.action; else -> Pal.text3 })
        cable.set(when (s.chargeLock) { 1 -> "Cable bloqueado"; 2 -> "Cable desbloqueado"; else -> "Cable —" }, if (s.chargeLock == 2) Pal.heat else Pal.text3)
        soc.set(Fmt.dec(socV, if (s.socPrecise != null) 1 else 0), "%")
        target.set(if (v2l) "${CarProps.v2lPercent(CarProps.getInt(CarProps.V2X_SP_SOC)) ?: "—"}" else "→ $limit", "%")
        val rem = (if (v2l) s.dischargeRemainMin else s.chargeRemainMin)?.takeIf { it in 1..6000 }
        eta.text = when {
            rem != null && (charging || v2l) -> Fmt.hm(rem) + "\n" + endClock(rem)
            done -> "Completada"
            else -> ""
        }
        bar.fillColor = Pal.energy; bar.pendingColor = if (v2l) Pal.heat else Pal.energy
        bar.set(socV, if (v2l) (CarProps.v2lPercent(CarProps.getInt(CarProps.V2X_SP_SOC)) ?: -1).toFloat() else limit.toFloat(), if (charging) limit.toFloat() else -1f)
        limits.select(((limit / 10) - 4).takeIf { it in 0..6 })
        when {
            v2l -> { primary.setLabel(if (s.dischargeCtrl == 1) "Detener V2L" else "Iniciar V2L"); primary.look = if (s.dischargeCtrl == 1) Look.ALERT else Look.PRIMARY; primary.iconView?.icon = if (s.dischargeCtrl == 1) Icons.stop else Icons.play }
            charging -> { primary.setLabel("Detener carga"); primary.look = Look.PRIMARY; primary.iconView?.icon = Icons.stop }
            plugged && !done -> { primary.setLabel(if (stopReason != null) "Reintentar carga" else "Cargar ahora"); primary.look = Look.PRIMARY; primary.iconView?.icon = if (stopReason != null) Icons.retry else Icons.play }
            plugged && done -> { primary.setLabel("Cargar hasta 100 %"); primary.look = Look.NEUTRAL; primary.iconView?.icon = Icons.energy }
            else -> { primary.setLabel("Sin enchufar"); primary.look = Look.DISABLED; primary.iconView?.icon = Icons.plug }
        }
        if (plugged && done) primary.setOnClickListener { ChargeGuard.setLimit(c, 100); CarBridge.startCharge() } else primary.setOnClickListener { onPrimary() }
        val locked = s.chargeLock != 2
        cableBtn.setLabel(if (locked) "Desbloquear cable" else "Bloquear cable")
        cableBtn.setSub(if (charging && locked) "Detén la carga para desbloquear" else if (locked) "La tapa se abre a mano" else "Ya puedes desenchufar")
        cableBtn.look = when { !CarBridge.cableLockControl -> Look.DISABLED; done && locked -> Look.PRIMARY; else -> Look.NEUTRAL }
        cableBtn.iconView?.icon = if (locked) Icons.unlock else Icons.lock

        val p = ChargeLog.powerKw()?.takeIf { charging || v2l } ?: 0f
        kw.set(Fmt.dec(p), "kW")
        val acV = CarProps.getFloat(CarProps.ONBD_CHRG_AC_VOL); val acA = CarProps.getFloat(CarProps.ONBD_CHRG_AC_CRNT)
        va.text = if (acV != null && acA != null && acV > 50f) "${Math.round(acV)} V · ${Math.round(acA)} A" else ""
        val sess = ChargeLog.session.value
        val kwh = sess.addedKwh
        added.text = "${Fmt.dec(kwh)} kWh"
        cost.text = "${Fmt.dec(kwh * prefs.raw.getFloat("price_home", 0.15f), 2)} €"
        val pts = sess.points
        val span = (pts.lastOrNull()?.first ?: 60f).coerceAtLeast(30f)
        curve.set(pts, span, sess.startedAt.takeIf { it > 0 }?.let { clockOf(it) } ?: "", if (sess.active) "Ahora" else sess.endedAt.takeIf { it > 0 }?.let { clockOf(it) } ?: "", if ((pts.maxOfOrNull { it.third } ?: 0f) > 12f) 120f else 8f)

        amps.select((CarProps.getInt(CarProps.ALTNG_CHRG_CRNT) ?: 0).minus(1).takeIf { it in 0..3 })
        preheat.on = CarProps.getInt(CarProps.DRVNG_PTC_HEAT) == 0
        reasonCard.visibility = if (stopReason != null && plugged && !charging && !done) View.VISIBLE else View.GONE
        reason.text = "Motivo: " + when (stopReason) { 1 -> "defecto del sistema"; 2 -> "sobretemperatura"; 3 -> "fallo del enchufe o la toma"; 4 -> "tensión anómala"; else -> "otro" } + ". Comprueba el cargador o baja la corriente a 16 A."
        v2lCard.visibility = if (v2l) View.VISIBLE else View.GONE
        v2lSel.select((CarProps.v2lPercent(CarProps.getInt(CarProps.V2X_SP_SOC)) ?: 0).div(10).minus(2).takeIf { it in 0..6 })
    }

    private fun clockOf(t: Long): String { val cal = Calendar.getInstance().apply { timeInMillis = t }; return Fmt.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) }
    private fun endClock(min: Int) = "termina a las " + clockOf(System.currentTimeMillis() + min * 60_000L)

    // ───────────── Programar ─────────────

    private fun buildProgramar(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card(gap = 24).apply {
            addView(c.label("Carga programada"))
            mode = Segmented(c, listOf("Sin programar", "Solo en horario", "Horario y luego hasta el límite"), textSize = Ty.CAPTION) { i ->
                CarProps.setInt(CarProps.RESER_CTRL, listOf(2, 1, 3)[i], "programmation"); mode.select(i)
            }
            addView(mode, lp(MATCH, Dim.TAP))
            addView(c.row(Dim.GAP).apply {
                addView(stepper("Empieza") { d -> shift(true, d) }.also { start = it.second }.first, lp(0, WRAP, 1f))
                addView(stepper("Termina") { d -> shift(false, d) }.also { end = it.second }.first, lp(0, WRAP, 1f))
            })
            addView(c.text("La carga empieza sola en el horario elegido. Los días de la semana no se pueden programar en este coche.", Ty.LABEL, Pal.text2).apply { maxLines = 3 })
        }, lp(1080, MATCH))
        addView(c.card().apply {
            addView(c.label("Ahora"))
            addView(c.text("¿Necesitas batería ya?", Ty.TITLE, Pal.text, Ty.uiMedium))
            addView(c.text("Empieza a cargar sin esperar al horario.", Ty.LABEL, Pal.text2))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(Btn(c, "Cargar ahora", Icons.energy, 30, vertical = false, textSize = Ty.BODY).apply { look = Look.PRIMARY; setOnClickListener { CarBridge.startCharge(); show(1) } }, lp(MATCH, Dim.PRIMARY))
        }, lp(0, MATCH, 1f))
    }

    private fun stepper(title: String, onStep: (Int) -> Unit): Pair<View, TextView> {
        val value = c.text("--:--", Ty.BIG, Pal.text, Ty.num).apply { gravity = Gravity.CENTER }
        val box = c.col(10).apply {
            addView(c.label(title))
            addView(c.row(12).apply {
                addView(Btn(c, null, Icons.minus, 40).apply { setOnClickListener { onStep(-15) } }, lp(Dim.PRIMARY, Dim.PRIMARY))
                addView(value, lp(0, WRAP, 1f))
                addView(Btn(c, null, Icons.plus, 40).apply { setOnClickListener { onStep(15) } }, lp(Dim.PRIMARY, Dim.PRIMARY))
            })
        }
        return box to value
    }

    private fun readSchedule() {
        stH = CarProps.getInt(CarProps.RESER_ST_HOUR)?.takeIf { it in 0..23 } ?: stH
        stM = CarProps.getInt(CarProps.RESER_ST_MIN)?.takeIf { it in 0..59 } ?: stM
        spH = CarProps.getInt(CarProps.RESER_SP_HOUR)?.takeIf { it in 0..23 } ?: spH
        spM = CarProps.getInt(CarProps.RESER_SP_MIN)?.takeIf { it in 0..59 } ?: spM
        start.text = Fmt.clock(stH, stM); end.text = Fmt.clock(spH, spM)
        mode.select(when (CarProps.getInt(CarProps.RESER_CTRL)) { 1 -> 1; 3 -> 2; else -> 0 })
    }

    private fun shift(isStart: Boolean, delta: Int) {
        val (h, m) = if (isStart) stH to stM else spH to spM
        val total = ((h * 60 + m + delta) % 1440 + 1440) % 1440
        if (isStart) {
            stH = total / 60; stM = total % 60
            CarProps.setInt(CarProps.RESER_ST_HOUR, stH, "début (h)"); CarProps.setInt(CarProps.RESER_ST_MIN, stM, "début (min)")
        } else {
            spH = total / 60; spM = total % 60
            CarProps.setInt(CarProps.RESER_SP_HOUR, spH, "fin (h)"); CarProps.setInt(CarProps.RESER_SP_MIN, spM, "fin (min)")
        }
        start.text = Fmt.clock(stH, stM); end.text = Fmt.clock(spH, spM)
    }

    override fun bind(s: VehicleState) {
        if (!autoTabDone) { autoTabDone = true; show(if (VehicleState.isPlugged(s.chargeStatus)) 1 else 0) }
        when (tab) { 0 -> bindConsumo(s); 1 -> bindCarga(s) }
    }
}
