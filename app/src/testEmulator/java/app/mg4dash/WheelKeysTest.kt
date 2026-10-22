package app.mg4dash

import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.CarSettings
import app.mg4dash.system.WheelActions
import app.mg4dash.system.WheelKeys
import app.mg4dash.system.WheelKeys.Press
import app.mg4dash.system.WheelKeys.Star
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Botones estrella del volante, sus acciones y los ajustes del coche que van en Volante y luces. */
class WheelKeysTest {

    private fun decoder(doubleBound: Boolean = true) = WheelKeys.Decoder { doubleBound }

    @Test
    fun `doble toque en la estrella derecha`() {
        val d = decoder()
        assertNull(d.accept(286, true, false, 0)); assertNull(d.accept(286, false, false, 80))
        assertNull(d.accept(286, true, false, 250))
        assertEquals(Star.RIGHT to Press.DOUBLE, d.accept(286, false, false, 330))
        assertTrue("el primer toque no sale suelto", d.expire(1_000).isEmpty())
    }

    @Test
    fun `un toque espera a que pase la ventana del doble`() {
        val d = decoder()
        d.accept(17, true, false, 0)
        assertNull(d.accept(17, false, false, 100))
        assertTrue(d.expire(100 + WheelKeys.DOUBLE_MS).isEmpty())
        assertEquals(listOf(Star.LEFT to Press.SHORT), d.expire(100 + WheelKeys.DOUBLE_MS + 1))
    }

    @Test
    fun `sin acción en el doble, el toque sale al momento`() {
        val d = decoder(doubleBound = false)
        d.accept(18, true, false, 0)
        assertEquals(Star.RIGHT to Press.SHORT, d.accept(18, false, false, 90))
    }

    @Test
    fun `dos toques separados son dos toques`() {
        val d = decoder()
        d.accept(17, true, false, 0); d.accept(17, false, false, 80)
        assertEquals(1, d.expire(600).size)
        d.accept(17, true, false, 700); d.accept(17, false, false, 780)
        assertEquals(listOf(Star.LEFT to Press.SHORT), d.expire(1_300))
    }

    @Test
    fun `pulsación larga una sola vez, y su suelta no cuenta`() {
        val d = decoder()
        d.accept(286, true, false, 0)
        assertEquals(Star.RIGHT to Press.LONG, d.accept(286, true, true, 800))
        assertNull(d.accept(286, true, true, 900))
        assertNull(d.accept(286, false, false, 1_200))
        assertTrue(d.expire(5_000).isEmpty())
    }

    @Test
    fun `otros botones del volante no se tocan`() {
        val d = decoder()
        assertNull(d.accept(24, true, false, 0)); assertNull(d.accept(24, false, false, 50))
        assertNull(d.accept(87, false, false, 60))
    }

    @Test
    fun `por defecto el doble toque de la derecha aplica el perfil`() {
        assertEquals(WheelKeys.Action.PROFILE, WheelKeys.default(Star.RIGHT, Press.DOUBLE))
        Star.entries.forEach { s -> Press.entries.forEach { p ->
            if (s != Star.RIGHT || p != Press.DOUBLE) assertEquals(WheelKeys.Action.NONE, WheelKeys.default(s, p))
        } }
    }

    @Test
    fun `siguiente nivel de asiento, regeneración y modo`() {
        assertEquals(listOf(3, 2, 1, 0), listOf(0, 3, 2, 1).map { WheelActions.nextSeatLevel(it) })
        assertEquals(3, WheelActions.nextSeatLevel(null))
        assertEquals(listOf(1, 2, 3, 0), listOf(0, 1, 2, 3).map { WheelActions.nextRegen(it) })
        assertEquals(0, WheelActions.nextRegen(null))
        assertEquals(listOf(3, 4, 2), listOf(2, 3, 4).map { WheelActions.nextDriveMode(it) })
        assertEquals(2, WheelActions.nextDriveMode(6))
    }

    @Test
    fun `luces y remolque - 1 encendido, 0 apagado`() {
        assertEquals(true, CarSettings.parse(1))
        assertEquals(false, CarSettings.parse(0))
        assertEquals(false, CarSettings.parse(2))
        assertNull(CarSettings.parse(-1)); assertNull(CarSettings.parse(null))
        assertEquals(1, CarSettings.command(true)); assertEquals(0, CarSettings.command(false))
    }

    @Test
    fun `dirección en Mi perfil con los valores del menú de MG`() {
        assertEquals(listOf(-1, 1, 2, 3), Adas.Drive.STEERING.options.map { it.first })
    }
}
