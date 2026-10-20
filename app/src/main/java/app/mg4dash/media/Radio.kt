package app.mg4dash.media

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import app.mg4dash.BuildConfig
import app.mg4dash.media.SaicRadio.Entry
import app.mg4dash.system.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Radio de l'écran Medios : FM, AM et DAB par le service radio de la voiture ([SaicRadio]).
 *
 * Les appels binder sont bloquants : ils passent tous par un seul fil d'arrière-plan, dans l'ordre
 * (une commande ne double jamais une relecture en cours). Chaque commande met d'abord à jour l'état
 * de façon « optimiste » (le chiffre change sous le doigt), puis l'état réel est relu. Tant que
 * l'écran est visible ([start] … [stop]), la station est relue chaque seconde ; favorites, liste et
 * logo DAB toutes les cinq secondes, ou dès que la bande ou la station change.
 *
 * Sur l'émulateur (BuildConfig.MOCK), une radio simulée remplace le service pour essayer l'écran.
 */
object Radio {
    private const val TAG = "Radio"

    const val FM = SaicRadio.BAND_FM
    const val AM = SaicRadio.BAND_AM
    const val DAB = SaicRadio.BAND_DAB

    /** FM 87,5–108 MHz au pas de 0,1 MHz ; AM 531–1602 kHz au pas de 9 kHz (canaux européens). */
    const val FM_MIN = 87_500
    const val FM_MAX = 108_000
    const val FM_STEP = 100
    const val AM_MIN = 531
    const val AM_MAX = 1_602
    const val AM_STEP = 9

    private const val POLL_MS = 1_000L
    /** Relecture complète (favorites, liste, logo) une fois sur [FULL_EVERY]. */
    private const val FULL_EVERY = 5
    /** Recherche terminée quand la station ne bouge plus depuis [SEEK_SETTLE_MS]. */
    private const val SEEK_SETTLE_MS = 1_500L
    private const val SEEK_TIMEOUT_MS = 20_000L
    /** Balayage : au moins [SCAN_MIN_MS], puis fin quand la liste ne change plus. */
    private const val SCAN_MIN_MS = 8_000L
    private const val SCAN_QUIET_MS = 4_000L
    private const val SCAN_TIMEOUT_MS = 45_000L
    private const val NO_SEARCH = 0
    private const val SEEK = 1
    private const val SCAN = 2

    data class State(
        val available: Boolean = false,
        val band: Int = FM,
        val frequencyKhz: Int = 0,
        val name: String = "",
        val text: String = "",
        val playing: Boolean = false,
        val seeking: Boolean = false,
        val favourite: Boolean = false,
        val favourites: List<Entry> = emptyList(),
        val stations: List<Entry> = emptyList(),
        val logo: Bitmap? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mg4-radio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    /** Boucle de relecture (fil principal seulement). */
    private var poll: Job? = null
    /** Commandes en attente ou en cours : aucune relecture n'est publiée tant qu'il en reste. */
    private val pending = AtomicInteger()
    @Volatile private var epoch = 0
    /** Station en cours avec ses identifiants DAB (favorites, sélection dans les listes). */
    @Volatile private var current = Entry(FM, 0, "")
    /** Dernier état connu de chaque bande : changer de bande affiche aussitôt ses favorites. */
    private val byBand = ConcurrentHashMap<Int, State>()
    @Volatile private var searchKind = NO_SEARCH
    @Volatile private var searchSince = 0L
    @Volatile private var lastMove = 0L
    /** Échecs de lecture d'affilée (fil radio seulement). */
    private var failures = 0

    // ───────── cycle de vie ─────────

    /** Écran visible : liaison au service et relecture rapide. */
    fun start(context: Context) {
        if (!BuildConfig.MOCK) SaicRadio.connect(context.applicationContext)
        if (poll?.isActive == true) return
        poll = scope.launch {
            var tick = 0
            while (isActive) {
                reload(full = tick % FULL_EVERY == 0 || searchKind != NO_SEARCH)
                tick++
                delay(POLL_MS)
            }
        }
    }

    /** Écran masqué : plus de relecture (les commandes en cours vont à leur terme). */
    fun stop() {
        poll?.cancel()
        poll = null
    }

    // ───────── commandes ─────────

    fun selectBand(band: Int) {
        val before = _state.value
        val wasSearching = endSearch()
        command(settleMs = 600, optimistic = { st ->
            if (st.band == band) st.copy(playing = true, seeking = false)
            else (byBand[band] ?: State(band = band)).copy(available = st.available, playing = true, seeking = false)
        }) {
            if (BuildConfig.MOCK) { Sim.band = band; Sim.playing = true; return@command }
            if (wasSearching) SaicRadio.cancelSeek()
            if (before.band != band || !before.available) {
                if (!SaicRadio.switchBand(band)) Log.w(TAG, "changement de bande refusé : $band")
                delay(400)
            }
            // La bande choisie doit s'entendre : on reprend la radio si elle était en pause
            val st = SaicRadio.refresh()
            if (st != null && !st.playing) SaicRadio.play()
        }
    }

    fun stepDown() = step(up = false)
    fun stepUp() = step(up = true)

    /** Réglage fin : 0,1 MHz en FM, un canal (9 kHz) en AM. */
    private fun step(up: Boolean) {
        val s = _state.value
        if (s.band == DAB) return
        val f = stepped(s.band, s.frequencyKhz, up)
        val wasSearching = endSearch()
        // Fréquence encore inconnue : pas d'affichage anticipé (le service part de sa station en cours)
        command(entry = if (f != 0) Entry(s.band, f, "") else null, optimistic = { st ->
            if (f == 0) st.copy(seeking = false)
            else st.copy(frequencyKhz = f, name = "", text = "", seeking = false,
                favourite = st.favourites.any { it.band == st.band && it.frequencyKhz == f })
        }) {
            if (BuildConfig.MOCK) { Sim.step(up); return@command }
            if (wasSearching) SaicRadio.cancelSeek()
            if (!SaicRadio.step(up)) Log.w(TAG, "réglage fin refusé")
        }
    }

    fun seekDown() = seek(up = false)
    fun seekUp() = seek(up = true)

    /**
     * Recherche de la station suivante. Un nouvel appui pendant un balayage, ou avant que la recherche
     * ait trouvé une station, l'arrête ; une fois la station trouvée (la recherche du service est souvent
     * immédiate), il relance la recherche : appuis répétés = stations suivantes, comme sur le lienzo.
     */
    private fun seek(up: Boolean) {
        if (searchKind == SCAN || (searchKind == SEEK && lastMove == searchSince)) { cancelSearch(); return }
        beginSearch(SEEK)
        command(optimistic = { it.copy(seeking = true) }) {
            if (BuildConfig.MOCK) Sim.seek(up)
            else if (!SaicRadio.seek(up)) { Log.w(TAG, "recherche refusée"); searchKind = NO_SEARCH }
        }
    }

    /** Station précédente (DAB : service précédent de la liste). */
    fun previous() {
        endSearch()
        command { if (BuildConfig.MOCK) Sim.skip(-1) else if (!SaicRadio.previous()) Log.w(TAG, "précédente refusée") }
    }

    /** Station suivante (DAB : service suivant de la liste). */
    fun next() {
        endSearch()
        command { if (BuildConfig.MOCK) Sim.skip(1) else if (!SaicRadio.next()) Log.w(TAG, "suivante refusée") }
    }

    fun playPause() {
        val playing = _state.value.playing
        command(optimistic = { it.copy(playing = !playing) }) {
            if (BuildConfig.MOCK) Sim.playing = !playing
            else if (!(if (playing) SaicRadio.pause() else SaicRadio.play())) Log.w(TAG, "lecture/pause refusée")
        }
    }

    /** Station d'une liste (favorites ou stations trouvées) : FM/AM par fréquence, DAB par identifiants. */
    fun tune(e: Entry) {
        val wasSearching = endSearch()
        val before = current
        command(settleMs = 500, entry = e, optimistic = { st ->
            val base = if (st.band == e.band) st else (byBand[e.band] ?: State(band = e.band)).copy(available = st.available)
            base.copy(frequencyKhz = e.frequencyKhz, name = e.name, text = "", seeking = false, playing = true,
                favourite = base.favourites.any { same(it, e) },
                logo = if (e.band == DAB && same(e, before)) base.logo else null)
        }) {
            if (BuildConfig.MOCK) { Sim.tune(e); return@command }
            if (wasSearching) SaicRadio.cancelSeek()
            if (!SaicRadio.tuneEntry(e)) Log.w(TAG, "station refusée : ${e.name} ${e.frequencyKhz}")
        }
    }

    /** Ajoute la station en cours aux favorites de la voiture, ou l'en retire. */
    fun toggleFavourite() {
        val s = _state.value
        val cur = current
        if ((!s.available && !BuildConfig.MOCK) || cur.band != s.band || (cur.frequencyKhz == 0 && cur.name.isBlank())) return
        val match = s.favourites.firstOrNull { same(it, cur) }
        command(optimistic = { st ->
            st.copy(favourite = match == null, favourites = if (match != null) st.favourites - match else st.favourites + cur)
        }) {
            if (BuildConfig.MOCK) { Sim.toggleFavourite(cur, match != null); return@command }
            val ok = if (match != null) SaicRadio.removeFavourite(match) else SaicRadio.addFavourite(cur)
            if (!ok) Log.w(TAG, "favorite refusée : ${cur.name} ${cur.frequencyKhz}")
        }
    }

    /** Balayage de la bande (liste des stations ; DAB : nouvelle recherche des multiplex). */
    fun scan() {
        if (searchKind != NO_SEARCH) { cancelSearch(); return }
        val band = _state.value.band
        beginSearch(SCAN)
        command(optimistic = { it.copy(seeking = true) }) {
            if (BuildConfig.MOCK) Sim.scan(band)
            else if (!SaicRadio.scan(band)) { Log.w(TAG, "balayage refusé"); searchKind = NO_SEARCH }
        }
    }

    /** Cette station est-elle celle qu'on écoute ? (DAB : par identifiant de service) */
    fun isCurrent(e: Entry): Boolean = same(e, current)

    // ───────── mécanique ─────────

    /**
     * Commande radio : [optimistic] (et [entry], la station visée) s'affichent tout de suite, [action]
     * part sur le fil radio, puis l'état réel est relu.
     */
    private fun command(settleMs: Long = 300, entry: Entry? = null, optimistic: ((State) -> State)? = null, action: suspend () -> Unit) {
        pending.incrementAndGet()
        epoch++
        if (entry != null) current = entry
        if (optimistic != null) _state.update(optimistic)
        scope.launch {
            try {
                action()
                // le service applique la commande avant qu'on relise la station
                if (!BuildConfig.MOCK) delay(settleMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "commande radio : ${e.message}")
            } finally {
                pending.decrementAndGet()
            }
            reload(full = true)
        }
    }

    private fun beginSearch(kind: Int) {
        val now = SystemClock.uptimeMillis()
        searchKind = kind; searchSince = now; lastMove = now
    }

    /** Oublie la recherche en cours ; renvoie true s'il y en avait une (à annuler côté radio). */
    private fun endSearch(): Boolean {
        val was = searchKind != NO_SEARCH
        searchKind = NO_SEARCH
        return was
    }

    private fun cancelSearch() {
        endSearch()
        command(optimistic = { it.copy(seeking = false) }) {
            if (BuildConfig.MOCK) Sim.cancel() else if (!SaicRadio.cancelSeek()) Log.w(TAG, "arrêt de la recherche refusé")
        }
    }

    /** Relecture (fil radio). Rien n'est publié si une commande est arrivée entre-temps. */
    private fun reload(full: Boolean) {
        if (pending.get() > 0) return
        val e = epoch
        val read: Pair<State, Entry>? = try {
            if (BuildConfig.MOCK) readMock() else readCar(_state.value, full)
        } catch (ex: Exception) {
            Log.w(TAG, "lecture radio : ${ex.message}"); null
        }
        if (read == null || pending.get() > 0 || e != epoch) return
        current = read.second
        _state.value = read.first
        byBand[read.first.band] = read.first
    }

    private fun readCar(prev: State, full: Boolean): Pair<State, Entry>? {
        val st = SaicRadio.refresh()
        if (st == null) {
            // un échec isolé ne fait pas clignoter l'écran ; service absent : radio indisponible
            failures++
            return if (!SaicRadio.ready || failures >= 3) prev.copy(available = false, seeking = false) to current else null
        }
        failures = 0
        val band = st.band.takeIf { it == FM || it == AM || it == DAB } ?: prev.band
        val newBand = band != prev.band || !prev.available
        val entry = Entry(band, st.frequencyKhz, st.name, st.serviceId, st.ensembleId, st.subId)
        val moved = !same(entry, current) || entry.frequencyKhz != current.frequencyKhz
        val favourites = if (full || newBand) SaicRadio.favourites(band) else prev.favourites
        val stations = if (full || newBand) SaicRadio.stations(band) else prev.stations
        val logo = when {
            band != DAB -> null
            moved || newBand || (full && prev.logo == null) -> SaicRadio.dabLogo()
            else -> prev.logo
        }
        val seeking = stillSearching(moved || newBand, stations != prev.stations)
        return State(
            available = true, band = band, frequencyKhz = st.frequencyKhz, name = st.name, text = st.text,
            playing = st.playing, seeking = seeking, favourite = favourites.any { same(it, entry) },
            favourites = favourites, stations = stations, logo = logo,
        ) to entry
    }

    /** Le service ne dit pas quand la recherche finit : on la croit finie quand plus rien ne bouge. */
    private fun stillSearching(moved: Boolean, listChanged: Boolean): Boolean {
        val kind = searchKind
        if (kind == NO_SEARCH) return false
        val now = SystemClock.uptimeMillis()
        if (moved || listChanged) lastMove = now
        val age = now - searchSince
        val quiet = now - lastMove
        val done = if (kind == SEEK) age > SEEK_TIMEOUT_MS || (age > SEEK_SETTLE_MS && quiet > SEEK_SETTLE_MS)
        else age > SCAN_TIMEOUT_MS || (age > SCAN_MIN_MS && quiet > SCAN_QUIET_MS)
        if (done) searchKind = NO_SEARCH
        return !done
    }

    private fun readMock(): Pair<State, Entry> {
        if (!Sim.searching) searchKind = NO_SEARCH
        return Sim.read()
    }

    private fun stepped(band: Int, f: Int, up: Boolean): Int {
        if (f == 0) return 0
        return when (band) {
            AM -> (f + if (up) AM_STEP else -AM_STEP).let { if (it > AM_MAX) AM_MIN else if (it < AM_MIN) AM_MAX else it }
            FM -> (f + if (up) FM_STEP else -FM_STEP).let { if (it > FM_MAX) FM_MIN else if (it < FM_MIN) FM_MAX else it }
            else -> f
        }
    }

    /** Même station : FM/AM par fréquence ; DAB par service (à défaut, par nom). */
    private fun same(a: Entry, b: Entry): Boolean {
        if (a.band != b.band) return false
        if (a.band != DAB) return a.frequencyKhz != 0 && a.frequencyKhz == b.frequencyKhz
        if (a.serviceId != 0L && b.serviceId != 0L) return a.serviceId == b.serviceId && (a.subId == 0 || b.subId == 0 || a.subId == b.subId)
        return a.name.isNotBlank() && a.name.trim().equals(b.name.trim(), ignoreCase = true)
    }

    // ───────── radio simulée (émulateur) ─────────

    /** Radio fictive : n'est touchée que depuis le fil radio (commandes et relectures). */
    private object Sim {
        val fm = listOf(
            Entry(FM, 88_200, "Radio Nacional"), Entry(FM, 90_300, "Radio 5"), Entry(FM, 93_200, "Radio 3"),
            Entry(FM, 96_500, "Radio Clásica"), Entry(FM, 99_100, "Onda Litoral"), Entry(FM, 101_700, "Ritmo FM"),
            Entry(FM, 104_300, "Radio Sierra"), Entry(FM, 106_300, "Cadena Sur"),
        )
        val am = listOf(
            Entry(AM, 585, "Radio Nacional"), Entry(AM, 621, "Emisora AM"), Entry(AM, 855, "Onda Media Centro"),
            Entry(AM, 1_107, ""), Entry(AM, 1_251, "Radio Comarca"),
        )
        val dab = listOf(
            Entry(DAB, 225_648, "Radio Nacional", 0xE201L, 0xE001, 1), Entry(DAB, 225_648, "Radio 3", 0xE202L, 0xE001, 2),
            Entry(DAB, 225_648, "Radio Clásica", 0xE203L, 0xE001, 3), Entry(DAB, 225_648, "Radio 5", 0xE204L, 0xE001, 4),
            Entry(DAB, 225_648, "Radio Exterior", 0xE205L, 0xE001, 5), Entry(DAB, 227_360, "Onda Digital", 0xE301L, 0xE002, 1),
        )
        private val texts = mapOf(
            "Radio Nacional" to "RNE · Radio Nacional", "Radio 3" to "Radio 3 · RTVE", "Radio Clásica" to "Beethoven · Sinfonía n.º 7",
            "Radio 5" to "Radio 5 · Todo noticias", "Radio Exterior" to "Radio Exterior · RTVE", "Onda Litoral" to "Onda Litoral · Música sin pausa",
            "Radio Sierra" to "El tiempo en la sierra a las 8:00",
        )
        var band = FM
        var playing = true
        @Volatile var searching = false
        private var cancelled = false
        private var fmFreq = 96_500
        private var amFreq = 621
        private var dabIndex = 2
        private val favourites = hashMapOf(
            FM to mutableListOf(fm[0], fm[2], fm[3], fm[1], fm[4]),
            AM to mutableListOf(am[1], am[0]),
            DAB to mutableListOf(dab[0], dab[2], dab[1]),
        )
        private val stations = hashMapOf(FM to fm.take(6), AM to am.take(3), DAB to dab.take(5))
        private val logos = HashMap<Long, Bitmap>()

        fun read(): Pair<State, Entry> {
            val e = entry()
            val favs = favourites[band].orEmpty().toList()
            val text = texts[e.name].orEmpty()
            return State(
                available = true, band = band, frequencyKhz = e.frequencyKhz, name = e.name,
                text = if (band == AM) "" else text, playing = playing, seeking = searching,
                favourite = favs.any { same(it, e) }, favourites = favs, stations = stations[band].orEmpty(),
                logo = if (band == DAB) logo(e) else null,
            ) to e
        }

        private fun entry(): Entry = when (band) {
            AM -> am.firstOrNull { it.frequencyKhz == amFreq } ?: Entry(AM, amFreq, "")
            DAB -> dab[dabIndex]
            else -> fm.firstOrNull { it.frequencyKhz == fmFreq } ?: Entry(FM, fmFreq, "")
        }

        fun step(up: Boolean) {
            if (band == AM) amFreq = stepped(AM, amFreq, up) else if (band == FM) fmFreq = stepped(FM, fmFreq, up)
        }

        /** Recherche : la station suivante de la liste, après un petit temps de balayage. */
        suspend fun seek(up: Boolean) {
            if (band == DAB) { skip(if (up) 1 else -1); return }
            searching = true; cancelled = false
            delay(1_200)
            if (!cancelled) {
                val list = (if (band == AM) am else fm).map { it.frequencyKhz }.sorted()
                val f = if (band == AM) amFreq else fmFreq
                val to = if (up) list.firstOrNull { it > f } ?: list.first() else list.lastOrNull { it < f } ?: list.last()
                if (band == AM) amFreq = to else fmFreq = to
            }
            searching = false
        }

        suspend fun scan(band: Int) {
            searching = true; cancelled = false
            delay(3_000)
            if (!cancelled) stations[band] = when (band) { AM -> am; DAB -> dab; else -> fm }
            searching = false
        }

        fun cancel() { cancelled = true; searching = false }

        /** Précédente / suivante : parcourt les favorites (DAB : la liste des services). */
        fun skip(dir: Int) {
            if (band == DAB) { dabIndex = Math.floorMod(dabIndex + dir, dab.size); return }
            val list = favourites[band].orEmpty().ifEmpty { stations[band].orEmpty() }
            if (list.isEmpty()) return
            val i = list.indexOfFirst { same(it, entry()) }
            tune(list[Math.floorMod((if (i < 0) 0 else i) + dir, list.size)])
        }

        fun tune(e: Entry) {
            band = e.band; playing = true
            when (e.band) {
                AM -> amFreq = e.frequencyKhz
                DAB -> dabIndex = dab.indexOfFirst { same(it, e) }.coerceAtLeast(0)
                else -> fmFreq = e.frequencyKhz
            }
        }

        fun toggleFavourite(cur: Entry, remove: Boolean) {
            val list = favourites.getOrPut(cur.band) { mutableListOf() }
            if (remove) list.removeAll { same(it, cur) } else if (list.none { same(it, cur) }) list.add(cur)
        }

        /** Logo fictif d'un service DAB (dégradé + initiales), comme une image diffusée en DAB+. */
        private fun logo(e: Entry): Bitmap = logos.getOrPut(e.serviceId) {
            val size = 240
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val colors = listOf(0xFFE53935 to 0xFF8E24AA, 0xFF1E88E5 to 0xFF00ACC1, 0xFFFB8C00 to 0xFFE53935, 0xFF43A047 to 0xFF00897B, 0xFF5E35B1 to 0xFF1E88E5)
            val (a, b) = colors[(e.serviceId % colors.size).toInt()]
            p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), a.toInt(), b.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
            p.shader = null
            p.color = 0xFFFFFFFF.toInt()
            p.textAlign = android.graphics.Paint.Align.CENTER
            p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            val initials = e.name.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.take(3).uppercase()
            p.textSize = size * 0.34f
            c.drawText(initials, size / 2f, size * 0.56f, p)
            p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            p.textSize = size * 0.14f
            c.drawText("DAB+", size / 2f, size * 0.80f, p)
            bmp
        }
    }
}
