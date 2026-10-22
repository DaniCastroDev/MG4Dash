package app.mg4dash

import app.mg4dash.car.Windows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ventanillas : lo que el coche lee (0–100 %) y lo que se le manda (comandos 3 y 4 del launcher de MG). */
class WindowsTest {

    @Test
    fun `abrir y cerrar usan los comandos del launcher de MG`() {
        assertEquals(4, Windows.command(open = true))
        assertEquals(3, Windows.command(open = false))
        assertEquals(4, Windows.SETTERS.size)
        assertEquals(Windows.GETTERS.size, Windows.SETTERS.size)
    }

    @Test
    fun `la posición fuera de 0–100 no se cree`() {
        assertEquals(0, Windows.percent(0f))
        assertEquals(37, Windows.percent(36.6f))
        assertEquals(100, Windows.percent(100f))
        assertNull(Windows.percent(-1f))
        assertNull(Windows.percent(255f))
        assertNull(Windows.percent(Float.NaN))
        assertNull(Windows.percent(null))
    }

    @Test
    fun `resumen de la tarjeta`() {
        assertEquals("Sin lectura", Windows.summary(null))
        assertEquals("Sin lectura", Windows.summary(listOf(null, null, null, null)))
        assertEquals("Todas cerradas", Windows.summary(listOf(0, 0, 0, 0)))
        assertEquals("Todas abiertas", Windows.summary(listOf(100, 100, 100, 100)))
        assertEquals("1 abierta", Windows.summary(listOf(30, 0, 0, 0)))
        assertEquals("2 abiertas", Windows.summary(listOf(100, 40, 0, null)))
    }

    @Test
    fun `cerradas solo si se leen las cuatro`() {
        assertTrue(Windows.allClosed(listOf(0, 0, 0, 0)))
        assertFalse(Windows.allClosed(listOf(0, 0, 0, null)))
        assertFalse(Windows.allClosed(null))
        assertEquals("Cerrada", Windows.cell(0))
        assertEquals("40 %", Windows.cell(40))
        assertEquals("—", Windows.cell(null))
    }
}
