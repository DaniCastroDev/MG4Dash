package app.mg4dash.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import app.mg4dash.ui.Fullscreen
import java.util.Locale

/**
 * Paramètres du launcher, ouverts par un appui long de 3 s sur l'heure.
 * Contient le mode Valet / Entretien : le launcher SAIC redevient l'accueil par défaut.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var homeState: TextView
    private lateinit var logView: TextView

    private val cities = listOf(
        Triple("Paris", 48.8566f, 2.3522f), Triple("Lyon", 45.764f, 4.8357f),
        Triple("Marseille", 43.2965f, 5.3698f), Triple("Toulouse", 43.6047f, 1.4442f),
        Triple("Bordeaux", 44.8378f, -0.5792f), Triple("Lille", 50.6292f, 3.0573f),
        Triple("Nantes", 47.2184f, -1.5536f), Triple("Strasbourg", 48.5734f, 7.7521f),
        Triple("Nice", 43.7102f, 7.262f), Triple("Bruxelles", 50.8503f, 4.3517f),
        Triple("Genève", 46.2044f, 6.1432f),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(12); topMargin = dp(60) /* zone antibrouillards */ })
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        setContentView(root)

        // ── Mode Entretien ──
        val valet = panel(left, getString(R.string.st_home))
        homeState = text(valet, "", 19f, R.color.text_sub)
        text(valet, getString(R.string.st_home_desc), 17f, R.color.text_title)
        row(valet,
            button(getString(R.string.st_replace), R.color.accent_action) { confirmReplace() },
            button(getString(R.string.st_valet), R.color.accent_heat) { confirmValet() },
        )
        text(valet, getString(R.string.st_adb), 15f, R.color.text_title)

        // ── Énergie ──
        val energy = panel(left, getString(R.string.title_energy))
        val cap = text(energy, "", 18f, R.color.text_value)
        fun showCap() { cap.text = getString(R.string.st_capacity, String.format(Locale.getDefault(), "%.1f", prefs.batteryKwh)) }
        showCap()
        row(energy,
            button("− 0.5 kWh") { prefs.batteryKwh = (prefs.batteryKwh - 0.5f).coerceAtLeast(30f); showCap() },
            button("+ 0.5 kWh") { prefs.batteryKwh = (prefs.batteryKwh + 0.5f).coerceAtMost(80f); showCap() },
        )
        text(energy, getString(R.string.st_capacity_desc), 16f, R.color.text_title)

        // ── Météo ──
        val weather = panel(left, getString(R.string.title_weather))
        val city = text(weather, "", 18f, R.color.text_value)
        fun showCity() { city.text = getString(R.string.st_city, prefs.weatherCity) }
        showCity()
        row(weather, button(getString(R.string.st_change_city)) {
            val idx = (cities.indexOfFirst { it.first == prefs.weatherCity } + 1) % cities.size
            val c = cities[idx]
            prefs.weatherCity = c.first; prefs.weatherLat = c.second; prefs.weatherLon = c.third
            showCity(); WeatherRepo.refreshNow(this)
        })

        // ── Démarrage ──
        val boot = panel(left, getString(R.string.st_startup))
        val warm = text(boot, "", 18f, R.color.text_value)
        fun showWarm() {
            warm.text = getString(R.string.st_warm, getString(if (prefs.warmUpStock) R.string.yes else R.string.no))
        }
        showWarm()
        text(boot, getString(R.string.st_warm_desc), 16f, R.color.text_title)
        row(boot, button(getString(R.string.st_toggle)) { prefs.warmUpStock = !prefs.warmUpStock; showWarm() })

        // ── Affichage ──
        val display = panel(left, getString(R.string.st_display))
        val fs = text(display, "", 18f, R.color.text_value)
        fun showFs() { fs.text = getString(R.string.st_fullscreen, getString(if (prefs.fullscreen) R.string.yes else R.string.no)) }
        showFs()
        text(display, getString(R.string.st_fullscreen_desc), 16f, R.color.text_title)
        row(display, button(getString(R.string.st_toggle)) { prefs.fullscreen = !prefs.fullscreen; showFs(); Fullscreen.apply(this) })

        // ── Journal ──
        val logs = panel(right, getString(R.string.st_log), fill = true)
        row(logs,
            button(getString(R.string.st_refresh)) { refreshLog() },
            button(getString(R.string.st_close), R.color.accent_action) { finish() },
        )
        logView = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        logs.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        text(logs, "MG4Dash ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}", 15f, R.color.text_title)

        refreshHome(); refreshLog()
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
    }

    private fun confirmReplace() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_replace)
            .setMessage(R.string.st_replace_msg)
            .setPositiveButton(R.string.st_replace_btn) { _, _ ->
                val ok = HomeSwitcher.replaceStock(this)
                Log.i("Valet", "remplacement de l'accueil SAIC → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmValet() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_valet)
            .setMessage(R.string.st_valet_msg)
            .setPositiveButton(R.string.activate) { _, _ ->
                val ok = HomeSwitcher.switchToStock(this)
                Log.i("Valet", "mode Entretien → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toastState(ok: Boolean) {
        android.widget.Toast.makeText(this, getString(if (ok) R.string.st_home_changed else R.string.st_failed), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refreshHome() {
        val cur = HomeSwitcher.currentHome(this)
        homeState.text = getString(R.string.st_current_home, when (cur) {
            HomeSwitcher.STOCK -> getString(R.string.st_home_stock)
            HomeSwitcher.ours(this) -> "MG4Dash"
            null -> getString(R.string.st_home_none)
            else -> cur.flattenToShortString()
        })
    }

    private fun refreshLog() { logView.text = Log.snapshot().joinToString("\n") }

    // ── Petites briques d'UI ──

    private fun panel(parent: LinearLayout, title: String, fill: Boolean = false): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        p.addView(TextView(this).apply {
            text = title; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            letterSpacing = 0.12f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        parent.addView(p, LinearLayout.LayoutParams(-1, if (fill) -1 else -2).apply { bottomMargin = dp(12) })
        return p
    }

    private fun text(parent: LinearLayout, value: String, sp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            text = value; setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setPadding(0, dp(6), 0, dp(2))
        }.also { parent.addView(it) }

    private fun button(label: String, accentRes: Int = R.color.text_value, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; isAllCaps = false; setTextColor(color(accentRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = dp(56); setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { onClick() }
        }

    private fun row(parent: LinearLayout, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
