package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.BatteryBar
import app.mg4dash.cockpit.Btn
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
import app.mg4dash.cockpit.Sparkline
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.header
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.energy.EnergyMonitor
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs

/** Inicio · parado · sin Android Auto (lienzo : Home.dc.html). */
class HomeScreen(host: Host) : Screen(host) {
    override val nav = Nav.HOME

    private lateinit var soc: ValueText
    private lateinit var range: ValueText
    private lateinit var plug: Pill
    private lateinit var bar: BatteryBar
    private lateinit var spark: Sparkline
    private lateinit var avg: ValueText
    private lateinit var media: MediaCard
    private lateinit var adasState: android.widget.TextView
    private lateinit var adasSub: android.widget.TextView
    private lateinit var adasApply: Btn
    private lateinit var modes: Segmented
    private lateinit var regen: Btn
    private lateinit var onePedal: Btn
    private lateinit var esc: Btn
    private lateinit var tyres: android.widget.TextView
    private lateinit var aaPill: Pill
    private lateinit var aaTitle: android.widget.TextView

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(battery(), lp(368, MATCH))
        media = MediaCard(c, compact = false)
        addView(media, lp(424, MATCH))
        addView(c.col(Dim.GAP).apply {
            addView(assists(), lp(MATCH, WRAP))
            addView(driving(), lp(MATCH, 0, 1f))
        }, lp(576, MATCH))
        addView(c.col(Dim.GAP).apply {
            addView(androidAuto(), lp(MATCH, 0, 1f))
            addView(quick(), lp(MATCH, WRAP))
        }, lp(0, MATCH, 1f))
    }

    private fun battery() = c.card().apply {
        plug = Pill(c, "Sin enchufar", Pal.text3)
        addView(c.header("Batería", plug))
        soc = ValueText(c, Ty.HERO, Ty.VALUE); addView(soc)
        range = ValueText(c, Ty.VALUE, Ty.LABEL); addView(range)
        bar = BatteryBar(c); addView(bar, lp(MATCH, 44))
        addView(View(c), lp(MATCH, 0, 1f))
        addView(c.label("Consumo reciente"))
        spark = Sparkline(c); addView(spark, lp(MATCH, 56))
        avg = ValueText(c, Ty.TITLE, Ty.CAPTION); addView(avg)
        addView(Btn(c, "Energía", Icons.chevronRight, 28, vertical = false, iconEnd = true).apply {
            setOnClickListener { host.go("energy") }
        }, lp(MATCH, Dim.TAP_MIN))
    }

    private fun assists() = c.card().apply {
        addView(c.row(16).apply {
            addView(c.col(2).apply {
                addView(c.label("Asistentes"))
                adasState = c.text("—", Ty.TITLE, Pal.text, Ty.uiMedium); addView(adasState)
                adasSub = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }; addView(adasSub)
            }, lp(0, WRAP, 1f))
            adasApply = Btn(c, "Aplicar mi perfil", Icons.shield, 30, vertical = false).apply {
                look = Look.PRIMARY
                setOnClickListener { applyAdas() }
            }
            addView(adasApply, lp(260, Dim.TAP))
            addView(Btn(c, null, Icons.chevronRight, 30).apply { setOnClickListener { host.go("adas") } }, lp(Dim.TAP, Dim.TAP))
        })
    }

    private fun applyAdas() {
        when (Adas.apply(c)) {
            CarBridge.Write.SENT -> host.toast("Aplicando mi perfil", Adas.changes(c).size.let { if (it == 0) "Ya estaba aplicado" else "$it cambios" })
            CarBridge.Write.MOVING -> host.toast("Detén el coche para aplicar el perfil", tone = Pal.heat)
            CarBridge.Write.SPEED_UNKNOWN -> host.toast("No se puede leer la velocidad", "Perfil no aplicado", Pal.heat)
            CarBridge.Write.UNAVAILABLE -> host.toast("Ajustes del coche no disponibles", tone = Pal.alert)
        }
    }

    private fun driving() = c.card().apply {
        addView(c.label("Conducción"))
        modes = Segmented(c, listOf("Eco", "Normal", "Sport", "Nieve")) { i ->
            when (CarBridge.setDriveMode(CarBridge.driveModes[i])) {
                CarBridge.Write.MOVING -> host.toast("Detén el coche para cambiar el modo", tone = Pal.heat)
                CarBridge.Write.SPEED_UNKNOWN -> host.toast("No se puede leer la velocidad", "Modo sin cambiar", Pal.heat)
                else -> {}
            }
        }
        addView(modes, lp(MATCH, Dim.TAP))
        addView(c.row(12).apply {
            regen = Btn(c, "Regeneración", Icons.regen, 30, sub = "—").apply { setOnClickListener { cycleRegen() } }
            onePedal = Btn(c, "Un pedal", Icons.car, 30, led = true).apply { accent = Pal.energy; setOnClickListener { CarBridge.toggleOnePedal() } }
            esc = Btn(c, "ESC", Icons.esc, 30, led = true).apply { accent = Pal.energy; setOnClickListener { host.go("vehicle") } }
            addView(regen, lp(0, 112, 1f)); addView(onePedal, lp(0, 112, 1f)); addView(esc, lp(0, 112, 1f))
        }, lp(MATCH, WRAP))
        addView(View(c), lp(MATCH, 0, 1f))
        tyres = c.text("Neumáticos —", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }
        addView(c.row(10).apply {
            addView(app.mg4dash.cockpit.IconView(c, Icons.tyre, 24).apply { color = Pal.text2 })
            addView(tyres, lp(0, WRAP, 1f))
            addView(app.mg4dash.cockpit.IconView(c, Icons.chevronRight, 24).apply { color = Pal.text2 })
            setOnClickListener { host.go("vehicle") }
        }, lp(MATCH, 36))
    }

    private fun cycleRegen() {
        val s = CarBridge.state.value
        val next = ((s.regenLevel ?: 0) + 1) % 4
        CarBridge.setRegenLevel(next)
    }

    private fun androidAuto() = c.card().apply {
        aaPill = Pill(c, "Buscando teléfono…", Pal.heat)
        addView(c.label("Android Auto"))
        addView(aaPill, lp(WRAP, 40))
        aaTitle = c.text("Conecta tu teléfono", Ty.TITLE, Pal.text, Ty.uiMedium); addView(aaTitle)
        addView(c.text("Wi-Fi del coche: MG4 · servidor de unidad principal", Ty.CAPTION, Pal.text2))
        addView(View(c), lp(MATCH, 0, 1f))
        addView(Btn(c, "Ver conexión", Icons.chevronRight, 28, vertical = false, iconEnd = true).apply {
            setOnClickListener { host.go("aa") }
        }, lp(MATCH, Dim.TAP_MIN))
    }

    private fun quick() = c.card(gap = 12).apply {
        addView(quickBtn("Cámara 360°", Icons.camera) { Launch.open(c, Launch.AROUND_VIEW) })
        addView(quickBtn("Apagar pantalla", Icons.screenOff) { if (!CarBridge.setDisplay(false)) host.toast("Apagar pantalla no disponible en este firmware") })
        addView(quickBtn("Apps originales", Icons.apps) { c.startActivity(android.content.Intent(c, app.mg4dash.apps.AppsActivity::class.java)) })
    }

    private fun quickBtn(label: String, icon: app.mg4dash.cockpit.Icon, onClick: () -> Unit) =
        Btn(c, label, icon, 30, vertical = false).apply { setOnClickListener { onClick() } }.also { it.layoutParams = lp(MATCH, Dim.TAP_MIN) }

    override fun bind(s: VehicleState) {
        val p = s.socPrecise
        soc.set(if (p != null) Fmt.dec(p) else s.batteryPercent?.toString() ?: "—", "%")
        range.set(s.rangeKm?.toString() ?: "—", "km")
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        val charging = VehicleState.isCharging(s.chargeStatus)
        plug.set(when {
            !plugged -> "Sin enchufar"
            VehicleState.isDischargeMode(s.chargeStatus) -> "V2L"
            charging -> "Cargando"
            else -> "Enchufado"
        }, if (charging) Pal.energy else if (plugged) Pal.action else Pal.text3)
        val limit = CarProps.targetSocPercent(CarProps.getInt(CarProps.CHRG_TRGT_SOC)) ?: Prefs(c).chargeLimit
        bar.set((p ?: s.batteryPercent?.toFloat() ?: 0f), limit.toFloat())
        val hist = ChargeGuard.history.value.takeLast(24)
        val a = EnergyMonitor.sinceCharge.value.per100 ?: hist.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        spark.set(hist, a)
        avg.set(a?.let { Fmt.dec(it) } ?: "—", "kWh/100 km media")

        media.bind()

        val st = Adas.state.value
        val match = Adas.matches(c)
        adasState.put(when (match) { true -> "Mi perfil"; false -> "De serie"; null -> "Sin datos" })
        adasSub.text = when {
            st.busy -> "Aplicando…"
            match == false -> Adas.changes(c).size.let { "$it cambios pendientes" }
            match == true -> "Activos según tu perfil"
            else -> "Sin conexión"
        }
        adasApply.look = if (match == true) Look.NEUTRAL else Look.PRIMARY
        adasApply.setLabel(if (match == true) "Perfil aplicado" else "Aplicar mi perfil")

        modes.select(CarBridge.driveModes.indexOf(s.driveMode).takeIf { it >= 0 })
        regen.setSub(VehicleState.regenLabel(s.regenLevel, s.onePedal))
        onePedal.on = s.onePedal == 1
        esc.on = CarBridge.escActive() == true
        esc.setSub(if (CarBridge.escActive() == false) "Desactivado" else "Activado")

        val t = (0..3).map { CarProps.getInt(0x21401553 + it)?.takeIf { v -> v in 50..600 } }
        tyres.text = if (t.all { it == null }) "Neumáticos sin datos"
            else "Neumáticos  " + t.joinToString(" · ") { it?.let { v -> Fmt.dec(v / 100f) } ?: "—" } + " bar"

        val aa = MediaHub.androidAutoAvailable()
        aaPill.set(if (aa) "Conectado" else "Buscando teléfono…", if (aa) Pal.energy else Pal.heat)
        aaTitle.put(if (aa) "Android Auto activo" else "Conecta tu teléfono")
    }
}
