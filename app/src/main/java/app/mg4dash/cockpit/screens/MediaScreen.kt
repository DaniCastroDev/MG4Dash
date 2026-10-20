package app.mg4dash.cockpit.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.BuildConfig
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.Icon
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.ValueText
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.pxf
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.media.MediaHub
import app.mg4dash.media.Radio
import app.mg4dash.media.SaicRadio
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ───────────────────────────── icônes propres à l'écran (lienzo Media.dc.html) ─────────────────────────────

private const val RADIO_BODY = "M7 11H25A3 3 0 0 1 28 14V24A3 3 0 0 1 25 27H7A3 3 0 0 1 4 24V14A3 3 0 0 1 7 11Z"
private val ICON_AM = Icon(listOf(Icon.Part(RADIO_BODY), Icon.Part("M8 11 23 4.5"), Icon.Part("M8 19c1.3-2.7 2.7-2.7 4 0s2.7 2.7 4 0 2.7-2.7 4 0 2.7 2.7 4 0")))
private val ICON_DAB = Icon(listOf(
    Icon.Part("M13.5 13a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z"), Icon.Part("M16 15.5V28"),
    Icon.Part("M11 8a7 7 0 0 0 0 10M21 8a7 7 0 0 1 0 10"), Icon.Part("M6.5 4.5a12.5 12.5 0 0 0 0 17M25.5 4.5a12.5 12.5 0 0 1 0 17"),
))
private val ICON_USB = Icon(listOf(
    Icon.Part("M16 5v18"), Icon.Part("M13 8l3-3 3 3"), Icon.Part("M13.5 25.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z"),
    Icon.Part("M16 19.5l-6-3.5v-4"), Icon.Part("M8 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z"), Icon.Part("M16 16l6-3v-3"),
    Icon.Part("M20.5 6H23.5A0.5 0.5 0 0 1 24 6.5V9.5A0.5 0.5 0 0 1 23.5 10H20.5A0.5 0.5 0 0 1 20 9.5V6.5A0.5 0.5 0 0 1 20.5 6Z"),
))
private val ICON_SCAN = Icon(listOf(
    Icon.Part("M4 10V6.5A2.5 2.5 0 0 1 6.5 4H10M22 4h3.5A2.5 2.5 0 0 1 28 6.5V10M28 22v3.5a2.5 2.5 0 0 1-2.5 2.5H22M10 28H6.5A2.5 2.5 0 0 1 4 25.5V22"),
    Icon.Part("M13 11l8 5-8 5Z"),
))
private const val STAR = "M16 4.5 19.5 11.7 27.4 12.8 21.7 18.4 23.1 26.3 16 22.6 8.9 26.3 10.3 18.4 4.6 12.8 12.5 11.7Z"
private val ICON_STAR = Icon(listOf(Icon.Part(STAR)))
private val ICON_STAR_ON = Icon(listOf(Icon.Part(STAR, fill = true), Icon.Part(STAR)))

private const val RADIO_PKG = "com.saicmotor.radio"
private const val BT_PKG = "com.android.bluetooth"
private const val USB_PKG = "com.saicmotor.media"
/** Appli d'origine Bluetooth / USB (mêmes paquets que l'ancien écran Medios). */
private val MEDIA_APP = listOf("com.saicmotor.hmi.music", "com.saicmotor.media")
/** Temps laissé au service radio pour répondre avant d'afficher « Radio no disponible ». */
private const val GRACE_MS = 2_500L

private val FM_LABELS = listOf(87_500 to "87,5", 92_000 to "92", 96_000 to "96", 100_000 to "100", 104_000 to "104", 108_000 to "108")

/** Fréquence affichée : « 96,5 » (MHz) en FM, « 621 » (kHz) en AM. */
private fun freqText(band: Int, f: Int): String = when (band) {
    Radio.FM -> "${f / 1000},${(f % 1000) / 100}"
    Radio.AM -> f.toString()
    else -> ""
}

private fun unitOf(band: Int) = when (band) { Radio.FM -> "MHz"; Radio.AM -> "kHz"; else -> "" }

private fun snapFm(f: Int) = (Radio.FM_MIN + (f - Radio.FM_MIN + Radio.FM_STEP / 2) / Radio.FM_STEP * Radio.FM_STEP).coerceIn(Radio.FM_MIN, Radio.FM_MAX)
private fun snapAm(f: Int) = (Radio.AM_MIN + (f - Radio.AM_MIN + Radio.AM_STEP / 2) / Radio.AM_STEP * Radio.AM_STEP).coerceIn(Radio.AM_MIN, Radio.AM_MAX)

/**
 * Medios · radio FM / AM / DAB et sources Bluetooth / USB (lienzo : Media.dc.html).
 *
 * À gauche (1048 px) : onglets des sources et lecture de la source choisie — fréquence, réglage fin
 * et recherche en FM / AM, logo et texte de la station en DAB, titre et commandes en Bluetooth / USB.
 * À droite (696 px) : favorites de la radio de la voiture (ou stations trouvées), étoile pour la
 * station en cours et balayage. Le panneau téléphone du lienzo viendra plus tard : en Bluetooth / USB,
 * la colonne de droite ouvre seulement l'appli d'origine.
 *
 * L'onglet suit la source de la voiture ([MediaHub.currentSource]) et la bande de la radio ; un appui
 * sur un onglet l'affiche tout de suite, sans attendre que la voiture ait changé de source.
 */
class MediaScreen(host: Host) : Screen(host) {
    override val nav = Nav.MEDIA
    override val parkedOnly = true

    private enum class Tab(val label: String, val icon: Icon) {
        FM("FM", Icons.radio), AM("AM", ICON_AM), DAB("DAB", ICON_DAB), BT("Bluetooth", Icons.bluetooth), USB("USB", ICON_USB)
    }

    private lateinit var tabs: Map<Tab, Btn>
    private lateinit var stage: FrameLayout
    private lateinit var fm: Tuner
    private lateinit var am: Tuner
    private lateinit var dab: DabPanel
    private lateinit var media: MediaPanel
    private lateinit var empty: EmptyPanel
    private lateinit var favCard: View
    private lateinit var linkCard: View
    private lateinit var listTabs: Segmented
    private lateinit var star: Btn
    private lateinit var scanBtn: Btn
    private lateinit var listCol: LinearLayout
    private lateinit var listEmpty: TextView

    /** Onglet affiché (null : à choisir d'après la source au prochain affichage). */
    private var tab: Tab? = null
    private var followedSource: String? = null
    private var followedBand = 0
    /** Colonne de droite : 0 = favorites, 1 = stations trouvées. */
    private var listMode = 0
    /** Le balayage en cours vient du bouton « Escanear » (et non d'une recherche). */
    private var scanAsked = false
    private var shownAt = 0L
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private val graceTick = Runnable { render() }
    private val tiles = ArrayList<Tile>()
    private var shownEntries: List<SaicRadio.Entry>? = null
    private var shownMode = -1
    private var available: List<String> = emptyList()
    private var availableAt = 0L

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(playback(), w(1048))
        addView(FrameLayout(c).apply {
            favCard = favourites(); addView(favCard, FrameLayout.LayoutParams(MATCH, MATCH))
            linkCard = appLink(); addView(linkCard, FrameLayout.LayoutParams(MATCH, MATCH))
        }, w(696))
    }

    // ───────── reproducción (lado del conductor) ─────────

    private fun playback() = c.card(gap = 24).apply {
        val bar = c.row(12)
        tabs = Tab.values().associateWith { t ->
            Btn(c, t.label, t.icon, 28, vertical = false, textSize = Ty.LABEL).also { b ->
                b.setPadding(12, 0, 12, 0)
                b.labelView?.maxLines = 1
                hug(b, 10)
                b.setOnClickListener { pick(t) }
                bar.addView(b, lp(0, 80, 1f))
            }
        }
        addView(bar, lp(MATCH, 80))
        stage = FrameLayout(c)
        fm = Tuner(Radio.FM); am = Tuner(Radio.AM); dab = DabPanel(); media = MediaPanel(); empty = EmptyPanel()
        for (v in listOf(fm.view, am.view, dab.view, media.view, empty.view)) stage.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(stage, lp(MATCH, 0, 1f))
    }

    /** FM ou AM : station, fréquence, échelle de sintonía, réglage fin et recherche. */
    private inner class Tuner(private val band: Int) {
        private val isAm = band == Radio.AM
        private val kicker = c.label("").apply { ellipsize = TextUtils.TruncateAt.END }
        private val name = line(Ty.VALUE, Pal.text, Ty.uiMedium)
        private val info = line(Ty.BODY, Pal.text2)
        private val freq = ValueText(c, Ty.HERO, Ty.TITLE).apply { gravity = Gravity.BOTTOM or Gravity.END }
        private val scale = if (isAm) TuneScale(c, Radio.AM_MIN, Radio.AM_MAX, 0, 0, listOf(Radio.AM_MIN to "531", Radio.AM_MAX to "1602"), ::snapAm)
            else TuneScale(c, Radio.FM_MIN, Radio.FM_MAX, 500, 1000, FM_LABELS, ::snapFm)
        private val play = playButton()
        private val seekDown = pad(if (isAm) "Buscar −" else "Buscar", Icons.seekPrev, 32) { scanAsked = false; Radio.seekDown() }
        private val seekUp = pad(if (isAm) "Buscar +" else "Buscar", Icons.seekNext, 32) { scanAsked = false; Radio.seekUp() }
        val view: View

        init {
            // Glisser sur l'échelle : le chiffre suit le doigt, la radio se règle au relâcher
            scale.onPreview = { f -> if (f != null) freq.set(freqText(band, f), unitOf(band)) else render() }
            scale.onTune = { f -> Radio.tune(SaicRadio.Entry(band, f, "")) }
            scale.contentDescription = if (isAm) "Escala AM de 531 a 1602 kHz" else "Escala FM de 87,5 a 108 MHz"
            seekDown.contentDescription = "Buscar emisora anterior"
            seekUp.contentDescription = "Buscar emisora siguiente"
            val stepLabel = if (isAm) "${Radio.AM_STEP}" else "0,1"
            view = c.col().apply {
                addView(c.row(24).apply {
                    addView(iconTile(if (isAm) ICON_AM else Icons.radio, 60, 1.4f, Pal.surface3, Pal.action, 16f), lp(120, 120))
                    addView(c.col(4).apply { addView(kicker); addView(name); addView(info) }, lp(0, WRAP, 1f))
                    addView(freq, lp(340, WRAP))
                }, lp(MATCH, 120))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(scale, lp(MATCH, 84))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    gravity = Gravity.CENTER
                    addView(pad("−$stepLabel", Icons.chevronLeft, 28, num = true) { Radio.stepDown() }.apply {
                        contentDescription = if (isAm) "Bajar un canal" else "Bajar 0,1 MHz"
                    }, lp(120, Dim.PRIMARY))
                    addView(seekDown, lp(120, Dim.PRIMARY))
                    addView(play.apply { setOnClickListener { Radio.playPause() } }, lp(128, Dim.PRIMARY))
                    addView(seekUp, lp(120, Dim.PRIMARY))
                    addView(pad("+$stepLabel", Icons.chevronRight, 28, num = true) { Radio.stepUp() }.apply {
                        contentDescription = if (isAm) "Subir un canal" else "Subir 0,1 MHz"
                    }, lp(120, Dim.PRIMARY))
                }, lp(MATCH, Dim.PRIMARY))
            }
        }

        fun bind(r: Radio.State) {
            val mine = r.band == band
            val f = if (mine) r.frequencyKhz else 0
            val favIdx = if (mine) r.favourites.indexOfFirst { Radio.isCurrent(it) } else -1
            val place = when {
                favIdx >= 0 -> "Favorita ${favIdx + 1} de ${r.favourites.size}"
                isAm -> "Onda media"
                else -> "Sintonía manual"
            }
            kicker.put((if (isAm) "AM · " else "FM · ") + place + if (mine) busy(r) else "")
            name.put(when {
                f == 0 -> "—"
                r.name.isNotBlank() -> r.name
                isAm -> "Sintonía manual"
                else -> "Sin nombre RDS"
            })
            info.put(when {
                f == 0 -> ""
                r.text.isNotBlank() -> r.text
                !isAm && r.name.isBlank() -> "Pulsa Buscar para ir a la siguiente emisora"
                else -> "Sin texto RDS"
            })
            if (scale.dragging == 0) freq.set(if (f == 0) "—" else freqText(band, f), unitOf(band))
            scale.set(f, if (mine) r.favourites.filter { it.band == band }.map { it.frequencyKhz } else emptyList())
            setPlay(play, mine && r.playing)
            val seekLook = if (mine && r.seeking && !scanAsked) Look.SELECTED else Look.NEUTRAL
            if (seekDown.look != seekLook) { seekDown.look = seekLook; seekUp.look = seekLook }
        }
    }

    /** DAB : logo de la station (ou icône), nom, texte diffusé et station précédente / suivante. */
    private inner class DabPanel {
        private val kicker = c.label("").apply { ellipsize = TextUtils.TruncateAt.END }
        private val name = line(Ty.VALUE, Pal.text, Ty.uiMedium)
        private val dls = line(Ty.BODY, Pal.text, Ty.uiMedium)
        private val logoTile = iconTile(ICON_DAB, 60, 1.4f, Pal.surface3, Pal.action, 16f)
        private val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        private val play = playButton()
        private var shownLogo: Bitmap? = null
        val view: View

        init {
            logoTile.addView(logo, FrameLayout.LayoutParams(MATCH, MATCH))
            view = c.col().apply {
                addView(c.row(24).apply {
                    addView(logoTile, lp(120, 120))
                    addView(c.col(4).apply {
                        addView(kicker); addView(name)
                        addView(line(Ty.BODY, Pal.text2).apply { text = "Radio digital" })
                    }, lp(0, WRAP, 1f))
                }, lp(MATCH, 120))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.col(6).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Pal.surface2, Dim.BTN_R)
                    setPadding(24, 16, 24, 16)
                    addView(c.label("Texto de la emisora"))
                    addView(dls)
                }, lp(MATCH, 104))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    gravity = Gravity.CENTER
                    addView(pad("Anterior", Icons.prev, 32) { Radio.previous() }, lp(120, Dim.PRIMARY))
                    addView(play.apply { setOnClickListener { Radio.playPause() } }, lp(128, Dim.PRIMARY))
                    addView(pad("Siguiente", Icons.next, 32) { Radio.next() }, lp(120, Dim.PRIMARY))
                }, lp(MATCH, Dim.PRIMARY))
            }
        }

        fun bind(r: Radio.State) {
            val mine = r.band == Radio.DAB
            val favIdx = if (mine) r.favourites.indexOfFirst { Radio.isCurrent(it) } else -1
            kicker.put("DAB+" + (if (favIdx >= 0) " · Favorita ${favIdx + 1} de ${r.favourites.size}" else "") +
                if (mine) busy(r) else "")
            val none = !mine || (r.name.isBlank() && r.frequencyKhz == 0)
            name.put(if (none) "—" else r.name.ifBlank { "Sin nombre" })
            dls.put(if (none) "" else r.text.ifBlank { "Sin texto DAB" })
            val bmp = if (mine) r.logo else null
            if (bmp !== shownLogo) {
                shownLogo = bmp
                logo.setImageBitmap(bmp)
                logo.visibility = if (bmp != null) View.VISIBLE else View.GONE
                logoTile.getChildAt(0).visibility = if (bmp != null) View.GONE else View.VISIBLE
            }
            setPlay(play, mine && r.playing)
        }
    }

    /** Bluetooth / USB : pochette, titre, artiste, album et commandes du lecteur de [MediaHub]. */
    private inner class MediaPanel {
        private val artTile = iconTile(Icons.media, 96, 1f, Pal.surface3, Pal.text3, Dim.CARD_R)
        private val art = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        private val pill = Pill(c, "—", Pal.text3)
        private val kicker = c.label("")
        private val title = line(Ty.VALUE, Pal.text, Ty.uiMedium)
        private val artist = line(Ty.BODY, Pal.text2, Ty.uiMedium)
        private val album = line(Ty.LABEL, Pal.text3)
        private val play = playButton()
        private var shownArt: Bitmap? = null
        /** Le lecteur affiché par [MediaHub] est bien celui de cet onglet. */
        private var mine = false
        private var pkg = BT_PKG
        val view: View

        init {
            artTile.addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            view = c.col().apply {
                addView(c.row(32).apply {
                    gravity = Gravity.TOP
                    addView(artTile, lp(272, 272))
                    addView(c.col().apply {
                        setPadding(0, 4, 0, 4)
                        addView(pill, lp(WRAP, 44))
                        addView(View(c), lp(MATCH, 0, 1f))
                        addView(c.col(6).apply { addView(kicker); addView(title); addView(artist); addView(album) }, lp(MATCH, WRAP))
                    }, lp(0, MATCH, 1f))
                }, lp(MATCH, 272))
                addView(View(c), lp(MATCH, 0, 1f))
                addView(c.row(16).apply {
                    gravity = Gravity.CENTER
                    addView(pad("Anterior", Icons.prev, 32) { if (mine) MediaHub.previous() }, lp(120, Dim.PRIMARY))
                    // Lecteur pas encore affiché : « lecture » bascule d'abord la voiture sur cette source
                    addView(play.apply { setOnClickListener { if (mine) MediaHub.playPause() else MediaHub.switchTo(pkg) } }, lp(128, Dim.PRIMARY))
                    addView(pad("Siguiente", Icons.next, 32) { if (mine) MediaHub.next() }, lp(120, Dim.PRIMARY))
                }, lp(MATCH, Dim.PRIMARY))
            }
        }

        fun bind(t: Tab, n: MediaHub.NowPlaying) {
            val bt = t == Tab.BT
            pkg = if (bt) BT_PKG else USB_PKG
            mine = n.available && n.packageName == pkg
            val state = when { mine && n.playing -> "Reproduciendo"; mine -> "En pausa"; else -> "Conectado" }
            pill.set((if (bt) "Bluetooth" else "USB") + " · " + state, if (mine && n.playing) Pal.energy else Pal.action)
            kicker.put(if (bt) "Audio Bluetooth" else "Música USB")
            title.put(if (mine) n.title.ifBlank { "Sin título" } else "Sin reproducción")
            artist.put(when {
                mine -> n.artist
                bt -> "Pulsa reproducir para escuchar el teléfono"
                else -> "Pulsa reproducir para escuchar la memoria USB"
            })
            album.put(if (mine) n.album else "")
            val bmp = if (mine) n.art else null
            if (bmp !== shownArt) {
                shownArt = bmp
                art.setImageBitmap(bmp)
                art.visibility = if (bmp != null) View.VISIBLE else View.GONE
                artTile.getChildAt(0).visibility = if (bmp != null) View.GONE else View.VISIBLE
            }
            setPlay(play, mine && n.playing)
        }
    }

    /** État vide centré : pas de clé USB, pas de téléphone, radio injoignable. */
    private inner class EmptyPanel {
        private val icon = IconView(c, ICON_USB, 56, 1.5f).apply { color = Pal.text2 }
        private val title = c.text("", Ty.TITLE, Pal.text, Ty.uiMedium).apply { gravity = Gravity.CENTER; maxLines = 1 }
        private val sub = c.text("", Ty.LABEL, Pal.text2).apply { gravity = Gravity.CENTER; maxLines = 2 }
        private val button = Btn(c, "", Icons.radio, 30, vertical = false, textSize = Ty.BODY)
        private var action: () -> Unit = {}
        val view: View

        init {
            hug(button)
            button.setOnClickListener { action() }
            view = c.col(24).apply {
                gravity = Gravity.CENTER
                addView(FrameLayout(c).apply {
                    background = rounded(Pal.surface2, Dim.BTN_R)
                    addView(icon, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, lp(112, 112))
                addView(c.col(8).apply { addView(title); addView(sub) }, lp(MATCH, WRAP))
                addView(button, lp(320, Dim.PRIMARY))
            }
        }

        fun show(icon: Icon, title: String, sub: String, button: String, buttonIcon: Icon, action: () -> Unit) {
            if (this.icon.icon !== icon) this.icon.icon = icon
            this.title.put(title)
            this.sub.put(sub)
            this.button.setLabel(button)
            this.button.iconView?.let { if (it.icon !== buttonIcon) it.icon = buttonIcon }
            this.action = action
        }
    }

    // ───────── favoritas (colonne de droite) ─────────

    private fun favourites() = c.card(gap = 0).apply {
        listTabs = Segmented(c, listOf("Favoritas", "Emisoras"), height = Dim.TAP_MIN, textSize = Ty.LABEL, gap = 8) { i -> listMode = i; render() }
        listTabs.buttons.forEach { b -> b.labelView?.typeface = Ty.uiMedium; b.labelView?.fontFeatureSettings = null }
        star = Btn(c, null, ICON_STAR, 32).apply { setOnClickListener { Radio.toggleFavourite(); render() } }
        scanBtn = Btn(c, "Escanear", ICON_SCAN, 28, vertical = false, textSize = Ty.LABEL).apply {
            setPadding(16, 0, 16, 0)
            hug(this, 10)
            contentDescription = "Escanear la banda"
            setOnClickListener {
                // Un appui pendant une recherche l'arrête ; sinon, balayage et liste des stations trouvées
                val searching = Radio.state.value.seeking
                scanAsked = !searching
                if (!searching) listMode = 1
                Radio.scan()
                render()
            }
        }
        addView(c.row(12).apply {
            addView(listTabs, lp(0, Dim.TAP_MIN, 1f))
            addView(star, lp(Dim.TAP_MIN, Dim.TAP_MIN))
            addView(scanBtn, lp(184, Dim.TAP_MIN))
        }, lp(MATCH, Dim.TAP_MIN))
        listCol = c.col(12)
        listEmpty = c.text("", Ty.LABEL, Pal.text2).apply { gravity = Gravity.CENTER; maxLines = 3; visibility = View.GONE }
        // Quatre rangées de 88 px tiennent ; au-delà, la liste défile (fondu en bas)
        addView(FrameLayout(c).apply {
            addView(ScrollView(c).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(32)
                addView(listCol, FrameLayout.LayoutParams(MATCH, WRAP))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(listEmpty, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp(MATCH, 0, 1f).apply { topMargin = 32 })
    }

    /** Une station de la liste : numéro, fréquence (ou nom en DAB) et nom (ou « DAB+ »). */
    private inner class Tile(val entry: SaicRadio.Entry, index: Int) {
        val btn = Btn(c, vertical = false)
        private val main: TextView

        init {
            btn.setPadding(18, 0, 18, 0)
            val isDab = entry.band == Radio.DAB
            btn.addView(c.text("${index + 1}", 22f, Pal.text2, Ty.num).apply { maxLines = 1 }, lp(32, WRAP))
            main = if (isDab) line(Ty.BODY, Pal.text, Ty.uiMedium).apply { text = entry.name.ifBlank { "Emisora ${index + 1}" } }
                else line(Ty.TITLE, Pal.text, Ty.num).apply { text = freqText(entry.band, entry.frequencyKhz) }
            val sub = line(22f, Pal.text2, Ty.uiMedium).apply { text = if (isDab) "DAB+" else entry.name.ifBlank { unitOf(entry.band) } }
            btn.addView(c.col(2).apply { addView(main); addView(sub) }, lp(0, WRAP, 1f).apply { leftMargin = 12 })
            btn.contentDescription = if (isDab) entry.name else "${entry.name} ${freqText(entry.band, entry.frequencyKhz)} ${unitOf(entry.band)}".trim()
            btn.setOnClickListener { Radio.tune(entry) }
        }

        fun select(on: Boolean) {
            val look = if (on) Look.SELECTED else Look.NEUTRAL
            if (btn.look == look) return
            btn.look = look
            main.setTextColor(if (on) Pal.action else Pal.text)
        }
    }

    /** Bluetooth / USB : en attendant le panneau téléphone, un lien vers l'appli d'origine. */
    private fun appLink() = c.card().apply {
        addView(c.label("Bluetooth y USB"))
        addView(Btn(c, "Abrir Bluetooth y USB", Icons.bluetooth, 30, vertical = false, textSize = Ty.BODY, sub = "App original del coche").apply {
            setOnClickListener { if (!Launch.open(c, MEDIA_APP)) host.toast("App de Bluetooth y USB no disponible", tone = Pal.alert) }
        }, lp(MATCH, Dim.PRIMARY))
    }

    // ───────── état ─────────

    override fun onShow() {
        // À chaque affichage : onglet de la source en cours et favorites, comme sur le lienzo
        tab = null
        listMode = 0
        shownAt = SystemClock.uptimeMillis()
        Radio.start(c)
        job?.cancel()
        // Relecture rapide de la radio tant que l'écran est visible et l'activité au premier plan
        val owner = c as? LifecycleOwner
        if (owner != null) job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Radio.start(c)
                try { Radio.state.collect { render() } } finally { Radio.stop() }
            }
        }
        main.removeCallbacks(graceTick)
        main.postDelayed(graceTick, GRACE_MS + 100)
    }

    override fun onHide() {
        job?.cancel()
        job = null
        main.removeCallbacks(graceTick)
        Radio.stop()
    }

    override fun bind(s: VehicleState) = render()

    private fun render() {
        if (!::stage.isInitialized) return
        val r = Radio.state.value
        val t = follow(r)
        for ((k, b) in tabs) {
            val look = if (k == t) Look.SELECTED else Look.NEUTRAL
            if (b.look != look) b.look = look
        }
        val radio = t == Tab.FM || t == Tab.AM || t == Tab.DAB
        favCard.visibility = if (radio) View.VISIBLE else View.GONE
        linkCard.visibility = if (radio) View.GONE else View.VISIBLE
        val shown: View = when {
            radio && !r.available && !BuildConfig.MOCK && SystemClock.uptimeMillis() - shownAt > GRACE_MS -> {
                empty.show(Icons.radio, "Radio no disponible", "El servicio de radio del coche no responde", "Radio original", Icons.radio) {
                    if (!Launch.open(c, Launch.RADIO)) host.toast("Radio original no disponible", tone = Pal.alert)
                }
                empty.view
            }
            t == Tab.FM -> { fm.bind(r); fm.view }
            t == Tab.AM -> { am.bind(r); am.view }
            t == Tab.DAB -> { dab.bind(r); dab.view }
            else -> {
                val bt = t == Tab.BT
                if ((if (bt) BT_PKG else USB_PKG) in sources()) {
                    media.bind(t, MediaHub.now.value)
                    media.view
                } else {
                    if (bt) empty.show(Icons.bluetooth, "Sin teléfono conectado", "Conecta tu teléfono por Bluetooth para escuchar su música", "Volver a FM", Icons.radio) { pick(Tab.FM) }
                    else empty.show(ICON_USB, "Sin dispositivo USB", "Conecta una memoria USB con música para reproducirla aquí", "Volver a FM", Icons.radio) { pick(Tab.FM) }
                    empty.view
                }
            }
        }
        for (i in 0 until stage.childCount) {
            val v = stage.getChildAt(i)
            val vis = if (v === shown) View.VISIBLE else View.GONE
            if (v.visibility != vis) v.visibility = vis
        }
        if (radio) renderList(r)
    }

    /**
     * Onglet à afficher : celui de la source de la voiture quand elle change (radio → bande en cours),
     * sinon celui choisi à la main. Android Auto ou source inconnue : l'onglet ne bouge pas.
     */
    private fun follow(r: Radio.State): Tab {
        val src = MediaHub.currentSource()
        val bandTab = when (r.band) { Radio.AM -> Tab.AM; Radio.DAB -> Tab.DAB; else -> Tab.FM }
        val cur = tab
        val next = when {
            cur == null -> when (src) { BT_PKG -> Tab.BT; USB_PKG -> Tab.USB; else -> bandTab }
            src != followedSource -> when (src) { BT_PKG -> Tab.BT; USB_PKG -> Tab.USB; RADIO_PKG -> bandTab; else -> cur }
            r.band != followedBand && (src == RADIO_PKG || src == null) -> bandTab
            else -> cur
        }
        followedSource = src
        followedBand = r.band
        tab = next
        return next
    }

    /** Appui sur un onglet : affichage immédiat, puis bascule de la voiture (bande ou source). */
    private fun pick(t: Tab) {
        tab = t
        val src = MediaHub.currentSource()
        when (t) {
            Tab.FM, Tab.AM, Tab.DAB -> {
                val band = when (t) { Tab.AM -> Radio.AM; Tab.DAB -> Radio.DAB; else -> Radio.FM }
                val r = Radio.state.value
                if (r.band != band || !r.playing || src != RADIO_PKG) Radio.selectBand(band)
                // Bluetooth / USB / Android Auto en cours : la voiture repasse sur la radio
                if (src != null && src != RADIO_PKG) MediaHub.switchTo(RADIO_PKG) else MediaHub.select(RADIO_PKG)
            }
            // Sans téléphone ni clé USB, on n'interrompt pas la source en cours : l'état vide s'affiche
            Tab.BT -> if (src != BT_PKG && BT_PKG in sources(force = true)) MediaHub.switchTo(BT_PKG)
            Tab.USB -> if (src != USB_PKG && USB_PKG in sources(force = true)) MediaHub.switchTo(USB_PKG)
        }
        render()
    }

    /** Sources présentes (téléphone Bluetooth connecté, clé USB montée…), relues au plus toutes les 2 s. */
    private fun sources(force: Boolean = false): List<String> {
        val now = SystemClock.uptimeMillis()
        if (force || now - availableAt > 2_000) { available = MediaHub.availableSources(); availableAt = now }
        return available
    }

    private fun renderList(r: Radio.State) {
        if (listTabs.selected != listMode) listTabs.select(listMode)
        val canSave = (r.available || BuildConfig.MOCK) && (r.frequencyKhz != 0 || r.name.isNotBlank())
        val starLook = when { !canSave -> Look.DISABLED; r.favourite -> Look.SELECTED; else -> Look.NEUTRAL }
        if (star.look != starLook) star.look = starLook
        val starIcon = if (r.favourite) ICON_STAR_ON else ICON_STAR
        star.iconView?.let { if (it.icon !== starIcon) it.icon = starIcon }
        star.contentDescription = if (r.favourite) "Quitar de favoritas" else "Guardar en favoritas"
        if (!r.seeking) scanAsked = false
        val scanLook = if (r.seeking && scanAsked) Look.SELECTED else Look.NEUTRAL
        if (scanBtn.look != scanLook) scanBtn.look = scanLook

        val entries = if (listMode == 0) r.favourites else r.stations
        if (entries != shownEntries || listMode != shownMode) {
            shownEntries = entries
            shownMode = listMode
            listCol.removeAllViews()
            tiles.clear()
            entries.forEachIndexed { i, e -> tiles.add(Tile(e, i)) }
            for (pair in tiles.chunked(2)) {
                listCol.addView(c.row(12).apply {
                    for (tile in pair) addView(tile.btn, lp(0, 88, 1f))
                    if (pair.size == 1) addView(View(c), lp(0, 88, 1f))
                }, lp(MATCH, 88))
            }
            listEmpty.put(when {
                entries.isNotEmpty() -> ""
                listMode == 0 -> "Sin favoritas en esta banda.\nPulsa la estrella para guardar la emisora actual."
                else -> "Sin lista de emisoras.\nPulsa Escanear para buscarlas."
            })
            listEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
        for (tile in tiles) tile.select(Radio.isCurrent(tile.entry))
    }

    // ───────── petites briques ─────────

    /** Suffixe de l'en-tête pendant une recherche (lienzo : « · Buscando… » ou « · Escaneando »). */
    private fun busy(r: Radio.State) = when {
        !r.seeking -> ""
        scanAsked -> " · Escaneando"
        else -> " · Buscando…"
    }

    private fun line(size: Float, color: Int, face: Typeface = Ty.ui) =
        c.text("", size, color, face).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    /** Bouton de commande 120 × 96 du lienzo : icône au-dessus d'un libellé de 20 px. */
    private fun pad(label: String, icon: Icon, iconSize: Int, num: Boolean = false, onClick: () -> Unit) =
        Btn(c, label, icon, iconSize, textSize = Ty.CAPTION).apply {
            if (num) labelView?.let { it.typeface = Ty.num; it.fontFeatureSettings = "tnum" }
            setOnClickListener { onClick() }
        }

    private fun playButton() = Btn(c, null, Icons.play, 40).apply { look = Look.RAISED; contentDescription = "Reproducir" }

    private fun setPlay(b: Btn, playing: Boolean) {
        val icon = if (playing) Icons.pause else Icons.play
        b.iconView?.let { if (it.icon !== icon) it.icon = icon }
        b.contentDescription = if (playing) "Pausar" else "Reproducir"
    }

    /** Vignette arrondie avec une grande icône au trait fin (station, pochette absente). */
    private fun iconTile(icon: Icon, iconPx: Int, stroke: Float, bg: Int, ink: Int, radius: Float) = FrameLayout(c).apply {
        background = rounded(bg, radius)
        clipToOutline = true
        addView(IconView(c, icon, iconPx, stroke).apply { color = ink }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    /** Bouton horizontal au contenu centré (icône + libellé serrés) au lieu d'un libellé calé à gauche. */
    private fun hug(b: Btn, gap: Int = 12) {
        (b.labelView?.parent as? View)?.layoutParams = lp(WRAP, WRAP).apply { if (b.iconView != null) leftMargin = gap }
    }
}

/**
 * Échelle de sintonía du lienzo : graduations, favorites en points, curseur bleu. Toucher ou glisser
 * règle la radio : [onPreview] suit le doigt, [onTune] reçoit la fréquence au relâcher.
 */
@SuppressLint("ViewConstructor")
private class TuneScale(
    c: Context,
    private val min: Int,
    private val max: Int,
    /** Pas des graduations (0 : aucune) et pas des graduations moyennes. */
    private val tick: Int,
    private val whole: Int,
    private val labels: List<Pair<Int, String>>,
    private val snap: (Int) -> Int,
) : View(c) {
    var onPreview: (Int?) -> Unit = {}
    var onTune: (Int) -> Unit = {}
    /** Fréquence sous le doigt pendant un glissé, 0 sinon. */
    var dragging = 0
        private set
    private var marker = 0
    private var dots: List<Int> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ty.num; textSize = c.pxf(Ty.CAPTION); textAlign = Paint.Align.CENTER }
    /** Marge intérieure : les libellés des extrémités restent entiers. */
    private val side = 28f

    fun set(marker: Int, dots: List<Int>) {
        if (marker == this.marker && dots == this.dots) return
        this.marker = marker
        this.dots = dots
        invalidate()
    }

    private fun x(f: Int): Float = side + (width - 2 * side) * ((f - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    private fun at(px: Float): Int = snap((min + (px - side) / (width - 2 * side) * (max - min)).toInt().coerceIn(min, max))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val base = h - 30f
        paint.color = Pal.line
        canvas.drawRoundRect(side, base - 2f, width - side, base, 1f, 1f, paint)
        if (tick > 0) {
            var t = min
            while (t <= max) {
                val major = labels.any { it.first == t }
                val mid = whole > 0 && t % whole == 0
                val th = if (major) 28f else if (mid) 18f else 12f
                paint.color = if (major) Pal.text2 else if (mid) Pal.text3 else Pal.line
                val tx = x(t)
                canvas.drawRoundRect(tx - 1f, base - 2f - th, tx + 1f, base - 2f, 1f, 1f, paint)
                t += tick
            }
        }
        txt.color = Pal.text2
        for ((f, l) in labels) {
            val half = txt.measureText(l) / 2
            canvas.drawText(l, x(f).coerceIn(half, width - half), h - 4f, txt)
        }
        val cur = if (dragging != 0) dragging else marker
        for (f in dots) {
            paint.color = if (f == cur) Pal.action else Pal.text3
            canvas.drawCircle(x(f), 6f, 6f, paint)
        }
        if (cur != 0) {
            paint.color = Pal.action
            val mx = x(cur)
            canvas.drawRoundRect(mx - 2f, 18f, mx + 2f, h - 26f, 2f, 2f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width <= 2 * side) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = at(e.x); onPreview(dragging); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val f = at(e.x)
                if (f != dragging) { dragging = f; onPreview(f); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val f = dragging
                dragging = 0
                if (f != 0) { marker = f; onTune(f) }
                onPreview(null)
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = 0; onPreview(null); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
