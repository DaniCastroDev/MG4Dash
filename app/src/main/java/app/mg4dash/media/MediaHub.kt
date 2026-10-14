package app.mg4dash.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

/**
 * Lecteur courant, quelle que soit la source (radio DAB/FM SAIC, Bluetooth, USB, Android Auto…).
 * Repose sur MediaSessionManager.getActiveSessions(), réservé au uid système
 * (MEDIA_CONTENT_CONTROL) : c'est ce que fait le launcher d'origine.
 */
object MediaHub {
    private const val TAG = "Media"

    data class NowPlaying(
        val available: Boolean = false,
        val packageName: String? = null,
        val source: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
    )

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private var lastArtKey: String? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.MOCK) {
            _now.value = NowPlaying(true, "com.saicmotor.radio", "RADIO DAB", "France Inter", "Le 7/9", "DAB+ · 11B", null, true)
            return
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = msm
        try {
            msm.addOnActiveSessionsChangedListener({ reevaluate() }, null, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "MEDIA_CONTENT_CONTROL refusé (uid système requis) : ${e.message}")
        }
        // Constaté sur la voiture : les sessions SAIC (radio, Bluetooth) restent « inactives »
        // même en lecture, donc absentes de getActiveSessions(). Comme le launcher d'origine,
        // on se connecte directement à leurs MediaBrowserService pour obtenir leur contrôleur.
        BROWSERS.forEach { connectBrowser(it) }
        watchAudioSource(context)
        reevaluate()
    }

    /**
     * Source audio active selon la voiture (API SAIC de AudioManager, comme le launcher d'origine) :
     * c'est elle qui décide du lecteur affiché, la radio restant « en lecture » même muette.
     */
    @Volatile private var sourcePkg: String? = null

    /** Source choisie à la main sur la carte (puces RADIO / USB-BT), jusqu'à ce que la voiture change de source. */
    @Volatile private var pinnedPkg: String? = null

    /** Affiche la source donnée sans ouvrir son appli. */
    fun select(pkg: String) {
        if (BuildConfig.MOCK) return
        pinnedPkg = pkg
        main.post { reevaluate() }
    }

    /** Puce USB / BT : bascule entre le Bluetooth et l'USB, en partant de ce qui est affiché. */
    fun selectUsbBt() {
        val bt = "com.android.bluetooth"; val usb = "com.saicmotor.media"
        select(if (current?.packageName == bt) usb else bt)
    }
    private const val AA_MBS = "com.allgo.remoteui.mediabrowserservice"

    private fun sourceFromCarType(t: Int): String? = when (t) {
        1, 2, 3 -> "com.saicmotor.radio"
        5 -> "com.android.bluetooth"
        6, 7, 14 -> "com.saicmotor.media"
        70 -> AA_MBS
        else -> null
    }

    private fun watchAudioSource(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            val last = am.javaClass.getMethod("getLastSource").invoke(am) as Int
            sourcePkg = sourceFromCarType(last)
            Log.i(TAG, "source audio au démarrage : $last → $sourcePkg")
        } catch (e: Throwable) { Log.w(TAG, "getLastSource indisponible : ${e.message}") }
        try {
            val reg = am.javaClass.methods.first { it.name == "registerAudioSourceChange" }
            val cbType = reg.parameterTypes[0]
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { proxy, m, args ->
                when (m.name) {
                    "onSourceChanged" -> {
                        val attrs = args?.getOrNull(0)
                        val pkg = args?.getOrNull(1) as? String
                        val type = try {
                            (attrs?.javaClass?.getMethod("getBundle")?.invoke(attrs) as? android.os.Bundle)?.getInt("key_car_source_type", -1) ?: -1
                        } catch (_: Throwable) { -1 }
                        val src = when {
                            pkg == "com.saicmotor.media" && type == 5 -> "com.android.bluetooth"
                            pkg == "com.allgo.app.androidauto" -> AA_MBS
                            pkg == "com.allgo.carplay.service" -> AA_MBS
                            else -> pkg
                        }
                        Log.i(TAG, "source audio : $pkg ($type) → $src")
                        main.post { sourcePkg = src; pinnedPkg = null; reevaluate() }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "MG4DashSourceCallback"
                    else -> null
                }
            }
            val extra = reg.parameterTypes.drop(1).map { null }
            reg.invoke(am, cb, *extra.toTypedArray())
            Log.i(TAG, "suivi de la source audio actif")
        } catch (e: Throwable) { Log.w(TAG, "registerAudioSourceChange indisponible : ${e.message}") }
    }

    private val BROWSERS = listOf(
        ComponentName("com.saicmotor.radio", "com.saicmotor.radio.service.RadioMBService"),
        ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"),
        ComponentName("com.saicmotor.media", "com.saicmotor.media.mbs.Usb1MediaBrowserService"),
        ComponentName("com.allgo.remoteui.mediabrowserservice", "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService"),
    )
    private val browserControllers = mutableMapOf<String, MediaController>()
    private val watched = mutableSetOf<MediaSession.Token>()

    private fun connectBrowser(component: ComponentName) {
        val ctx = appContext ?: return
        var browser: MediaBrowser? = null
        browser = MediaBrowser(ctx, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                browserControllers[component.packageName] = MediaController(ctx, b.sessionToken)
                Log.i(TAG, "navigateur média connecté : ${component.packageName}")
                reevaluate()
            }
            override fun onConnectionSuspended() {
                browserControllers.remove(component.packageName)
                main.postDelayed({ connectBrowser(component) }, 5_000)
            }
            override fun onConnectionFailed() {
                Log.w(TAG, "navigateur média indisponible : ${component.flattenToShortString()}")
                main.postDelayed({ connectBrowser(component) }, 30_000)
            }
        }, null)
        try { browser.connect() } catch (e: Exception) { Log.w(TAG, "connexion ${component.packageName} : ${e.message}") }
    }

    /**
     * Choix du lecteur à afficher, sans clignoter :
     * 0) celui de la source audio active de la voiture ; 1) une session en lecture ; 2) la session déjà affichée tant qu'elle existe ;
     * 3) la radio, puis le Bluetooth, puis l'USB (même s'ils sont en pause).
     */
    private fun reevaluate() {
        val active = try { manager?.getActiveSessions(null).orEmpty() } catch (_: SecurityException) { emptyList() }
        val candidates = active + browserControllers.values
        candidates.forEach { c ->
            if (watched.add(c.sessionToken)) c.registerCallback(callback, main)
        }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val pinned = pinnedPkg?.let { browserControllers[it] }
        val fromSource = sourcePkg?.let { src ->
            browserControllers[src] ?: candidates.firstOrNull { it.packageName == src }
        }
        val chosen = pinned
            ?: fromSource
            ?: playing
            ?: current?.takeIf { cur -> candidates.any { it.sessionToken == cur.sessionToken } }
            ?: active.firstOrNull()
            ?: BROWSERS.firstNotNullOfOrNull { browserControllers[it.packageName] }
        if (chosen?.sessionToken != current?.sessionToken) {
            current = chosen
            Log.i(TAG, "lecteur affiché : ${chosen?.packageName ?: "aucun"}")
        }
        publish()
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            current = null
            watched.clear()
            reevaluate()
        }
    }

    private fun publish() {
        val c = current
        if (c == null) { _now.value = NowPlaying(); return }
        val md = c.metadata
        val st = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val np = NowPlaying(
            available = true,
            packageName = c.packageName,
            source = sourceLabel(c.packageName),
            title = title,
            artist = artist,
            album = album,
            art = bmp ?: _now.value.art.takeIf { lastArtKey == "$title|$artist" },
            playing = st?.state == PlaybackState.STATE_PLAYING,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = st?.position ?: 0,
        )
        _now.value = np
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "$title|$artist"
        if (bmp == null && artUri != null && key != lastArtKey) {
            lastArtKey = key
            io.launch { loadArt(artUri)?.let { a -> if (_now.value.title == title) _now.value = _now.value.copy(art = a) } }
        } else if (bmp != null) {
            lastArtKey = key
        }
    }

    /** Pochette Bluetooth AVRCP : /storage/emulated/0/bluetooth/<MAC>/AVRCP_BIP_IMG_*.JPEG */
    private fun loadArt(uriString: String): Bitmap? = try {
        val ctx = appContext ?: return null
        val decoded = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decoded)
        when (uri.scheme) {
            "content", "android.resource" -> ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> File(uri.path ?: decoded).takeIf { it.canRead() }?.let { BitmapFactory.decodeFile(it.path) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "pochette illisible : ${e.message}"); null
    }

    fun sourceLabel(pkg: String?): String = when {
        pkg == null -> ""
        pkg == "com.android.bluetooth" -> "BLUETOOTH"
        pkg == "com.saicmotor.radio" -> "RADIO"
        pkg == "com.saicmotor.media" -> "USB"
        pkg.startsWith("com.allgo.app.androidauto") || pkg == AA_MBS -> "ANDROID AUTO"
        pkg.startsWith("com.allgo.carplay") -> "CARPLAY"
        else -> appContext?.let { ctx ->
            try {
                val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
                ctx.packageManager.getApplicationLabel(ai).toString().uppercase()
            } catch (_: Exception) { pkg.substringAfterLast('.').uppercase() }
        } ?: pkg
    }

    fun playPause() {
        if (BuildConfig.MOCK) { _now.value = _now.value.copy(playing = !_now.value.playing); return }
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { current?.transportControls?.skipToNext() }
    fun previous() { current?.transportControls?.skipToPrevious() }

    /** Composant à ouvrir quand on touche la carte média. */
    fun ownerComponent(): ComponentName? = current?.packageName?.let { pkg ->
        if (pkg == AA_MBS) return app.mg4dash.system.Apps.ANDROID_AUTO
        appContext?.packageManager?.getLaunchIntentForPackage(pkg)?.component
    }
}
