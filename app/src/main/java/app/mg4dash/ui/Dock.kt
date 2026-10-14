package app.mg4dash.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.MainActivity
import app.mg4dash.R
import app.mg4dash.apps.AppsActivity
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.pages.EnergyActivity
import app.mg4dash.pages.VehicleActivity
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.shortcuts.Shortcuts
import app.mg4dash.system.Apps
import app.mg4dash.system.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dock inférieur commun à tous les écrans : horloge + Accueil, Véhicule, Clim, Énergie,
 * Android Auto, Paramètres. On passe d'une page à l'autre sans repasser par l'accueil.
 * Appui long sur Accueil : liste des applis ; sur Paramètres : réglages du launcher.
 */
object Dock {
    enum class Page { HOME, VEHICLE, CLIMATE, ENERGY, PHONE }

    private data class Item(val icon: Int, val label: Int, val page: Page?, val accent: Boolean = false)

    private val ITEMS = listOf(
        Item(R.drawable.ic_home, R.string.dock_home, Page.HOME),
        Item(R.drawable.ic_car, R.string.dock_vehicle, Page.VEHICLE),
        Item(R.drawable.ic_snow, R.string.dock_climate, Page.CLIMATE),
        Item(R.drawable.ic_bolt, R.string.dock_energy, Page.ENERGY),
        Item(R.drawable.ic_phone, R.string.dock_phone, Page.PHONE),
        Item(R.drawable.ic_navigation, R.string.dock_aa, null, accent = true),
        Item(R.drawable.ic_settings, R.string.dock_settings, null),
    )

    fun build(activity: Activity, dock: LinearLayout, current: Page, simple: Boolean = false) {
        val kit = Kit(activity)
        fun color(r: Int) = ContextCompat.getColor(activity, r)
        dock.removeAllViews()
        // Le dock fixe lui-même son apparence : identique sur l'accueil et sur toutes les pages.
        dock.orientation = LinearLayout.HORIZONTAL
        dock.setBackgroundResource(R.drawable.bg_panel)
        dock.setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(6))
        dock.elevation = kit.dp(6).toFloat()

        val clockCell = kit.column().apply { gravity = Gravity.CENTER; setPadding(kit.dp(12), 0, kit.dp(20), 0) }
        val clock = TextView(activity).apply {
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); includeFontPadding = false
        }
        val date = TextView(activity).apply { setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        clockCell.addView(clock); clockCell.addView(date)
        dock.addView(clockCell, LinearLayout.LayoutParams(-2, -1))
        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                date.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).trimEnd('.')
                clock.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
        tick.run()

        // Accueil épuré : seulement Accueil, Téléphone, Android Auto et Paramètres, en plus grand.
        val shown = if (simple) ITEMS.filter {
            it.label == R.string.dock_home || it.label == R.string.dock_phone || it.label == R.string.dock_aa || it.label == R.string.dock_settings
        } else ITEMS
        shown.forEachIndexed { index, item ->
            val selected = item.page == current
            val cell = kit.row().apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dock_item)
                isClickable = true; isFocusable = true
                isSelected = selected
                setOnClickListener { open(activity, item, current) }
            }
            // Bouton Android Auto : remplaçable par une autre appli (appui long)
            val custom = if (item.label == R.string.dock_aa) customAa(activity) else null
            cell.addView(ImageView(activity).apply {
                val appIcon = custom?.let { Shortcuts.appIcon(activity, it) }
                if (appIcon != null) setImageDrawable(appIcon) else {
                    setImageResource(item.icon)
                    setColorFilter(color(if (selected || item.accent) R.color.accent_action else R.color.text_sub))
                }
            }, LinearLayout.LayoutParams(kit.dp(if (simple) 40 else 30), kit.dp(if (simple) 40 else 30)))
            cell.addView(TextView(activity).apply {
                if (custom != null) text = Shortcuts.appLabel(activity, custom) else setText(item.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (simple) 26f else 19f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(if (selected || item.accent) R.color.text_value else R.color.text_sub))
                setPadding(kit.dp(12), 0, 0, 0)
            })
            when (item.label) {
                R.string.dock_home -> cell.setOnLongClickListener { activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                R.string.dock_aa -> cell.setOnLongClickListener {
                    Shortcuts.pickApp(activity, activity.getString(R.string.dock_aa_pick), activity.getString(R.string.dock_aa_stock)) { cn ->
                        Prefs(activity).dockAaTarget = cn?.flattenToString()
                        build(activity, dock, current, simple)
                    }
                    true
                }
                R.string.dock_settings -> cell.setOnLongClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)); true }
            }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index >= 0) leftMargin = kit.dp(6) })
        }
    }

    private fun customAa(activity: Activity): android.content.ComponentName? =
        Prefs(activity).dockAaTarget?.let { android.content.ComponentName.unflattenFromString(it) }

    private fun open(activity: Activity, item: Item, current: Page) {
        when (item.label) {
            R.string.dock_aa -> {
                val custom = customAa(activity)
                if (custom != null) Shortcuts.launch(activity, custom)
                else Apps.open(activity, Apps.ANDROID_AUTO, activity.getString(R.string.dock_aa))
                return
            }
            R.string.dock_settings -> { Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings)); return }
        }
        val page = item.page ?: return
        if (page == current) return
        val target = when (page) {
            Page.HOME -> MainActivity::class.java
            Page.VEHICLE -> VehicleActivity::class.java
            Page.CLIMATE -> ClimateActivity::class.java
            Page.ENERGY -> EnergyActivity::class.java
            Page.PHONE -> app.mg4dash.pages.PhoneActivity::class.java
        }
        // L'accueil (singleTask) referme les pages au-dessus de lui ; entre deux pages, on
        // remplace la page courante pour ne pas empiler l'historique.
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        if (current != Page.HOME) activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
