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
    private var appContext: Context? = null

    val ready: Boolean get() = ac != null

    fun init(context: Context) {
        appContext = context.applicationContext
        connect()
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
            val listener = if (lt.isInterface) try {
                java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { _, m, _ ->
                    if (m.name == "onServiceConnected") { ac = singleton(); Log.i(TAG, "AirConditionManager connecté") }
                    null
                }
            } catch (_: Exception) { null } else null
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "AirConditionManager.init : ${e.message}") }
        }
        if (ac == null) ac = singleton()
    }

    private fun int(name: String): Int? {
        val a = ac ?: return null
        return try { a.javaClass.getMethod(name).invoke(a) as? Int } catch (_: Exception) { null }
    }

    private fun float(name: String): Float? {
        val a = ac ?: return null
        return try { a.javaClass.getMethod(name).invoke(a) as? Float } catch (_: Exception) { null }
    }

    private fun set(name: String, v: Int): Boolean {
        val a = ac ?: return false
        return try { a.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(a, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v) : ${(e.cause ?: e).message}"); false }
    }

    private fun call(name: String): Boolean {
        val a = ac ?: return false
        return try { a.javaClass.getMethod(name).invoke(a); true }
        catch (e: Exception) { Log.w(TAG, "$name() : ${(e.cause ?: e).message}"); false }
    }

    private fun prop(id: Int): Int? = CarProps.readIntArea(id, AREA_HVAC)?.takeIf { it >= 0 }

    /** Lecture de la clim dans l'état véhicule ; inchangé si le service ne répond pas. */
    fun readHvac(s: VehicleState): VehicleState {
        if (ac == null) { initDone = false; connect() }
        if (ac == null) return s
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

    /** Pour l'écran Compatibilité. */
    fun probe(): String = when {
        ac == null -> "absent"
        else -> "ok (temp ${int("getDrvTemp")}, fan ${int("getAirVolumeLevel")})"
    }
}
