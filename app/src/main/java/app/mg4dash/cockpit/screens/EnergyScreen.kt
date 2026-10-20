package app.mg4dash.cockpit.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import app.mg4dash.cockpit.Icon
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.LivePower
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Sparkline
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.pxf
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.energy.BatteryCapacity
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.energy.EnergyMonitor
import app.mg4dash.system.Prefs
import java.util.Calendar

/**
 * Energía (lienzo : Energy, Charging, Charge-Plan, Charge-Done, Charge-Error, V2L). Une vue par état,
 * choisie selon la prise : débranché → Resumen ; branché → Cargando, Completada, Interrumpida, V2L ou,
 * en attente, Programar. Programar et Resumen s'ouvrent aussi à la main, jusqu'au prochain changement d'état.
 */
class EnergyScreen(host: Host) : Screen(host) {
    override val nav = Nav.ENERGY
    private val prefs by lazy { Prefs(c) }

    private enum class P { SUMMARY, CHARGING, DONE, ERROR, V2L, PLAN }

    /** État de charge lu une fois par passage. */
    private inner class K(val s: VehicleState) {
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        val v2l = VehicleState.isDischargeMode(s.chargeStatus)
        val v2lOn = v2l && VehicleState.isDischarging(s)
        val charging = !v2l && (VehicleState.isCharging(s.chargeStatus) || s.chargeCtrl == 1)
        val soc = s.socPrecise ?: s.batteryPercent?.toFloat() ?: 0f
        val socText = if (s.socPrecise == null && s.batteryPercent == null) "—" else Fmt.dec(soc, if (s.socPrecise != null) 1 else 0)
        val limit = CarProps.targetSocPercent(CarProps.getInt(CarProps.CHRG_TRGT_SOC)) ?: prefs.chargeLimit
        val stopReason = CarProps.getInt(CarProps.BMS_CHRG_SP_RSN)?.takeIf { it in 1..5 }
        val done = plugged && !charging && !v2l && soc >= limit - 0.5f
        val v2lStop = CarProps.v2lPercent(CarProps.getInt(CarProps.V2X_SP_SOC))
        val range = s.rangeKm?.toString() ?: "—"
    }

    private abstract inner class Page {
        lateinit var root: View
        abstract fun build(): View
        abstract fun bind(k: K)
        open fun live() {}
    }

    private val pages = LinkedHashMap<P, Page>()
    private var shown: P? = null
    private var manual: P? = null
    private var lastAuto: P? = null

    override fun build(): View = FrameLayout(c).apply {
        pages[P.SUMMARY] = Summary(); pages[P.CHARGING] = Charging(); pages[P.DONE] = Done()
        pages[P.ERROR] = Interrupted(); pages[P.V2L] = V2l(); pages[P.PLAN] = Plan()
        for (pg in pages.values) {
            pg.root = pg.build().apply { visibility = View.GONE }
            addView(pg.root, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onShow() { manual = null }

    private fun open(p: P?) { manual = p; render(CarBridge.state.value) }

    private fun auto(k: K) = when {
        !k.plugged -> P.SUMMARY
        k.v2l -> P.V2L
        k.charging -> P.CHARGING
        k.done -> P.DONE
        k.stopReason != null -> P.ERROR
        else -> P.PLAN
    }

    override fun bind(s: VehicleState) = render(s)

    private fun render(s: VehicleState) {
        val k = K(s)
        val a = auto(k)
        if (a != lastAuto) { lastAuto = a; manual = null }
        val p = manual ?: a
        if (p != shown) {
            shown?.let { pages.getValue(it).root.visibility = View.GONE }
            pages.getValue(p).root.visibility = View.VISIBLE
            shown = p
            if (p == P.PLAN) (pages.getValue(P.PLAN) as Plan).readSchedule()
        }
        pages.getValue(p).bind(k)
    }

    // Puissance en direct (0,2 s ; chiffres deux fois par seconde)
    override val liveRefresh = true
    private var lastLiveText = 0L

    override fun onLive() {
        val t = android.os.SystemClock.uptimeMillis()
        if (t - lastLiveText < LivePower.TEXT_MS) return
        lastLiveText = t
        shown?.let { pages[it]?.live() }
    }

    // ───────────────────────────── pièces communes ─────────────────────────────

    private fun price() = prefs.raw.getFloat("price_home", 0.15f)
    private fun clockOf(t: Long): String { val cal = Calendar.getInstance().apply { timeInMillis = t }; return Fmt.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) }
    private fun aLas(h: Int) = if (h == 1) "a la" else "a las"

    /** Pastilles d'état et du câble, en tête des cartes (1). */
    private inner class Head(parent: LinearLayout) {
        val status = Pill(c, "—", Pal.text3)
        val cable = Pill(c, "—", Pal.text3)
        init {
            parent.addView(c.row(12).apply {
                addView(status, lp(WRAP, 44))
                addView(View(c), lp(0, 1, 1f))
                addView(cable, lp(WRAP, 44))
            })
        }
        fun cable(k: K) = cable.set(when (k.s.chargeLock) { 1 -> "Cable bloqueado"; 2 -> "Cable desbloqueado"; else -> "Cable —" }, if (k.s.chargeLock == 2) Pal.heat else Pal.text3)
    }

    /** Batería y autonomía en grande. */
    private inner class Hero(parent: LinearLayout) {
        val soc = ValueText(c, Ty.BIG, Ty.TITLE)
        val range = ValueText(c, Ty.VALUE, Ty.LABEL)
        val side = c.col(2).apply { gravity = Gravity.END }
        init {
            parent.addView(c.row(24).apply {
                gravity = Gravity.BOTTOM
                addView(soc); addView(range)
                addView(View(c), lp(0, 1, 1f))
                addView(side)
            })
        }
        fun set(k: K) { soc.set(k.socText, "%"); range.set(k.range, "km") }
    }

    /** Valeur chiffrée avec légende au-dessus (grilles de stats des cartes (2)). */
    private fun stat(parent: LinearLayout, title: String, size: Float = Ty.TITLE, weight: Float = 1f): ValueText {
        val v = ValueText(c, size, Ty.CAPTION)
        parent.addView(c.col(2).apply {
            addView(c.text(title, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
            addView(v)
        }, lp(if (weight > 0f) 0 else WRAP, WRAP, weight))
        return v
    }

    private fun legend(parent: LinearLayout, items: List<Pair<String, Int>>) = parent.addView(c.row(16).apply {
        for ((t, col) in items) {
            addView(View(c).apply { background = rounded(col, 3f) }, lp(22, 6))
            addView(c.text(t, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
        }
    })

    /** Acceso à une autre vue (lienzo : 96 px, titre 24 px, chevron au bout). */
    private fun tile(title: String, sub: String?, icon: Icon, chevron: Boolean = true, onClick: () -> Unit) =
        Btn(c, title, icon, 30, vertical = false, textSize = Ty.LABEL, sub = sub, chevron = chevron).apply { setOnClickListener { onClick() } }

    /** Action sur place, sans chevron (lienzo : 88 px). */
    private fun screenOffTile(sub: String) = tile("Apagar pantalla", sub, Icons.screenOff, chevron = false) {
        if (!CarBridge.setDisplay(false)) host.toast("Apagar pantalla no disponible en este firmware")
    }

    private fun cableBtn() = Btn(c, "Desbloquear cable", Icons.unlock, 30, vertical = false, textSize = Ty.LABEL, sub = "").apply { setOnClickListener { CarBridge.toggleCableLock() } }

    /** Bouton principal du lienzo : 96 px, icône et libellé centrés, 28 px. */
    private fun primaryBtn(label: String, icon: Icon, onClick: () -> Unit) =
        Btn(c, label, icon, 28, vertical = false, textSize = Ty.BODY, centered = true).apply { look = Look.PRIMARY; setOnClickListener { onClick() } }

    private fun bindCable(b: Btn, k: K, highlight: Boolean = false) {
        val locked = k.s.chargeLock != 2
        b.setLabel(if (locked) "Desbloquear cable" else "Bloquear cable")
        b.setSub(if (k.charging && locked) "Detén antes la carga" else if (locked) "La tapa se abre a mano" else "Ya puedes desenchufar")
        b.look = when { !CarBridge.cableLockControl -> Look.DISABLED; highlight && locked -> Look.PRIMARY; else -> Look.NEUTRAL }
        b.iconView?.icon = if (locked) Icons.unlock else Icons.lock
    }

    private val ampLabels = listOf("6 A", "8 A", "16 A", "Máx")
    private val ampKw = listOf(1.4f, 1.8f, 3.7f, 7.4f)
    private fun ampsCode() = CarProps.getInt(CarProps.ALTNG_CHRG_CRNT)?.takeIf { it in 1..4 }
    private fun ampsSeg(h: Int = Dim.TAP_MIN): Segmented {
        lateinit var seg: Segmented
        seg = Segmented(c, ampLabels, height = h, textSize = Ty.LABEL, gap = 10) { i ->
            CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "courant AC"); seg.select(i)
        }
        return seg
    }

    /** Limite : seulement là où la voiture l'applique elle-même (voir ChargeGuard). */
    private val limitShown get() = app.mg4dash.system.Firmware.nativeChargeLimit || app.mg4dash.BuildConfig.MOCK
    private fun limitSeg(h: Int = Dim.TAP_MIN): Segmented {
        lateinit var seg: Segmented
        seg = Segmented(c, (4..10).map { "${it * 10}" }, height = h, gap = 8) { i -> ChargeGuard.setLimit(c, (i + 4) * 10); seg.select(i) }
        return seg
    }

    private fun curveSet(curve: ChargeCurve, sess: ChargeLog.Session) {
        val pts = sess.points
        val span = (pts.lastOrNull()?.first ?: 60f).coerceAtLeast(30f)
        curve.set(pts, span, sess.startedAt.takeIf { it > 0 }?.let { clockOf(it) } ?: "",
            if (sess.active) "Ahora" else sess.endedAt.takeIf { it > 0 }?.let { clockOf(it) } ?: "",
            if ((pts.maxOfOrNull { it.third } ?: 0f) > 12f) 120f else 8f)
    }

    /** Minutes de vraie charge (premier → dernier point à plus de 0,5 kW). */
    private fun chargedMin(sess: ChargeLog.Session): Int {
        val on = sess.points.filter { it.third > 0.5f }
        return if (on.size < 2) 0 else Math.round(on.last().first - on.first().first)
    }

    private fun note(s: String) = c.text(s, Ty.LABEL, Pal.text2).apply { maxLines = 3 }

    // ───────────────────────────── (Energy) Resumen ─────────────────────────────

    private inner class Summary : Page() {
        lateinit var soc: ValueText; lateinit var range: ValueText; lateinit var avg: ValueText
        lateinit var packV: ValueText; lateinit var packA: ValueText; lateinit var packKw: ValueText
        lateinit var perKm: BarChart; lateinit var mean: ValueText
        lateinit var periods: Segmented; var period = 0
        lateinit var usedTotal: ValueText; lateinit var usedKm: ValueText; lateinit var split: LinearLayout
        lateinit var driveV: ValueText; lateinit var climV: ValueText; lateinit var accV: ValueText
        lateinit var regenV: ValueText; lateinit var regenKm: TextView
        lateinit var week: ValueText; lateinit var weekCost: TextView; lateinit var days: BarChart

        override fun build(): View = c.col(Dim.GAP).apply {
            // (1)+(5) lecturas principales y batería ahora
            addView(c.row(28).apply {
                background = rounded(Pal.surface, Dim.CARD_R); setPadding(28, 12, 12, 12)
                gravity = Gravity.CENTER_VERTICAL
                fun readout(t: String, v: ValueText) = addView(c.col(0).apply {
                    addView(c.text(t, Ty.CAPTION, Pal.text2).apply { maxLines = 1 }); addView(v)
                })
                soc = ValueText(c, Ty.VALUE, Ty.LABEL); readout("Batería", soc)
                addView(divider())
                range = ValueText(c, Ty.VALUE, Ty.LABEL); readout("Autonomía", range)
                addView(divider())
                avg = ValueText(c, Ty.VALUE, Ty.LABEL); readout("Consumo medio · 50 km", avg)
                addView(divider())
                addView(c.text("Batería\nahora", Ty.CAPTION, Pal.text2).apply { maxLines = 2 })
                fun pack(t: String) = ValueText(c, Ty.TITLE, Ty.CAPTION).also { v ->
                    addView(c.col(0).apply { addView(v); addView(c.text(t, Ty.CAPTION, Pal.text3)) })
                }
                packV = pack("Tensión"); packA = pack("Corriente"); packKw = pack("Potencia")
                addView(View(c), lp(0, 1, 1f))
                addView(tile("Plan de carga", null, Icons.calendar) { open(P.PLAN) }, lp(WRAP, Dim.TAP))
            }, lp(MATCH, 112))

            addView(c.row(Dim.GAP).apply {
                // (2) consumo por km, últimos 50 km
                addView(c.card(gap = 14).apply {
                    addView(c.row(10).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(c.label("Consumo · últimos 50 km"), lp(0, WRAP, 1f))
                        addView(c.text("Media", Ty.CAPTION, Pal.text2))
                        mean = ValueText(c, Ty.TITLE, Ty.CAPTION); addView(mean)
                    })
                    perKm = BarChart(c); addView(perKm, lp(MATCH, 0, 1f))
                }, w(712))
                // (3)+(4) energía usada por periodo
                addView(c.card(gap = 14).apply {
                    periods = Segmented(c, listOf("Desde la última carga", "Desde el arranque"), height = 56, textSize = Ty.CAPTION, gap = 10) { i -> period = i; bindUsed() }
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
                        background = rounded(Pal.surface2, Dim.BTN_R); setPadding(18, 0, 18, 0)
                        gravity = Gravity.CENTER_VERTICAL
                        addView(c.text("Regenerado", Ty.LABEL, Pal.text2))
                        regenV = ValueText(c, Ty.TITLE, Ty.CAPTION); addView(regenV)
                        addView(View(c), lp(0, 1, 1f))
                        regenKm = c.text("", Ty.TITLE, Pal.energy, Ty.num); addView(regenKm)
                    }, lp(MATCH, 56))
                }, w(600))
                // (6) últimos 7 días
                addView(c.card(gap = 8).apply {
                    addView(c.label("Últimos 7 días"))
                    week = ValueText(c, Ty.VALUE, Ty.LABEL); addView(week)
                    weekCost = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }; addView(weekCost)
                    days = BarChart(c); addView(days, lp(MATCH, 0, 1f).apply { topMargin = 8 })
                }, w(416))
            }, lp(MATCH, 0, 1f))
        }

        private fun divider() = View(c).apply { setBackgroundColor(Pal.line) }.also { it.layoutParams = lp(1, 56) }

        private fun part(row: LinearLayout, title: String, color: Int): ValueText {
            val v = ValueText(c, Ty.TITLE, Ty.CAPTION)
            row.addView(c.col(2).apply {
                addView(c.row(6).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(c).apply { background = rounded(color, 4f) }, lp(14, 14))
                    addView(c.text(title, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
                })
                addView(v)
            }, lp(0, WRAP, 1f))
            return v
        }

        fun bindUsed() {
            periods.select(period)
            val t = EnergyMonitor.totals(period)
            val used = t.driveKwh + t.climKwh + t.accKwh
            usedTotal.set(Fmt.dec(used), "kWh usados en")
            usedKm.set(t.km.toString(), "km")
            driveV.set(Fmt.dec(t.driveKwh), "kWh"); climV.set(Fmt.dec(t.climKwh), "kWh"); accV.set(Fmt.dec(t.accKwh), "kWh")
            regenV.set(Fmt.dec(t.regenKwh), "kWh")
            regenKm.put(t.per100?.takeIf { it > 0.5f }?.let { "+" + Math.round(t.regenKwh / it * 100f) + " km" } ?: "")
            split.removeAllViews()
            val total = used.coerceAtLeast(0.001f)
            for ((v, col) in listOf(t.driveKwh to Pal.text, t.climKwh to Pal.cold, t.accKwh to Pal.text3)) {
                if (v <= 0f) continue
                split.addView(View(c).apply { background = rounded(col, 6f) }, lp(0, MATCH, v / total))
            }
        }

        override fun bind(k: K) {
            soc.set(k.socText, "%")
            range.set(k.range, "km")
            val hist = ChargeGuard.history.value.takeLast(50)
            val a = hist.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: EnergyMonitor.sinceCharge.value.per100
            avg.set(a?.let { Fmt.dec(it) } ?: "—", "kWh/100 km")
            mean.set(a?.let { Fmt.dec(it) } ?: "—", "kWh/100 km")
            perKm.set(hist, emptyList(), a)
            bindUsed()
            // 7 derniers jours civils, jours sans trajet à 0
            val byDay = EnergyMonitor.days.value.toMap()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val cal = Calendar.getInstance()
            val vals = ArrayList<Float>(); val labels = ArrayList<String>()
            cal.add(Calendar.DAY_OF_YEAR, -6)
            repeat(7) {
                vals += (byDay[fmt.format(cal.time)] ?: 0f).coerceAtLeast(0f)
                labels += "DLMXJVS"[cal.get(Calendar.DAY_OF_WEEK) - 1].toString()
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            val sum = vals.sum()
            week.set(Fmt.dec(sum), "kWh")
            weekCost.put("≈ ${Fmt.dec(sum * price(), 2)} € · a ${Fmt.dec(price(), 2)} €/kWh")
            days.set(vals, labels, null)
        }

        override fun live() {
            val v = CarProps.getFloat(CarProps.BMS_PACK_VOL); val a = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
            packV.set(v?.let { "${Math.round(it)}" } ?: "—", "V")
            packA.set(a?.let { Fmt.dec(it) } ?: "—", "A")
            packKw.set(if (v != null && a != null) Fmt.dec(v * a / 1000f) else "—", "kW")
        }
    }

    // ───────────────────────────── (Charging) Cargando ─────────────────────────────

    private inner class Charging : Page() {
        lateinit var head: Head; lateinit var hero: Hero
        lateinit var target: ValueText; lateinit var eta: TextView; lateinit var etaCaption: TextView
        lateinit var bar: BatteryBar; lateinit var limitRow: View; lateinit var limits: Segmented; lateinit var limitCaption: TextView
        lateinit var primary: Btn; lateinit var cable: Btn
        lateinit var curve: ChargeCurve; lateinit var kw: ValueText; lateinit var input: ValueText; lateinit var added: ValueText; lateinit var cost: ValueText
        lateinit var amps: Segmented; lateinit var ampsCaption: TextView; lateinit var preheat: Btn

        override fun build(): View = c.row(Dim.GAP).apply {
            // (1) estado de la carga
            addView(c.card(gap = 12).apply {
                head = Head(this)
                hero = Hero(this)
                target = ValueText(c, Ty.TITLE, Ty.CAPTION); hero.side.addView(target)
                eta = c.text("", Ty.TITLE, Pal.text, Ty.uiMedium).apply { maxLines = 1 }; hero.side.addView(eta)
                etaCaption = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }; hero.side.addView(etaCaption)
                bar = BatteryBar(c); addView(bar, lp(MATCH, 32))
                limitRow = c.col(8).apply {
                    addView(c.row().apply {
                        addView(c.label("Límite de carga"), lp(0, WRAP, 1f))
                        limitCaption = c.text("", Ty.CAPTION, Pal.text2); addView(limitCaption)
                    })
                    limits = limitSeg(80); addView(limits, lp(MATCH, 80))
                }
                addView(limitRow)
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(12).apply {
                    primary = primaryBtn("Detener carga", Icons.stop) { CarBridge.stopCharge() }
                    cable = cableBtn()
                    addView(primary, lp(0, Dim.PRIMARY, 284f)); addView(cable, lp(0, Dim.PRIMARY, 308f))
                })
            }, w(652))
            // (2) curva de carga
            addView(c.card(gap = 12).apply {
                addView(c.row().apply {
                    addView(c.label("Curva de carga"), lp(0, WRAP, 1f))
                    legend(this, listOf("Batería" to Pal.text, "Potencia" to Pal.energy))
                })
                curve = ChargeCurve(c); addView(curve, lp(MATCH, 0, 1f))
                addView(c.row(20).apply {
                    gravity = Gravity.BOTTOM
                    kw = stat(this, "Potencia", Ty.VALUE, 1.3f)
                    input = stat(this, "Entrada")
                    added = stat(this, "Añadido")
                    cost = stat(this, "Coste", weight = 0.8f)
                })
            }, w(628))
            // (3) ajustes de carga
            addView(c.card(gap = 12).apply {
                addView(c.row().apply {
                    addView(c.label("Corriente de carga"), lp(0, WRAP, 1f))
                    ampsCaption = c.text("", Ty.CAPTION, Pal.text2); addView(ampsCaption)
                })
                amps = ampsSeg(Dim.TAP); addView(amps, lp(MATCH, Dim.TAP))
                preheat = Btn(c, "Precalentar batería", Icons.climate, 30, vertical = false, textSize = Ty.LABEL, sub = "", led = true).apply {
                    accent = Pal.heat
                    setOnClickListener { CarProps.setInt(CarProps.DRVNG_PTC_HEAT, if (on) 2 else 1, "réchauffage batterie"); on = !on }
                }
                addView(preheat, lp(MATCH, Dim.PRIMARY))
                addView(tile("Programar carga", "Horario de carga y V2L", Icons.calendar) { open(P.PLAN) }, lp(MATCH, Dim.PRIMARY))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(screenOffTile("La carga continúa"), lp(MATCH, Dim.TAP))
            }, w(448))
        }

        override fun bind(k: K) {
            head.status.set("Cargando · " + VehicleState.chargeStatusLabel(k.s.chargeStatus), Pal.energy)
            head.cable(k)
            hero.set(k)
            target.set("→ ${k.limit}", "%")
            val rem = k.s.chargeRemainMin?.takeIf { it in 1..6000 }
            eta.put(rem?.let { Fmt.hm(it) } ?: "")
            etaCaption.put(rem?.let { "termina a las " + clockOf(System.currentTimeMillis() + it * 60_000L) } ?: "")
            bar.fillColor = Pal.energy; bar.pendingColor = Pal.energy
            bar.set(k.soc, k.limit.toFloat(), k.limit.toFloat())
            limitRow.visibility = if (limitShown) View.VISIBLE else View.GONE
            limits.select(((k.limit / 10) - 4).takeIf { it in 0..6 })
            limitCaption.put("Para al ${k.limit} %")
            primary.look = Look.PRIMARY
            bindCable(cable, k)
            val sess = ChargeLog.session.value
            curveSet(curve, sess)
            added.set(Fmt.dec(sess.addedKwh), "kWh")
            cost.set(Fmt.dec(sess.addedKwh * price(), 2), "€")
            val code = ampsCode()
            amps.select(code?.minus(1))
            ampsCaption.put(code?.let { "≈ ${Fmt.dec(ampKw[it - 1])} kW" } ?: "")
            preheat.on = CarProps.getInt(CarProps.DRVNG_PTC_HEAT) == 0
            preheat.setSub(if (preheat.on) "Calentando la batería" else "Carga más rápida con frío")
        }

        override fun live() {
            kw.set(Fmt.dec(ChargeLog.powerKw() ?: 0f), "kW")
            val acV = CarProps.getFloat(CarProps.ONBD_CHRG_AC_VOL); val acA = CarProps.getFloat(CarProps.ONBD_CHRG_AC_CRNT)
            if (acV != null && acA != null && acV > 50f) input.set("${Math.round(acV)} V · ${Math.round(acA)}", "A") else input.set("—", "")
        }
    }

    // ───────────────────────────── (Charge-Done) Completada ─────────────────────────────

    private inner class Done : Page() {
        lateinit var head: Head; lateinit var hero: Hero
        lateinit var word: TextView; lateinit var caption: TextView; lateinit var bar: BatteryBar
        lateinit var cable: Btn; lateinit var full: Btn
        lateinit var sessLabel: TextView; lateinit var curve: ChargeCurve
        lateinit var added: ValueText; lateinit var cost: ValueText; lateinit var duration: ValueText; lateinit var from: ValueText
        lateinit var foot: TextView; lateinit var next: Btn; lateinit var keep: TextView

        override fun build(): View = c.row(Dim.GAP).apply {
            // (1) carga completada
            addView(c.card(gap = 12).apply {
                head = Head(this)
                hero = Hero(this)
                word = c.text("Completada", Ty.TITLE, Pal.energy, Ty.uiMedium).apply { maxLines = 1 }; hero.side.addView(word)
                caption = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }; hero.side.addView(caption)
                bar = BatteryBar(c); addView(bar, lp(MATCH, 32))
                addView(View(c), lp(MATCH, 0, 1f))
                cable = cableBtn(); addView(cable, lp(MATCH, Dim.PRIMARY))
                full = Btn(c, "Cargar hasta 100 %", Icons.energy, 30, vertical = false, textSize = Ty.LABEL, sub = "Sube el límite y reanuda").apply {
                    setOnClickListener { ChargeGuard.setLimit(c, 100); CarBridge.startCharge() }
                }
                addView(full, lp(MATCH, Dim.PRIMARY))
            }, w(652))
            // (2) resumen de la sesión
            addView(c.card(gap = 12).apply {
                addView(c.row(12).apply {
                    addView(c.label("Resumen de la sesión"), lp(0, WRAP, 1f))
                    sessLabel = c.text("", Ty.CAPTION, Pal.text2); addView(sessLabel)
                })
                addView(c.row().apply { legend(this, listOf("Batería" to Pal.text, "Potencia" to Pal.energy)) })
                curve = ChargeCurve(c); addView(curve, lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    added = stat(this, "Añadido"); cost = stat(this, "Coste")
                    duration = stat(this, "Duración", weight = 1.3f); from = stat(this, "Desde", weight = 0.8f)
                })
                foot = c.text("", Ty.CAPTION, Pal.text3).apply { maxLines = 1 }; addView(foot)
            }, w(628))
            // (3) después de cargar
            addView(c.card(gap = 12).apply {
                addView(c.label("Después de cargar"))
                addView(tile("Volver al inicio", "Pantalla de inicio", Icons.home) { host.go("home") }, lp(MATCH, Dim.PRIMARY))
                next = tile("Programar carga", "", Icons.calendar) { open(P.PLAN) }; addView(next, lp(MATCH, Dim.PRIMARY))
                addView(screenOffTile("El coche sigue enchufado"), lp(MATCH, Dim.TAP))
                addView(View(c), lp(MATCH, 0, 1f))
                keep = note(""); addView(keep)
            }, w(448))
        }

        override fun bind(k: K) {
            head.status.set("Carga completada", Pal.energy)
            head.cable(k)
            hero.set(k)
            val sess = ChargeLog.session.value
            val lastOn = sess.points.lastOrNull { it.third > 0.5f }
            caption.put(lastOn?.let { "a las " + clockOf(sess.startedAt + (it.first * 60_000L).toLong()) } ?: "al ${k.limit} %")
            bar.fillColor = Pal.energy; bar.set(k.soc, k.limit.toFloat(), -1f)
            bindCable(cable, k, highlight = true)
            full.visibility = if (k.limit < 100 && limitShown) View.VISIBLE else View.GONE
            curveSet(curve, sess)
            val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            val startDay = Calendar.getInstance().apply { timeInMillis = sess.startedAt }.get(Calendar.DAY_OF_YEAR)
            sessLabel.put(if (sess.startedAt <= 0) "" else if (startDay != today) "Sesión de anoche" else "Sesión de hoy · desde las ${clockOf(sess.startedAt)}")
            added.set(Fmt.dec(sess.addedKwh), "kWh")
            cost.set(Fmt.dec(sess.addedKwh * price(), 2), "€")
            val min = chargedMin(sess)
            if (min >= 60) duration.set("${min / 60} h ${"%02d".format(min % 60)}", "min") else duration.set("$min", "min")
            from.set(if (sess.startedAt > 0) Math.round(sess.startSoc).toString() else "—", "%")
            val code = ampsCode()
            foot.put("Precio en casa ${Fmt.dec(price(), 2)} €/kWh" + (code?.let { " · corriente " + if (it == 4) "máxima" else ampLabels[it - 1] } ?: ""))
            next.setSub(nextSchedule())
            keep.put("El coche mantiene el ${k.limit} % sin seguir cargando mientras siga enchufado.")
        }
    }

    /** « Próxima: hoy a las 01:00 » ou « Sin programar ». */
    private fun nextSchedule(): String {
        val ctrl = CarProps.getInt(CarProps.RESER_CTRL)
        val h = CarProps.getInt(CarProps.RESER_ST_HOUR)?.takeIf { it in 0..23 } ?: return "Sin programar"
        val m = CarProps.getInt(CarProps.RESER_ST_MIN)?.takeIf { it in 0..59 } ?: 0
        if (ctrl != 1 && ctrl != 3) return "Sin programar"
        val now = Calendar.getInstance()
        val today = h * 60 + m > now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return "Próxima: ${if (today) "hoy" else "mañana"} ${aLas(h)} ${Fmt.clock(h, m)}"
    }

    // ───────────────────────────── (Charge-Error) Interrumpida ─────────────────────────────

    private inner class Interrupted : Page() {
        lateinit var head: Head; lateinit var hero: Hero
        lateinit var reason: TextView; lateinit var when_: TextView
        lateinit var retry: Btn; lateinit var cable: Btn
        lateinit var curve: ChargeCurve
        lateinit var added: ValueText; lateinit var cost: ValueText; lateinit var charged: ValueText; lateinit var now: ValueText
        lateinit var drop: TextView; lateinit var amps: Segmented

        override fun build(): View = c.row(Dim.GAP).apply {
            // (1) carga interrumpida
            addView(c.card(gap = 8).apply {
                head = Head(this)
                hero = Hero(this)
                addView(c.col(4).apply {
                    background = rounded(Pal.surface2, Dim.BTN_R); setPadding(20, 12, 20, 12)
                    addView(c.row(12).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(IconView(c, Icons.alert, 30).apply { color = Pal.alert })
                        addView(c.text("Motivo que da el coche", Ty.CAPTION, Pal.text2))
                    })
                    reason = c.text("", Ty.TITLE, Pal.text, Ty.uiMedium).apply { maxLines = 1 }; addView(reason)
                    when_ = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }; addView(when_)
                })
                addView(View(c), lp(MATCH, 0, 1f))
                retry = Btn(c, "Reintentar carga", Icons.retry, 30, vertical = false, textSize = Ty.BODY, sub = "Vuelve a pedir la carga al coche").apply {
                    look = Look.PRIMARY; setOnClickListener { CarBridge.startCharge() }
                }
                addView(retry, lp(MATCH, Dim.PRIMARY))
                cable = cableBtn(); addView(cable, lp(MATCH, Dim.PRIMARY))
            }, w(652))
            // (2) curva hasta el corte
            addView(c.card(gap = 12).apply {
                addView(c.row().apply {
                    addView(c.label("Curva de la sesión"), lp(0, WRAP, 1f))
                    legend(this, listOf("Batería" to Pal.text, "Potencia" to Pal.energy))
                })
                curve = ChargeCurve(c); addView(curve, lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    added = stat(this, "Añadido"); cost = stat(this, "Coste")
                    charged = stat(this, "Cargó", weight = 1.3f); now = stat(this, "Ahora", weight = 0.9f)
                })
                drop = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 2 }; addView(drop)
            }, w(628))
            // (3) qué probar
            addView(c.card(gap = 14).apply {
                addView(c.label("Qué probar"))
                listOf("Comprueba el cargador o la toma de corriente", "Baja la corriente a 16 A", "Desenchufa y vuelve a enchufar el cable")
                    .forEachIndexed { i, t ->
                        addView(c.row(14).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(c.text("${i + 1}", Ty.LABEL, Pal.text, Ty.num).apply {
                                gravity = Gravity.CENTER; background = rounded(Pal.surface3, 20f)
                            }, lp(40, 40))
                            addView(c.text(t, Ty.LABEL, Pal.text).apply { maxLines = 2 }, lp(0, WRAP, 1f))
                        })
                    }
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row().apply {
                    addView(c.label("Corriente de carga"), lp(0, WRAP, 1f))
                    addView(c.text("Sugerido: 16 A", Ty.CAPTION, Pal.action))
                })
                amps = ampsSeg(Dim.TAP); addView(amps, lp(MATCH, Dim.TAP))
            }, w(448))
        }

        override fun bind(k: K) {
            head.status.set("Carga interrumpida", Pal.alert)
            head.cable(k)
            hero.set(k)
            reason.put(when (k.stopReason) { 1 -> "Defecto del sistema"; 2 -> "Sobretemperatura"; 3 -> "Fallo del enchufe o la toma"; 4 -> "Tensión anómala"; else -> "Otro motivo" })
            val sess = ChargeLog.session.value
            val lastOn = sess.points.lastOrNull { it.third > 0.5f }
            when_.put(lastOn?.let { "A las " + clockOf(sess.startedAt + (it.first * 60_000L).toLong()) + " · ${Math.round(it.second)} % · límite ${k.limit} %" } ?: "Límite ${k.limit} %")
            bindCable(cable, k)
            curveSet(curve, sess)
            added.set(Fmt.dec(sess.addedKwh), "kWh")
            cost.set(Fmt.dec(sess.addedKwh * price(), 2), "€")
            val min = chargedMin(sess)
            if (min >= 60) charged.set("${min / 60} h ${"%02d".format(min % 60)}", "min") else charged.set("$min", "min")
            // brusque : dernier point à 0 juste après un point à plus de 1 kW
            val p = sess.points
            val i = p.indexOfLast { it.third > 0.5f }
            drop.put(when {
                i < 0 -> ""
                i + 1 < p.size && p[i].third > 1f -> "La potencia cayó a 0 de golpe: el corte vino del cargador o la toma"
                else -> "La potencia bajó poco a poco antes del corte"
            })
            amps.select(ampsCode()?.minus(1))
        }

        override fun live() { now.set(Fmt.dec(ChargeLog.powerKw()?.takeIf { CarBridge.state.value.chargeCtrl == 1 } ?: 0f), "kW") }
    }

    // ───────────────────────────── (V2L) ─────────────────────────────

    private inner class V2l : Page() {
        lateinit var head: Head; lateinit var hero: Hero
        lateinit var kw: ValueText; lateinit var eta: TextView; lateinit var bar: BatteryBar
        lateinit var primary: Btn
        lateinit var since: TextView; lateinit var spark: Sparkline
        lateinit var delivered: ValueText; lateinit var now: ValueText; lateinit var max: ValueText; lateinit var spent: ValueText
        lateinit var stopCaption: TextView; lateinit var stopSel: Segmented

        override fun build(): View = c.row(Dim.GAP).apply {
            // (1) V2L en marcha
            addView(c.card(gap = 12).apply {
                head = Head(this)
                hero = Hero(this)
                kw = ValueText(c, Ty.TITLE, Ty.CAPTION); hero.side.addView(kw)
                eta = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }; hero.side.addView(eta)
                bar = BatteryBar(c); addView(bar, lp(MATCH, 32))
                addView(View(c), lp(MATCH, 0, 1f))
                primary = primaryBtn("Detener V2L", Icons.stop) {
                    if (VehicleState.isDischarging(CarBridge.state.value)) CarBridge.stopV2L() else CarBridge.startV2L()
                }
                addView(primary, lp(MATCH, Dim.PRIMARY))
            }, w(652))
            // (2) salida
            addView(c.card(gap = 12).apply {
                addView(c.row(12).apply {
                    addView(c.label("Salida · última hora"), lp(0, WRAP, 1f))
                    since = c.text("", Ty.CAPTION, Pal.text2); addView(since)
                })
                spark = Sparkline(c).apply { color = Pal.heat }; addView(spark, lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    delivered = stat(this, "Entregado"); now = stat(this, "Ahora")
                    max = stat(this, "Máximo"); spent = stat(this, "Gastado", weight = 0.8f)
                })
                addView(c.text("Salida de 230 V por el adaptador de la toma de carga", Ty.CAPTION, Pal.text3).apply { maxLines = 1 })
            }, w(628))
            // (3) parada automática
            addView(c.card(gap = 14).apply {
                addView(c.label("Parada automática"))
                addView(c.col(2).apply {
                    addView(c.text("Detener al llegar a", Ty.LABEL, Pal.text))
                    stopCaption = c.text("", Ty.CAPTION, Pal.text2); addView(stopCaption)
                })
                stopSel = Segmented(c, (2..8).map { "${it * 10}" }, height = 80, gap = 6) { i ->
                    CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode((i + 2) * 10), "seuil V2L"); stopSel.select(i)
                }
                addView(stopSel, lp(MATCH, 80))
                addView(note("El coche corta la salida solo al llegar al límite, aunque la pantalla esté apagada."))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(screenOffTile("La V2L sigue funcionando"), lp(MATCH, Dim.TAP))
            }, w(448))
        }

        override fun bind(k: K) {
            head.status.set(if (k.v2lOn) "V2L en marcha" else "V2L detenida", if (k.v2lOn) Pal.heat else Pal.text3)
            head.cable.set("Adaptador V2L conectado", Pal.heat)
            hero.set(k)
            val rem = k.s.dischargeRemainMin?.takeIf { it in 1..6000 }
            eta.put(listOfNotNull(k.v2lStop?.let { "Hasta el $it %" }, rem?.let { Fmt.hm(it) }).joinToString(" · "))
            bar.fillColor = Pal.energy; bar.pendingColor = Pal.heat
            bar.set(k.soc, (k.v2lStop ?: -1).toFloat(), -1f)
            primary.setLabel(if (k.v2lOn) "Detener V2L" else "Iniciar V2L")
            primary.look = if (k.v2lOn) Look.ALERT else Look.PRIMARY
            primary.iconView?.icon = if (k.v2lOn) Icons.stop else Icons.play
            val v = ChargeLog.v2l.value
            since.put(if (v.startedAt > 0) "Desde las " + clockOf(v.startedAt) else "")
            spark.set(v.points.takeLast(60).map { it.second })
            delivered.set(Fmt.dec(v.deliveredKwh), "kWh")
            max.set(Fmt.dec(v.points.maxOfOrNull { it.second } ?: 0f), "kW")
            spent.set(if (v.startedAt > 0) Math.round((v.startSoc - k.soc).coerceAtLeast(0f)).toString() else "—", "%")
            stopSel.select(k.v2lStop?.div(10)?.minus(2)?.takeIf { it in 0..6 })
            // km restants à l'arrêt : autonomie au prorata du % gardé
            val keepKm = k.v2lStop?.let { st -> k.s.rangeKm?.takeIf { k.soc > 1f }?.let { Math.round(it * st / k.soc) } }
            stopCaption.put(keepKm?.let { "Quedarán unos $it km" } ?: "")
        }

        override fun live() {
            val p = if (VehicleState.isDischarging(CarBridge.state.value)) ChargeLog.powerKw() ?: 0f else 0f
            kw.set(Fmt.dec(p), "kW"); now.set(Fmt.dec(p), "kW")
        }
    }

    // ───────────────────────────── (Charge-Plan) Programar ─────────────────────────────

    private inner class Plan : Page() {
        lateinit var status: TextView; lateinit var hero: Hero
        lateinit var startV: TextView; lateinit var startC: TextView; lateinit var endV: TextView; lateinit var endC: TextView
        lateinit var now: Btn; lateinit var cable: Btn; lateinit var back: Btn
        lateinit var start: TextView; lateinit var end: TextView; lateinit var window: ValueText
        lateinit var mode: Segmented; lateinit var timeline: PlanTimeline
        lateinit var limitBox: View; lateinit var limits: Segmented; lateinit var limitCaption: TextView
        lateinit var amps: Segmented; lateinit var ampsCaption: TextView
        lateinit var v2lText: TextView; lateinit var v2lBtn: Btn; lateinit var v2lSel: Segmented; lateinit var v2lCaption: TextView
        var stH = 1; var stM = 0; var spH = 7; var spM = 0

        override fun build(): View = c.row(Dim.GAP).apply {
            // (1) estado + acciones (lado del conductor)
            addView(c.card(gap = 14).apply {
                status = c.text("", Ty.LABEL, Pal.text2, Ty.uiMedium).apply { maxLines = 1 }; addView(status)
                hero = Hero(this)
                addView(c.row(16).apply {
                    fun block(t: String): Pair<TextView, TextView> {
                        val v = c.text("--:--", Ty.VALUE, Pal.text, Ty.num); val cap = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 2 }
                        addView(c.col(0).apply { addView(c.text(t, Ty.CAPTION, Pal.text2)); addView(v); addView(cap) }, lp(0, WRAP, 1f))
                        return v to cap
                    }
                    block("Empieza").let { startV = it.first; startC = it.second }
                    block("Termina").let { endV = it.first; endC = it.second }
                })
                addView(View(c), lp(MATCH, 0, 1f))
                now = primaryBtn("Cargar ahora", Icons.energy) { CarBridge.startCharge() }
                addView(now, lp(MATCH, Dim.PRIMARY))
                addView(c.row(12).apply {
                    cable = Btn(c, "Desbloquear", null, textSize = Ty.LABEL).apply { setOnClickListener { CarBridge.toggleCableLock() } }
                    back = Btn(c, "Consumo", null, textSize = Ty.LABEL).apply { setOnClickListener { open(if (manual == P.PLAN) null else P.SUMMARY) } }
                    addView(cable, lp(0, Dim.TAP_MIN, 1f)); addView(back, lp(0, Dim.TAP_MIN, 1f))
                })
            }, w(420))
            // (2) horario de carga
            addView(c.card(gap = 14).apply {
                addView(c.row().apply {
                    addView(c.label("Horario de carga"), lp(0, WRAP, 1f))
                    addView(c.text("Pasos de 15 min", Ty.CAPTION, Pal.text2))
                })
                addView(c.row(Dim.GAP).apply {
                    addView(stepper("Inicio") { d -> shift(true, d) }.also { start = it.second }.first, lp(0, WRAP, 1f))
                    addView(stepper("Fin") { d -> shift(false, d) }.also { end = it.second }.first, lp(0, WRAP, 1f))
                })
                addView(c.row(12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(c.text("Ventana", Ty.LABEL, Pal.text2))
                    window = ValueText(c, Ty.TITLE, Ty.CAPTION); addView(window)
                })
                mode = Segmented(c, listOf("Sin horario", "Solo en horario", "En horario y luego hasta el límite"), height = Dim.TAP_MIN, textSize = Ty.CAPTION, gap = 10) { i ->
                    CarProps.setInt(CarProps.RESER_CTRL, listOf(2, 1, 3)[i], "programmation"); mode.select(i); bind(K(CarBridge.state.value))
                }
                addView(mode, lp(MATCH, Dim.TAP_MIN))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(12).apply {
                    addView(c.label("Plan de esta noche"), lp(0, WRAP, 1f))
                    legend(this, listOf("Ahora" to Pal.text, "Horario" to Pal.action, "Carga" to Pal.energy))
                })
                timeline = PlanTimeline(c); addView(timeline, lp(MATCH, 80))
            }, w(656))
            // (3) límite + corriente · (4) V2L
            addView(c.col(Dim.GAP).apply {
                addView(c.card(pad = 20, gap = 10).apply {
                    limitBox = c.col(10).apply {
                        addView(c.row().apply {
                            addView(c.label("Límite de carga"), lp(0, WRAP, 1f))
                            limitCaption = c.text("", Ty.CAPTION, Pal.text2); addView(limitCaption)
                        })
                        limits = limitSeg(Dim.TAP_MIN); addView(limits, lp(MATCH, Dim.TAP_MIN))
                    }
                    addView(limitBox)
                    addView(c.row().apply {
                        addView(c.label("Corriente AC"), lp(0, WRAP, 1f))
                        ampsCaption = c.text("", Ty.CAPTION, Pal.text2); addView(ampsCaption)
                    })
                    amps = ampsSeg(Dim.TAP_MIN); addView(amps, lp(MATCH, Dim.TAP_MIN))
                }, lp(MATCH, 0, 1f))
                addView(c.card(pad = 20, gap = 10).apply {
                    addView(c.row(12).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(c.col(2).apply {
                            addView(c.label("Descarga V2L"))
                            v2lText = c.text("", Ty.LABEL, Pal.text).apply { maxLines = 1 }; addView(v2lText)
                        }, lp(0, WRAP, 1f))
                        v2lBtn = Btn(c, "Iniciar V2L", Icons.play, 26, vertical = false, centered = true).apply { setOnClickListener { CarBridge.startV2L() } }
                        addView(v2lBtn, lp(236, Dim.TAP_MIN))
                    })
                    addView(c.row().apply {
                        addView(c.text("Detener la descarga al", Ty.LABEL, Pal.text2), lp(0, WRAP, 1f))
                        v2lCaption = c.text("", Ty.CAPTION, Pal.text2); addView(v2lCaption)
                    })
                    v2lSel = Segmented(c, (2..8).map { "${it * 10}" }, height = 64, gap = 6) { i ->
                        CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode((i + 2) * 10), "seuil V2L"); v2lSel.select(i)
                    }
                    addView(v2lSel, lp(MATCH, 64))
                }, lp(MATCH, 244))
            }, w(652))
        }

        private fun stepper(title: String, onStep: (Int) -> Unit): Pair<View, TextView> {
            val value = c.text("--:--", Ty.VALUE, Pal.text, Ty.num).apply { gravity = Gravity.CENTER; maxLines = 1 }
            val box = c.col(8).apply {
                addView(c.text(title, Ty.CAPTION, Pal.text2))
                addView(c.row(8).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Btn(c, null, Icons.minus, 36).apply { setOnClickListener { onStep(-15) } }, lp(72, Dim.TAP))
                    addView(value, lp(0, WRAP, 1f))
                    addView(Btn(c, null, Icons.plus, 36).apply { setOnClickListener { onStep(15) } }, lp(72, Dim.TAP))
                })
            }
            return box to value
        }

        fun readSchedule() {
            stH = CarProps.getInt(CarProps.RESER_ST_HOUR)?.takeIf { it in 0..23 } ?: stH
            stM = CarProps.getInt(CarProps.RESER_ST_MIN)?.takeIf { it in 0..59 } ?: stM
            spH = CarProps.getInt(CarProps.RESER_SP_HOUR)?.takeIf { it in 0..23 } ?: spH
            spM = CarProps.getInt(CarProps.RESER_SP_MIN)?.takeIf { it in 0..59 } ?: spM
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
            bind(K(CarBridge.state.value))
        }

        override fun bind(k: K) {
            val ctrl = CarProps.getInt(CarProps.RESER_CTRL)
            val scheduled = ctrl == 1 || ctrl == 3
            status.put(when {
                !k.plugged -> "Sin enchufar · el horario se aplica al enchufar"
                k.charging -> "Cargando"
                scheduled -> "Enchufado · espera al horario"
                else -> "Enchufado · sin cargar"
            })
            hero.set(k)
            start.text = Fmt.clock(stH, stM); end.text = Fmt.clock(spH, spM)
            val st = stH * 60 + stM; val sp = spH * 60 + spM
            val win = ((sp - st) % 1440 + 1440) % 1440
            window.set(if (win >= 60) "${win / 60} h ${"%02d".format(win % 60)}" else "$win", "min")
            mode.select(when (ctrl) { 1 -> 1; 3 -> 2; else -> 0 })

            // estimation : kWh manquants / puissance AC choisie
            val code = ampsCode() ?: 4
            val needKwh = ((k.limit - k.soc).coerceAtLeast(0f) / 100f) * BatteryCapacity.nominal(c)
            val durMin = Math.round(needKwh / ampKw[code - 1] * 60f)
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val chargeFrom = if (scheduled) st else nowMin
            val chargeLen = if (ctrl == 1) minOf(durMin, win) else durMin
            val reach = if (ctrl == 1 && durMin > win && durMin > 0) k.soc + (k.limit - k.soc) * win / durMin else k.limit.toFloat()
            if (scheduled) {
                val until = ((st - nowMin) % 1440 + 1440) % 1440
                startV.put(Fmt.clock(stH, stM)); startC.put("dentro de " + Fmt.hm(until))
                val endAt = (st + chargeLen) % 1440
                endV.put(Fmt.clock(endAt / 60, endAt % 60))
                endC.put(if (ctrl == 1 && reach < k.limit) "hasta el ${Math.round(reach)} %" else "al ${k.limit} %")
            } else {
                startV.put("—"); startC.put("Sin horario")
                val endAt = (nowMin + durMin) % 1440
                endV.put(if (k.plugged && durMin > 0) Fmt.clock(endAt / 60, endAt % 60) else "—")
                endC.put(if (k.plugged) "si cargas ahora" else "")
            }
            timeline.set(if (scheduled) st else -1, if (scheduled) (if (ctrl == 1) sp else (st + chargeLen) % 1440) else -1,
                if (k.plugged || scheduled) chargeFrom else -1, chargeLen, nowMin)

            now.look = if (!k.plugged || k.charging) Look.DISABLED else Look.PRIMARY
            now.setLabel(if (!k.plugged) "Sin enchufar" else if (k.charging) "Cargando" else "Cargar ahora")
            val locked = k.s.chargeLock != 2
            cable.setLabel(if (locked) "Desbloquear" else "Bloquear")
            cable.look = if (!CarBridge.cableLockControl || !k.plugged) Look.DISABLED else Look.NEUTRAL
            back.setLabel(if (manual == P.PLAN) "Volver" else "Consumo")

            limitBox.visibility = if (limitShown) View.VISIBLE else View.GONE
            limits.select(((k.limit / 10) - 4).takeIf { it in 0..6 })
            limitCaption.put("Para al ${k.limit} %")
            amps.select(ampsCode()?.minus(1))
            ampsCaption.put(ampsCode()?.let { "≈ ${Fmt.dec(ampKw[it - 1])} kW" } ?: "")

            v2lText.put(if (k.v2l) "Adaptador V2L conectado" else "Conecta el adaptador V2L")
            v2lBtn.look = if (k.v2l) Look.PRIMARY else Look.DISABLED
            v2lSel.select(k.v2lStop?.div(10)?.minus(2)?.takeIf { it in 0..6 })
            v2lCaption.put(k.v2lStop?.let { "Al ${it} %" } ?: "")
        }
    }

    /**
     * « Plan de esta noche » : 20:00 → 08:00. Bande bleue = horaire, vert = charge estimée,
     * trait = maintenant.
     */
    private class PlanTimeline(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(20f) }
        private val r = RectF()
        private var band = -1 to -1; private var charge = -1 to 0; private var now = -1

        fun set(start: Int, end: Int, chargeFrom: Int, chargeLen: Int, nowMin: Int) {
            band = start to end; charge = chargeFrom to chargeLen; now = nowMin; invalidate()
        }

        /** Minute du jour → fraction de 20:00 à 08:00 (hors plage : < 0 ou > 1). */
        private fun f(min: Int) = (((min - 1200) % 1440 + 1440) % 1440) / 720f

        override fun onDraw(canvas: Canvas) {
            val trackH = 44f; val w = width.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Pal.surface2; r.set(0f, 0f, w, trackH); canvas.drawRoundRect(r, 12f, 12f, paint)
            paint.color = Pal.line
            for (i in 1..5) canvas.drawRect(w * i / 6f - 1f, 10f, w * i / 6f + 1f, trackH - 10f, paint)
            val (cs, cl) = charge
            if (cs >= 0 && cl > 0) {
                val a = f(cs); val b = a + cl / 720f
                if (a <= 1f) { paint.color = Pal.energy; r.set(w * a, 8f, w * b.coerceAtMost(1f), trackH - 8f); canvas.drawRoundRect(r, 6f, 6f, paint) }
            }
            val (bs, be) = band
            if (bs >= 0) {
                val a = f(bs); var b = f(be); if (b < a) b = 1f
                if (a <= 1f) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Pal.action
                    r.set(w * a + 2f, 4f, w * b.coerceAtMost(1f) - 2f, trackH - 4f); canvas.drawRoundRect(r, 8f, 8f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            if (now >= 0 && f(now) <= 1f) { paint.color = Pal.text; canvas.drawRoundRect(w * f(now) - 2f, 2f, w * f(now) + 2f, trackH - 2f, 2f, 2f, paint) }
            txt.color = Pal.text2
            for (i in 0..6) {
                val l = "%02d:00".format((20 + i * 2) % 24); val tw = txt.measureText(l)
                canvas.drawText(l, (w * i / 6f - tw / 2).coerceIn(0f, w - tw), trackH + 34f, txt)
            }
        }
    }
}
