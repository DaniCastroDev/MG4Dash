package app.mg4dash.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
        app.mg4dash.ui.Themes.apply(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(12); topMargin = dp(60) /* zone antibrouillards */ })
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol.addView(right)
        root.addView(ScrollView(this).apply { addView(rightCol) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        setContentView(root)

        // ── En-tête de la colonne droite : version, journal, fermer ──
        val head = panel(right, "MG4Dash ${BuildConfig.VERSION_NAME}")
        row(head,
            button(getString(R.string.st_log)) { showLogDialog() },
            button(getString(R.string.st_close), R.color.accent_action) { finish() },
        )

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

        // ── Mise à jour depuis GitHub ──
        val upd = panel(right, getString(R.string.st_update))
        val updState = text(upd, getString(R.string.st_update_installed, BuildConfig.VERSION_NAME), 18f, R.color.text_value)
        text(upd, getString(R.string.st_update_desc), 16f, R.color.text_title)
        fun install(r: app.mg4dash.system.Updater.Release) {
            lifecycleScope.launch {
                try {
                    val apk = withContext(Dispatchers.IO) {
                        app.mg4dash.system.Updater.download(this@SettingsActivity, r) { pct ->
                            runOnUiThread { updState.text = getString(R.string.st_update_downloading, pct) }
                        }
                    }
                    updState.text = getString(R.string.st_update_installing)
                    withContext(Dispatchers.IO) { app.mg4dash.system.Updater.install(this@SettingsActivity, apk) }
                } catch (e: Exception) {
                    Log.w("MàJ", "échec : ${e.message}")
                    updState.text = getString(R.string.st_update_error)
                }
            }
        }
        row(upd, button(getString(R.string.st_update_check), R.color.accent_action) {
            updState.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { try { app.mg4dash.system.Updater.latest() } catch (e: Exception) { Log.w("MàJ", "${e.message}"); null } }
                val newer = r?.takeIf { app.mg4dash.system.Updater.isNewer(it.version) }
                updState.text = when {
                    r == null -> getString(R.string.st_update_error)
                    newer != null -> getString(R.string.st_update_available, BuildConfig.VERSION_NAME, r.version)
                    else -> getString(R.string.st_update_uptodate, BuildConfig.VERSION_NAME)
                }
                // Nouvelle version : notes de la release et choix Installer / Annuler
                if (newer != null && !isFinishing) {
                    val notes = ScrollView(this@SettingsActivity).apply {
                        setPadding(dp(24), dp(8), dp(24), 0)
                        addView(TextView(this@SettingsActivity).apply {
                            text = plainNotes(newer.notes); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            setTextColor(color(R.color.text_value)); setLineSpacing(0f, 1.15f)
                        })
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.st_update_available, BuildConfig.VERSION_NAME, newer.version))
                        .setView(notes)
                        .setPositiveButton(R.string.st_update_install) { _, _ -> install(newer) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        })

        // ── Thème : fond et couleur des boutons ──
        val theme = panel(right, getString(R.string.st_theme))
        text(theme, getString(R.string.st_theme_bg), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.BACKGROUND_SWATCHES, prefs.themeBackground) { prefs.themeBackground = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_accent), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.ACCENT_SWATCHES, prefs.themeAccent) { prefs.themeAccent = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_desc), 16f, R.color.text_title)

        // ── Style de l'accueil ──
        val style = panel(right, getString(R.string.st_style))
        val styleState = text(style, "", 18f, R.color.text_value)
        fun showStyle() {
            styleState.text = getString(R.string.st_style_now, getString(if (prefs.homeStyle == 1) R.string.st_style_simple else R.string.st_style_full))
        }
        showStyle()
        text(style, getString(R.string.st_style_desc), 16f, R.color.text_title)
        row(style,
            button(getString(R.string.st_style_full), if (prefs.homeStyle == 0) R.color.accent_action else R.color.text_value) { prefs.homeStyle = 0; showStyle() },
            button(getString(R.string.st_style_simple), if (prefs.homeStyle == 1) R.color.accent_action else R.color.text_value) { prefs.homeStyle = 1; showStyle() },
        )

        // ── Diagnostic énergie ──
        val diag = panel(right, getString(R.string.st_diag))
        val logState = text(diag, "", 18f, R.color.text_value)
        fun showLog() { logState.text = getString(R.string.st_energy_log, getString(if (prefs.energyLog) R.string.yes else R.string.no)) }
        showLog()
        text(diag, getString(R.string.st_energy_log_desc), 16f, R.color.text_title)
        row(diag, button(getString(R.string.st_toggle)) { prefs.energyLog = !prefs.energyLog; showLog() })

        // ── Home Assistant ──
        val ha = panel(right, "HOME ASSISTANT")
        val haState = text(ha, "", 18f, R.color.text_value)
        fun showHa() {
            haState.text = getString(R.string.st_ha_url, prefs.haUrl.ifBlank { "—" }) + "\n" +
                getString(if (prefs.haToken.isBlank()) R.string.st_ha_no_token else R.string.st_ha_token_set)
        }
        showHa()
        text(ha, getString(R.string.st_ha_desc), 16f, R.color.text_title)
        row(ha,
            button(getString(R.string.st_ha_set_url)) {
                ask(getString(R.string.st_ha_set_url), prefs.haUrl.ifBlank { "http://homeassistant.local:8123" }, secret = false) { prefs.haUrl = it; showHa() }
            },
            button(getString(R.string.st_ha_set_token)) {
                ask(getString(R.string.st_ha_set_token), "", secret = true) { prefs.haToken = it; showHa() }
            },
            button(getString(R.string.st_ha_test), R.color.accent_action) {
                app.mg4dash.shortcuts.Shortcuts.testHa(this) { ok ->
                    android.widget.Toast.makeText(this, if (ok) R.string.st_ha_ok else R.string.ha_error, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )

        refreshHome()
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

    /** Journal dans une fenêtre, à la demande. */
    private fun showLogDialog() {
        val view = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            text = Log.snapshot().joinToString("\n")
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val d = AlertDialog.Builder(this)
            .setTitle(getString(R.string.st_log) + " · MG4Dash ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}")
            .setView(ScrollView(this).apply { addView(view) })
            .setNeutralButton(R.string.st_refresh, null)
            .setPositiveButton(R.string.st_close, null)
            .show()
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { view.text = Log.snapshot().joinToString("\n") }
    }

    /** Notes de release GitHub (Markdown) en texte lisible. */
    private fun plainNotes(md: String): String = md
        .replace("**", "").replace("`", "")
        .lines().joinToString("\n") { l -> l.trim().let { if (it.startsWith("- ")) "•  " + it.drop(2) else it } }
        .trim()

    private fun onThemeChanged() {
        app.mg4dash.ui.Themes.publish(this)
        recreate()
    }

    /** Rangée de pastilles de couleur ; la pastille choisie est entourée de blanc. */
    private fun swatches(parent: LinearLayout, colors: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colors.forEachIndexed { i, c ->
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(if (i == selected) 4 else 1), if (i == selected) 0xFFFFFFFF.toInt() else 0xFF4A5366.toInt())
                }
                isClickable = true
                setOnClickListener { if (i != selected) onPick(i) }
            }
            r.addView(v, LinearLayout.LayoutParams(dp(56), dp(56)).apply { if (i > 0) leftMargin = dp(18) })
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
    }

    // ── Petites briques d'UI ──

    /** Saisie d'un texte (adresse, jeton masqué). */
    private fun ask(title: String, initial: String, secret: Boolean, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
            setSelection(text.length)
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
