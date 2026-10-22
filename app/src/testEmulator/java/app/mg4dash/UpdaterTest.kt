package app.mg4dash

import app.mg4dash.system.UpdateState
import app.mg4dash.system.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.UnknownHostException
import java.security.MessageDigest

/** Botón « Buscar actualización » de Ajustes : versiones de GitHub, comprobación del APK y textos. */
@RunWith(RobolectricTestRunner::class)
class UpdaterTest {

    private fun release(tag: String, vararg assets: String) = JSONObject("""
        {"tag_name": "$tag", "body": "## Novedades\n- Botón de actualización\n- Otra cosa",
         "assets": [${assets.joinToString(",")}]}
    """.trimIndent())

    private fun asset(name: String, size: Long = 10, digest: String? = null) =
        """{"name": "$name", "size": $size, "browser_download_url": "https://github.com/x/$name"${digest?.let { ", \"digest\": \"$it\"" } ?: ""}}"""

    @Test
    fun `lee el APK y su SHA-256 de la versión publicada`() {
        val r = Updater.parse(release("v1.1.12-cockpit.26", asset("notes.txt"), asset("MG4Dash-1.1.12-cockpit.26.apk", 42, "sha256:ABCDEF")))!!
        assertEquals("1.1.12-cockpit.26", r.version)
        assertEquals("https://github.com/x/MG4Dash-1.1.12-cockpit.26.apk", r.url)
        assertEquals(42L, r.size)
        assertEquals("abcdef", r.sha256)
    }

    @Test
    fun `una versión sin APK no cuenta y sin digest no hay SHA-256`() {
        assertNull(Updater.parse(release("v2.0", asset("notes.txt"))))
        assertNull(Updater.parse(release("v2.0", asset("a.apk")))!!.sha256)
    }

    @Test
    fun `las versiones cockpit se ordenan por su número`() {
        assertTrue(Updater.isNewer("1.1.12-cockpit.26", "1.1.12-cockpit.25"))
        assertTrue(Updater.isNewer("1.1.12-cockpit.100", "1.1.12-cockpit.99"))
        assertFalse(Updater.isNewer("1.1.12-cockpit.25", "1.1.12-cockpit.25"))
        assertFalse(Updater.isNewer("1.1.12-cockpit.24", "1.1.12-cockpit.25"))
    }

    private fun apk(bytes: ByteArray) = File.createTempFile("upd", ".apk").apply { writeBytes(bytes); deleteOnExit() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rel(size: Long, sha256: String?) = Updater.Release("1.1.12-cockpit.26", "https://x", size, "", sha256)

    @Test
    fun `un APK igual al publicado pasa la comprobación`() {
        val bytes = "apk de prueba".toByteArray()
        Updater.verify(apk(bytes), rel(bytes.size.toLong(), sha(bytes)))
        Updater.verify(apk(bytes), rel(bytes.size.toLong(), null))
    }

    @Test
    fun `un APK cortado o distinto no se instala`() {
        val bytes = "apk de prueba".toByteArray()
        try { Updater.verify(apk(bytes.copyOf(5)), rel(bytes.size.toLong(), sha(bytes))); fail() }
        catch (e: Updater.BadDownload) { assertTrue(e.message!!, e.message!!.startsWith("descarga incompleta")) }
        try { Updater.verify(apk("otro apk de pru".toByteArray().copyOf(bytes.size)), rel(bytes.size.toLong(), sha(bytes))); fail() }
        catch (e: Updater.BadDownload) { assertEquals("la descarga no coincide con la publicada", e.message) }
    }

    @Test
    fun `textos del botón y de la línea de estado`() {
        val r = rel(1, null).copy(notes = "## Novedades\n- Botón de actualización")
        assertEquals("Buscar actualización", UpdateState.Idle.button())
        assertEquals("Versión 1.1.12-cockpit.25 · es la última", UpdateState.UpToDate.status("1.1.12-cockpit.25"))
        assertEquals("Versión 1.1.12-cockpit.25 · aún no hay versiones publicadas", UpdateState.NoRelease.status("1.1.12-cockpit.25"))
        assertEquals("Instalar 1.1.12-cockpit.26", UpdateState.Available(r).button())
        assertEquals("Nueva versión 1.1.12-cockpit.26 · Botón de actualización", UpdateState.Available(r).status("x"))
        assertEquals("Descargando 1.1.12-cockpit.26 · 40 %", UpdateState.Downloading("1.1.12-cockpit.26", 40).status("x"))
        assertFalse(UpdateState.Downloading("1.1.12-cockpit.26", 40).actionable)
        assertTrue(UpdateState.Failed("x").actionable)
    }

    @Test
    fun `sin datos en la SIM se dice que no hay Internet`() {
        assertEquals("sin conexión a Internet", UpdateState.reason(UnknownHostException("api.github.com")))
        assertEquals("GitHub limita las consultas, prueba más tarde", UpdateState.reason(Updater.HttpError(403)))
    }
}
