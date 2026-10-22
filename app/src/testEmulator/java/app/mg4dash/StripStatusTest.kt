package app.mg4dash

import app.mg4dash.cockpit.strip.StripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que Ajustes dice de la franja, para saber por qué no aparece sin ADB. */
class StripStatusTest {

    private val now = 100_000L

    @Test
    fun `desactivada en Ajustes`() {
        assertEquals("Desactivada", StripStatus.describe(now, enabled = false))
    }

    @Test
    fun `el servicio no responde`() {
        assertEquals("El servicio de la franja no está en marcha",
            StripStatus.describe(now, enabled = true, lastPollMs = 0L, visible = false, top = null, error = null, failures = 0))
        assertEquals("El servicio de la franja no está en marcha",
            StripStatus.describe(now, enabled = true, lastPollMs = now - 10_000, visible = false, top = null, error = null, failures = 0))
    }

    @Test
    fun `visible sobre DiAuto`() {
        assertEquals("Visible sobre DiAuto",
            StripStatus.describe(now, enabled = true, lastPollMs = now - 400, visible = true, top = null, error = null, failures = 0))
    }

    @Test
    fun `esperando dice qué hay delante`() {
        val text = StripStatus.describe(now, true, now - 400, false, "com.saicmotor.hmi.launcher/.view.MainActivity", null, 0)
        assertTrue(text, text.contains("com.saicmotor.hmi.launcher"))
    }

    @Test
    fun `un error se ve y no para la franja hasta el tercero`() {
        val once = StripStatus.describe(now, true, now - 400, false, null, "show: NullPointerException", 1)
        assertTrue(once, once.startsWith("Esperando a DiAuto") && once.contains("NullPointerException"))
        val stopped = StripStatus.describe(now, true, now - 400, false, null, "show: NullPointerException", StripStatus.MAX_FAILURES)
        assertTrue(stopped, stopped.startsWith("Parada tras"))
    }
}
