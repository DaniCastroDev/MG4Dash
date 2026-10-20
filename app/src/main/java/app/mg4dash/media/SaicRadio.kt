package app.mg4dash.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import app.mg4dash.system.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Radio des firmwares ancien SDK (SWI68…) : pas de MediaBrowserService ni de session média,
 * seulement le service `com.saicmotor.service.radio` et son interface AIDL `IRadioAppService`.
 * Codes de transaction relevés dans IRadioAppService$Stub du RadioService d'origine (SWI68 R46) :
 * 1 à 52 sans trou, tous à deux sens (flags 0, réponse précédée de writeNoException).
 *
 * Tous les appels sont des transactions binder bloquantes : à faire hors du fil principal. Un échec
 * renvoie false, une liste vide ou null et laisse une ligne de journal, jamais d'exception.
 */
object SaicRadio {
    private const val TAG = "Radio"
    private const val PKG = "com.saicmotor.service.radio"
    private const val ACTION = "com.saicmotor.service.radio.radioservice"
    private const val DESCRIPTOR = "com.saicmotor.sdk.radio.IRadioAppService"
    /** Appli radio d'origine (écran ouvert en touchant la carte). */
    const val HMI_PKG = "com.saicmotor.hmi.radio"

    private const val TX_ADD_COLLECTION = 3         // void addCollection(RadioBean)
    private const val TX_CANCEL_COLLECTION = 4      // void cancelCollection(int type, int kHz)
    private const val TX_CANCEL_DAB_COLLECTION = 5  // void cancelDabCollection(int sid, int eid)
    private const val TX_COLLECTION_LIST = 7        // List<RadioBean> getCollectionList(int type)
    private const val TX_TUNE = 8                   // void tune(int type, int kHz)
    private const val TX_TUNE_DAB = 9               // void tuneDab(int index, long sid, int eid, int scids)
    private const val TX_SCAN = 11                  // void scan(int type, boolean forward, ITuneCallback)
    private const val TX_CANCEL_SEEK = 12           // void cancelSeek(int isStop)
    private const val TX_NEXT = 13                  // boolean next(int type)
    private const val TX_PREVIOUS = 14              // boolean previous(int type)
    private const val TX_SET_MUTED = 18             // void setMuted(boolean, int) : la « pause » du lanceur
    private const val TX_CURRENT_INFO = 19          // RadioBean getCurrentRadioInfo()
    private const val TX_LAST_RADIO_INFO = 21       // RadioBean getLastRadioInfo(int k)
    private const val TX_RADIO_LIST = 22            // List<RadioBean> getRadioList(int type)
    private const val TX_START_ACTIVITY = 26        // boolean startRadioActivity()
    private const val TX_SRC_PLAY = 27              // boolean srcPlayRadio()
    private const val TX_DAB_SWITCH_SOURCE = 41     // void radioDabSwitchSource(int source)
    private const val TX_DAB_SCAN = 44              // void radioDabScan(int direction)
    private const val TX_DAB_LOGO = 52              // Bitmap getRadioDabLogo()

    /** RadioType d'origine. */
    const val BAND_AM = 1
    const val BAND_FM = 2
    const val BAND_DAB = 4

    /** Plages de RadioConstants (kHz) ; le service ne contrôle rien, c'est au client de le faire. */
    private const val AM_MIN_KHZ = 522
    private const val AM_MAX_KHZ = 1_620
    private const val FM_MIN_KHZ = 87_500
    private const val FM_MAX_KHZ = 108_000
    /** Réglage fin : 0,1 MHz en FM, un canal de 9 kHz en AM. */
    private const val FM_STEP_KHZ = 100
    private const val AM_STEP_KHZ = 9
    /** RadioState : 0 chargement, 1 lecture, 2 arrêt. */
    private const val STATE_PLAYING = 1
    /** getLastRadioInfo n'utilise pas les RadioType : 1 = dernière DAB, 2 = dernière AM ou FM (une seule place). */
    private const val LAST_DAB = 1
    private const val LAST_AM_FM = 2
    /** Source DAB du tuner et sens « en avant » (RadioTunerDabExt). */
    private const val DAB_SOURCE = 4
    private const val DAB_FORWARD = 1
    /** Au-delà, un balayage lancé ici est considéré comme fini de lui-même. */
    private const val SCAN_MS = 60_000L

    data class Station(
        val name: String,
        val frequencyKhz: Int,
        val band: Int,
        val playing: Boolean,
        /** Texte de la station : RadioText RDS (AM/FM) ; DLS en DAB, à défaut le nom du multiplex. */
        val text: String = "",
        /** Identifiants DAB (SId, EId, SCIdS) ; 0 en AM/FM. */
        val serviceId: Long = 0,
        val ensembleId: Int = 0,
        val subId: Int = 0,
    )

    /** Station d'une liste (stations trouvées ou favorites). DAB : fréquence du multiplex, souvent 0. */
    data class Entry(
        val band: Int,
        val frequencyKhz: Int,
        val name: String,
        val serviceId: Long = 0,
        val ensembleId: Int = 0,
        val subId: Int = 0,
    )

    /**
     * RadioBean tel que le service l'envoie, champs 1 à 19 de RadioBean.writeToParcel (9 mSubStationName
     * et 10 mRssi ne sont jamais remplis par le service ; 20 à 23 sont sautés).
     */
    private data class Bean(
        val enable: Boolean,        // 1 mRadioEnable (byte = un int)
        val name: String?,          // 2 mRadioName : nom RDS, sinon « 98.50 FM » ; toujours "" en DAB
        val rds: Boolean,           // 3 mIsRds
        val radioText: String?,     // 4 mRadioCover : en fait le RadioText RDS
        val frequencyKhz: Int,      // 5 mFrequencyKhz (DAB : fréquence du multiplex, 0 si jamais écouté)
        val type: Int,              // 6 mRadioType : 1 AM, 2 FM, 4 DAB
        val state: Int,             // 7 mRadioState
        val collection: Boolean,    // 8 mIsCollection : favorite
        val ensembleId: Int,        // 11 mEnsembleId
        val serviceId: Long,        // 12 mServiceId
        val pty: Int,               // 13 mPty
        val subId: Int,             // 14 mSecondaryServiceId (SCIdS)
        val frequencyIndex: Int,    // 15 mFrequencyIndex : 1er argument de tuneDab
        val serviceName: String?,   // 16 mServiceName : nom de la station DAB (« -- » si inconnue)
        val ensembleName: String?,  // 17 mEnsembleName
        val dlPlus: String?,        // 18 mDlPlus
        val dlsText: String?,       // 19 mDlsText : texte DLS qui défile
    )

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    @Volatile var last: Station? = null
        private set
    /** Dernier RadioBean courant, renvoyé tel quel à addCollection. */
    @Volatile private var currentBean: Bean? = null
    /** Services DAB vus dans les listes : tuneDab a besoin de leur index de fréquence (champ 15). */
    private val dabBeans = ConcurrentHashMap<String, Bean>()
    /** Balayages lancés ici (0 = aucun) : cancelSeek n'agit que sur eux. */
    @Volatile private var amScanSince = 0L
    @Volatile private var dabScanSince = 0L

    private val SPACES = Regex("[\\p{Cntrl}\\s]+")
    /** Nom AM/FM sans RDS : le service y met la fréquence (« 98.50 FM », « 999 AM »). */
    private val FREQUENCY_NAME = Regex("^[0-9][0-9.,]*\\s*(MHz|kHz|FM|AM)?$", RegexOption.IGNORE_CASE)

    val ready: Boolean get() = binder?.isBinderAlive == true

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; Log.i(TAG, "service radio connecté") }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null; Log.w(TAG, "service radio déconnecté") }
    }

    fun connect(context: Context) {
        if (bound && binder != null) return
        bound = try {
            context.applicationContext.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "liaison au service radio : ${e.message}"); false }
        Log.i(TAG, "liaison au service radio : $bound")
    }

    /** Transaction brute : jeton d'interface, arguments, transact (flags 0), readException, lecture. */
    private fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.w(TAG, "transaction $code : ${e.message}"); null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "transaction $code : mémoire insuffisante"); null
        } finally { data.recycle(); reply.recycle() }
    }

    /** Transaction sans valeur de retour (void) : true si le service l'a reçue. */
    private fun send(code: Int, write: (Parcel) -> Unit = {}): Boolean = call(code, write) { true } ?: false

    // ───────── station en cours ─────────

    /**
     * Station en cours (19 getCurrentRadioInfo) : marqueur int, puis le RadioBean. AM/FM : nom RDS (ou
     * vide quand le service n'y met que la fréquence) et RadioText ; DAB : nom du service (champ 16),
     * DLS (champ 19) et identifiants. Le reste du bean (diaporama, EPG, métadonnées) n'est pas lu.
     */
    fun refresh(): Station? {
        val bean = call(TX_CURRENT_INFO) { r -> if (r.readInt() == 0) null else readBean(r, whole = false) }
        if (bean == null) return null
        currentBean = bean
        val st = stationOf(bean)
        last = st
        return st
    }

    /** 27 srcPlayRadio : reprend la station en cours (prise du focus audio). false pendant un appel Bluetooth. */
    fun play(): Boolean = call(TX_SRC_PLAY) { it.readInt() != 0 } ?: false

    /**
     * 18 setMuted(false, bande) : la pause du lanceur d'origine (les deux arguments sont ignorés : coupe
     * le son du tuner et passe l'état à 2). 28 srcPauseRadio fait de même, mais seulement si la radio est
     * la dernière source audio, d'où ce choix.
     */
    fun pause(): Boolean = send(TX_SET_MUTED) { d -> d.writeInt(0); d.writeInt(last?.band ?: BAND_FM) }

    /** 13 next(bande) : station suivante de la liste mémorisée de la bande (DAB : service suivant), en boucle. */
    fun next(): Boolean = skip(TX_NEXT, currentBand())

    /** 14 previous(bande) : station précédente de la liste mémorisée de la bande, en boucle. */
    fun previous(): Boolean = skip(TX_PREVIOUS, currentBand())

    /** 26 startRadioActivity : ouvre l'appli radio d'origine. */
    fun openScreen(): Boolean = call(TX_START_ACTIVITY) { it.readInt() != 0 } ?: false

    // ───────── réglage ─────────

    /** 8 tune(bande, kHz) : accord direct en FM ou AM (le service déduit la bande de la fréquence). */
    fun tune(band: Int, frequencyKhz: Int): Boolean {
        if (band != BAND_AM && band != BAND_FM) { Log.w(TAG, "accord : bande $band refusée"); return false }
        if (frequencyKhz !in rangeOf(band)) { Log.w(TAG, "accord : $frequencyKhz kHz hors de la bande $band"); return false }
        return send(TX_TUNE) { d -> d.writeInt(band); d.writeInt(frequencyKhz) }
    }

    /**
     * Réglage fin d'un pas (0,1 MHz en FM, 9 kHz en AM), en boucle aux bords de la bande, par 8 tune :
     * 15 step(boolean, ITuneCallback) ne fait rien dans ce service. En DAB : service voisin (13 / 14).
     */
    fun step(up: Boolean): Boolean {
        val st = refresh() ?: last ?: run { Log.w(TAG, "réglage fin : station en cours inconnue"); return false }
        if (st.band == BAND_DAB) return skip(if (up) TX_NEXT else TX_PREVIOUS, BAND_DAB)
        if (st.band != BAND_AM && st.band != BAND_FM) return false
        val range = rangeOf(st.band)
        if (st.frequencyKhz !in range) return tune(st.band, range.first)
        val inc = if (st.band == BAND_AM) AM_STEP_KHZ else FM_STEP_KHZ
        val f = st.frequencyKhz + if (up) inc else -inc
        return tune(st.band, if (f > range.last) range.first else if (f < range.first) range.last else f)
    }

    /**
     * Recherche de la station suivante qu'on capte. Le service n'a pas de vraie recherche (10 seek avance à
     * l'aveugle de 50 kHz toutes les 500 ms jusqu'au bord de la bande ; RadioTuner.seek n'est jamais appelé) :
     * on prend donc la station mémorisée (22 getRadioList) juste au-dessus ou au-dessous de la fréquence en
     * cours, en boucle, et on s'y accorde (8 tune). En DAB : service suivant ou précédent (13 / 14).
     * false si la liste de la bande est vide (balayer d'abord).
     */
    fun seek(up: Boolean): Boolean {
        val st = refresh() ?: last ?: run { Log.w(TAG, "recherche : station en cours inconnue"); return false }
        return when (st.band) {
            BAND_DAB -> skip(if (up) TX_NEXT else TX_PREVIOUS, BAND_DAB)
            BAND_AM, BAND_FM -> {
                val list = stations(st.band).map { it.frequencyKhz }.distinct().sorted()
                if (list.isEmpty()) { Log.w(TAG, "recherche : aucune station mémorisée en ${bandLabel(st.band)}"); return false }
                val f = st.frequencyKhz
                val to = if (up) list.firstOrNull { it > f } ?: list.first() else list.lastOrNull { it < f } ?: list.last()
                tune(st.band, to)
            }
            else -> false
        }
    }

    /**
     * Arrête un balayage AM lancé par [scan] : 12 cancelSeek(1) (RadioTuner.stop puis enregistrement des
     * stations trouvées). Rien à arrêter (la recherche [seek] est immédiate) : true sans transaction.
     * Le balayage DAB ne s'arrête pas (aucun appel pour cela) : false.
     */
    fun cancelSeek(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val am = amScanSince; val dab = dabScanSince
        amScanSince = 0L; dabScanSince = 0L
        if (am != 0L && now - am < SCAN_MS) return send(TX_CANCEL_SEEK) { it.writeInt(1) }
        if (dab != 0L && now - dab < SCAN_MS) { Log.w(TAG, "le balayage DAB ne peut pas être arrêté"); return false }
        return true
    }

    /**
     * Passe sur une bande, à sa dernière station. FM/AM : 21 getLastRadioInfo(2) si c'est la même bande,
     * sinon la première station de 22 getRadioList, sinon le bas de la bande, puis 8 tune. DAB :
     * 21 getLastRadioInfo(1), sinon le premier service de la liste, puis 9 tuneDab ; sans aucun service
     * connu, 41 radioDabSwitchSource(4) et 44 radioDabScan(1) pour en chercher.
     */
    fun switchBand(band: Int): Boolean = when (band) {
        BAND_AM, BAND_FM -> {
            val lastAmFm = lastRadio(LAST_AM_FM)?.takeIf { it.type == band && it.frequencyKhz in rangeOf(band) }
            tune(band, lastAmFm?.frequencyKhz ?: stations(band).firstOrNull()?.frequencyKhz ?: rangeOf(band).first)
        }
        BAND_DAB -> {
            val lastDab = lastRadio(LAST_DAB)?.takeIf { it.serviceId != 0L }
            val first = if (lastDab == null) stations(BAND_DAB).firstOrNull() else null
            when {
                lastDab != null -> { remember(lastDab); tuneDab(lastDab.frequencyIndex, lastDab.serviceId, lastDab.ensembleId, lastDab.subId) }
                first != null -> tuneEntry(first)
                else -> { Log.i(TAG, "aucun service DAB connu : recherche"); scan(BAND_DAB) }
            }
        }
        else -> { Log.w(TAG, "bande inconnue : $band"); false }
    }

    /** Accorde une station de liste : FM/AM par 8 tune, DAB par 9 tuneDab (index de fréquence, SId, EId, SCIdS). */
    fun tuneEntry(e: Entry): Boolean = when (e.band) {
        BAND_AM, BAND_FM -> tune(e.band, e.frequencyKhz)
        BAND_DAB -> {
            if (e.serviceId == 0L) { Log.w(TAG, "service DAB sans identifiant : ${e.name}"); false }
            else {
                // l'index part tel quel au tuner : on le reprend de la liste, relue si besoin
                val bean = dabBean(e) ?: run { stations(BAND_DAB); dabBean(e) } ?: run { favourites(BAND_DAB); dabBean(e) }
                if (bean == null) Log.w(TAG, "service DAB absent de la liste : ${e.name} (le service ne fera que passer en DAB)")
                tuneDab(bean?.frequencyIndex ?: 0, e.serviceId, e.ensembleId, e.subId)
            }
        }
        else -> { Log.w(TAG, "station de bande inconnue : ${e.band}"); false }
    }

    // ───────── listes et favorites ─────────

    /** Stations mémorisées d'une bande (22 getRadioList) ; FM/AM triées par fréquence. */
    fun stations(band: Int): List<Entry> {
        val list = entries(TX_RADIO_LIST, band)
        return if (band == BAND_DAB) list else list.sortedBy { it.frequencyKhz }
    }

    /**
     * Favorites d'une bande (7 getCollectionList). Le service les rend de la plus récente à la plus
     * ancienne : on les remet dans l'ordre d'ajout (une nouvelle favorite arrive en fin de liste).
     */
    fun favourites(band: Int): List<Entry> = entries(TX_COLLECTION_LIST, band).reversed()

    /**
     * 3 addCollection(RadioBean). AM/FM : seule la fréquence compte (50 favorites au plus par bande ;
     * au-delà la voiture affiche « liste pleine » et n'ajoute rien). DAB : SId et EId doivent figurer dans
     * la liste DAB du service, sinon l'ajout est ignoré ; on renvoie donc le bean reçu de cette liste.
     */
    fun addFavourite(e: Entry): Boolean {
        val bean = when (e.band) {
            BAND_DAB -> {
                if (e.serviceId == 0L) { Log.w(TAG, "favorite DAB sans identifiant : ${e.name}"); return false }
                dabBean(e) ?: run { stations(BAND_DAB); dabBean(e) }
                    ?: currentBean?.takeIf { it.type == BAND_DAB && it.serviceId == e.serviceId && it.ensembleId == e.ensembleId }
                    ?: beanOf(e)
            }
            BAND_AM, BAND_FM -> {
                if (e.frequencyKhz !in rangeOf(e.band)) { Log.w(TAG, "favorite hors bande : ${e.frequencyKhz} kHz"); return false }
                currentBean?.takeIf { it.type == e.band && it.frequencyKhz == e.frequencyKhz } ?: beanOf(e)
            }
            else -> { Log.w(TAG, "favorite de bande inconnue : ${e.band}"); return false }
        }
        return send(TX_ADD_COLLECTION) { d -> d.writeInt(1); writeBean(d, bean.copy(collection = true)) }
    }

    /** Retire une favorite : 4 cancelCollection(bande, kHz) en AM/FM, 5 cancelDabCollection((int) SId, EId) en DAB. */
    fun removeFavourite(e: Entry): Boolean = when (e.band) {
        BAND_AM, BAND_FM ->
            if (e.frequencyKhz <= 0) false
            else send(TX_CANCEL_COLLECTION) { d -> d.writeInt(e.band); d.writeInt(e.frequencyKhz) }
        BAND_DAB ->
            if (e.serviceId == 0L) false
            else send(TX_CANCEL_DAB_COLLECTION) { d -> d.writeInt(e.serviceId.toInt()); d.writeInt(e.ensembleId) }
        else -> false
    }

    /**
     * Logo du service DAB en cours (52 getRadioDabLogo : marqueur int, puis Bitmap.writeToParcel),
     * null s'il n'y en a pas ou hors DAB.
     */
    fun dabLogo(): Bitmap? {
        if (last?.band?.let { it != BAND_DAB } == true) return null
        return call(TX_DAB_LOGO) { r -> if (r.readInt() != 0) Bitmap.CREATOR.createFromParcel(r) else null }
    }

    /**
     * Relance la recherche des stations d'une bande. AM : 11 scan(1, true, null) ; le rappel n'est jamais
     * appelé (null accepté), la nouvelle liste arrive environ 3 s après la dernière station trouvée.
     * DAB : 41 radioDabSwitchSource(4) puis 44 radioDabScan(1). FM : le service ne balaie que l'AM
     * (11 règle toujours le tuner en AM, quels que soient ses arguments) ; la liste FM est tenue par le
     * tuner lui-même, donc false.
     */
    fun scan(band: Int): Boolean = when (band) {
        BAND_AM -> send(TX_SCAN) { d -> d.writeInt(BAND_AM); d.writeInt(1); d.writeStrongBinder(null) }
            .also { if (it) amScanSince = SystemClock.elapsedRealtime() }
        BAND_DAB -> (send(TX_DAB_SWITCH_SOURCE) { it.writeInt(DAB_SOURCE) } && send(TX_DAB_SCAN) { it.writeInt(DAB_FORWARD) })
            .also { if (it) dabScanSince = SystemClock.elapsedRealtime() }
        BAND_FM -> { Log.w(TAG, "balayage FM : le service radio ne balaie que l'AM"); false }
        else -> { Log.w(TAG, "balayage : bande inconnue $band"); false }
    }

    fun bandLabel(band: Int): String = when (band) { BAND_AM -> "RADIO AM"; BAND_DAB -> "RADIO DAB"; else -> "RADIO FM" }

    fun frequencyLabel(st: Station): String = when (st.band) {
        BAND_FM -> String.format(java.util.Locale.getDefault(), "%.1f MHz", st.frequencyKhz / 1000f)
        BAND_AM -> "${st.frequencyKhz} kHz"
        else -> ""
    }

    // ───────── mécanique ─────────

    /** 13 / 14 avec la bande en argument (le service lit un int : sans lui, il prendrait 0, donc la FM). */
    private fun skip(code: Int, band: Int): Boolean {
        val ok = call(code, { it.writeInt(band) }) { it.readInt() != 0 } ?: false
        if (!ok) Log.w(TAG, "station ${if (code == TX_NEXT) "suivante" else "précédente"} refusée (appel Bluetooth ?)")
        return ok
    }

    private fun currentBand(): Int =
        (refresh() ?: last)?.band?.takeIf { it == BAND_AM || it == BAND_FM || it == BAND_DAB } ?: BAND_FM

    /** 9 tuneDab : le service cherche (SId, EId) dans sa liste DAB, puis passe l'index tel quel au tuner. */
    private fun tuneDab(index: Int, sid: Long, eid: Int, scids: Int): Boolean =
        send(TX_TUNE_DAB) { d -> d.writeInt(index); d.writeLong(sid); d.writeInt(eid); d.writeInt(scids) }

    /** 21 getLastRadioInfo(k) : marqueur int puis RadioBean (dernier élément de la réponse). */
    private fun lastRadio(k: Int): Bean? =
        call(TX_LAST_RADIO_INFO, { it.writeInt(k) }) { r -> if (r.readInt() == 0) null else readBean(r, whole = false) }

    /** Liste 7 ou 22 (writeTypedList de RadioBean) convertie en stations de la bande demandée. */
    private fun entries(code: Int, band: Int): List<Entry> {
        if (band != BAND_AM && band != BAND_FM && band != BAND_DAB) return emptyList()
        val beans = call(code, { it.writeInt(band) }) { readBeans(it) } ?: return emptyList()
        if (band == BAND_DAB) beans.forEach { remember(it) }
        return beans.mapNotNull { entryOf(it, band) }.filter { it.band == band }.distinct()
    }

    private fun remember(b: Bean) {
        if (b.type == BAND_DAB && b.serviceId != 0L) dabBeans[dabKey(b.serviceId, b.ensembleId, b.subId)] = b
    }

    private fun dabKey(sid: Long, eid: Int, scids: Int) = "$sid:$eid:$scids"

    private fun dabBean(e: Entry): Bean? = dabBeans[dabKey(e.serviceId, e.ensembleId, e.subId)]
        ?: dabBeans.values.firstOrNull { it.serviceId == e.serviceId && it.ensembleId == e.ensembleId }

    private fun stationOf(b: Bean): Station = if (b.type == BAND_DAB) {
        Station(clean(b.serviceName), b.frequencyKhz, b.type, b.state == STATE_PLAYING,
            clean(b.dlsText).ifEmpty { clean(b.ensembleName) }, b.serviceId, b.ensembleId, b.subId)
    } else {
        Station(stationName(b.name), b.frequencyKhz, b.type, b.state == STATE_PLAYING, clean(b.radioText))
    }

    private fun entryOf(b: Bean, band: Int): Entry? {
        val t = if (b.type == BAND_AM || b.type == BAND_FM || b.type == BAND_DAB) b.type else band
        return if (t == BAND_DAB) {
            if (b.serviceId == 0L) null
            else Entry(BAND_DAB, b.frequencyKhz, clean(b.serviceName), b.serviceId, b.ensembleId, b.subId)
        } else {
            if (b.frequencyKhz !in rangeOf(t)) null else Entry(t, b.frequencyKhz, stationName(b.name))
        }
    }

    /** Bean minimal pour addCollection quand la station n'a pas été lue chez le service. */
    private fun beanOf(e: Entry): Bean {
        val dab = e.band == BAND_DAB
        return Bean(
            enable = true, name = if (dab) "" else e.name, rds = false, radioText = null,
            frequencyKhz = e.frequencyKhz, type = e.band, state = 0, collection = true,
            ensembleId = e.ensembleId, serviceId = e.serviceId, pty = 0, subId = e.subId, frequencyIndex = 0,
            serviceName = if (dab) e.name else null, ensembleName = null, dlPlus = null, dlsText = null,
        )
    }

    private fun rangeOf(band: Int): IntRange = if (band == BAND_AM) AM_MIN_KHZ..AM_MAX_KHZ else FM_MIN_KHZ..FM_MAX_KHZ

    /** Texte du service nettoyé : coupé au premier NUL, sans retours ni espaces de remplissage, « -- » = vide. */
    private fun clean(s: String?): String {
        if (s == null) return ""
        val t = s.substringBefore('\u0000').replace(SPACES, " ").trim()
        return if (t == "--") "" else t
    }

    /** Nom AM/FM, vide quand le service n'y a mis que la fréquence (pas de RDS). */
    private fun stationName(s: String?): String = clean(s).let { if (FREQUENCY_NAME.matches(it)) "" else it }

    /**
     * Lit un RadioBean dans l'ordre exact de RadioBean.writeToParcel (un byte occupe un int, un char[] est
     * N puis N ints). [whole] : lit aussi 20 à 23 pour atteindre l'élément suivant d'une liste.
     */
    private fun readBean(p: Parcel, whole: Boolean): Bean {
        val enable = p.readInt() != 0                       // 1 mRadioEnable
        val name = p.readString()                           // 2 mRadioName
        val rds = p.readInt() != 0                          // 3 mIsRds
        val radioText = p.readString()                      // 4 mRadioCover (RadioText RDS)
        val freq = p.readInt()                              // 5 mFrequencyKhz
        val type = p.readInt()                              // 6 mRadioType
        val state = p.readInt()                             // 7 mRadioState
        val collection = p.readInt() != 0                   // 8 mIsCollection
        p.readString()                                      // 9 mSubStationName
        p.readInt()                                         // 10 mRssi
        val eid = p.readInt()                               // 11 mEnsembleId
        val sid = p.readLong()                              // 12 mServiceId
        val pty = p.readInt()                               // 13 mPty
        val scids = p.readInt()                             // 14 mSecondaryServiceId
        val index = p.readInt()                             // 15 mFrequencyIndex
        val serviceName = p.createCharArray()?.let { String(it) }   // 16 mServiceName
        val ensembleName = p.createCharArray()?.let { String(it) }  // 17 mEnsembleName
        val dlPlus = p.createCharArray()?.let { String(it) }        // 18 mDlPlus
        val dls = p.createCharArray()?.let { String(it) }           // 19 mDlsText
        if (whole) {
            p.readInt()                                     // 20 mIsTitle
            skipTypedList(p) { Bitmap.CREATOR.createFromParcel(it).recycle() }   // 21 mDabSlideShow
            skipTypedList(p) { q ->                         // 22 mSubRadios (DabSubRadioBean)
                q.readString(); q.readString(); q.readInt(); q.readLong(); q.readInt(); q.readInt(); q.readInt()
            }
            skipParcelable(p)                               // 23 mRadioMetadata
        }
        return Bean(enable, name, rds, radioText, freq, type, state, collection, eid, sid, pty, scids, index,
            serviceName, ensembleName, dlPlus, dls)
    }

    /** writeTypedList de RadioBean : N, puis pour chaque élément 0 (nul) ou 1 suivi du bean. */
    private fun readBeans(p: Parcel): List<Bean> {
        val n = p.readInt()
        if (n <= 0) return emptyList()
        if (n > p.dataAvail() / 4) { Log.w(TAG, "liste radio incohérente ($n éléments)"); return emptyList() }
        val out = ArrayList<Bean>(n)
        try {
            repeat(n) { if (p.readInt() != 0) out += readBean(p, whole = true) }
        } catch (e: Exception) {
            Log.w(TAG, "liste radio lue en partie (${out.size}/$n) : ${e.message}")
        }
        return out
    }

    /** Saute une liste typée : N (-1 = nulle), puis pour chaque élément 0 (nul) ou 1 suivi de l'objet. */
    private inline fun skipTypedList(p: Parcel, item: (Parcel) -> Unit) {
        val n = p.readInt()
        if (n <= 0) return
        check(n <= p.dataAvail() / 4) { "liste typée incohérente ($n)" }
        repeat(n) { if (p.readInt() != 0) item(p) }
    }

    /**
     * Saute un writeParcelable (RadioMetadata en AM/FM, nul en DAB : un seul int -1). Lu par le framework
     * lui-même ; s'il échoue, saut à la main : nom de classe, puis le Bundle (longueur, magie, données).
     */
    @Suppress("DEPRECATION")
    private fun skipParcelable(p: Parcel) {
        val start = p.dataPosition()
        try {
            p.readParcelable<Parcelable>(null)
            return
        } catch (e: Exception) {
            Log.w(TAG, "métadonnées radio illisibles, saut manuel : ${e.message}")
        }
        p.setDataPosition(start)
        p.readString() ?: return
        val len = p.readInt()
        if (len > 0) { p.readInt(); p.setDataPosition(p.dataPosition() + len) }
    }

    /** Écrit un RadioBean exactement comme RadioBean.writeToParcel (booléens en writeByte = un int). */
    private fun writeBean(d: Parcel, b: Bean) {
        d.writeBool(b.enable)                               // 1 mRadioEnable
        d.writeString(b.name)                               // 2 mRadioName
        d.writeBool(b.rds)                                  // 3 mIsRds
        d.writeString(b.radioText)                          // 4 mRadioCover
        d.writeInt(b.frequencyKhz)                          // 5 mFrequencyKhz
        d.writeInt(b.type)                                  // 6 mRadioType
        d.writeInt(b.state)                                 // 7 mRadioState
        d.writeBool(b.collection)                           // 8 mIsCollection
        d.writeString(null)                                 // 9 mSubStationName
        d.writeInt(0)                                       // 10 mRssi
        d.writeInt(b.ensembleId)                            // 11 mEnsembleId
        d.writeLong(b.serviceId)                            // 12 mServiceId
        d.writeInt(b.pty)                                   // 13 mPty
        d.writeInt(b.subId)                                 // 14 mSecondaryServiceId
        d.writeInt(b.frequencyIndex)                        // 15 mFrequencyIndex
        d.writeCharArray(b.serviceName?.toCharArray())      // 16 mServiceName
        d.writeCharArray(b.ensembleName?.toCharArray())     // 17 mEnsembleName
        d.writeCharArray(b.dlPlus?.toCharArray())           // 18 mDlPlus
        d.writeCharArray(b.dlsText?.toCharArray())          // 19 mDlsText
        d.writeBool(false)                                  // 20 mIsTitle
        d.writeTypedList(emptyList<Bitmap>())               // 21 mDabSlideShow : liste vide
        d.writeTypedList(emptyList<Parcelable>())           // 22 mSubRadios : liste vide
        d.writeParcelable(null, 0)                          // 23 mRadioMetadata : nul (un int -1)
    }

    private fun Parcel.writeBool(v: Boolean) = writeByte(if (v) 1.toByte() else 0.toByte())
}
