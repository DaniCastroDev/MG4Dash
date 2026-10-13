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
import app.mg4dash.system.Apps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dock inférieur commun à tous les écrans : horloge + Accueil, Véhicule, Clim, Énergie,
 * Android Auto, Paramètres. On passe d'une page à l'autre sans repasser par l'accueil.
 * Appui long sur Accueil : liste des applis ; sur Paramètres : réglages du launcher.
 */
object Dock {
    enum class Page { HOME, VEHICLE, CLIMATE, ENERGY }

    private data class Item(val icon: Int, val label: Int, val page: Page?, val accent: Boolean = false)

    private val ITEMS = listOf(
        Item(R.drawable.ic_home, R.string.dock_home, Page.HOME),
        Item(R.drawable.ic_car, R.string.dock_vehicle, Page.VEHICLE),
        Item(R.drawable.ic_snow, R.string.dock_climate, Page.CLIMATE),
        Item(R.drawable.ic_bolt, R.string.dock_energy, Page.ENERGY),
        Item(R.drawable.ic_navigation, R.string.dock_aa, null, accent = true),
        Item(R.drawable.ic_settings, R.string.dock_settings, null),
    )

    fun build(activity: Activity, dock: LinearLayout, current: Page) {
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
                clock.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
                date.text = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(now).trimEnd('.')
                clock.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
        tick.run()

        ITEMS.forEachIndexed { index, item ->
            val selected = item.page == current
            val cell = kit.row().apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dock_item)
                isClickable = true; isFocusable = true
                isSelected = selected
                setOnClickListener { open(activity, item, current) }
            }
            cell.addView(ImageView(activity).apply {
                setImageResource(item.icon)
                setColorFilter(color(if (selected || item.accent) R.color.accent_action else R.color.text_sub))
            }, LinearLayout.LayoutParams(kit.dp(30), kit.dp(30)))
            cell.addView(TextView(activity).apply {
                setText(item.label); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); maxLines = 1
                setTextColor(color(if (selected || item.accent) R.color.text_value else R.color.text_sub))
                setPadding(kit.dp(12), 0, 0, 0)
            })
            when (item.label) {
                R.string.dock_home -> cell.setOnLongClickListener { activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                R.string.dock_settings -> cell.setOnLongClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)); true }
            }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index >= 0) leftMargin = kit.dp(6) })
        }
    }

    private fun open(activity: Activity, item: Item, current: Page) {
        when (item.label) {
            R.string.dock_aa -> { Apps.open(activity, Apps.ANDROID_AUTO, "Android Auto"); return }
            R.string.dock_settings -> { Apps.open(activity, Apps.SETTINGS, "Paramètres"); return }
        }
        val page = item.page ?: return
        if (page == current) return
        val target = when (page) {
            Page.HOME -> MainActivity::class.java
            Page.VEHICLE -> VehicleActivity::class.java
            Page.CLIMATE -> ClimateActivity::class.java
            Page.ENERGY -> EnergyActivity::class.java
        }
        // L'accueil (singleTask) referme les pages au-dessus de lui ; entre deux pages, on
        // remplace la page courante pour ne pas empiler l'historique.
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        if (current != Page.HOME) activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
