package app.mg4dash.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.media.MediaHub
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.pages.EnergyActivity
import app.mg4dash.shortcuts.Shortcuts
import app.mg4dash.system.Apps
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import kotlinx.coroutines.launch

/**
 * Accueil épuré : trois volets (climatisation, batterie, multimédia) et une bande avec deux
 * raccourcis configurables et la météo du jour. Peu de boutons, grands textes.
 * Les raccourcis utilisent les cases 100 et 101 de [Shortcuts].
 */
class SimpleHome(private val a: AppCompatActivity) {
    private val kit = Kit(a)
    private val prefs = Prefs(a)
    private val handler = Handler(Looper.getMainLooper())

    val root: LinearLayout = kit.column()

    private lateinit var power: ImageButton
    private lateinit var temp: TextView
    private lateinit var outside: TextView
    private lateinit var fanValue: TextView
    private lateinit var climateCard: LinearLayout

    private lateinit var battery: TextView
    private lateinit var range: TextView
    private lateinit var chargeState: TextView
    private lateinit var chargeAction: TextView
    private lateinit var gauge: SegmentedGauge

    private lateinit var mediaSource: TextView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var mediaArt: ImageView
    private lateinit var mediaPlay: ImageButton
    private lateinit var srcRadio: TextView
    private lateinit var srcMedia: TextView
    private lateinit var srcAa: TextView

    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var weatherPlace: TextView

    private val shortcutHolders = arrayOf(kit.column(), kit.column())

    init {
        root.setBackgroundResource(R.drawable.bg_root)
        root.setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))

        val top = kit.row().apply { isBaselineAligned = false }
        top.addView(buildClimate(), kit.lp(0, -1, 1f, r = 6))
        top.addView(buildBattery(), kit.lp(0, -1, 1f, l = 6, r = 6))
        top.addView(buildMedia(), kit.lp(0, -1, 1f, l = 6))
        root.addView(top, kit.lp(-1, 0, 62f))

        val band = kit.row().apply { isBaselineAligned = false }
        band.addView(shortcutTile(0), kit.lp(0, -1, 1f, r = 6))
        band.addView(shortcutTile(1), kit.lp(0, -1, 1f, l = 6, r = 6))
        band.addView(buildWeather(), kit.lp(0, -1, 2f, l = 6))
        root.addView(band, kit.lp(-1, 0, 25f, t = 12))

        val dock = LinearLayout(a)
        root.addView(dock, kit.lp(-1, kit.dp(96), t = 12))
        Dock.mount(a, root, dock, Dock.Page.HOME, simple = true)
    }

    fun start() {
        a.lifecycleScope.launch {
            a.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CarBridge.state.collect { renderClimate(it); renderBattery(it) } }
                launch { MediaHub.now.collect { renderMedia(it) } }
                launch { WeatherRepo.weather.collect { renderWeather(it, CarBridge.state.value) } }
            }
        }
    }

    fun onResume() {
        renderShortcuts()
    }

    // ── Construction ──

    private fun big(text: String, sp: Float, light: Boolean = false, color: Int = R.color.text_value): TextView =
        kit.text(text, sp, color, light)

    private fun title(text: String) = kit.title(text).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }

    private fun buildClimate(): LinearLayout {
        climateCard = kit.panel()
        val head = kit.row()
        // Coin haut-gauche libre pour le bouton des antibrouillards
        head.addView(title(a.getString(R.string.title_climate)), kit.lp(0, -2, 1f, l = if (Dock.vertical(a)) 0 else Kit.FOG_SAFE_DP - 20))
        outside = kit.text("", 20f, R.color.text_sub)
        head.addView(outside, kit.lp(-2, -2, r = 12))
        power = kit.roundButton(R.drawable.ic_power, 64) { CarBridge.togglePower() }
        head.addView(power)
        climateCard.addView(head, kit.lp(-1, -2))

        val tRow = kit.row().apply { gravity = Gravity.CENTER }
        tRow.addView(holdButton(R.drawable.ic_minus, 92, R.color.accent_cold) { CarBridge.stepTemp(-1) })
        temp = big("—", 100f, light = true).apply { gravity = Gravity.CENTER }
        tRow.addView(temp, kit.lp(0, -2, 1f))
        tRow.addView(holdButton(R.drawable.ic_plus, 92, R.color.accent_heat) { CarBridge.stepTemp(1) })
        climateCard.addView(tRow, kit.lp(-1, 0, 1f))

        val fRow = kit.row().apply { gravity = Gravity.CENTER }
        fRow.addView(kit.roundButton(R.drawable.ic_minus, 72) { CarBridge.stepFan(-1) })
        val fanBox = kit.column().apply { gravity = Gravity.CENTER }
        fanBox.addView(kit.text(a.getString(R.string.cl_fan), 18f, R.color.text_title).apply { gravity = Gravity.CENTER })
        fanValue = big("—", 34f).apply { gravity = Gravity.CENTER }
        fanBox.addView(fanValue)
        fRow.addView(fanBox, kit.lp(0, -2, 1f))
        fRow.addView(kit.roundButton(R.drawable.ic_plus, 72) { CarBridge.stepFan(1) })
        climateCard.addView(fRow, kit.lp(-1, -2, t = 4))

        temp.setOnClickListener { a.startActivity(Intent(a, ClimateActivity::class.java)) }
        return climateCard
    }

    private fun buildBattery(): LinearLayout {
        val p = kit.panel()
        val head = kit.row()
        head.addView(title(a.getString(R.string.title_battery)), kit.lp(0, -2, 1f))
        p.addView(head, kit.lp(-1, kit.dp(64)))
        battery = big("— %", 116f, light = true).apply { gravity = Gravity.CENTER }
        p.addView(battery, kit.lp(-1, 0, 1f))
        range = big("— km", 44f).apply { gravity = Gravity.CENTER }
        p.addView(range, kit.lp(-1, -2, t = 6))
        gauge = SegmentedGauge(a).apply { segments = 20 }
        p.addView(gauge, kit.lp(-1, kit.dp(16), t = 14))
        chargeState = kit.text("", 22f, R.color.accent_energy).apply { gravity = Gravity.CENTER }
        p.addView(chargeState, kit.lp(-1, -2, t = 10))
        chargeAction = kit.toggleTile(a.getString(R.string.charge_btn)) { onChargeAction() }.apply { textSize = 24f }
        p.addView(chargeAction, kit.lp(-1, kit.dp(72), t = 10))
        p.setOnClickListener { a.startActivity(Intent(a, EnergyActivity::class.java)) }
        return p
    }

    private fun buildMedia(): LinearLayout {
        val p = kit.panel()
        mediaSource = title(a.getString(R.string.title_media))
        // Puces de source : elles basculent la source affichée (sans ouvrir l'appli)
        val head = kit.row()
        head.addView(mediaSource, kit.lp(0, -2, 1f))
        srcRadio = kit.toggleTile(a.getString(R.string.src_radio)) { MediaHub.select("com.saicmotor.radio") }.apply { textSize = 18f }
        srcMedia = kit.toggleTile(a.getString(R.string.src_media)) { MediaHub.selectUsbBt() }.apply { textSize = 18f }
        head.addView(srcRadio, kit.lp(kit.dp(110), kit.dp(48)))
        head.addView(srcMedia, kit.lp(kit.dp(130), kit.dp(48), l = 8))
        srcAa = kit.toggleTile(a.getString(R.string.src_aa)) { MediaHub.selectAndroidAuto() }.apply { textSize = 18f; visibility = View.GONE }
        head.addView(srcAa, kit.lp(kit.dp(80), kit.dp(48), l = 8))
        p.addView(head, kit.lp(-1, kit.dp(52)))

        val info = kit.row().apply { isClickable = true; isFocusable = true }
        val frame = RoundedFrame(a)
        mediaArt = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.setBackgroundResource(R.drawable.bg_art)
        frame.addView(mediaArt, android.widget.FrameLayout.LayoutParams(-1, -1))
        info.addView(frame, kit.lp(kit.dp(220), kit.dp(220)))
        val texts = kit.column()
        mediaTitle = big("", 34f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        mediaArtist = kit.text("", 26f, R.color.text_sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(mediaTitle)
        texts.addView(mediaArtist, kit.lp(-1, -2, t = 6))
        info.addView(texts, kit.lp(0, -2, 1f, l = 16))
        info.setOnClickListener {
            val owner = MediaHub.ownerComponent()
            if (owner != null) Apps.open(a, owner, "Media") else Apps.open(a, Apps.RADIO, "Radio")
        }
        p.addView(info, kit.lp(-1, 0, 1f, t = 8))

        val controls = kit.row().apply { gravity = Gravity.CENTER }
        controls.addView(kit.roundButton(R.drawable.ic_prev, 76) { MediaHub.previous() })
        mediaPlay = ImageButton(a).apply {
            setBackgroundResource(R.drawable.bg_play); setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.FIT_CENTER; setColorFilter(kit.color(R.color.on_action))
            val pad = kit.dp(22); setPadding(pad, pad, pad, pad)
            setOnClickListener { MediaHub.playPause() }
        }
        controls.addView(mediaPlay, kit.lp(kit.dp(96), kit.dp(96), l = 24, r = 24))
        controls.addView(kit.roundButton(R.drawable.ic_next, 76) { MediaHub.next() })
        p.addView(controls, kit.lp(-1, -2, t = 8))
        return p
    }

    private fun buildWeather(): LinearLayout {
        val p = kit.panel().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(24), kit.dp(10), kit.dp(24), kit.dp(10))
        }
        weatherIcon = ImageView(a)
        p.addView(weatherIcon, LinearLayout.LayoutParams(kit.dp(76), kit.dp(76)))
        weatherTemp = big("—", 64f, light = true)
        p.addView(weatherTemp, kit.lp(-2, -2, l = 16))
        val col = kit.column()
        weatherLabel = big("", 28f)
        weatherPlace = kit.text("", 22f, R.color.text_sub)
        col.addView(weatherLabel); col.addView(weatherPlace, kit.lp(-1, -2, t = 4))
        p.addView(col, kit.lp(0, -2, 1f, l = 20))
        p.setOnClickListener { WeatherRepo.refreshNow(a) }
        return p
    }

    /** Tuile de raccourci : appli ou action Home Assistant ; case vide = « + ». */
    private fun shortcutTile(n: Int): LinearLayout {
        val slot = 100 + n
        val tile = shortcutHolders[n]
        tile.setBackgroundResource(R.drawable.bg_panel)
        tile.gravity = Gravity.CENTER
        tile.elevation = kit.dp(6).toFloat()
        tile.isClickable = true; tile.isFocusable = true
        tile.tag = slot
        return tile
    }

    private fun renderShortcuts() {
        shortcutHolders.forEachIndexed { n, tile ->
            val slot = 100 + n
            val s = Shortcuts.get(prefs, slot)
            tile.removeAllViews()
            val icon = ImageView(a)
            val label = kit.text("", 24f, R.color.text_value).apply { gravity = Gravity.CENTER }
            when (s) {
                Shortcuts.Slot.Empty -> {
                    icon.setImageResource(R.drawable.ic_plus); icon.setColorFilter(kit.color(R.color.text_title))
                }
                is Shortcuts.Slot.App -> {
                    val d = Shortcuts.appIcon(a, s.component)
                    if (d != null) icon.setImageDrawable(d) else { icon.setImageResource(R.drawable.ic_apps); icon.setColorFilter(kit.color(R.color.text_sub)) }
                    label.text = Shortcuts.appLabel(a, s.component)
                }
                is Shortcuts.Slot.Ha -> {
                    icon.setImageResource(Shortcuts.haIcon(s.entityId, s.label)); icon.setColorFilter(kit.color(R.color.accent_action))
                    label.text = s.label
                }
            }
            tile.addView(icon, LinearLayout.LayoutParams(kit.dp(64), kit.dp(64)))
            if (label.text.isNotEmpty()) tile.addView(label, kit.lp(-2, -2, t = 4))
            tile.setOnClickListener {
                when (s) {
                    Shortcuts.Slot.Empty -> Shortcuts.edit(a, slot) { renderShortcuts() }
                    is Shortcuts.Slot.App -> Shortcuts.launch(a, s.component)
                    is Shortcuts.Slot.Ha -> Shortcuts.runHa(a, s)
                }
            }
            tile.setOnLongClickListener { Shortcuts.edit(a, slot) { renderShortcuts() }; true }
        }
    }

    /** Appui = un cran ; maintenu = répétition. */
    private fun holdButton(icon: Int, sizeDp: Int, tint: Int, action: () -> Unit): ImageButton =
        kit.roundButton(icon, sizeDp, tint) {}.apply {
            val repeat = object : Runnable { override fun run() { action(); handler.postDelayed(this, 350) } }
            setOnClickListener(null)
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); handler.postDelayed(repeat, 500) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
                }
                true
            }
        }

    // ── Rendu ──

    private fun renderClimate(s: VehicleState) {
        val on = s.hvacOn == true
        val known = s.connected && s.hvacOn != null
        power.setColorFilter(kit.color(if (on) R.color.accent_energy else R.color.text_sub))
        temp.text = if (on || s.driverTemp != null) VehicleState.tempLabel(s.driverTemp) else a.getString(R.string.no_data)
        temp.setTextColor(kit.color(if (on) R.color.text_value else R.color.text_disabled))
        outside.text = s.outsideTemp?.let { a.getString(R.string.outside_short, Math.round(it)) } ?: ""
        val auto = on && s.autoOn == true
        fanValue.text = when { !on -> a.getString(R.string.off); auto -> "AUTO"; else -> s.fanSpeed?.toString() ?: "—" }
        fanValue.setTextColor(kit.color(if (auto) R.color.accent_cold else if (on) R.color.text_value else R.color.text_disabled))
        climateCard.alpha = if (known) 1f else 0.6f
    }

    private fun renderBattery(s: VehicleState) {
        val pct = s.batteryPercent
        battery.text = pct?.let { "$it %" } ?: "— %"
        val c = when {
            pct == null -> R.color.text_disabled
            pct <= 15 -> R.color.accent_danger
            pct <= 30 -> R.color.accent_heat
            else -> R.color.accent_energy
        }
        battery.setTextColor(kit.color(R.color.text_value))
        gauge.onColor = kit.color(c)
        gauge.value = pct?.let { (it + 4) / 5 } ?: 0
        range.text = s.rangeKm?.let { "$it km" } ?: "— km"
        val remain = s.chargeRemainMin
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        chargeState.text = when {
            !VehicleState.isPlugged(s.chargeStatus) -> ""
            VehicleState.isCharging(s.chargeStatus) && remain != null && remain in 1..2000 ->
                a.getString(R.string.charging_eta, remain / 60, remain % 60)
            else -> VehicleState.chargeStatusLabel(s.chargeStatus)
        }
        chargeState.setTextColor(kit.color(if (discharge) R.color.accent_heat else R.color.accent_energy))
        // Le bouton n'apparaît que câble branché
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        chargeAction.visibility = if (plugged && CarBridge.chargeControl) View.VISIBLE else View.GONE
        chargeAction.isSelected = if (discharge) s.dischargeCtrl == 1 else s.chargeCtrl == 1
        chargeAction.text = when {
            discharge -> a.getString(if (s.dischargeCtrl == 1) R.string.v2l_stop else R.string.v2l_start)
            s.chargeCtrl == 1 -> a.getString(R.string.stop_btn)
            else -> a.getString(R.string.charge_btn)
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

    private fun renderMedia(m: MediaHub.NowPlaying) {
        if (!m.available) {
            mediaSource.text = a.getString(R.string.title_media)
            mediaTitle.text = a.getString(R.string.no_media)
            mediaArtist.text = ""
        } else {
            mediaSource.text = m.source.ifEmpty { a.getString(R.string.title_media) }
            mediaTitle.text = m.title.ifEmpty { m.source }
            mediaArtist.text = listOf(m.artist, m.album).filter { it.isNotBlank() }.joinToString(" · ")
        }
        srcRadio.isSelected = m.packageName == "com.saicmotor.radio"
        srcMedia.isSelected = m.packageName == "com.saicmotor.media" || m.packageName == "com.android.bluetooth"
        srcAa.isSelected = MediaHub.isAndroidAuto(m.packageName)
        srcAa.visibility = if (m.androidAuto || srcAa.isSelected) View.VISIBLE else View.GONE
        mediaArt.setImageBitmap(m.art)
        mediaPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        mediaPlay.isEnabled = m.available
    }

    private fun renderWeather(w: WeatherRepo.Weather?, s: VehicleState) {
        if (w == null) {
            weatherIcon.setImageResource(R.drawable.ic_cloud)
            weatherIcon.setColorFilter(kit.color(R.color.text_sub))
            weatherTemp.text = s.outsideTemp?.let { "${Math.round(it)}°" } ?: "—"
            weatherLabel.text = a.getString(R.string.weather_pending)
            weatherPlace.text = ""
            return
        }
        weatherIcon.setImageResource(WeatherRepo.icon(w.code))
        weatherIcon.setColorFilter(kit.color(if (w.code <= 1) R.color.accent_heat else R.color.text_value))
        weatherTemp.text = "${w.tempC}°"
        weatherLabel.text = WeatherRepo.label(w.code)
        weatherPlace.text = w.place
    }
}
