package app.mg4dash.cockpit.strip

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import app.mg4dash.car.CarBridge
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.LivePower
import app.mg4dash.cockpit.Pal
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Franja del cockpit sobre Android Auto (lienzo : AA-Parked, AA-Driving, AA-Climate…).
 *
 * DiAuto dessine la projection avec une marge latérale de 640 px (réglage « Márgenes » de DiAuto) ;
 * ce service pose par-dessus une fenêtre superposée (uid système) de 640 × 720 dans cette marge,
 * seulement quand la projection de DiAuto est au premier plan. La feuille de clim élargit la fenêtre
 * à 1600 px le temps de l'afficher.
 *
 * Tant qu'elle est affichée, la franja compte comme une vue du cockpit à l'écran (relecture rapide
 * du véhicule) ; elle se redessine à chaque changement d'état et lit la puissance toutes les 0,2 s.
 */
class StripService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var view: StripView? = null
    private var params: WindowManager.LayoutParams? = null
    /** Une erreur de la franja ne doit jamais faire tomber l'accueil : on la coupe jusqu'au prochain démarrage. */
    private var broken = false
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val am by lazy { getSystemService(ACTIVITY_SERVICE) as ActivityManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        main.post(poll)
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        hide()
        ui.cancel()
        super.onDestroy()
    }

    /** Erreur dans la franja : on la retire et on la laisse coupée (l'accueil continue). */
    private fun fail(where: String, t: Throwable) {
        Log.e(TAG, "franja desactivada tras un error ($where)", t)
        broken = true
        hide()
    }

    private val liveTick = object : Runnable {
        override fun run() {
            val v = view ?: return
            try { v.live() } catch (t: Throwable) { fail("live", t); return }
            main.postDelayed(this, LivePower.SAMPLE_MS)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val sp = Prefs(this@StripService).raw
            val show = sp.getBoolean("strip_enabled", true) &&
                (projectionOnTop() || (app.mg4dash.BuildConfig.MOCK && sp.getBoolean("mock_strip", false)))
            try {
                if (show && view == null && !broken) show() else if (!show && view != null) hide()
            } catch (t: Throwable) { fail("poll", t) }
            main.postDelayed(this, VISIBILITY_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionOnTop(): Boolean = try {
        val top = am.getRunningTasks(1).firstOrNull()?.topActivity
        top != null && top.packageName.startsWith(DIAUTO_PKG) && top.className.endsWith(PROJECTION)
    } catch (e: Exception) { false }

    private fun show() {
        Pal.set(Prefs(this).themeMode != 2)
        val right = Prefs(this).raw.getBoolean("strip_right", false)
        val v = StripView(this, right) { expanded -> try { resize(expanded) } catch (t: Throwable) { Log.e(TAG, "resize", t) } }
        val p = WindowManager.LayoutParams(
            Dim.STRIP, resources.displayMetrics.heightPixels.coerceAtLeast(720),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START); title = "MG4 cockpit strip" }
        try { wm.addView(v, p); view = v; params = p; Log.i(TAG, "franja visible") }
        catch (e: Exception) { Log.w(TAG, "franja : ${e.message}"); return }
        CarBridge.setVisible("strip", true)
        // redessin à chaque changement (véhicule, médias, asistentes) ; puissance et heure à part, 0,2 s
        updates = ui.launch {
            merge(CarBridge.state, MediaHub.now, Adas.state).collect {
                try { view?.tick() } catch (t: Throwable) { fail("tick", t) }
            }
        }
        main.post(liveTick)
    }

    private fun resize(expanded: Boolean) {
        val v = view ?: return; val p = params ?: return
        p.width = if (expanded) Dim.STRIP + 960 else Dim.STRIP
        p.format = if (expanded) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun hide() {
        updates?.cancel(); updates = null
        main.removeCallbacks(liveTick)
        CarBridge.setVisible("strip", false)
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view?.dispose()
        view = null; params = null
    }

    companion object {
        private const val TAG = "Strip"
        const val DIAUTO_PKG = "com.drivehub.diauto.mg4"
        private const val PROJECTION = ".aap.AapProjectionActivity"
        /** Apparition / disparition de la franja quand DiAuto passe au premier plan ou le quitte. */
        private const val VISIBILITY_MS = 500L

        /** Arranque ou arrêt selon le réglage (appelé au démarrage et depuis Ajustes). */
        fun sync(c: Context) {
            val on = Prefs(c).raw.getBoolean("strip_enabled", true)
            val i = Intent(c, StripService::class.java)
            try { if (on) c.startService(i) else c.stopService(i) } catch (e: Exception) { Log.w(TAG, "sync : ${e.message}") }
        }
    }
}
