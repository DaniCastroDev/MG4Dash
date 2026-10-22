package app.mg4dash

import app.mg4dash.car.WindowExit
import app.mg4dash.car.WindowExit.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Cerrar al salir » : cuándo empieza la cuenta atrás, cuándo se cancela y cuándo cierra. */
class WindowExitTest {

    private val x = WindowExit()
    private var t = 0L

    private fun step(speed: Float = 0f, gear: Int? = WindowExit.PARK, door: Boolean? = false, locked: Boolean? = false, enabled: Boolean = true): Action {
        t += 1000
        return x.step(WindowExit.Input(t, enabled, speed, gear, door, locked))
    }

    private fun drive() { step(speed = 50f, gear = 4); step(speed = 0f, gear = 4); step() }

    @Test
    fun `sin haber circulado no se cierra nada`() {
        step(); assertEquals(Action.NONE, step(door = true))
    }

    @Test
    fun `al abrir la puerta en P tras circular, cuenta atrás y luego cierre`() {
        drive()
        assertEquals(Action.COUNTDOWN, step(door = true))
        assertTrue(x.state is WindowExit.State.Countdown)
        repeat(4) { assertEquals(Action.NONE, step(door = true)) }
        assertEquals(Action.CLOSE, step(door = true))
        assertEquals(Action.NONE, step(door = false)); assertEquals(Action.NONE, step(door = true))
    }

    @Test
    fun `cerrar el coche también lo dispara`() {
        drive()
        assertEquals(Action.COUNTDOWN, step(locked = true))
    }

    @Test
    fun `la puerta ya abierta al empezar no cuenta como abrirla`() {
        drive()
        val y = WindowExit()
        y.step(WindowExit.Input(1, true, 50f, 4, true, false))
        assertEquals(Action.NONE, y.step(WindowExit.Input(2, true, 0f, WindowExit.PARK, true, false)))
    }

    @Test
    fun `salir de P o arrancar cancela la cuenta atrás`() {
        drive(); step(door = true)
        assertEquals(Action.CANCEL, step(gear = 4))
        drive(); step(door = false); assertEquals(Action.COUNTDOWN, step(door = true))
        assertEquals(Action.CANCEL, step(speed = 5f))
    }

    @Test
    fun `cancelar en pantalla desarma hasta volver a circular`() {
        drive(); step(door = true)
        assertTrue(x.cancel())
        step(door = false); assertEquals(Action.NONE, step(door = true))
        drive(); step(door = false)
        // dentro de la pausa de 60 s no hay otro cierre ; pasada, sí
        assertEquals(Action.COUNTDOWN, step(door = true))
    }

    @Test
    fun `tras cerrar hay una pausa de un minuto`() {
        drive(); step(door = true); repeat(5) { step(door = true) }
        drive(); step(door = false)
        assertEquals(Action.NONE, step(door = true))
        t += WindowExit.COOLDOWN_MS
        drive(); step(door = false)
        assertEquals(Action.COUNTDOWN, step(door = true))
    }

    @Test
    fun `sin marcha legible vale estar parado`() {
        step(speed = 40f, gear = null); step(gear = null)
        assertEquals(Action.COUNTDOWN, step(gear = null, door = true))
    }

    @Test
    fun `desactivar durante la cuenta atrás la cancela`() {
        drive(); step(door = true)
        assertEquals(Action.CANCEL, step(door = true, enabled = false))
        assertEquals("Desactivado", x.describe(WindowExit.Input(t, false, 0f, 1, true, false)))
    }

    @Test
    fun `la línea de estado dice lo que ve`() {
        assertEquals("Se activa al circular · en P · puerta cerrada", x.describe(WindowExit.Input(t, true, 0f, 1, false, false)))
        drive()
        assertEquals("Listo al salir · marcha 4 · puerta —", x.describe(WindowExit.Input(t, true, 0f, 4, null, false)))
    }
}
