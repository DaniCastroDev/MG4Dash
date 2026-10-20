package app.mg4dash.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import app.mg4dash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth de la voiture (pile AOSP de nFore, com.android.bluetooth) : téléphones appairés, connexion
 * et déconnexion, téléphone préféré et reconnexion au démarrage.
 *
 * La voiture joue le rôle « puits » : A2DP_SINK (11) pour l'audio, HEADSET_CLIENT (16) pour les appels.
 * connect / disconnect / setPriority de ces profils sont masqués : la version voiture (uid système) y a
 * accès par réflexion. Le préféré passe en PRIORITY_AUTO_CONNECT (1000), les autres en PRIORITY_ON (100),
 * pour que la pile le reconnecte d'elle-même ; MG4Dash relance en plus la connexion s'il ne revient pas.
 */
object BtControl {
    private const val TAG = "Bluetooth"
    private const val A2DP_SINK = 11
    private const val HEADSET_CLIENT = 16
    private const val PRIORITY_ON = 100
    private const val PRIORITY_AUTO_CONNECT = 1000
    /** Tentatives après le démarrage ou le réveil, si aucun téléphone n'est revenu tout seul. */
    private val RETRY_MS = longArrayOf(20_000, 45_000, 90_000)

    data class Phone(
        val address: String,
        val name: String,
        val audio: Boolean,
        val calls: Boolean,
        val preferred: Boolean,
    ) { val connected get() = audio || calls }

    data class State(
        val available: Boolean = false,
        val on: Boolean = false,
        val phones: List<Phone> = emptyList(),
        /** Adresse en cours de connexion / déconnexion. */
        val busy: String? = null,
        val autoReconnect: Boolean = true,
    ) { val connected get() = phones.firstOrNull { it.connected } }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val adapter: BluetoothAdapter? get() = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Exception) { null }
    private var mock = emptyList<Phone>()

    private val prefs get() = Prefs(app).raw
    val preferred: String? get() = prefs.getString("bt_preferred", null)
    var autoReconnect: Boolean
        get() = prefs.getBoolean("bt_autoreconnect", true)
        set(v) { prefs.edit().putBoolean("bt_autoreconnect", v).apply(); refresh(); if (v) scheduleReconnect("activée") }

    fun start(context: Context) {
        app = context.applicationContext
        if (BuildConfig.MOCK) {
            mock = listOf(
                Phone("00:11:22:33:44:55", "Pixel de Daniel", audio = true, calls = true, preferred = true),
                Phone("66:77:88:99:AA:BB", "iPhone trabajo", audio = false, calls = false, preferred = false),
                Phone("CC:DD:EE:FF:00:11", "Galaxy Tab", audio = false, calls = false, preferred = false),
            )
            refresh(); return
        }
        val a = adapter ?: run { Log.w(TAG, "pas d'adaptateur"); return }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; refresh() }
            override fun onServiceDisconnected(profile: Int) { proxies.remove(profile); refresh() }
        }
        for (p in intArrayOf(A2DP_SINK, HEADSET_CLIENT)) {
            try { a.getProfileProxy(app, listener, p) } catch (e: Exception) { Log.w(TAG, "profil $p : ${e.message}") }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction(Intent.ACTION_SCREEN_ON)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> scheduleReconnect("réveil")
                    BluetoothAdapter.ACTION_STATE_CHANGED ->
                        if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) scheduleReconnect("Bluetooth allumé")
                }
                refresh()
            }
        }, filter)
        refresh()
        scheduleReconnect("démarrage")
    }

    fun refresh() {
        if (!::app.isInitialized) return
        val busy = _state.value.busy
        if (BuildConfig.MOCK) {
            val pref = preferred ?: mock.firstOrNull { it.preferred }?.address
            _state.value = State(true, true, mock.map { it.copy(preferred = it.address == pref) }, busy, autoReconnect)
            return
        }
        val a = adapter
        if (a == null) { _state.value = State(); return }
        val on = a.isEnabled
        val pref = preferred
        val phones = if (!on) emptyList() else try {
            a.bondedDevices.orEmpty().map { d ->
                Phone(d.address, d.name ?: d.address, stateOf(A2DP_SINK, d), stateOf(HEADSET_CLIENT, d), d.address == pref)
            }.sortedWith(compareByDescending<Phone> { it.connected }.thenByDescending { it.preferred }.thenBy { it.name.lowercase() })
        } catch (e: Exception) { Log.w(TAG, "appairés : ${e.message}"); emptyList() }
        _state.value = State(true, on, phones, busy, autoReconnect)
    }

    private fun stateOf(profile: Int, d: BluetoothDevice) = try {
        proxies[profile]?.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { false }

    /** Connecte audio et appels (les autres téléphones se déconnectent d'eux-mêmes ou restent en appels). */
    fun connect(address: String): Boolean {
        markBusy(address)
        if (BuildConfig.MOCK) {
            mock = mock.map { it.copy(audio = it.address == address, calls = it.address == address) }
            main.postDelayed({ markBusy(null) }, 1_200); return true
        }
        val d = device(address) ?: return false
        var ok = false
        for ((p, proxy) in proxies) ok = call(proxy, "connect", d, p) || ok
        Log.i(TAG, "connexion ${d.name} : ${if (ok) "demandée" else "refusée"}")
        return ok
    }

    fun disconnect(address: String): Boolean {
        markBusy(address)
        if (BuildConfig.MOCK) {
            mock = mock.map { if (it.address == address) it.copy(audio = false, calls = false) else it }
            main.postDelayed({ markBusy(null) }, 800); return true
        }
        val d = device(address) ?: return false
        var ok = false
        for ((p, proxy) in proxies) ok = call(proxy, "disconnect", d, p) || ok
        Log.i(TAG, "déconnexion ${d.name} : ${if (ok) "demandée" else "refusée"}")
        return ok
    }

    /** Téléphone préféré : reconnecté en priorité par la pile et par MG4Dash. null = aucun. */
    fun setPreferred(address: String?) {
        prefs.edit().putString("bt_preferred", address).apply()
        if (!BuildConfig.MOCK) adapter?.bondedDevices?.forEach { d ->
            val prio = if (d.address == address) PRIORITY_AUTO_CONNECT else PRIORITY_ON
            for ((_, proxy) in proxies) try {
                proxy.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(proxy, d, prio)
            } catch (e: Exception) { Log.w(TAG, "priorité ${d.name} : ${e.message}") }
        }
        refresh()
    }

    fun setEnabled(on: Boolean): Boolean {
        if (BuildConfig.MOCK) return false
        val a = adapter ?: return false
        return try { if (on) a.enable() else a.disable() } catch (e: Exception) { Log.w(TAG, "allumer/éteindre : ${e.message}"); false }
    }

    private fun device(address: String) = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }

    private fun call(proxy: BluetoothProfile, name: String, d: BluetoothDevice, profile: Int) = try {
        proxy.javaClass.getMethod(name, BluetoothDevice::class.java).invoke(proxy, d) as? Boolean ?: false
    } catch (e: Exception) { Log.w(TAG, "$name (profil $profile) : ${e.cause?.message ?: e.message}"); false }

    private val clearBusy = Runnable { markBusy(null) }
    private fun markBusy(address: String?) {
        main.removeCallbacks(clearBusy)
        _state.value = _state.value.copy(busy = address)
        if (address != null) main.postDelayed(clearBusy, 15_000)
        refresh()
    }

    // ───────── reconnexion ─────────

    private val attempts = ArrayList<Runnable>()

    private fun scheduleReconnect(why: String) {
        attempts.forEach { main.removeCallbacks(it) }; attempts.clear()
        if (!autoReconnect || BuildConfig.MOCK) return
        RETRY_MS.forEachIndexed { n, ms ->
            val r = Runnable { tryReconnect("$why, essai ${n + 1}") }
            attempts += r; main.postDelayed(r, ms)
        }
    }

    private fun tryReconnect(why: String) {
        refresh()
        val s = _state.value
        val pref = preferred ?: return
        if (!autoReconnect || !s.on || s.connected != null) return
        if (s.phones.none { it.address == pref }) return
        Log.i(TAG, "reconnexion automatique ($why)")
        connect(pref)
    }
}
