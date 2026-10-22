package app.mg4dash

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.strip.StripView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Capturas del cockpit para el README, con los datos simulados y la dalle de la MG4
 * (1920 × 720, 160 dpi). Los PNG se escriben en build/screenshots/cockpit/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "es-w1920dp-h720dp-land-mdpi")
class CockpitScreenshotTest {

    @Test fun driving() = shoot("home", "driving.png")
    @Test fun climate() = shoot("climate", "climate.png", "power", "ac", "auto")
    @Test fun energy() = shoot("energy", "energy.png", "unplug")
    @Test fun energyPlugged() = shoot("energy", "energy_plugged.png", "plug")
    @Test fun energyCharging() = shoot("energy", "energy_charging.png", "plug", "startCharge")
    @Test fun energyDone() = shoot("energy", "energy_done.png", "chargeDone")
    @Test fun energyV2l() = shoot("energy", "energy_v2l.png", "startV2L")
    @Test fun vehicle() = shoot("vehicle", "vehicle.png")
    @Test fun profiles() = shoot("adas", "profiles.png")
    @Test fun media() = shoot("media", "media.png")
    @Test fun settings() = shoot("settings", "settings.png")
    @Test fun connections() = shoot("connections", "connections.png")
    @Test fun androidAuto() = shoot("aa", "android_auto.png")
    @Test fun drivingLight() = shoot("home", "driving_light.png", light = true)
    @Test fun drivingFog() = shoot("home", "driving_fog.png", "fog")
    @Test fun vehicleWindows() = shoot("vehicle", "vehicle_windows.png", "windows")
    @Test fun windowExitCountdown() = shoot("vehicle", "window_exit.png", "windows", "exitCountdown")
    @Test fun energyLight() = shoot("energy", "energy_light.png", "plug", "startCharge", light = true)

    /**
     * La franja tal como se dibuja sobre DiAuto. La proyección no se puede capturar aquí: el resto
     * de la pantalla es un hueco rotulado del tamaño que DiAuto deja a Android Auto.
     */
    @Test fun strip() = stripShot("strip.png", fogOn = false)
    @Test fun stripFog() = stripShot("strip_fog.png", fogOn = true)

    private fun stripShot(name: String, fogOn: Boolean) {
        val app: Context = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        resetCar()
        if (fogOn) { CarBridge.mock("fog"); idle() }
        val strip = StripView(activity, false) {}
        strip.tick()
        idle()
        strip.measure(View.MeasureSpec.makeMeasureSpec(Dim.STRIP, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        strip.layout(0, 0, Dim.STRIP, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF05070B.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF151B26.toInt()
        }
        canvas.drawRoundRect(Dim.STRIP + 16f, 16f, 1904f, 704f, 24f, 24f, paint)
        paint.apply { color = 0xFF6B7689.toInt(); textSize = 40f; textAlign = android.graphics.Paint.Align.CENTER }
        canvas.drawText("Android Auto (DiAuto) · 1280 × 720", (Dim.STRIP + 1920) / 2f, 372f, paint)
        strip.draw(canvas)
        val dir = File("build/screenshots/cockpit").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(screen: String, name: String, vararg mock: String, light: Boolean = false) {
        val app: Context = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", if (light) 2 else 1).commit()
        val intent = Intent(app, MainActivity::class.java).putExtra("screen", screen)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        idle()
        resetCar()
        mock.forEach { CarBridge.mock(it); idle() }
        // Reabre la pantalla para que pinte el estado simulado ya aplicado.
        controller.newIntent(Intent(intent))
        idle()
        save(controller.get().window.decorView, name)
    }

    private fun idle() = repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    /**
     * CarBridge es un objeto: su estado simulado sobrevive de una prueba a otra (la carga de una
     * captura aparecía en la siguiente). Se guarda el estado inicial la primera vez y se repone.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resetCar() {
        val field = CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val flow = field.get(CarBridge) as MutableStateFlow<VehicleState>
        val start = initialCar ?: flow.value.also { initialCar = it }
        flow.value = start
        idle()
    }

    companion object {
        private var initialCar: VehicleState? = null
    }

    private fun save(root: View, name: String) {
        val w = 1920; val h = 720
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots/cockpit").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
