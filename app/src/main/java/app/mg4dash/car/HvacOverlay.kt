package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log
import kotlinx.coroutines.launch

/**
 * Panneau clim plein écran de la SystemUI SAIC (fenêtre « hvac page »). La SystemUI l'ouvre à chaque
 * mise en marche de la clim (AirConditionController, signal 4001), quelle que soit la commande :
 * bouton physique, launcher, MG4Dash. Seules quelques applis SAIC au premier plan l'en empêchent.
 *
 * La SystemUI expose `IStartActivityService` (StartActivityService) : stopHVAC() cache ce panneau.
 * Relevé sur SWI68 R46.
 */
object HvacOverlay {
    private const val TAG = "HvacOverlay"
    private const val ACTION = "com.android.systemui.saicmotor.action.StartActivity"
    private const val PKG = "com.android.systemui"
    private const val DESCRIPTOR = "com.android.systemui.IStartActivityService"
    private const val TX_STOP_HVAC = 2

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    private var appContext: Context? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var lastBurst = 0L

    /** Rappel immédiat du SDK enregistré : la relecture périodique de CarBridge n'a plus à s'en charger. */
    @Volatile var eventDriven = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; Log.i(TAG, "service SystemUI connecté") }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    fun connect(context: Context) {
        appContext = context.applicationContext
        if (bound) return
        bound = try {
            context.applicationContext.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "liaison SystemUI : ${e.message}"); false }
        Log.i(TAG, "liaison SystemUI : $bound")
    }

    /**
     * La clim vient de s'allumer : la SystemUI ouvre son panneau dans la foulée (handler.post), parfois
     * juste après notre premier appel. On répète stopHVAC pendant la première seconde ; s'il est déjà
     * caché, l'appel ne change rien.
     */
    fun suppress(reason: String) {
        val ctx = appContext ?: return
        if (!app.mg4dash.system.Prefs(ctx).hideStockHvac) return
        val now = System.currentTimeMillis()
        if (now - lastBurst < 1500) return
        lastBurst = now
        scope.launch {
            var ok = false
            for (wait in longArrayOf(0, 80, 150, 250, 400, 700)) {
                kotlinx.coroutines.delay(wait)
                ok = hide() || ok
            }
            Log.i(TAG, "clim allumée ($reason) : panneau SystemUI masqué → $ok")
        }
    }

    /** Cache le panneau clim de la SystemUI ; false si le service ne répond pas. */
    fun hide(): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_STOP_HVAC, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "stopHVAC : ${e.message}"); false
        } finally { data.recycle(); reply.recycle() }
    }
}
