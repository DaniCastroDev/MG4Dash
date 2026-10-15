package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.settings.SettingsActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Rendu des écrans en plein écran sur la dalle de la MG4 (1920 × 720 dp, 160 dpi),
 * avec les données simulées. Les PNG sont écrits dans build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class ScreenshotTest {

    @Test fun home() = shoot(MainActivity::class.java, "home.png")
    @Test fun climate() = shoot(ClimateActivity::class.java, "climate.png")
    @Test fun vehicle() = shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle.png")
    @Test fun energy() = shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy.png")
    @Test fun settings() = shoot(SettingsActivity::class.java, "settings.png")
    @Test fun homeThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 2).putInt("theme_accent", 4).apply()
        shoot(MainActivity::class.java, "home_theme_charcoal_orange.png")
    }

    @Test fun energyThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 4).putInt("theme_accent", 2).apply()
        shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_theme_burgundy_red.png")
    }

    @Test fun settingsThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 3).putInt("theme_accent", 3).apply()
        shoot(app.mg4dash.settings.SettingsActivity::class.java, "settings_theme_forest_green.png")
    }

    @Test fun homeSimple() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 1)
            .putString("sc_100", "ha|cover.gate|Gate")
            .putString("sc_101", "ha|cover.garage_door|Garage")
            .apply()
        shoot(MainActivity::class.java, "home_simple.png")
    }

    @Test fun phone() = shoot(app.mg4dash.pages.PhoneActivity::class.java, "phone.png")

    @Test fun homeShortcuts() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_widget", 1)
            .putString("sc_0", "ha|cover.gate|Gate")
            .putString("sc_1", "ha|cover.garage_door|Garage")
            .putString("sc_2", "ha|script.driveway_lights|Driveway")
            .putString("sc_3", "ha|scene.arriving_home|Arriving")
            .putString("sc_4", "ha|switch.heating|Heating")
            .putString("sc_5", "ha|automation.alarm|Alarm")
            .apply()
        shoot(MainActivity::class.java, "home_shortcuts.png")
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun homeShortcutsFr() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_widget", 1)
            .putString("sc_0", "ha|cover.portail|Portail")
            .putString("sc_1", "ha|cover.porte_garage|Garage")
            .putString("sc_2", "ha|script.eclairage_allee|Allée")
            .putString("sc_3", "ha|scene.arrivee|Arrivée")
            .putString("sc_4", "ha|switch.chauffage|Chauffage")
            .putString("sc_5", "ha|automation.alarme|Alarme")
            .apply()
        shoot(MainActivity::class.java, "home_shortcuts_fr.png")
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun homeFr() = shoot(MainActivity::class.java, "home_fr.png")

    private fun <T : Activity> shoot(cls: Class<T>, name: String) {
        val activity = Robolectric.buildActivity(cls).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(activity.window.decorView, name)
    }

    private fun save(root: View, name: String) {
        val w = 1920; val h = 720
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
