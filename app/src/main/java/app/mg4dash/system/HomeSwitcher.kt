package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/**
 * Écran d'accueil (HOME).
 *
 * Le launcher SAIC déclare une priorité 2 sur HOME ; une appli installée est plafonnée à 0,
 * et Android ignore alors le choix « par défaut ». Pour que MG4Dash soit l'accueil (choix B
 * du propriétaire), on DÉSACTIVE uniquement l'activité d'accueil SAIC ; son service (caméra, sources
 * audio, commandes SystemUI) reste actif. Le mode Entretien et le garde-fou anti-crash la
 * réactivent. Exige le uid système.
 *
 * Secours ADB, sans l'appli :
 *   adb shell pm enable com.saicmotor.launcher/.view.MainActivity
 */
object HomeSwitcher {
    private const val TAG = "Home"
    val STOCK = ComponentName("com.saicmotor.launcher", "com.saicmotor.launcher.view.MainActivity")

    fun ours(context: Context) = ComponentName(context.packageName, "app.mg4dash.MainActivity")

    /** Composant HOME résolu actuellement, ou null. */
    fun currentHome(context: Context): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }

    fun isStockEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(STOCK) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { true }

    /** MG4Dash devient l'accueil : préférence posée, puis accueil SAIC désactivé. */
    fun replaceStock(context: Context): Boolean {
        Prefs(context).valetMode = false
        setPreferred(context, ours(context))
        return try {
            context.packageManager.setComponentEnabledSetting(
                STOCK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            val now = currentHome(context)
            Log.i(TAG, "accueil SAIC désactivé ; HOME résolu : ${now?.flattenToShortString()}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "désactivation de l'accueil SAIC refusée (uid système requis)", e)
            false
        }
    }

    /** Mode Entretien : réactive l'accueil SAIC, le rend préféré, puis l'ouvre. */
    fun switchToStock(context: Context): Boolean {
        Prefs(context).valetMode = true
        val ok = try {
            context.packageManager.setComponentEnabledSetting(
                STOCK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
            )
            setPreferred(context, STOCK)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "réactivation de l'accueil SAIC refusée", e)
            false
        }
        Log.i(TAG, "accueil SAIC réactivé → $ok")
        if (ok) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(STOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "ouverture launcher SAIC : ${e.message}")
            }
        }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun setPreferred(context: Context, target: ComponentName) {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = (pm.queryIntentActivities(homeIntent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) } + target).distinct()
        try {
            candidates.map { it.packageName }.distinct().forEach { pm.clearPackagePreferredActivities(it) }
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY, candidates.toTypedArray(), target)
        } catch (e: SecurityException) {
            Log.w(TAG, "préférence HOME refusée : ${e.message}")
        }
    }
}
