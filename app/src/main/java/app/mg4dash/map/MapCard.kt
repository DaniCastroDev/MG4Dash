package app.mg4dash.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Location
import android.os.Build
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import app.mg4dash.R
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Carte OpenStreetMap sombre centrée sur la voiture (remplace la tuile Android Auto,
 * déjà présente dans le dock). Suit la position tant que l'utilisateur ne la déplace pas ;
 * le bouton « recentrer » reprend le suivi.
 */
class MapCard(private val context: Context, holder: ViewGroup) {

    private val map: MapView?
    private val marker: Marker?
    private var follow = true
    private var programmaticMove = false

    init {
        // Robolectric (captures sur PC) ne sait pas dessiner osmdroid : on laisse le fond.
        if (Build.FINGERPRINT == "robolectric") {
            map = null; marker = null
        } else {
            val conf = Configuration.getInstance()
            conf.userAgentValue = "MG4Launcher/${context.packageName}"
            conf.osmdroidBasePath = File(context.filesDir, "osmdroid")
            conf.osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
            conf.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            val m = MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 5.0
                maxZoomLevel = 19.0
                controller.setZoom(16.0)
                overlayManager.tilesOverlay.setColorFilter(darkFilter())
                overlayManager.tilesOverlay.loadingBackgroundColor = ContextCompat.getColor(context, R.color.bg_top)
                overlayManager.tilesOverlay.loadingLineColor = ContextCompat.getColor(context, R.color.panel_border)
            }
            val mk = Marker(m).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_car_marker)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setFlat(true)
                setInfoWindow(null)
            }
            m.overlays.add(mk)
            m.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    if (!programmaticMove) follow = false
                    return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean = false
            })
            holder.addView(m, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            map = m; marker = mk
        }
    }

    /** Inversion des couleurs + désaturation : rendu « nuit » lisible, sans clé ni style payant. */
    private fun darkFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val desat = ColorMatrix().apply { setSaturation(0.25f) }
        val dim = ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.8f, 0f, 0f, 0f,
            0f, 0f, 0.95f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f,
        ))
        invert.postConcat(desat)
        invert.postConcat(dim)
        return ColorMatrixColorFilter(invert)
    }

    val available: Boolean get() = map != null

    fun update(location: Location) {
        val m = map ?: return
        val p = GeoPoint(location.latitude, location.longitude)
        marker?.position = p
        if (location.hasBearing()) marker?.rotation = -location.bearing
        if (follow) {
            programmaticMove = true
            m.controller.animateTo(p)
            m.postDelayed({ programmaticMove = false }, 1200)
        }
        m.invalidate()
    }

    fun recenter(location: Location?) {
        follow = true
        location?.let { update(it) }
    }

    fun zoom(delta: Int) {
        map?.controller?.apply { if (delta > 0) zoomIn() else zoomOut() }
    }

    fun onResume() = map?.onResume()
    fun onPause() = map?.onPause()
}
