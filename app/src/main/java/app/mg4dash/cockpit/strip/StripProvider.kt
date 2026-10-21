package app.mg4dash.cockpit.strip

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.mg4dash.cockpit.Dim
import app.mg4dash.system.Prefs

/**
 * Expose à DiAuto la franja posée sur sa projection (lecture seule) : DiAuto réserve alors la marge
 * de [Dim.STRIP] px du bon côté, sans réglage à la main (« Márgenes personalizados »), et suit le
 * réglage « Lado de la franja » quand il change.
 *
 * Une seule ligne : enabled (1/0), side ("left"/"right"), width (px). Protégé par une permission
 * de signature ; sur la voiture les deux applis partagent de toute façon l'uid système.
 */
class StripProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val p = Prefs(context!!).raw
        return MatrixCursor(COLUMNS).apply {
            addRow(arrayOf<Any>(
                if (p.getBoolean("strip_enabled", true)) 1 else 0,
                if (p.getBoolean("strip_right", false)) "right" else "left",
                Dim.STRIP,
            ))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.app.mg4dash.strip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        val COLUMNS = arrayOf("enabled", "side", "width")

        fun uri(c: Context): Uri = Uri.parse("content://${c.packageName}.strip/strip")

        /** À appeler quand la franja change (activée, côté) : DiAuto relit à la prochaine projection. */
        fun changed(c: Context) {
            try { c.contentResolver.notifyChange(uri(c), null) } catch (_: Exception) {}
        }
    }
}
