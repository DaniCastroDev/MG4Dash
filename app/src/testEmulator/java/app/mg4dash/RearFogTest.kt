package app.mg4dash

import app.mg4dash.car.CarBridge
import app.mg4dash.car.RearFog
import app.mg4dash.car.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Antiniebla trasera : valores del SDK de SAIC y orden del cockpit (datos simulados). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RearFogTest {

    @Test
    fun `el coche dice 1 cuando está encendida`() {
        assertEquals(true, RearFog.parse(1))
        assertEquals(false, RearFog.parse(2))
        assertEquals(false, RearFog.parse(0))
    }

    @Test
    fun `sin respuesta o con un valor desconocido no hay botón`() {
        assertNull(RearFog.parse(null))
        assertNull(RearFog.parse(-1))
        assertNull(RearFog.parse(7))
    }

    @Test
    fun `encender envía 1 y apagar 2`() {
        assertEquals(1, RearFog.command(true))
        assertEquals(2, RearFog.command(false))
    }

    @Test
    fun `sin antiniebla en el coche la orden no se envía`() {
        state().value = VehicleState(rearFog = null)
        assertEquals(CarBridge.Write.UNAVAILABLE, CarBridge.setRearFog(true))
        assertNull(CarBridge.state.value.rearFog)
    }

    @Test
    fun `la orden cambia el estado mostrado`() {
        state().value = VehicleState(rearFog = false)
        assertEquals(CarBridge.Write.SENT, CarBridge.setRearFog(true))
        assertEquals(true, CarBridge.state.value.rearFog)
        assertEquals(CarBridge.Write.SENT, CarBridge.setRearFog(false))
        assertEquals(false, CarBridge.state.value.rearFog)
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(): MutableStateFlow<VehicleState> =
        CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(CarBridge) as MutableStateFlow<VehicleState>
}
