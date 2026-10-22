package app.mg4dash

import app.mg4dash.system.CarPlayDoubleTap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doble toque en el botón CarPlay del inicio de MG para volver a MG4Dash. */
class CarPlayDoubleTapTest {

    private val icon = "com.saicmotor.hmi.launcher:id/iv_apple_carplay"
    private val label = "com.saicmotor.hmi.launcher:id/tv_apple_carplay"
    private val androidAuto = "com.saicmotor.hmi.launcher:id/iv_android_auto"

    @Test
    fun `dos toques rápidos en CarPlay vuelven a MG4Dash`() {
        val t = CarPlayDoubleTap()
        assertFalse(t.onClick(icon, 1_000))
        assertTrue(t.onClick(icon, 1_400))
    }

    @Test
    fun `el icono y el texto cuentan como el mismo botón`() {
        val t = CarPlayDoubleTap()
        assertFalse(t.onClick(icon, 1_000))
        assertTrue(t.onClick(label, 1_300))
    }

    @Test
    fun `un solo toque no hace nada`() {
        assertFalse(CarPlayDoubleTap().onClick(icon, 1_000))
    }

    @Test
    fun `dos toques separados no cuentan`() {
        val t = CarPlayDoubleTap()
        assertFalse(t.onClick(icon, 1_000))
        assertFalse(t.onClick(icon, 1_000 + CarPlayDoubleTap.WINDOW_MS + 1))
    }

    @Test
    fun `otro botón entre medias anula el primer toque`() {
        val t = CarPlayDoubleTap()
        assertFalse(t.onClick(icon, 1_000))
        assertFalse(t.onClick(androidAuto, 1_100))
        assertFalse(t.onClick(icon, 1_200))
    }

    @Test
    fun `tres toques son un doble toque y uno nuevo`() {
        val t = CarPlayDoubleTap()
        assertFalse(t.onClick(icon, 1_000))
        assertTrue(t.onClick(icon, 1_200))
        assertFalse(t.onClick(icon, 1_400))
    }

    @Test
    fun `sin identificador no es el botón CarPlay`() {
        assertFalse(CarPlayDoubleTap.isCarPlayButton(null))
        assertFalse(CarPlayDoubleTap.isCarPlayButton(androidAuto))
        assertTrue(CarPlayDoubleTap.isCarPlayButton(icon))
    }
}
