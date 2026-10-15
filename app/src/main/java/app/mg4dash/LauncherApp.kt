package app.mg4dash

import android.app.Application
import app.mg4dash.car.CarBridge
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.media.MediaHub
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.weather.WeatherRepo

class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Tr.app = this
        installCrashGuard()
        Log.i("App", "démarrage ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        CarBridge.start(this)
        app.mg4dash.car.CarProps.start(this)
        app.mg4dash.energy.ChargeGuard.start(this)
        app.mg4dash.energy.EnergyMonitor.start(this)
        ConsumptionEstimator.start(this)
        app.mg4dash.energy.EnergyLogger.start(this)
        MediaHub.start(this)
        app.mg4dash.map.LocationRepo.start(this)
        WeatherRepo.start(this)
    }

    /** Langue de la voiture changée : la météo (libellés, lieu, heures) est recalculée dans la nouvelle langue. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WeatherRepo.refreshNow(this)
    }

    /**
     * Garde-fou : 3 plantages en moins de 3 minutes → le launcher SAIC redevient l'accueil,
     * pour que l'écran de la voiture ne reste jamais inutilisable.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sp = getSharedPreferences("crash_guard", MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val recent = (sp.getString("times", "") ?: "")
                    .split(',').mapNotNull { it.toLongOrNull() }
                    .filter { now - it < CRASH_WINDOW_MS } + now
                sp.edit().putString("times", recent.joinToString(",")).commit()
                Log.e("Crash", "plantage n°${recent.size} dans la fenêtre", error)
                if (recent.size >= CRASH_LIMIT) {
                    sp.edit().putString("times", "").putBoolean("fell_back", true).commit()
                    HomeSwitcher.switchToStock(this)
                }
            } catch (_: Throwable) {
                // Ne jamais masquer le plantage d'origine.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val CRASH_LIMIT = 3
        private const val CRASH_WINDOW_MS = 3 * 60 * 1000L
    }
}

/** Accès aux textes traduits depuis du code sans Context (états véhicule, météo…). */
object Tr {
    lateinit var app: Application
    fun s(id: Int, vararg args: Any): String = app.getString(id, *args)
}
