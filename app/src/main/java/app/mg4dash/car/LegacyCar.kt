/*
 * MG4Dash — accès voiture des firmwares « ancien SDK » (SWI68, SWI165, SWI133).
 * Clim adaptée de MG4Hardware.kt de MG4Control (https://github.com/SliDeeN/MG4Control),
 * Copyright © 2026 Slice Denkins et les contributeurs de MG4Control, GPL v3 ou ultérieure.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.car

import android.content.Context
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log

/**
 * Clim des firmwares ancien SDK : `AirConditionManager`, chargé depuis le launcher d'origine
 * (`com.saicmotor.hmi.launcher`) puis initialisé avec un écouteur factice (le SDK refuse null).
 *
 * Non testé par l'auteur (SWI69) : tout est lu par réflexion et chaque échec rend null, l'écran
 * grise alors le réglage. Comportements relevés par MG4Control sur ces firmwares :
 *  - A/C, AUTO et recyclage sont des **appuis** : l'argument est ignoré, on avance d'un cran
 *    puis on relit ;
 *  - le recyclage se lit sur la propriété HVAC_AC_LOOP_MODE (0 intérieur, 1 extérieur, 2 auto).
 */
object LegacyCar {
    private const val TAG = "CarLegacy"
    private const val AIRCON_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.AirConditionManager"
    private const val PROP_LOOP_MODE = 0x15402507
    private const val PROP_BLOWER_DIRECTION = 0x1540250e
    private const val PROP_AC_ON = 0x15402500
    private const val AREA_HVAC = 117

    @Volatile private var ac: Any? = null
    @Volatile private var initDone = false
    @Volatile private var lastConnectTry = 0L
    private var appContext: Context? = null

    val ready: Boolean get() = ac != null

    fun init(context: Context) {
        appContext = context.applicationContext
        connect()
        connectCharging()
        connectSetting()
    }

    private fun connect() {
        if (ac != null || initDone) return
        val ctx = appContext ?: return
        initDone = true
        val launcher = listOf(Firmware.LEGACY_LAUNCHER, "com.saicmotor.launcher").firstNotNullOfOrNull { pkg ->
            try { ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { null }
        } ?: run { Log.w(TAG, "launcher SAIC (ancien SDK) introuvable"); return }
        val cls = try { launcher.classLoader.loadClass(AIRCON_CLASS) } catch (e: Exception) {
            Log.w(TAG, "AirConditionManager absent : ${e.message}"); return
        }
        fun singleton(): Any? = try { cls.getMethod("getInstance").invoke(null) } catch (_: Exception) { null }
        val initM = cls.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        if (initM != null) {
            val lt = initM.parameterTypes[1]
            val listener = serviceListener(lt, "AirConditionManager") { ac = it ?: singleton(); watchPower() }
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "AirConditionManager.init : ${e.message}") }
        }
        if (ac == null) ac = singleton()
        watchPower()
    }

    // ── Mise en marche de la clim, en direct ──
    // Même rappel que la SystemUI (AirConditionController) : signal 4001 avec HvacPowerStatus = 1. On
    // referme son panneau plein écran dans la même fraction de seconde, au lieu d'attendre la relecture.
    private const val SIGNAL_HVAC_POWER = 4001
    @Volatile private var powerCallback: Any? = null

    private fun watchPower() {
        val a = ac ?: return
        if (powerCallback != null) return
        val reg = a.javaClass.methods.firstOrNull { it.name == "registerAirConditionCallback" && it.parameterCount == 1 } ?: return
        val type = reg.parameterTypes[0].takeIf { it.isInterface } ?: return
        val cb = try {
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
                when (m.name) {
                    "onAirConditionChangeEvent" -> { onHvacEvent(args?.getOrNull(0)); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "MG4DashAirConditionCallback"
                    else -> null
                }
            }
        } catch (_: Exception) { return }
        try {
            reg.invoke(a, cb)
            powerCallback = cb
            HvacOverlay.eventDriven = true
            Log.i(TAG, "rappel clim enregistré")
        } catch (e: Exception) { Log.w(TAG, "registerAirConditionCallback : ${(e.cause ?: e).message}") }
    }

    private fun onHvacEvent(bean: Any?) {
        bean ?: return
        fun get(name: String) = try { Refl.m(bean, name).invoke(bean) as? Int } catch (_: Exception) { null }
        if (get("getMarkedSignalId") == SIGNAL_HVAC_POWER && get("getHvacPowerStatus") == 1) HvacOverlay.suppress("rappel")
    }

    private fun int(name: String): Int? {
        val a = ac ?: return null
        return try { Refl.m(a, name).invoke(a) as? Int } catch (_: Exception) { null }
    }

    private fun float(name: String): Float? {
        val a = ac ?: return null
        return try { Refl.m(a, name).invoke(a) as? Float } catch (_: Exception) { null }
    }

    private fun set(name: String, v: Int): Boolean {
        val a = ac ?: return false
        return try { Refl.m(a, name, Int::class.javaPrimitiveType).invoke(a, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v) : ${(e.cause ?: e).message}"); false }
    }

    private fun call(name: String): Boolean {
        val a = ac ?: return false
        return try { Refl.m(a, name).invoke(a); true }
        catch (e: Exception) { Log.w(TAG, "$name() : ${(e.cause ?: e).message}"); false }
    }

    private fun prop(id: Int): Int? = CarProps.readIntArea(id, AREA_HVAC)?.takeIf { it >= 0 }

    /** Lecture de la clim dans l'état véhicule ; inchangé si le service ne répond pas. */
    fun readHvac(s: VehicleState): VehicleState {
        if (ac == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastConnectTry >= 5_000) { lastConnectTry = now; initDone = false; connect() }
            if (ac == null) return s
        }
        fun b(v: Int?) = v?.takeIf { it >= 0 }?.let { it == 1 }
        return s.copy(
            connected = true,
            hvacOn = b(int("getHvacPowerStatus")),
            acOn = b(int("getAcSwitch")),
            autoOn = b(int("getAutoStatus")),
            recirc = prop(PROP_LOOP_MODE),
            fanSpeed = int("getAirVolumeLevel")?.takeIf { it >= 0 },
            fanDirection = (prop(PROP_BLOWER_DIRECTION) ?: int("getBlowerDirectionMode"))?.takeIf { it in 0..7 },
            driverTemp = int("getDrvTemp")?.takeIf { it in 10..40 }?.toFloat(),
            outsideTemp = float("getOutCarTemp")?.takeIf { !it.isNaN() && it > -60f && it < 70f } ?: s.outsideTemp,
            defrostFront = b(int("getFrontWindowDefroster")),
            defrostRear = b(int("getBackWindowDefroster")),
        )
    }

    // ── Commandes (bloquantes : appelées hors du fil principal par CarBridge) ──

    fun power(on: Boolean) = call(if (on) "openHvacPower" else "closeHvacPower")
    fun temp(c: Int) = set("setDrvTemp", c)
    fun fan(level: Int) = set("setAirVolumeLevel", level)
    fun direction(v: Int) = set("setBlowerDirectionMode", v)
    fun defrostFront(on: Boolean) = call(if (on) "openFrontWindowDefroster" else "closeFrontWindowDefroster")
    fun defrostRear(on: Boolean) = call(if (on) "openBackWindowDefroster" else "closeBackWindowDefroster")

    /** A/C : appui, relu sur HVAC_AC_ON (1 = allumé). */
    fun ac(on: Boolean) = cycle(PROP_AC_ON, if (on) 1 else 0, 2) { set("setAcStatus", 1) }

    /** AUTO : un appui l'allume ; pour en sortir, on écrit un niveau de ventilation (comme l'écran d'origine). */
    fun auto(on: Boolean): Boolean {
        val cur = int("getAutoStatus")?.let { it == 1 }
        if (cur == on) return true
        return if (on) set("setAutoStatus", 1) else fan((int("getAirVolumeLevel")?.takeIf { it in 1..10 } ?: 3))
    }

    /** Recyclage : appui qui avance d'un cran (extérieur → intérieur → auto), relu sur la propriété. */
    fun loop(target: Int) = cycle(PROP_LOOP_MODE, target, 3) { set("setLoopMode", 1) }

    private fun cycle(id: Int, target: Int, max: Int, advance: () -> Unit): Boolean {
        repeat(max + 1) {
            val cur = prop(id) ?: return false
            if (cur == target) return true
            advance()
            Thread.sleep(400)
        }
        return prop(id) == target
    }

    // ── Recharge et V2L : VehicleChargingManager du même SDK ──
    // Même logique que la voie A9 : 1 = démarrer, 2 = arrêter (signaux CHARGING / DISCHARGING
    // _CONTROL_SWITCH) ; l'état de charge suit la même table que le launcher A9.
    private const val CHARGING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleChargingManager"
    @Volatile private var charging: Any? = null
    @Volatile private var chargingInit = false

    val chargingReady: Boolean get() = charging != null

    private fun connectCharging() {
        if (charging != null || chargingInit) return
        val ctx = appContext ?: return
        chargingInit = true
        charging = sdkManager(ctx, CHARGING_CLASS) { charging = it }
    }

    /** Charge un gestionnaire du SDK SAIC depuis le launcher d'origine et l'initialise (écouteur factice). */
    internal fun sdkManager(
        ctx: Context,
        className: String,
        packages: List<String> = listOf(Firmware.LEGACY_LAUNCHER, "com.saicmotor.launcher"),
        onConnected: (Any?) -> Unit,
    ): Any? {
        val launcher = packages.firstNotNullOfOrNull { pkg ->
            try { ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { null }
        } ?: return null
        val cls = try { launcher.classLoader.loadClass(className) } catch (_: Exception) { return null }
        // Seul AirConditionManager a un getInstance() : les autres gestionnaires (VehicleChargingManager,
        // VehicleSettingManager) gardent l'instance dans un champ statique et la passent à
        // onServiceConnected(BaseManager). Sans cela, l'instance restait null et chaque relecture
        // relançait init() (« VehicleChargingManager connecté » toutes les 3 s, bouton de charge masqué).
        fun singleton(): Any? = try { cls.getMethod("getInstance").invoke(null) } catch (_: Exception) {
            cls.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == cls }
                ?.let { f -> try { f.isAccessible = true; f.get(null) } catch (_: Exception) { null } }
        }
        val name = className.substringAfterLast('.')
        val initM = cls.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        if (initM != null) {
            val listener = serviceListener(initM.parameterTypes[1], name) { onConnected(it ?: singleton()) }
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "$name.init : ${e.message}") }
        }
        return singleton()
    }

    /**
     * Écouteur IVehicleServiceListener factice (le SDK refuse null). onServiceConnected reçoit le
     * gestionnaire prêt ; les méthodes d'Object doivent répondre, le SDK range l'écouteur dans une liste.
     */
    private fun serviceListener(type: Class<*>, name: String, onConnected: (Any?) -> Unit): Any? {
        if (!type.isInterface) return null
        return try {
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
                when (m.name) {
                    "onServiceConnected" -> { onConnected(args?.getOrNull(0)); Log.i(TAG, "$name connecté"); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "MG4Dash$name"
                    else -> null
                }
            }
        } catch (_: Exception) { null }
    }

    // ── ESC : VehicleControlManager du même SDK (SWI68/165, méthodes relevées par MG4Control) ──
    // Lecture 1 = actif, 0 ou 2 = coupé ; l'écriture est une bascule (1), comme l'interrupteur d'origine.
    private const val CONTROL_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleControlManager"
    @Volatile private var control: Any? = null
    @Volatile private var controlInit = false

    private fun connectControl() {
        if (control != null || controlInit) return
        val ctx = appContext ?: return
        controlInit = true
        control = sdkManager(ctx, CONTROL_CLASS) { control = it }
    }

    /** ESC actif (true), coupé (false), ou null si ce SDK ne répond pas. */
    fun escActive(): Boolean? {
        connectControl()
        val c = control ?: return null
        val v = try { Refl.m(c, "getEspSwitch").invoke(c) as? Int } catch (_: Exception) { null } ?: return null
        return when (v) { 1 -> true; 0, 2 -> false; else -> null }
    }

    fun toggleEsc(): Boolean {
        connectControl()
        val c = control ?: return false
        return try { Refl.m(c, "setEspSwitch", Int::class.javaPrimitiveType).invoke(c, 1); true }
        catch (e: Exception) { Log.w(TAG, "setEspSwitch : ${(e.cause ?: e).message}"); false }
    }

    private fun chInt(name: String): Int? {
        val c = charging ?: return null
        return try { Refl.m(c, name).invoke(c) as? Int } catch (_: Exception) { null }
    }

    private fun chSet(name: String, v: Int): Boolean {
        val c = charging ?: return false
        return try { Refl.m(c, name, Int::class.javaPrimitiveType).invoke(c, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v) : ${(e.cause ?: e).message}"); false }
    }

    /** État de charge et commandes en cours ; inchangé si le service ne répond pas. */
    fun readCharging(s: VehicleState): VehicleState {
        if (charging == null && retryDue(::lastChargingTry)) { chargingInit = false; connectCharging() }
        if (charging == null) return s
        return s.copy(
            chargeLock = chInt("getChargingLockSwitch")?.takeIf { it in 0..2 } ?: s.chargeLock,
            chargeStatus = chInt("getChargingStatus")?.takeIf { it >= 0 } ?: s.chargeStatus,
            chargeCtrl = chInt("getChargingControlSwitch")?.takeIf { it >= 0 } ?: s.chargeCtrl,
            dischargeCtrl = chInt("getDischrgControlStatus")?.takeIf { it >= 0 } ?: s.dischargeCtrl,
            chargeRemainMin = chInt("getPredictChargingTime")?.takeIf { it in 0..6000 } ?: s.chargeRemainMin,
            dischargeRemainMin = chInt("getPredictDischrgTime")?.takeIf { it in 0..6000 } ?: s.dischargeRemainMin,
        )
    }

    fun chargeCtrl(v: Int) = chSet("setChargingControlSwitch", v)
    fun dischargeCtrl(v: Int) = chSet("setDischrgControlSwitch", v)

    /** Verrou du câble (bouton cadenas du launcher d'origine) : 1 = verrouiller, 2 = déverrouiller. */
    fun chargeLock(v: Int) = chSet("setChargingLockSwitch", v)

    @Volatile private var lastChargingTry = 0L
    @Volatile private var lastSettingTry = 0L

    /** Une nouvelle tentative d'init() au plus toutes les 30 s : chaque init() recrée une liaison au service. */
    private fun retryDue(last: kotlin.reflect.KMutableProperty0<Long>): Boolean {
        val now = System.currentTimeMillis()
        if (now - last.get() < 30_000) return false
        last.set(now)
        return true
    }

    // ── Mode de conduite : VehicleSettingManager du même SDK ──
    // Valeurs de l'appli Réglages d'origine (propriété 0x2140a17c) : 2 éco, 3 normal, 4 sport,
    // 6 neige, 7 personnalisé.
    private const val SETTING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    @Volatile private var setting: Any? = null

    private fun connectSetting() {
        if (setting != null) return
        val ctx = appContext ?: return
        setting = sdkManager(ctx, SETTING_CLASS) { setting = it }
    }

    /** Mode de conduite écrit par le service véhicule ; false si le SDK ne répond pas. */
    fun driveMode(v: Int): Boolean {
        if (setting == null && retryDue(::lastSettingTry)) connectSetting()
        val m = setting ?: return false
        return try { Refl.m(m, "setDriveMode", Int::class.javaPrimitiveType).invoke(m, v); true }
        catch (e: Exception) { Log.w(TAG, "setDriveMode($v) : ${(e.cause ?: e).message}"); false }
    }

    /** Pour l'écran Compatibilité. */
    fun probe(): String = when {
        ac == null -> "absent"
        else -> "ok (temp ${int("getDrvTemp")}, fan ${int("getAirVolumeLevel")})"
    }

    fun probeCharging(): String = if (charging == null) "absent" else "ok (status ${chInt("getChargingStatus")}, ctrl ${chInt("getChargingControlSwitch")})"
}
