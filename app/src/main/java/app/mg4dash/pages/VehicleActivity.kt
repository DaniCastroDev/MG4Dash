package app.mg4dash.pages

import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.system.Apps
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge
import java.util.Locale

/**
 * Page Véhicule, en lecture seule : les réglages se font dans MG4 Control (raccourci en
 * en-tête) ou dans les réglages SAIC d'origine.
 */
class VehicleActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_vehicle)
    override val page = app.mg4dash.ui.Dock.Page.VEHICLE

    private companion object {
        val TIRE_LABELS = listOf(R.string.vh_tire_fl, R.string.vh_tire_fr, R.string.vh_tire_rl, R.string.vh_tire_rr)
    }

    private lateinit var mode: TextView
    private lateinit var regenGauge: SegmentedGauge
    private lateinit var regen: TextView
    private val values = mutableMapOf<Int, TextView>()

    override fun buildBody() {
        header.addView(kit.text(getString(R.string.vh_saic), 19f, R.color.text_sub).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { Apps.open(this@VehicleActivity, Apps.VEHICLE, "SAIC") }
        }, kit.lp(-2, -2, r = 12))
        header.addView(kit.text(getString(R.string.vh_mg4control), 18f, R.color.on_action).apply {
            setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(12))
            setBackgroundResource(R.drawable.bg_aa_tile)
            setOnClickListener { Apps.open(this@VehicleActivity, Apps.MG4_CONTROL, "MG4 Control") }
        })

        // ── Conduite ──
        val drive = kit.panel(getString(R.string.vh_driving))
        mode = kit.text("—", 64f, light = true)
        drive.addView(kit.text(getString(R.string.vh_drive_mode), 18f, R.color.text_sub), kit.lp(-1, -2, t = 14))
        drive.addView(mode, kit.lp(-1, -2, t = 4))
        drive.addView(kit.text(getString(R.string.vh_regen), 18f, R.color.text_sub), kit.lp(-1, -2, t = 22))
        val rRow = kit.row()
        regenGauge = SegmentedGauge(this).apply {
            segments = 4; onColor = kit.color(R.color.accent_energy)
            setPadding(0, kit.dp(13), 0, kit.dp(13))
            onSegmentTap = { app.mg4dash.car.CarBridge.setRegenLevel(it) }
            onLongTap = { app.mg4dash.car.CarBridge.toggleOnePedal() }
        }
        regen = kit.text("—", 22f)
        rRow.addView(regenGauge, kit.lp(0, kit.dp(44), 1f, r = 16))
        rRow.addView(regen)
        drive.addView(rRow, kit.lp(-1, -2, t = 8))
        drive.addView(kit.divider(), kit.lp(-1, 1, t = 18))
        listOf(R.string.vh_one_pedal, R.string.vh_steering, R.string.vh_brake, R.string.vh_endurance).forEach { addInfo(drive, it) }

        // ── Aides à la conduite ──
        val adas = kit.panel(getString(R.string.vh_adas))
        listOf(R.string.vh_lane, R.string.vh_aeb, R.string.vh_overspeed, R.string.vh_limit_beep).forEach { addInfo(adas, it) }
        adas.addView(kit.text(getString(R.string.vh_use_mg4control), 17f, R.color.text_title), kit.lp(-1, -2, t = 10))

        // ── Pneus : pression et température (capteurs TPMS) ──
        val tires = kit.panel(getString(R.string.vh_tires))
        val grid = kit.column()
        listOf(listOf(0, 1), listOf(2, 3)).forEach { pair ->
            val r = kit.row()
            pair.forEach { i ->
                val cell = kit.column().apply { gravity = android.view.Gravity.CENTER }
                cell.addView(kit.text(getString(TIRE_LABELS[i]), 16f, R.color.text_title).apply { gravity = android.view.Gravity.CENTER })
                val p = kit.text("—", 34f, light = true).apply { gravity = android.view.Gravity.CENTER }
                val t = kit.text("", 17f, R.color.text_sub).apply { gravity = android.view.Gravity.CENTER }
                cell.addView(p); cell.addView(t)
                tirePressure += p; tireTemp += t
                r.addView(cell, kit.lp(0, -2, 1f))
            }
            grid.addView(r, kit.lp(-1, 0, 1f))
        }
        tires.addView(grid, kit.lp(-1, 0, 1f, t = 6))
        val mid = kit.column()
        mid.addView(adas, kit.lp(-1, -2))
        mid.addView(tires, kit.lp(-1, 0, 1f, t = 12))
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) { refreshTires(); kotlinx.coroutines.delay(5_000) }
            }
        }

        // ── État ──
        val state = kit.panel(getString(R.string.vh_state))
        listOf(R.string.vh_battery, R.string.vh_range, R.string.vh_avg_cons, R.string.vh_odometer, R.string.vh_speed, R.string.vh_outside, R.string.vh_charge).forEach { addInfo(state, it) }

        body.addView(drive, kit.lp(0, -1, 1f, r = 6))
        body.addView(mid, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(state, kit.lp(0, -1, 1f, l = 6))
    }

    private val tirePressure = mutableListOf<TextView>()
    private val tireTemp = mutableListOf<TextView>()

    /** Pression (kPa, affichée en bar), température (°C) et état du capteur : AVG, AVD, ARG, ARD. */
    private suspend fun refreshTires() {
        val data = withContext(Dispatchers.IO) {
            (0..3).map { i ->
                Triple(CarProps.getInt(0x21401553 + i), CarProps.getInt(0x2140155b + i), CarProps.getInt(0x21401557 + i))
            }
        }
        data.forEachIndexed { i, (kpa, temp, state) ->
            val alert = state != null && state != 0
            tirePressure[i].text = kpa?.takeIf { it in 50..600 }?.let { String.format(Locale.getDefault(), "%.2f bar", it / 100f) } ?: "—"
            tirePressure[i].setTextColor(kit.color(if (alert) R.color.accent_danger else R.color.text_value))
            tireTemp[i].text = listOfNotNull(
                temp?.takeIf { it in -40..150 }?.let { "$it °C" },
                if (alert) getString(R.string.vh_tire_check) else null,
            ).joinToString(" · ")
        }
    }

    private fun addInfo(panel: LinearLayout, label: Int) {
        val (row, value) = kit.infoRow(getString(label))
        panel.addView(row, kit.lp(-1, -2))
        values[label] = value
    }

    private fun set(label: Int, v: String) { values[label]?.text = v }

    override fun render(s: VehicleState) {
        mode.text = VehicleState.driveModeLabel(s.driveMode)
        regenGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        regen.text = VehicleState.regenLabel(s.regenLevel, s.onePedal)
        set(R.string.vh_one_pedal, VehicleState.onOff(s.onePedal))
        set(R.string.vh_steering, VehicleState.steeringLabel(s.steeringMode))
        set(R.string.vh_brake, VehicleState.pedalLabel(s.brakePedalMode))
        set(R.string.vh_endurance, VehicleState.onOff(s.enduranceMode))
        set(R.string.vh_lane, VehicleState.lasLabel(s.lasMode))
        set(R.string.vh_aeb, VehicleState.aebLabel(s.fcwState, s.fcwBrakeMode))
        set(R.string.vh_overspeed, VehicleState.onOff(s.overSpeedSound))
        set(R.string.vh_limit_beep, VehicleState.onOff(s.speedLimitSound))
        set(R.string.vh_battery, s.batteryPercent?.let { "$it %" } ?: "—")
        set(R.string.vh_range, s.rangeKm?.let { "$it km" } ?: "—")
        set(R.string.vh_avg_cons, ConsumptionEstimator.average.value?.let { String.format(Locale.getDefault(), "%.1f kWh/100", it) } ?: "—")
        set(R.string.vh_odometer, s.odometerKm?.let { String.format(Locale.getDefault(), "%,d km", it) } ?: "—")
        set(R.string.vh_speed, s.speedKmh?.let { "${Math.round(it)} km/h" } ?: "—")
        set(R.string.vh_outside, s.outsideTemp?.let { "${Math.round(it)}°" } ?: "—")
        set(R.string.vh_charge, VehicleState.chargeStatusLabel(s.chargeStatus))
    }
}
