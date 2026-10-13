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
