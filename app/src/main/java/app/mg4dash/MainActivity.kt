package app.mg4dash

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.ClimateBar
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Rail
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.screens.AaScreen
import app.mg4dash.cockpit.screens.AdasScreen
import app.mg4dash.cockpit.screens.ClimateScreen
import app.mg4dash.cockpit.screens.ConnectionsScreen
import app.mg4dash.cockpit.screens.DrivingScreen
import app.mg4dash.cockpit.screens.EnergyScreen
import app.mg4dash.cockpit.screens.HomeScreen
import app.mg4dash.cockpit.screens.MediaScreen
import app.mg4dash.cockpit.screens.SettingsScreen
import app.mg4dash.cockpit.screens.VehicleScreen
import app.mg4dash.cockpit.text
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Fullscreen
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Accueil « cockpit v2 » (lienzo MG4 cockpit) : une seule activité, les écrans sont des vues
 * permutées dans la zone de contenu (pas de transition d'activité sur le MT2712). L'ancienne
 * interface reste disponible : [ClassicActivity].
 */
class MainActivity : AppCompatActivity(), Host {
    override val context: Context get() = this
    override var driving = false
        private set

    private lateinit var rail: Rail
    private lateinit var climateBar: ClimateBar
    private lateinit var screenFrame: FrameLayout
    private lateinit var toastLayer: FrameLayout
    private val screens = LinkedHashMap<String, Screen>()
    private var current: Screen? = null
    private var currentKey = "home"
    private var last = VehicleState()
    private val main = Handler(Looper.getMainLooper())
    private var stillSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.set(Prefs(this).themeMode != 2)
        window.decorView.setBackgroundColor(Pal.bg)

        screens["home"] = HomeScreen(this)
        screens["driving"] = DrivingScreen(this)
        screens["climate"] = ClimateScreen(this)
        screens["energy"] = EnergyScreen(this)
        screens["vehicle"] = VehicleScreen(this)
        screens["adas"] = AdasScreen(this)
        screens["media"] = MediaScreen(this)
        screens["settings"] = SettingsScreen(this)
        screens["connections"] = ConnectionsScreen(this)
        screens["aa"] = AaScreen(this)
        screens["car"] = app.mg4dash.cockpit.screens.CarSettingsScreen(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Pal.bg) }
        val shell = row()
        rail = Rail(this, onNav = { go(keyFor(it)) }, onLocked = { toast("Disponible con el coche parado", "Detén el coche para abrir esta sección") })
        shell.addView(rail, lp(Dim.RAIL, MATCH))
        val content = col(Dim.GAP).apply { setPadding(Dim.PAD, Dim.PAD, Dim.PAD, Dim.PAD) }
        screenFrame = FrameLayout(this)
        content.addView(screenFrame, lp(MATCH, 0, 1f))
        climateBar = ClimateBar(this) { go("climate") }
        content.addView(climateBar, lp(MATCH, Dim.CLIMATE_BAR))
        shell.addView(content, lp(0, MATCH, 1f))
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        toastLayer = FrameLayout(this)
        root.addView(toastLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        go(intent?.getStringExtra("screen") ?: "home")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CarBridge.state.collect { onState(it) } }
                launch { MediaHub.now.collect { current?.bind(last) } }
                launch { Adas.state.collect { current?.bind(last) } }
            }
        }
    }

    private fun keyFor(nav: Nav) = when (nav) {
        Nav.HOME -> "home"; Nav.CLIMATE -> "climate"; Nav.ENERGY -> "energy"; Nav.VEHICLE -> "vehicle"
        Nav.MEDIA -> "media"; Nav.AA -> "aa"; Nav.SETTINGS -> "settings"
    }

    override fun go(screen: String) {
        // Une seule interface, à l'arrêt comme en marche : l'accueil est l'écran « en marche »,
        // et aucune section n'est verrouillée (retour d'essai).
        val key = if (screen == "home") "driving" else screen
        val next = screens[key] ?: return
        if (next === current) { next.bind(last); return }
        current?.onHide()
        // Transition : l'ancien écran s'efface, le nouveau apparaît en glissant un peu vers le haut
        // (≈ 200 ms, calques matériels : léger pour le MT2712).
        val old = current?.view
        (next.view.parent as? android.view.ViewGroup)?.removeView(next.view)
        next.view.animate().cancel()
        if (old != null && old.parent === screenFrame) {
            old.animate().cancel()
            old.animate().alpha(0f).setDuration(120).withLayer().withEndAction {
                screenFrame.removeView(old); old.alpha = 1f
            }.start()
        } else screenFrame.removeAllViews()
        screenFrame.addView(next.view, FrameLayout.LayoutParams(MATCH, MATCH))
        next.view.alpha = 0f; next.view.translationY = 18f
        next.view.animate().alpha(1f).translationY(0f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).withLayer().start()
        current = next
        currentKey = key
        climateBar.visibility = if (next.climateBar) View.VISIBLE else View.GONE
        rail.select(next.nav)
        next.onShow()
        next.bind(last)
        syncLive()
    }

    private var resumed = false

    /** Puissance en direct : seulement si l'écran courant l'affiche et que l'activité est visible. */
    private val liveTick = object : Runnable {
        override fun run() {
            val s = current?.takeIf { it.liveRefresh } ?: return
            try { s.onLive() } catch (e: Exception) { app.mg4dash.system.Log.w("Cockpit", "onLive : ${e.message}") }
            main.postDelayed(this, app.mg4dash.cockpit.LivePower.SAMPLE_MS)
        }
    }

    private fun syncLive() {
        main.removeCallbacks(liveTick)
        if (resumed && current?.liveRefresh == true) main.post(liveTick)
    }

    private fun onState(s: VehicleState) {
        last = s
        // En marcha : au-dessus de 2 km/h ; retour « arrêté » après 3 s sous 0,5 km/h.
        val v = s.speedKmh?.let { Math.abs(it) } ?: 0f
        val now = System.currentTimeMillis()
        val wasDriving = driving
        if (v > 2f) { driving = true; stillSince = 0L }
        else if (v < 0.5f) {
            if (stillSince == 0L) stillSince = now
            if (now - stillSince >= 3000) driving = false
            // sans nouvelle trame du véhicule, on réévalue nous-mêmes à l'échéance des 3 s
            else if (driving) { main.removeCallbacks(recheck); main.postDelayed(recheck, 3000 - (now - stillSince) + 50) }
        }
        climateBar.bind(s)
        exitCountdown(s.windowCountdown)
        rail.outside.put(s.outsideTemp?.let { "${Math.round(it)} °C" } ?: "")
        current?.bind(s)
    }

    private val recheck = Runnable { onState(last) }

    private val clockTick = object : Runnable {
        override fun run() {
            val c = Calendar.getInstance()
            rail.clock.put("%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
            main.postDelayed(this, 10_000)
        }
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
        // Relance la franja si le système a arrêté son service (sans effet s'il tourne déjà).
        app.mg4dash.cockpit.strip.StripService.sync(this)
        CarBridge.setForeground(true)
        main.post(clockTick)
        resumed = true
        syncLive()
    }

    override fun onPause() {
        super.onPause()
        CarBridge.setForeground(false)
        resumed = false
        main.removeCallbacks(liveTick)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(recheck)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (currentKey != "home" && currentKey != "driving") go("home") }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (BuildConfig.MOCK) intent?.getStringExtra("mock")?.let { m ->
            if (m == "strip") {
                val sp = Prefs(this).raw
                sp.edit().putBoolean("mock_strip", !sp.getBoolean("mock_strip", false)).apply()
                app.mg4dash.cockpit.strip.StripService.sync(this)
            } else CarBridge.mock(m)
            return
        }
        go(intent?.getStringExtra("screen") ?: "home")
    }

    // ───────── avisos (arriba al centro, 760 px como máximo, se cierran solos) ─────────

    override fun toast(title: String, sub: String, tone: Int) {
        toastLayer.removeAllViews()
        val card = row(16).apply {
            background = rounded(Pal.surface3, 20f)
            setPadding(24, 18, 24, 18)
            elevation = 12f
            addView(IconView(this@MainActivity, if (tone == Pal.alert) Icons.alert else Icons.info, 36).apply { color = if (tone == Pal.text2) Pal.action else tone })
            addView(col(4).apply {
                addView(text(title, Ty.BODY, Pal.text, Ty.uiMedium))
                if (sub.isNotEmpty()) addView(text(sub, Ty.CAPTION, Pal.text2))
            }, lp(0, WRAP, 1f))
            setOnClickListener { toastLayer.removeAllViews() }
        }
        toastLayer.addView(card, FrameLayout.LayoutParams(760, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24; leftMargin = Dim.RAIL / 2 })
        main.removeCallbacks(clearToast)
        main.postDelayed(clearToast, 4000)
    }

    private val clearToast = Runnable { toastLayer.removeAllViews() }

    // « Cerrar al salir » : compte à rebours au-dessus de tout, avec Cancelar (voir car/WindowExit)
    private var exitCard: LinearLayout? = null
    private var exitTitle: android.widget.TextView? = null

    private fun exitCountdown(left: Int?) {
        if (left == null) { exitCard?.let { toastLayer.removeView(it) }; return }
        val card = exitCard ?: row(16).apply {
            background = rounded(Pal.surface3, 20f)
            setPadding(24, 18, 18, 18)
            elevation = 12f
            addView(IconView(this@MainActivity, Icons.arrowUp, 36).apply { color = Pal.heat })
            exitTitle = text("", Ty.BODY, Pal.text, Ty.uiMedium)
            addView(col(4).apply {
                addView(exitTitle)
                addView(text("Cuidado con las manos en las ventanillas", Ty.CAPTION, Pal.text2))
            }, lp(0, WRAP, 1f))
            addView(app.mg4dash.cockpit.Btn(this@MainActivity, "Cancelar", Icons.close, 28, vertical = false).apply {
                setPadding(20, 0, 20, 0); setOnClickListener { CarBridge.cancelWindowExit() }
            }, lp(WRAP, app.mg4dash.cockpit.Dim.TAP_MIN))
        }.also { exitCard = it }
        if (card.parent == null) toastLayer.addView(card, FrameLayout.LayoutParams(860, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24; leftMargin = Dim.RAIL / 2 })
        exitTitle?.put("Cerrando las ventanillas en $left s")
    }
}
