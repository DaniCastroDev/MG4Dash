package app.mg4dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.strip.StripProvider
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Lo que DiAuto lee para dejar su margen del lado de la franja. */
@RunWith(RobolectricTestRunner::class)
class StripProviderTest {

    private val c: Context = ApplicationProvider.getApplicationContext()

    private fun row(): Triple<Int, String, Int> {
        Robolectric.setupContentProvider(StripProvider::class.java, "${c.packageName}.strip")
        c.contentResolver.query(StripProvider.uri(c), null, null, null, null)!!.use { cur ->
            assertEquals(1, cur.count)
            cur.moveToFirst()
            return Triple(
                cur.getInt(cur.getColumnIndexOrThrow("enabled")),
                cur.getString(cur.getColumnIndexOrThrow("side")),
                cur.getInt(cur.getColumnIndexOrThrow("width")),
            )
        }
    }

    @Test
    fun `por defecto la franja va a la izquierda y activada`() {
        Prefs(c).raw.edit().clear().commit()
        assertEquals(Triple(1, "left", Dim.STRIP), row())
    }

    @Test
    fun `sigue el lado y el interruptor de Ajustes`() {
        Prefs(c).raw.edit().putBoolean("strip_right", true).putBoolean("strip_enabled", false).commit()
        assertEquals(Triple(0, "right", Dim.STRIP), row())
    }
}
