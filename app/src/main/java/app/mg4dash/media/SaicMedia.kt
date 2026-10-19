package app.mg4dash.media

import android.content.Context
import app.mg4dash.car.Refl
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log

/**
 * Lecteur média de l'ancien SDK (SWI68, SWI165, SWI133) : MediaPlayControlManager, chargé depuis le
 * launcher d'origine comme les gestionnaires de clim et de réglages.
 *
 * Le menu de sources du launcher d'origine (MusicViewModel.playSelectedMusicResource) appelle
 * play(n) avec 2 = USB, 3 = musique en ligne, 4 = Bluetooth ; le service média SAIC prend alors la
 * source audio. La commande 0x8001 envoyée au MediaService de la voie A9 ne fait rien sur SWI68 :
 * la radio continuait par-dessus le Bluetooth (essai sur la voiture, 2026-10-19).
 */
object SaicMedia {
    private const val TAG = "SaicMedia"
    private const val CLS = "com.saicmotor.sdk.media.mananger.MediaPlayControlManager"
    const val USB = 2
    const val BT = 4

    @Volatile private var mgr: Any? = null
    @Volatile private var connected = false
    @Volatile private var tried = false

    val ready: Boolean get() = mgr != null && connected

    fun connect(context: Context) {
        if (mgr != null || tried) return
        tried = true
        val ctx = context.applicationContext
        val launcher = listOf(Firmware.LEGACY_LAUNCHER, "com.saicmotor.launcher").firstNotNullOfOrNull { pkg ->
            try { ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { null }
        } ?: run { Log.w(TAG, "launcher SAIC introuvable"); return }
        val cls = try { launcher.classLoader.loadClass(CLS) } catch (e: Exception) { Log.w(TAG, "MediaPlayControlManager absent : ${e.message}"); return }
        val getInstance = cls.methods.firstOrNull {
            it.name == "getInstance" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: run { Log.w(TAG, "getInstance(Context, …) absent"); return }
        val lt = getInstance.parameterTypes[1]
        // Écouteur IMediaServiceListener : le SDK le range dans une liste, les méthodes d'Object doivent répondre
        val listener = if (!lt.isInterface) null else try {
            java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { proxy, m, args ->
                when (m.name) {
                    "onServiceConnected" -> { connected = true; Log.i(TAG, "service média SAIC connecté"); null }
                    "onServiceDisconnected" -> { connected = false; Log.w(TAG, "service média SAIC déconnecté"); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "MG4DashMediaServiceListener"
                    else -> null
                }
            }
        } catch (_: Exception) { null }
        mgr = try { getInstance.invoke(null, ctx, listener) } catch (e: Exception) {
            Log.w(TAG, "MediaPlayControlManager.getInstance : ${(e.cause ?: e).message}"); null
        }
        Log.i(TAG, "MediaPlayControlManager ${if (mgr != null) "chargé" else "indisponible"}")
    }

    /** Lance un lecteur local (USB ou Bluetooth), comme le menu de sources d'origine ; false si indisponible. */
    fun play(player: Int): Boolean {
        val m = mgr ?: return false
        return try {
            Refl.m(m, "play", Int::class.javaPrimitiveType).invoke(m, player)
            Log.i(TAG, "play($player) ${if (connected) "" else "(service pas encore confirmé)"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "play($player) : ${(e.cause ?: e).message}"); false
        }
    }

    fun probe(): String = when { mgr == null -> "absent"; connected -> "ok"; else -> "chargé, service non confirmé" }
}
