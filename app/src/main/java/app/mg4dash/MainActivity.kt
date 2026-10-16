package app.mg4dash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.Location
import app.mg4dash.map.LocationRepo
import app.mg4dash.map.MapCard
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.pages.EnergyActivity
import app.mg4dash.pages.PhoneActivity
import app.mg4dash.pages.VehicleActivity
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.Dock
import app.mg4dash.ui.SimpleHome
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.apps.AppsActivity
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.databinding.ActivityMainBinding
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.media.MediaHub
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Apps
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var mapCard: MapCard
    private var simpleMode = false
    private var themeSig = 0
    private var simple: SimpleHome? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        app.mg4dash.ui.Themes.apply(this)
        themeSig = app.mg4dash.ui.Themes.signature(this)
        if (prefs.homeStyle == 1) {
            // Accueil épuré : même activité HOME, autre contenu
            simpleMode = true
            val sh = SimpleHome(this)
            simple = sh
            setContentView(sh.root)
            sh.start()
            warmUpStockLauncherIfNeeded()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.fanGauge.segments = VehicleState.FAN_MAX
        b.batteryGauge.segments = 20
        b.kersGauge.segments = 4
        b.kersGauge.onColor = color(R.color.accent_energy)
        mapCard = MapCard(this, b.mapHolder)
        buildDock()
        wireClimate()
        wireMedia()
        wireCards()
        observe()
        warmUpStockLauncherIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        CarBridge.setForeground(true)
        // Style d'accueil changé dans les paramètres : on reconstruit l'écran
        if ((prefs.homeStyle == 1) != simpleMode || app.mg4dash.ui.Themes.signature(this) != themeSig) { recreate(); return }
        if (simpleMode) { simple?.onResume(); Fullscreen.apply(this); return }
        mapCard.onResume()
        if (prefs.homeWidget == 1) renderShortcuts()
        Fullscreen.apply(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Passage jour / nuit de la voiture (mode auto) : on redessine avec l'autre thème
        if (app.mg4dash.ui.Themes.signature(this) != themeSig) { app.mg4dash.ui.Themes.publish(this); recreate() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Fullscreen.apply(this)
    }


    override fun onPause() {
        super.onPause()
        CarBridge.setForeground(false)
        if (!simpleMode) mapCard.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /** HOME pressé alors qu'on est déjà l'accueil : rien à faire, mais on ne recrée rien. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    @Deprecated("Accueil : retour sans effet")
    override fun onBackPressed() { /* l'accueil ne se ferme pas */ }

    // ── Carte ────────────────────────────────────────────────────────────────

    private fun renderLocation(loc: Location?, s: VehicleState) {
        b.mapEmpty.visibility = if (prefs.homeWidget != 1 && (loc == null || !mapCard.available)) View.VISIBLE else View.GONE
        if (!mapCard.available) b.mapEmpty.text = getString(R.string.map_unavailable)
        val speed = s.speedKmh?.takeIf { it >= 0f } ?: loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
        b.mapSpeed.text = speed?.let { "${Math.round(it)} km/h" } ?: "— km/h"
        b.mapInfo.text = listOfNotNull(
            loc?.takeIf { it.hasBearing() }?.let { getString(R.string.heading, LocationRepo.cardinal(it.bearing)) },
            loc?.takeIf { it.hasAltitude() }?.let { getString(R.string.altitude, Math.round(it.altitude).toInt()) },
        ).joinToString(" · ")
        loc?.let { mapCard.update(it) }
    }

    // ── Dock ─────────────────────────────────────────────────────────────────

    private fun buildDock() = Dock.build(this, b.dock, Dock.Page.HOME)


    // ── Clim ─────────────────────────────────────────────────────────────────

    private fun wireClimate() {
        b.climPower.setOnClickListener { CarBridge.togglePower() }
        repeatOnHold(b.tempDown) { CarBridge.stepTemp(-1) }
        repeatOnHold(b.tempUp) { CarBridge.stepTemp(1) }
        b.fanDown.setOnClickListener { CarBridge.stepFan(-1) }
        b.fanUp.setOnClickListener { CarBridge.stepFan(1) }
        b.chipAuto.setOnClickListener { CarBridge.toggleAuto() }
        b.chipAc.setOnClickListener { CarBridge.toggleAc() }
        b.chipRecirc.setOnClickListener { CarBridge.toggleRecirc() }
        // Direction de l'air : visage → visage + pieds → pieds → pieds + pare-brise (le pare-brise seul a son bouton physique)
        b.chipFlow.setOnClickListener {
            val cur = CarBridge.state.value.fanDirection
            CarBridge.setFanDirection(FLOW_CYCLE[(FLOW_CYCLE.indexOf(cur) + 1) % FLOW_CYCLE.size])
        }
        b.temp.setOnClickListener { startActivity(Intent(this, ClimateActivity::class.java)) }
    }

    /** Appui = un cran ; appui maintenu = répétition toutes les 350 ms. */
    private fun repeatOnHold(view: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 350) }
        }
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); handler.postDelayed(repeat, 500) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
    }

    private fun renderClimate(s: VehicleState) {
        val on = s.hvacOn == true
        val known = s.connected && s.hvacOn != null
        b.climPower.isSelected = on
        b.climPower.setColorFilter(color(if (on) R.color.accent_energy else R.color.text_sub))
        b.temp.text = if (on || s.driverTemp != null) VehicleState.tempLabel(s.driverTemp) else getString(R.string.no_data)
        b.temp.setTextColor(color(if (on) R.color.text_value else R.color.text_disabled))
        b.climOutside.text = s.outsideTemp?.let { getString(R.string.outside_short, Math.round(it)) } ?: ""
        // En mode AUTO, la voiture rapporte une vitesse peu parlante (souvent le maximum) :
        // on affiche « AUTO » et une jauge grisée plutôt qu'un niveau trompeur.
        val auto = on && s.autoOn == true
        b.fanGauge.onColor = color(if (auto) R.color.text_disabled else R.color.accent_cold)
        b.fanGauge.value = if (on && !auto) (s.fanSpeed ?: 0) else 0
        b.fanValue.text = when {
            !on -> getString(R.string.off)
            auto -> "AUTO"
            else -> s.fanSpeed?.toString() ?: "—"
        }
        b.fanValue.setTextColor(color(if (auto) R.color.accent_cold else if (on) R.color.text_value else R.color.text_disabled))
        b.fanValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (auto || !on) 16f else 20f)
        b.chipAuto.isSelected = s.autoOn == true
        b.chipAc.isSelected = s.acOn == true
        // Recyclage : 0 = recyclé, 1 = extérieur, 2 = auto (la voiture choisit ; état réel lu à part s'il existe)
        val autoRecirc = s.recirc == 2
        val inner = if (autoRecirc) actualRecirc else s.recirc == 0
        b.chipRecirc.isSelected = s.recirc == 0 || autoRecirc
        b.chipRecircIcon.setImageResource(if (inner == true) R.drawable.ic_air_recirc else R.drawable.ic_air_outside)
        b.chipRecircAuto.visibility = if (autoRecirc) View.VISIBLE else View.GONE
        // Direction de l'air ; en mode AUTO, c'est la voiture qui la choisit
        b.chipFlowIcon.setImageResource(flowIcon(s.fanDirection))
        b.chipFlow.isSelected = auto
        b.chipFlowAuto.visibility = if (auto) View.VISIBLE else View.GONE
        listOf(b.tempDown, b.tempUp, b.fanDown, b.fanUp, b.chipAuto, b.chipAc, b.chipRecirc, b.chipFlow, b.climPower)
            .forEach { it.isEnabled = known }
        b.climateCard.alpha = if (known) 1f else 0.6f
    }

    private val FLOW_CYCLE = listOf(0, 1, 2, 3)

    private fun flowIcon(d: Int?): Int = when (d) {
        1, 5 -> R.drawable.ic_flow_face_feet
        2 -> R.drawable.ic_flow_feet
        3, 4 -> R.drawable.ic_flow_feet_screen
        else -> R.drawable.ic_flow_face
    }

    /** Recyclage réellement actif (HVAC_RECIRC_ON), lu toutes les 5 s ; null si la voiture ne le fournit pas. */
    @Volatile private var actualRecirc: Boolean? = null

    // ── Énergie ──────────────────────────────────────────────────────────────

    private fun renderEnergy(s: VehicleState, avg: Float?) {
        val pct = s.batteryPercent
        b.battery.text = pct?.let { "$it %" } ?: "— %"
        val battColor = when {
            pct == null -> R.color.text_disabled
            pct <= 15 -> R.color.accent_danger
            pct <= 30 -> R.color.accent_heat
            else -> R.color.accent_energy
        }
        b.batteryGauge.onColor = color(battColor)
        b.batteryGauge.value = pct?.let { (it + 4) / 5 } ?: 0
        b.range.text = s.rangeKm?.let { "$it km" } ?: "— km"
        b.consumption.text = avg?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
        b.driveMode.text = VehicleState.driveModeLabel(s.driveMode)
        b.kersGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        b.kersLabel.text = VehicleState.regenLabel(s.regenLevel, s.onePedal)
        // État de charge : valeurs relevées dans le launcher d'origine non encore confirmées sur
        // véhicule ; on n'affiche que « en charge » quand un temps restant est fourni.
        val remain = s.chargeRemainMin
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        b.chargeState.text = when {
            !VehicleState.isPlugged(s.chargeStatus) -> ""
            VehicleState.isCharging(s.chargeStatus) && remain != null && remain in 1..2000 ->
                getString(R.string.charging_eta, remain / 60, remain % 60)
            else -> VehicleState.chargeStatusLabel(s.chargeStatus)
        }
        b.chargeState.setTextColor(color(if (discharge) R.color.accent_heat else R.color.accent_energy))
        // Bouton comme sur le launcher d'origine : inactif câble débranché, et bascule
        // automatiquement en « V2L » quand un adaptateur de décharge est branché.
        b.chargeAction.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        b.chargeAction.setBackgroundResource(if (discharge) R.drawable.bg_chip_heat else R.drawable.bg_chip)
        b.chargeAction.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, if (discharge) R.color.chip_text_heat else R.color.chip_text))
        b.chargeAction.isSelected = if (discharge) s.dischargeCtrl == 1 else s.chargeCtrl == 1
        b.chargeAction.text = when {
            discharge -> getString(if (s.dischargeCtrl == 1) R.string.v2l_stop else R.string.v2l_start)
            s.chargeCtrl == 1 -> getString(R.string.stop_btn)
            else -> getString(R.string.charge_btn)
        }
    }

    private fun onChargeAction() {
        val s = CarBridge.state.value
        if (!VehicleState.isPlugged(s.chargeStatus)) return
        when {
            VehicleState.isDischargeMode(s.chargeStatus) -> if (s.dischargeCtrl == 1) CarBridge.stopV2L() else CarBridge.startV2L()
            s.chargeCtrl == 1 -> CarBridge.stopCharge()
            else -> CarBridge.startCharge()
        }
    }

    // ── Média ────────────────────────────────────────────────────────────────

    private fun wireMedia() {
        b.mediaPlay.setOnClickListener { MediaHub.playPause() }
        b.mediaNext.setOnClickListener { MediaHub.next() }
        b.mediaPrev.setOnClickListener { MediaHub.previous() }
        // Les puces basculent la source affichée ; l'appli ne s'ouvre qu'en touchant la pochette / les infos.
        b.srcRadio.setOnClickListener { MediaHub.select("com.saicmotor.radio") }
        b.srcMedia.setOnClickListener { MediaHub.selectUsbBt() }
        b.srcAa.setOnClickListener { MediaHub.selectAndroidAuto() }
        b.mediaInfo.setOnClickListener {
            val owner = MediaHub.ownerComponent()
            if (owner != null) Apps.open(this, owner, "Media") else Apps.open(this, Apps.RADIO, "Radio")
        }
    }

    private fun renderMedia(m: MediaHub.NowPlaying) {
        if (!m.available) {
            b.mediaSource.text = getString(R.string.title_media)
            b.mediaTitle.text = getString(R.string.no_media)
            b.mediaArtist.text = getString(R.string.media_hint)
            b.mediaAlbum.text = ""
        } else {
            b.mediaSource.text = m.source.ifEmpty { getString(R.string.title_media) }
            b.mediaTitle.text = m.title.ifEmpty { m.source }
            b.mediaArtist.text = m.artist
            b.mediaAlbum.text = m.album
        }
        b.srcRadio.isSelected = m.packageName == "com.saicmotor.radio"
        b.srcMedia.isSelected = m.packageName == "com.saicmotor.media" || m.packageName == "com.android.bluetooth"
        b.srcAa.isSelected = MediaHub.isAndroidAuto(m.packageName)
        b.srcAa.visibility = if (m.androidAuto || b.srcAa.isSelected) View.VISIBLE else View.GONE
        b.mediaArt.setImageBitmap(m.art)
        b.mediaArtPlaceholder.visibility = if (m.art == null) View.VISIBLE else View.INVISIBLE
        b.mediaPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.mediaProgress.visibility = if (m.durationMs > 0) View.VISIBLE else View.INVISIBLE
        if (m.durationMs > 0) b.mediaProgress.progress = (m.positionMs * 1000 / m.durationMs).toInt()
        val controls = m.available
        b.mediaPlay.isEnabled = controls; b.mediaNext.isEnabled = controls; b.mediaPrev.isEnabled = controls
    }

    // ── Météo ────────────────────────────────────────────────────────────────

    private fun renderWeather(w: WeatherRepo.Weather?, s: VehicleState) {
        if (w == null) {
            b.weatherTemp.text = s.outsideTemp?.let { "${Math.round(it)}°" } ?: "—"
            b.weatherLabel.text = getString(R.string.weather_pending)
            b.weatherDetails.text = if (s.outsideTemp != null) getString(R.string.weather_outside_vehicle) else ""
            b.weatherPlace.text = ""
            b.weatherHours.removeAllViews()
            return
        }
        b.weatherPlace.text = w.place
        b.weatherIcon.setImageResource(WeatherRepo.icon(w.code))
        b.weatherIcon.setColorFilter(color(if (w.code <= 1) R.color.accent_heat else R.color.text_value))
        b.weatherTemp.text = "${w.tempC}°"
        b.weatherLabel.text = WeatherRepo.label(w.code)
        b.weatherDetails.text = getString(R.string.weather_details, w.feelsC, w.windKmh, w.humidity)
        fillForecast(b.weatherHours, w.hours.map { Triple(it.label, it.code, "${it.tempC}°") })
    }

    private fun fillForecast(row: LinearLayout, items: List<Triple<String, Int, String>>) {
        row.removeAllViews()
        items.forEach { (label, code, value) ->
            // Heure, icône et température empilées : plus gros, et la hauteur du cadre est mieux utilisée
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            cell.addView(TextView(this).apply {
                text = label; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            })
            cell.addView(ImageView(this).apply {
                setImageResource(WeatherRepo.icon(code)); setColorFilter(color(R.color.text_sub))
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(2); bottomMargin = dp(2) })
            cell.addView(TextView(this).apply {
                text = value; setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f); gravity = Gravity.CENTER
            })
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ── Widget secondaire ────────────────────────────────────────────────────

    private fun wireCards() {
        b.energyCard.setOnClickListener { startActivity(Intent(this, EnergyActivity::class.java)) }
        b.chargeAction.setOnClickListener { onChargeAction() }
        b.weatherCard.setOnClickListener { WeatherRepo.refreshNow(this) }
        b.secondaryCard.setOnClickListener {
            prefs.secondaryWidget = (prefs.secondaryWidget.coerceIn(0, 2) + 1) % 3
            renderSecondary()
            lifecycleScope.launch { updatePower() }
        }
        b.mapZoomIn.setOnClickListener { mapCard.zoom(1) }
        b.mapZoomOut.setOnClickListener { mapCard.zoom(-1) }
        b.mapCenter.setOnClickListener { mapCard.recenter(LocationRepo.location.value) }
        b.widgetToggle.setOnClickListener {
            prefs.homeWidget = if (prefs.homeWidget == 1) 0 else 1
            applyHomeWidget()
        }
        applyHomeWidget()
    }

    // ── Carte ou raccourcis ──────────────────────────────────────────────────

    private var shortcutsPage = 0

    private fun applyHomeWidget() {
        val shortcuts = prefs.homeWidget == 1
        // La carte et ses commandes disparaissent derrière les raccourcis (et inversement).
        for (i in 0 until b.mapCard.childCount) {
            val v = b.mapCard.getChildAt(i)
            if (v === b.widgetToggle) continue
            if (v === b.shortcutsHolder) v.visibility = if (shortcuts) View.VISIBLE else View.GONE
            else v.visibility = if (shortcuts) View.INVISIBLE else View.VISIBLE
        }
        if (!shortcuts) renderLocation(LocationRepo.location.value, CarBridge.state.value)
        b.widgetToggle.setImageResource(if (shortcuts) R.drawable.ic_map else R.drawable.ic_apps)
        if (shortcuts) renderShortcuts()
    }

    private fun renderShortcuts() {
        app.mg4dash.shortcuts.Shortcuts.render(this, b.shortcutsHolder, shortcutsPage) { p ->
            shortcutsPage = p; renderShortcuts()
        }
    }

    // Widget du bas : énergie du trajet, puissance en direct, téléphone (toucher le widget pour changer)
    private var builtMode = -1
    private var powerValue: TextView? = null
    private var powerLabel: TextView? = null
    private var powerAvg: TextView? = null
    private var powerBar: app.mg4dash.ui.PowerBar? = null

    private fun renderSecondary() {
        val mode = prefs.secondaryWidget.coerceIn(0, 2)
        b.secondaryDots.text = (0..2).joinToString(" ") { if (it == mode) "●" else "○" }
        when (mode) {
            0 -> renderTripEnergy()
            1 -> if (builtMode != 1) { buildPower(); builtMode = 1; lifecycleScope.launch { updatePower() } }
            else -> if (builtMode != 2) renderPhoneFavs()
        }
        builtMode = mode
    }

    private fun statColumn(title: String, value: String, valueSp: Float = 28f, valueColor: Int = color(R.color.text_value)) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = value; setTextColor(valueColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp)
                fontFeatureSettings = "tnum"; maxLines = 1; gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = title; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                letterSpacing = 0.12f; maxLines = 1; gravity = Gravity.CENTER
            })
        }

    private fun kwh(v: Float) = String.format(Locale.getDefault(), "%.1f", v)

    /** Énergie depuis le démarrage, répartie comme sur la page Énergie (mesures de MG4Dash). */
    private fun renderTripEnergy() {
        b.secondaryTitle.text = getString(R.string.sw_energy)
        val t = app.mg4dash.energy.EnergyMonitor.sinceStart.value
        val ok = t.outKwh > 0f
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.VERTICAL
        // Lisible d'un coup d'œil : une grosse barre colorée et les pourcentages ; le détail est sur la page Énergie.
        val d = resources.displayMetrics.density
        val parts = listOf(
            Triple(R.string.sw_drive, t.driveKwh, color(R.color.accent_energy)),
            Triple(R.string.sw_clim, t.climKwh, color(R.color.accent_cold)),
            Triple(R.string.sw_acc, t.accKwh, color(R.color.accent_heat)),
        )
        g.addView(app.mg4dash.ui.StackedBar(this).apply {
            this.parts = if (ok) parts.map { app.mg4dash.ui.StackedBar.Part(it.second, it.third) } else emptyList()
        }, LinearLayout.LayoutParams(-1, (34 * d).toInt()).apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parts.forEach { (label, v, c) ->
            row.addView(statColumn(getString(label), if (ok) "${Math.round(v / t.outKwh * 100)} %" else "—", 30f, c),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        g.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
    }

    private fun buildPower() {
        b.secondaryTitle.text = getString(R.string.sw_power)
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.VERTICAL
        val d = resources.displayMetrics.density
        // Même présentation que l'énergie du trajet : grosse barre, puis les chiffres en grand
        powerBar = app.mg4dash.ui.PowerBar(this)
        g.addView(powerBar, LinearLayout.LayoutParams(-1, (34 * d).toInt()).apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = statColumn(getString(R.string.sw_drawn), "—", 30f)
        powerValue = left.getChildAt(0) as TextView; powerLabel = left.getChildAt(1) as TextView
        val right = statColumn(getString(R.string.sw_trip_avg), "—", 30f)
        powerAvg = right.getChildAt(0) as TextView
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        g.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
    }

    /** Puissance batterie (tension × courant) : positive = tirée, négative = récupérée. */
    private suspend fun updatePower() {
        if (builtMode != 1) return
        val kw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_VOL)
            val i = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_CRNT)
            if (v != null && i != null && v in 100f..900f) v * i / 1000f else null
        }
        val plugged = VehicleState.isCharging(CarBridge.state.value.chargeStatus)
        val regen = kw != null && kw < -0.05f && !plugged
        powerValue?.text = kw?.let { String.format(Locale.getDefault(), "%.1f kW", kotlin.math.abs(it)) } ?: "—"
        powerValue?.setTextColor(color(if (regen) R.color.accent_energy else R.color.text_value))
        powerLabel?.text = getString(when { plugged -> R.string.sw_charging; regen -> R.string.sw_recovering; else -> R.string.sw_drawn })
        powerBar?.kw = if (plugged) 0f else kw ?: 0f
        val avg = app.mg4dash.energy.EnergyMonitor.sinceStart.value.per100
        powerAvg?.text = avg?.let { "${kwh(it)} kWh/100" } ?: "—"
    }

    /** Trois contacts favoris, choisis dans le répertoire ; toucher = appeler, appui long = changer. */
    private fun renderPhoneFavs() {
        b.secondaryTitle.text = getString(R.string.sw_phone)
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.HORIZONTAL
        val d = resources.displayMetrics.density
        val refresh = { builtMode = -1; renderSecondary() }
        for (i in 0 until app.mg4dash.shortcuts.PhoneFavorites.COUNT) {
            val fav = app.mg4dash.shortcuts.PhoneFavorites.get(prefs, i)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                setBackgroundResource(R.drawable.bg_dock_item)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (fav == null) app.mg4dash.shortcuts.PhoneFavorites.edit(this@MainActivity, i, refresh)
                    else PhoneActivity.placeCall(this@MainActivity, fav.number)
                }
                setOnLongClickListener { app.mg4dash.shortcuts.PhoneFavorites.edit(this@MainActivity, i, refresh); true }
            }
            cell.addView(ImageView(this).apply {
                setImageResource(if (fav == null) R.drawable.ic_plus else R.drawable.ic_phone)
                setColorFilter(color(if (fav == null) R.color.text_title else R.color.accent_energy))
            }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            if (fav != null) cell.addView(TextView(this).apply {
                text = fav.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
            g.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt(); topMargin = (6 * d).toInt()
            })
        }
    }

    // ── Observation des sources de données ───────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CarBridge.state.collect { s -> renderClimate(s); renderEnergy(s, ConsumptionEstimator.average.value) } }
                launch { ConsumptionEstimator.average.collect { renderEnergy(CarBridge.state.value, it) } }
                launch { MediaHub.now.collect { renderMedia(it) } }
                launch { WeatherRepo.weather.collect { renderWeather(it, CarBridge.state.value) } }
                launch { LocationRepo.location.collect { renderLocation(it, CarBridge.state.value) } }
                launch { while (true) { updatePower(); delay(1_000) } }
                launch {
                    while (true) {
                        if (app.mg4dash.ui.Themes.signature(this@MainActivity) != themeSig) { app.mg4dash.ui.Themes.publish(this@MainActivity); recreate(); return@launch }
                        renderSecondary()
                        renderWeatherFallbackOnly()
                        actualRecirc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.mg4dash.car.CarProps.readAny(354419976).let { v -> when (v) { "true", "1" -> true; "false", "0" -> false; else -> null } }
                        }
                        delay(5_000)
                    }
                }
            }
        }
    }

    private fun renderWeatherFallbackOnly() {
        if (WeatherRepo.weather.value == null) renderWeather(null, CarBridge.state.value)
    }

    // ── Démarrage : laisser le launcher SAIC enregistrer son interface ───────

    /**
     * Le service du launcher SAIC (caméra, sources audio, commandes de SystemUI) ne reçoit
     * son interface de rappel que lorsque son écran d'accueil est créé. Une fois par démarrage
     * de la voiture, on l'ouvre donc brièvement puis on revient ici.
     */
    private fun warmUpStockLauncherIfNeeded() {
        if (BuildConfig.MOCK || !prefs.warmUpStock) return
        if (!HomeSwitcher.isStockEnabled(this)) return   // accueil SAIC désactivé (remplacé par MG4Dash)
        val bootCount = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 }
        val sp = prefs.raw
        if (bootCount >= 0 && sp.getInt("warmup_boot", -2) == bootCount) return
        sp.edit().putInt("warmup_boot", bootCount).apply()
        Log.i("Boot", "ouverture brève du launcher SAIC (démarrage n°$bootCount)")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(HomeSwitcher.STOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Handler distinct : celui de l'activité est vidé dans onPause, qui survient justement ici.
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 1500)
        } catch (e: Exception) {
            Log.w("Boot", "launcher SAIC non ouvert : ${e.message}")
        }
    }

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
