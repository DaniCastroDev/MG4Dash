package app.mg4dash.pages

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.system.Apps
import app.mg4dash.system.Prefs
import app.mg4dash.ui.LineChart
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge
import app.mg4dash.ui.StackedBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Page Énergie : recharge (limite, courant, démarrage/arrêt), programmation, V2L et
 * consommation. Réglages écrits sur les mêmes propriétés que l'appli Énergie SAIC (EH32 EU).
 */
class EnergyActivity : PageActivity() {
    override val pageTitle = "ÉNERGIE"
    override val page = app.mg4dash.ui.Dock.Page.ENERGY

    private lateinit var prefs: Prefs
    // Recharge
    private lateinit var soc: TextView
    private lateinit var range: TextView
    private lateinit var status: TextView
    private lateinit var socGauge: SegmentedGauge
    private lateinit var power: TextView
    private lateinit var action: TextView
    private lateinit var limitNote: TextView
    private lateinit var setLimit: (Int?) -> Unit
    private lateinit var setCurrent: (Int?) -> Unit
    private lateinit var enforce: TextView
    // Programmation
    private lateinit var schedule: TextView
    private lateinit var finish: TextView
    private lateinit var startTime: TextView
    private lateinit var endTime: TextView
    // V2L
    private lateinit var v2lStatus: TextView
    private lateinit var setV2l: (Int?) -> Unit
    private lateinit var v2lAction: TextView
    // Conso
    private lateinit var setPeriod: (Int?) -> Unit
    private lateinit var consTotal: TextView
    private lateinit var bar: StackedBar
    private lateinit var legend: LinearLayout
    private lateinit var regen: TextView
    private lateinit var chart: LineChart
    private lateinit var chartNote: TextView

    private var period = 0          // 0 = depuis la recharge, 1 = depuis le démarrage
    private var reser: Int? = null
    private var stH = 22; private var stM = 0; private var spH = 6; private var spM = 0

    override fun buildBody() {
        prefs = Prefs(this)
        header.addView(kit.text("Appli Énergie d'origine  ›", 19f, R.color.accent_action).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { Apps.open(this@EnergyActivity, Apps.ENERGY, "Énergie SAIC") }
        })

        // ── Colonne 1 : recharge ──
        val c1 = kit.panel("RECHARGE")
        val top = kit.row()
        soc = kit.text("—", 72f, light = true)
        top.addView(soc)
        val side = kit.column()
        range = kit.text("—", 26f)
        status = kit.text("—", 19f, R.color.text_sub)
        side.addView(range); side.addView(status, kit.lp(-2, -2, t = 6))
        top.addView(side, kit.lp(0, -2, 1f, l = 22))
        c1.addView(top, kit.lp(-1, -2, t = 6))
        socGauge = SegmentedGauge(this).apply { segments = 20 }
        c1.addView(socGauge, kit.lp(-1, kit.dp(14), t = 12))
        power = kit.text("", 18f, R.color.text_sub)
        c1.addView(power, kit.lp(-1, -2, t = 8))
        action = kit.toggleTile("—") { onMainAction() }.apply { textSize = 20f }
        c1.addView(action, kit.lp(-1, kit.dp(64), t = 12))
        c1.addView(kit.title("LIMITE DE CHARGE"), kit.lp(-1, -2, t = 18))
        val limits = listOf(50, 60, 70, 80, 90, 100)
        val (lRow, lSet) = kit.segmented(limits.map { "$it %" }) { i ->
            ChargeGuard.setLimit(this, limits[i]); refreshProps()
        }
        setLimit = lSet
        c1.addView(lRow, kit.lp(-1, -2, t = 8))
        val eRow = kit.row()
        limitNote = kit.text("", 16f, R.color.text_title).apply { maxLines = 2 }
        eRow.addView(limitNote, kit.lp(0, -2, 1f))
        enforce = kit.toggleTile("ARRÊT AUTO") {
            prefs.enforceChargeLimit = !prefs.enforceChargeLimit; renderLimit()
        }.apply { textSize = 16f }
        eRow.addView(enforce, kit.lp(kit.dp(150), kit.dp(48), l = 10))
        c1.addView(eRow, kit.lp(-1, -2, t = 8))
        c1.addView(kit.title("COURANT DE CHARGE AC"), kit.lp(-1, -2, t = 14))
        val (cRow, cSet) = kit.segmented(listOf("6 A", "8 A", "16 A", "Max")) { i ->
            CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "courant AC"); refreshProps()
        }
        setCurrent = cSet
        c1.addView(cRow, kit.lp(-1, -2, t = 8))

        // ── Colonne 2 : programmation + V2L ──
        val c2 = kit.panel("CHARGE PROGRAMMÉE")
        val tRow = kit.row()
        schedule = kit.toggleTile("PROGRAMMATION") {
            val on = reser == 1 || reser == 3
            CarProps.setInt(CarProps.RESER_CTRL, if (on) 2 else 1, "programmation"); refreshProps()
        }
        finish = kit.toggleTile("FINIR JUSQU'À LA LIMITE") {
            CarProps.setInt(CarProps.RESER_CTRL, if (reser == 3) 1 else 3, "continuer après l'heure de fin"); refreshProps()
        }.apply { textSize = 17f }
        tRow.addView(schedule, kit.lp(0, kit.dp(60), 1f))
        tRow.addView(finish, kit.lp(0, kit.dp(60), 1f, l = 10))
        c2.addView(tRow, kit.lp(-1, -2, t = 10))
        val (sRow, sText) = kit.timeStepper("Début") { d -> shiftTime(true, d) }
        startTime = sText
        c2.addView(sRow, kit.lp(-1, -2, t = 14))
        val (eRow2, eText) = kit.timeStepper("Fin") { d -> shiftTime(false, d) }
        endTime = eText
        c2.addView(eRow2, kit.lp(-1, -2, t = 10))
        c2.addView(kit.divider(), kit.lp(-1, 1, t = 18))
        c2.addView(kit.title("V2L · PRISE EXTÉRIEURE"), kit.lp(-1, -2, t = 16))
        v2lStatus = kit.text("—", 19f, R.color.text_sub)
        c2.addView(v2lStatus, kit.lp(-1, -2, t = 8))
        c2.addView(kit.text("Arrêt de la décharge à", 17f, R.color.text_title), kit.lp(-1, -2, t = 12))
        val v2ls = listOf(20, 30, 40, 50, 60, 70, 80)
        val (vRow, vSet) = kit.segmented(v2ls.map { "$it" }) { i ->
            CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode(v2ls[i]), "seuil V2L ${v2ls[i]} %"); refreshProps()
        }
        setV2l = vSet
        c2.addView(vRow, kit.lp(-1, -2, t = 8))
        v2lAction = kit.toggleTile("LANCER LE V2L", heat = true) {
            val s = CarBridge.state.value
            if (s.dischargeCtrl == 1) CarBridge.stopV2L() else CarBridge.startV2L()
        }.apply { textSize = 18f }
        c2.addView(LinearLayout(this), kit.lp(-1, 0, 1f))
        c2.addView(v2lAction, kit.lp(-1, kit.dp(60), t = 10))

        // ── Colonne 3 : consommation ──
        val c3 = kit.panel("CONSOMMATION")
        val (pRow, pSet) = kit.segmented(listOf("Depuis la recharge", "Depuis le démarrage")) { i -> period = i; setPeriod(i); refreshProps() }
        setPeriod = pSet
        pSet(0)
        c3.addView(pRow, kit.lp(-1, -2, t = 10))
        consTotal = kit.text("—", 44f, light = true)
        c3.addView(consTotal, kit.lp(-1, -2, t = 14))
        bar = StackedBar(this)
        c3.addView(bar, kit.lp(-1, kit.dp(22), t = 10))
        legend = kit.column()
        c3.addView(legend, kit.lp(-1, -2, t = 8))
        regen = kit.text("", 18f, R.color.accent_energy)
        c3.addView(regen, kit.lp(-1, -2, t = 6))
        c3.addView(kit.title("CONSO RÉCENTE (kWh/100 km)"), kit.lp(-1, -2, t = 18))
        chart = LineChart(this)
        c3.addView(chart, kit.lp(-1, 0, 1f, t = 8))
        chartNote = kit.text("", 15f, R.color.text_title)
        c3.addView(chartNote, kit.lp(-1, -2, t = 4))

        body.addView(c1, kit.lp(0, -1, 1.05f, r = 6))
        body.addView(c2, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(c3, kit.lp(0, -1, 1f, l = 6))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ChargeGuard.history.collect { renderHistory(it) } }
                while (true) { refreshProps(); delay(2_000) }
            }
        }
        renderLimit()
    }

    // ── Données BMS (lues hors thread principal) ──

    private data class Props(
        val target: Int?, val current: Int?, val reser: Int?, val st: Pair<Int?, Int?>, val sp: Pair<Int?, Int?>,
        val v2l: Int?, val volt: Float?, val amp: Float?, val cons: FloatArray,
    )

    private fun refreshProps() {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) {
                val chrg = period == 0
                fun f(newId: Int, oldId: Int?) = CarProps.getFloat(newId) ?: oldId?.let { CarProps.getFloat(it) }
                Props(
                    CarProps.getInt(CarProps.CHRG_TRGT_SOC), CarProps.getInt(CarProps.ALTNG_CHRG_CRNT),
                    CarProps.getInt(CarProps.RESER_CTRL),
                    CarProps.getInt(CarProps.RESER_ST_HOUR) to CarProps.getInt(CarProps.RESER_ST_MIN),
                    CarProps.getInt(CarProps.RESER_SP_HOUR) to CarProps.getInt(CarProps.RESER_SP_MIN),
                    CarProps.getInt(CarProps.V2X_SP_SOC),
                    CarProps.getFloat(CarProps.BMS_PACK_VOL), CarProps.getFloat(CarProps.BMS_PACK_CRNT),
                    floatArrayOf(
                        f(if (chrg) CarProps.CONS_TOTAL_AFT_CHRG else CarProps.CONS_TOTAL_AFT_ST, if (chrg) CarProps.OLD_TOTAL_AFT_CHRG else CarProps.OLD_TOTAL_AFT_ST) ?: -1f,
                        f(if (chrg) CarProps.CONS_ATC_AFT_CHRG else CarProps.CONS_ATC_AFT_ST, if (chrg) CarProps.OLD_ACC_AFT_CHRG else CarProps.OLD_ACC_AFT_ST) ?: -1f,
                        f(if (chrg) CarProps.CONS_ACCRY_AFT_CHRG else CarProps.CONS_ACCRY_AFT_ST, null) ?: -1f,
                        f(if (chrg) CarProps.CONS_RGEN_AFT_CHRG else CarProps.CONS_RGEN_AFT_ST, if (chrg) CarProps.OLD_RGEN_AFT_CHRG else CarProps.OLD_RGEN_AFT_ST) ?: -1f,
                    ),
                )
            }
            renderProps(p)
        }
    }

    private fun renderProps(p: Props) {
        reser = p.reser
        val carLimit = CarProps.targetSocPercent(p.target)
        renderLimit(carLimit)
        setCurrent(p.current?.minus(1))
        val on = p.reser == 1 || p.reser == 3
        schedule.isSelected = on
        finish.isSelected = p.reser == 3
        finish.isEnabled = on
        p.st.first?.let { stH = it }; p.st.second?.let { stM = it }
        p.sp.first?.let { spH = it }; p.sp.second?.let { spM = it }
        startTime.text = if (p.st.first != null) hhmm(stH, stM) else "--:--"
        endTime.text = when {
            p.reser == 3 -> "limite"
            p.sp.first != null -> hhmm(spH, spM)
            else -> "--:--"
        }
        val v2lPct = CarProps.v2lPercent(p.v2l)
        setV2l(v2lPct?.let { listOf(20, 30, 40, 50, 60, 70, 80).indexOf(it) })
        val kw = if (p.volt != null && p.amp != null) p.volt * p.amp / 1000f else null
        val s = CarBridge.state.value
        power.text = listOfNotNull(
            kw?.takeIf { kotlin.math.abs(it) >= 0.1f }?.let { String.format(Locale.FRANCE, "Puissance %.1f kW", kotlin.math.abs(it)) },
            s.chargeRemainMin?.takeIf { VehicleState.isCharging(s.chargeStatus) && it in 1..2000 }
                ?.let { "fin dans ${it / 60} h ${String.format(Locale.FRANCE, "%02d", it % 60)}" },
        ).joinToString(" · ")
        renderConsumption(p.cons)
    }

    private fun renderLimit(carLimit: Int? = null) {
        val wanted = prefs.chargeLimit
        setLimit(listOf(50, 60, 70, 80, 90, 100).indexOf(wanted).takeIf { it >= 0 })
        enforce.isSelected = prefs.enforceChargeLimit
        limitNote.text = when {
            wanted >= 100 -> "Pas de limite : charge jusqu'à 100 %."
            prefs.enforceChargeLimit -> "MG4Dash arrête la charge à $wanted % (écran allumé)." +
                (carLimit?.let { " Voiture : $it %." } ?: "")
            else -> "Limite transmise à la voiture uniquement" + (carLimit?.let { " ($it %)." } ?: ".")
        }
    }

    private fun renderConsumption(c: FloatArray) {
        val (total, clim, acc, rgn) = c.toList()
        val ok = total > 0f
        val drive = if (ok) (total - clim.coerceAtLeast(0f) - acc.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f
        consTotal.text = if (ok) String.format(Locale.FRANCE, "%.1f kWh", total) else "—"
        val green = kit.color(R.color.accent_energy); val cyan = kit.color(R.color.accent_cold); val orange = kit.color(R.color.accent_heat)
        bar.parts = if (ok) listOf(
            StackedBar.Part(drive, green), StackedBar.Part(clim.coerceAtLeast(0f), cyan), StackedBar.Part(acc.coerceAtLeast(0f), orange),
        ) else emptyList()
        legend.removeAllViews()
        listOf(Triple("Conduite", drive, green), Triple("Climatisation", clim, cyan), Triple("Accessoires", acc, orange)).forEach { (l, v, col) ->
            val r = kit.row()
            r.addView(android.view.View(this).apply { setBackgroundColor(col) }, kit.lp(kit.dp(12), kit.dp(12)))
            r.addView(kit.text(l, 18f, R.color.text_sub), kit.lp(0, -2, 1f, l = 10))
            r.addView(kit.text(if (ok && v >= 0f) String.format(Locale.FRANCE, "%.1f kWh · %d %%", v, Math.round(v / total * 100)) else "—", 18f))
            legend.addView(r, kit.lp(-1, -2, t = 6))
        }
        regen.text = if (rgn > 0f) String.format(Locale.FRANCE, "+ %.1f kWh récupérés au freinage", rgn) else ""
    }

    private fun renderHistory(values: List<Float>) {
        chart.values = values
        chartNote.text = if (values.size >= 2)
            String.format(Locale.FRANCE, "%d derniers km · moyenne %.1f (pointillés)", values.size, values.average()) else ""
    }

    override fun render(s: VehicleState) {
        soc.text = s.batteryPercent?.let { "$it %" } ?: "—"
        range.text = s.rangeKm?.let { "$it km" } ?: "—"
        status.text = VehicleState.chargeStatusLabel(s.chargeStatus)
        val pct = s.batteryPercent
        socGauge.onColor = kit.color(when { pct == null -> R.color.text_disabled; pct <= 15 -> R.color.accent_danger; pct <= 30 -> R.color.accent_heat; else -> R.color.accent_energy })
        socGauge.value = pct?.let { (it + 4) / 5 } ?: 0
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        status.setTextColor(kit.color(when {
            discharge -> R.color.accent_heat
            VehicleState.isCharging(s.chargeStatus) -> R.color.accent_energy
            else -> R.color.text_sub
        }))
        action.text = mainActionLabel(s)
        action.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        action.isSelected = s.chargeCtrl == 1 || s.dischargeCtrl == 1
        v2lStatus.text = if (discharge) VehicleState.chargeStatusLabel(s.chargeStatus) +
            (s.dischargeRemainMin?.takeIf { it in 1..6000 }?.let { " · ${it / 60} h ${it % 60} min restantes" } ?: "")
        else "Branchez un adaptateur V2L sur la prise de charge"
        v2lAction.isEnabled = discharge
        v2lAction.isSelected = s.dischargeCtrl == 1
        v2lAction.text = if (s.dischargeCtrl == 1) "ARRÊTER LE V2L" else "LANCER LE V2L"
    }

    private fun mainActionLabel(s: VehicleState): String = when {
        !VehicleState.isPlugged(s.chargeStatus) -> "CÂBLE DÉBRANCHÉ"
        VehicleState.isDischargeMode(s.chargeStatus) -> if (s.dischargeCtrl == 1) "ARRÊTER LE V2L" else "LANCER LE V2L"
        s.chargeCtrl == 1 -> "ARRÊTER LA CHARGE"
        else -> "CHARGER MAINTENANT"
    }

    private fun onMainAction() {
        val s = CarBridge.state.value
        if (!VehicleState.isPlugged(s.chargeStatus)) return
        when {
            VehicleState.isDischargeMode(s.chargeStatus) -> if (s.dischargeCtrl == 1) CarBridge.stopV2L() else CarBridge.startV2L()
            s.chargeCtrl == 1 -> CarBridge.stopCharge()
            else -> CarBridge.startCharge()
        }
    }

    private fun shiftTime(start: Boolean, deltaMin: Int) {
        val (h, m) = if (start) stH to stM else spH to spM
        val total = ((h * 60 + m + deltaMin) % 1440 + 1440) % 1440
        val nh = total / 60; val nm = total % 60
        val other = if (start) spH * 60 + spM else stH * 60 + stM
        if (total == other) return    // l'appli SAIC refuse début = fin
        if (start) {
            stH = nh; stM = nm
            CarProps.setInt(CarProps.RESER_ST_HOUR, nh, "début programmation (h)")
            CarProps.setInt(CarProps.RESER_ST_MIN, nm, "début programmation (min)")
            startTime.text = hhmm(nh, nm)
        } else {
            spH = nh; spM = nm
            CarProps.setInt(CarProps.RESER_SP_HOUR, nh, "fin programmation (h)")
            CarProps.setInt(CarProps.RESER_SP_MIN, nm, "fin programmation (min)")
            endTime.text = hhmm(nh, nm)
        }
    }

    private fun hhmm(h: Int, m: Int) = String.format(Locale.FRANCE, "%02d:%02d", h, m)
}
