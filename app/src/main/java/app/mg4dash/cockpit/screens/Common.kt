package app.mg4dash.cockpit.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.header
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Log

/** Ouverture des applis d'origine : noms de paquet SWI68 d'abord, puis SWI69. */
object Launch {
    val AROUND_VIEW = listOf("com.saicmotor.hmi.aroundview")
    val HVAC = listOf("com.saicmotor.hmi.hvac", "com.saicmotor.hvac")
    val RADIO = listOf("com.saicmotor.hmi.radio", "com.saicmotor.radio")
    val SETTINGS = listOf("com.saicmotor.hmi.vehiclesettings", "com.saicmotor.vehiclesetting")
    val ENERGY = listOf("com.saicmotor.hmi.launcher", "com.saicmotor.saicev")

    fun open(c: Context, packages: List<String>): Boolean {
        for (p in packages) {
            val i = c.packageManager.getLaunchIntentForPackage(p) ?: continue
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (e: Exception) { Log.w("Launch", "$p : ${e.message}") }
        }
        return false
    }

    /**
     * Ajustes de Android : la acción pedida (Bluetooth, punto de acceso…) y, si la voiture ne la
     * propose pas, la page d'accueil des réglages.
     */
    fun androidSettings(c: Context, action: String = android.provider.Settings.ACTION_SETTINGS, component: String? = null): Boolean {
        val tries = buildList {
            component?.let { add(Intent().setClassName("com.android.settings", it)) }
            add(Intent(action))
            add(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (e: Exception) { Log.w("Launch", "${i.action ?: i.component} : ${e.message}") }
        }
        return open(c, listOf("com.android.settings"))
    }
}

/** Libellé court d'une source audio de [MediaHub]. */
fun sourceName(pkg: String): String = when {
    pkg == "com.saicmotor.radio" -> "Radio"
    pkg == "com.android.bluetooth" -> "Bluetooth"
    pkg == "com.saicmotor.media" -> "USB"
    MediaHub.isAndroidAuto(pkg) -> "Android Auto"
    else -> MediaHub.sourceLabel(pkg)
}

/**
 * Carte « lecture en cours » : sources disponibles, titre, sous-titre et commandes 88–96 px.
 * Partagée par l'accueil, la conduite et la page Medios.
 */
@SuppressLint("ViewConstructor")
class MediaCard(c: Context, compact: Boolean, private val big: Boolean = false, favourites: Boolean = false) : LinearLayout(c) {
    private val source = Pill(c, "—", Pal.action)
    private var sourceKeys: List<String> = emptyList()
    private var sources: Segmented? = null
    private val sourceRow = c.row()
    private val title = c.text("—", if (big) Ty.VALUE else Ty.TITLE, Pal.text, Ty.uiMedium)
    private val subtitle = c.text("", Ty.LABEL, Pal.text2).apply { maxLines = 2 }
    private val play = Btn(c, null, Icons.play, 40).apply { look = Look.RAISED }
    private val tap = if (big) Dim.PRIMARY else Dim.TAP
    /** Favoritas de la bande en cours (radio de la voiture), un toque pour sintonizar. */
    private val favBox: LinearLayout? = if (favourites) c.col(8) else null
    private val favRow = c.row(10)
    private val favHint = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }
    private var favKey: List<String> = emptyList()

    init {
        orientation = VERTICAL
        background = app.mg4dash.cockpit.rounded(Pal.surface, Dim.CARD_R)
        setPadding(Dim.PAD, Dim.PAD, Dim.PAD, Dim.PAD)
        addView(c.header("Medios", source))
        if (!compact) addView(sourceRow, lp(MATCH, Dim.TAP_MIN).apply { topMargin = Dim.GAP })
        addView(title, lp(MATCH, WRAP).apply { topMargin = Dim.GAP })
        addView(subtitle, lp(MATCH, WRAP).apply { topMargin = 6 })
        addView(View(c), lp(MATCH, 0, 1f))
        favBox?.let { box ->
            box.addView(favHint)
            box.addView(android.widget.HorizontalScrollView(c).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                addView(favRow, android.widget.FrameLayout.LayoutParams(WRAP, Dim.TAP_MIN))
            }, lp(MATCH, Dim.TAP_MIN))
            box.visibility = GONE
            addView(box, lp(MATCH, WRAP).apply { bottomMargin = Dim.GAP })
        }
        addView(c.row(12).apply {
            addView(Btn(c, null, Icons.prev, 36).apply { setOnClickListener { MediaHub.previous() } }, lp(0, tap, 1f))
            addView(play.apply { setOnClickListener { MediaHub.playPause() } }, lp(0, tap, 1.3f))
            addView(Btn(c, null, Icons.next, 36).apply { setOnClickListener { MediaHub.next() } }, lp(0, tap, 1f))
        })
        // Toucher la carte n'ouvre plus l'appli d'origine (retour d'essai : la radio s'ouvrait) ;
        // elle reste accessible depuis la page Medios (« Radio original », « Bluetooth y USB »).
    }

    fun bind() {
        val n = MediaHub.now.value
        source.set(if (n.available) n.source.ifEmpty { "—" } else "Sin fuente", if (n.playing) Pal.energy else Pal.text3)
        title.put(n.title.ifEmpty { if (n.available) n.source else "Nada en reproducción" })
        subtitle.put(n.artist)
        play.iconView?.icon = if (n.playing) Icons.pause else Icons.play
        val keys = MediaHub.availableSources()
        if (keys != sourceKeys) {
            sourceKeys = keys
            sourceRow.removeAllViews()
            sources = Segmented(context, keys.map { sourceName(it) }, height = Dim.TAP_MIN, textSize = Ty.CAPTION) { i -> MediaHub.switchTo(sourceKeys[i]) }
            sourceRow.addView(sources, lp(MATCH, Dim.TAP_MIN))
        }
        sources?.select(keys.indexOf(MediaHub.currentSource()).takeIf { it >= 0 })
        bindFavourites()
    }

    private fun bindFavourites() {
        val box = favBox ?: return
        val r = app.mg4dash.media.Radio.state.value
        val show = MediaHub.radioActive && (r.available || app.mg4dash.BuildConfig.MOCK)
        box.visibility = if (show) VISIBLE else GONE
        if (!show) return
        val band = when (r.band) { app.mg4dash.media.Radio.AM -> "AM"; app.mg4dash.media.Radio.DAB -> "DAB"; else -> "FM" }
        favHint.put(if (r.favourites.isEmpty()) "Sin favoritas en $band · guárdalas con la estrella en Medios" else "Favoritas · $band")
        val key = r.favourites.map { "${it.band}/${it.frequencyKhz}/${it.serviceId}/${it.name}" }
        if (key != favKey) {
            favKey = key
            favRow.removeAllViews()
            for (e in r.favourites) {
                val freq = favFreq(e)
                val name = e.name.trim()
                // FM / AM : fréquence en grand, nom dessous ; DAB : nom seul
                val b = Btn(context, if (freq.isNotEmpty()) freq else name.ifEmpty { "—" }, null, textSize = Ty.LABEL,
                    sub = if (freq.isNotEmpty() && name.isNotEmpty()) name else null)
                b.labelView?.maxLines = 1
                b.setOnClickListener { app.mg4dash.media.Radio.tune(e) }
                favRow.addView(b, lp(if (freq.isNotEmpty()) 150 else 200, Dim.TAP_MIN))
            }
        }
        r.favourites.forEachIndexed { i, e ->
            val on = e.band == r.band && (if (e.band == app.mg4dash.media.Radio.DAB) e.name.trim().equals(r.name.trim(), true) else e.frequencyKhz == r.frequencyKhz)
            (favRow.getChildAt(i) as? Btn)?.let { b -> val lk = if (on) Look.SELECTED else Look.NEUTRAL; if (b.look != lk) b.look = lk }
        }
    }

    private fun favFreq(e: app.mg4dash.media.SaicRadio.Entry): String = when (e.band) {
        app.mg4dash.media.Radio.FM -> if (e.frequencyKhz > 0) "${e.frequencyKhz / 1000},${(e.frequencyKhz % 1000) / 100}" else ""
        app.mg4dash.media.Radio.AM -> if (e.frequencyKhz > 0) "${e.frequencyKhz}" else ""
        else -> ""
    }
}

/** Rangée « libellé + valeur » d'une carte de détail. */
fun Context.statRow(label: String, value: TextView): LinearLayout = row().apply {
    addView(text(label, Ty.LABEL, Pal.text2), lp(0, WRAP, 1f))
    addView(value)
}

fun Context.sectionCard(title: String) = card().apply { addView(label(title)) }
