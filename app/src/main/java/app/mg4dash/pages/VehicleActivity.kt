package app.mg4dash.pages

import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.VehicleState
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
    override val pageTitle = "VÉHICULE"
    override val page = app.mg4dash.ui.Dock.Page.VEHICLE

    private lateinit var mode: TextView
    private lateinit var regenGauge: SegmentedGauge
    private lateinit var regen: TextView
    private val values = mutableMapOf<String, TextView>()

    override fun buildBody() {
        header.addView(kit.text("Réglages SAIC  ›", 19f, R.color.text_sub).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { Apps.open(this@VehicleActivity, Apps.VEHICLE, "Réglages véhicule") }
        }, kit.lp(-2, -2, r = 12))
        header.addView(kit.text("Ouvrir MG4 Control  ›", 18f, R.color.text_value).apply {
            setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(12))
            setBackgroundResource(R.drawable.bg_aa_tile)
            setOnClickListener { Apps.open(this@VehicleActivity, Apps.MG4_CONTROL, "MG4 Control") }
        })

        // ── Conduite ──
        val drive = kit.panel("CONDUITE")
        mode = kit.text("—", 64f, light = true)
        drive.addView(kit.text("Mode de conduite", 18f, R.color.text_sub), kit.lp(-1, -2, t = 14))
        drive.addView(mode, kit.lp(-1, -2, t = 4))
        drive.addView(kit.text("Récupération d'énergie (KERS)", 18f, R.color.text_sub), kit.lp(-1, -2, t = 22))
        val rRow = kit.row()
        regenGauge = SegmentedGauge(this).apply { segments = 4; onColor = kit.color(R.color.accent_energy) }
        regen = kit.text("—", 22f)
        rRow.addView(regenGauge, kit.lp(0, kit.dp(18), 1f, r = 16))
        rRow.addView(regen)
        drive.addView(rRow, kit.lp(-1, -2, t = 8))
        drive.addView(kit.divider(), kit.lp(-1, 1, t = 18))
        listOf("Conduite à une pédale", "Direction", "Pédale de frein", "Autonomie+").forEach { addInfo(drive, it) }

        // ── Aides à la conduite ──
        val adas = kit.panel("AIDES À LA CONDUITE")
        listOf("Maintien de voie", "Freinage d'urgence", "Alerte de survitesse", "Bip limite de vitesse").forEach { addInfo(adas, it) }
        adas.addView(LinearLayout(this), kit.lp(-1, 0, 1f))
        adas.addView(kit.text("Pour modifier ces réglages, utilisez MG4 Control.", 17f, R.color.text_title))

        // ── État ──
        val state = kit.panel("ÉTAT DU VÉHICULE")
        listOf("Batterie", "Autonomie", "Conso moyenne", "Odomètre", "Vitesse", "Température extérieure", "Charge").forEach { addInfo(state, it) }

        body.addView(drive, kit.lp(0, -1, 1f, r = 6))
        body.addView(adas, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(state, kit.lp(0, -1, 1f, l = 6))
    }

    private fun addInfo(panel: LinearLayout, label: String) {
        val (row, value) = kit.infoRow(label)
        panel.addView(row, kit.lp(-1, -2))
        values[label] = value
    }

    private fun set(label: String, v: String) { values[label]?.text = v }

    override fun render(s: VehicleState) {
        mode.text = VehicleState.driveModeLabel(s.driveMode)
        regenGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        regen.text = VehicleState.regenLabel(s.regenLevel, s.onePedal)
        set("Conduite à une pédale", VehicleState.onOff(s.onePedal))
        set("Direction", VehicleState.steeringLabel(s.steeringMode))
        set("Pédale de frein", VehicleState.pedalLabel(s.brakePedalMode))
        set("Autonomie+", VehicleState.onOff(s.enduranceMode))
        set("Maintien de voie", VehicleState.lasLabel(s.lasMode))
        set("Freinage d'urgence", VehicleState.aebLabel(s.fcwState, s.fcwBrakeMode))
        set("Alerte de survitesse", VehicleState.onOff(s.overSpeedSound))
        set("Bip limite de vitesse", VehicleState.onOff(s.speedLimitSound))
        set("Batterie", s.batteryPercent?.let { "$it %" } ?: "—")
        set("Autonomie", s.rangeKm?.let { "$it km" } ?: "—")
        set("Conso moyenne", ConsumptionEstimator.average.value?.let { String.format(Locale.FRANCE, "%.1f kWh/100", it) } ?: "—")
        set("Odomètre", s.odometerKm?.let { String.format(Locale.FRANCE, "%,d km", it) } ?: "—")
        set("Vitesse", s.speedKmh?.let { "${Math.round(it)} km/h" } ?: "—")
        set("Température extérieure", s.outsideTemp?.let { "${Math.round(it)}°" } ?: "—")
        set("Charge", VehicleState.chargeStatusLabel(s.chargeStatus))
    }
}
