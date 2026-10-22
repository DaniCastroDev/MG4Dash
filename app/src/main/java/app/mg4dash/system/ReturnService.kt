package app.mg4dash.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.mg4dash.MainActivity

/**
 * Service d'accessibilité limité à l'accueil SAIC (res/xml/return_service.xml : clics seulement, dans
 * com.saicmotor.hmi.launcher et com.saicmotor.launcher). Un double appui sur son bouton CarPlay
 * rouvre le cockpit et refait de MG4Dash l'accueil ([CarPlayDoubleTap]).
 *
 * Activé par MG4Dash lui-même (uid système : WRITE_SECURE_SETTINGS) quand on rend la main à
 * l'interface MG, sans passer par les Paramètres d'Android.
 */
class ReturnService : AccessibilityService() {

    private val doubleTap = CarPlayDoubleTap()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val id = try { event.source?.viewIdResourceName } catch (_: Exception) { null }
        if (!doubleTap.onClick(id, SystemClock.elapsedRealtime())) return
        Log.i(TAG, "double appui sur CarPlay ($id) : retour au cockpit")
        // L'écran change d'abord ; l'accueil SAIC est désactivé ensuite (DONT_KILL_APP).
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        HomeSwitcher.replaceStock(this)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "Return"

        private fun component(c: Context) = ComponentName(c, ReturnService::class.java).flattenToString()

        /** Active le service s'il ne l'est pas ; false sans le droit d'écrire les réglages sécurisés. */
        fun enable(c: Context): Boolean = try {
            val cr = c.contentResolver
            val me = component(c)
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val services = current.split(':').filter { it.isNotBlank() }
            if (me !in services) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (services + me).joinToString(":"))
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "service de retour actif")
            true
        } catch (e: Exception) {
            Log.w(TAG, "service de retour : ${e.message}")
            false
        }
    }
}
