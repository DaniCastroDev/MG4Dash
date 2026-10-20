package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.rounded
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.system.BtControl
import app.mg4dash.system.Hotspot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Ajustes › Conexiones : Bluetooth (teléfonos emparejados, conectar con un toque, preferido y
 * reconexión al arrancar), punto de acceso Wi-Fi y acceso a los ajustes de Android.
 */
class ConnectionsScreen(host: Host) : Screen(host) {
    override val nav = Nav.SETTINGS
    override val climateBar = false

    private lateinit var btPill: Pill
    private lateinit var btPower: Btn
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var reconnect: Btn
    private var listKey = ""
    private class Row(val view: View, val sub: TextView, val pref: Btn, val action: Btn)
    private val rows = LinkedHashMap<String, Row>()

    private lateinit var apPill: Pill
    private lateinit var apSwitch: Btn
    private lateinit var ssid: TextView
    private lateinit var pass: TextView
    private lateinit var showPass: Btn
    private var passShown = false
    private var job: Job? = null

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        // ───────── Bluetooth ─────────
        addView(c.card(gap = 14).apply {
            addView(c.row(14).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(IconView(c, Icons.bluetooth, 34).apply { color = Pal.action })
                addView(c.text("Bluetooth", Ty.TITLE, Pal.text, Ty.uiMedium))
                btPill = Pill(c, "—", Pal.text3); addView(btPill, lp(WRAP, 44))
                addView(View(c), lp(0, 1, 1f))
                btPower = Btn(c, "Activado", null, vertical = false, textSize = Ty.LABEL, led = true).apply {
                    accent = Pal.action
                    setOnClickListener {
                        val on = !BtControl.state.value.on
                        if (!BtControl.setEnabled(on)) host.toast("No se pudo ${if (on) "encender" else "apagar"} el Bluetooth", tone = Pal.alert)
                    }
                }
                addView(btPower, lp(220, Dim.TAP_MIN))
            })
            addView(c.label("Teléfonos emparejados"))
            list = c.col(10)
            empty = c.text("", Ty.LABEL, Pal.text2).apply { maxLines = 3 }
            addView(ScrollView(c).apply {
                isVerticalScrollBarEnabled = false
                addView(c.col(10).apply { addView(empty); addView(list) })
            }, lp(MATCH, 0, 1f))
            addView(c.row(Dim.GAP).apply {
                reconnect = Btn(c, "Reconectar al arrancar", null, vertical = false, textSize = Ty.LABEL,
                    sub = "Con el preferido si no vuelve solo", led = true).apply {
                    accent = Pal.energy
                    setOnClickListener { BtControl.autoReconnect = !BtControl.autoReconnect }
                }
                addView(reconnect, lp(0, Dim.TAP, 1f))
                addView(Btn(c, "Emparejar otro teléfono", Icons.plus, 28, vertical = false, textSize = Ty.LABEL, chevron = true).apply {
                    setOnClickListener { open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
                }, lp(0, Dim.TAP, 1f))
            })
        }, w(1040))

        // ───────── punto de acceso + ajustes de Android ─────────
        addView(c.col(Dim.GAP).apply {
            addView(c.card(gap = 14).apply {
                addView(c.row(14).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(IconView(c, Icons.wifi, 34).apply { color = Pal.action })
                    addView(c.text("Punto de acceso", Ty.TITLE, Pal.text, Ty.uiMedium), lp(0, WRAP, 1f))
                    apPill = Pill(c, "—", Pal.text3); addView(apPill, lp(WRAP, 44))
                })
                apSwitch = Btn(c, "Activar punto de acceso", null, vertical = false, textSize = Ty.LABEL,
                    sub = "Para Android Auto inalámbrico y otros dispositivos", led = true).apply {
                    accent = Pal.energy
                    setOnClickListener {
                        val on = !Hotspot.state.value.on
                        if (!Hotspot.set(on)) host.toast("El coche no deja cambiarlo desde aquí", "Usa los ajustes de Android", Pal.alert)
                    }
                }
                addView(apSwitch, lp(MATCH, Dim.TAP))
                ssid = c.text("—", Ty.TITLE, Pal.text, Ty.num).apply { maxLines = 1 }
                addView(c.statRow("Nombre", ssid))
                addView(c.row(12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(c.text("Contraseña", Ty.LABEL, Pal.text2), lp(0, WRAP, 1f))
                    pass = c.text("—", Ty.TITLE, Pal.text, Ty.num).apply { maxLines = 1 }; addView(pass)
                    showPass = Btn(c, "Ver", null, textSize = Ty.LABEL).apply { setOnClickListener { passShown = !passShown; render() } }
                    addView(showPass, lp(110, 64))
                })
                addView(View(c), lp(MATCH, 0, 1f))
                addView(Btn(c, "Cambiar nombre o contraseña", Icons.settings, 28, vertical = false, textSize = Ty.LABEL, chevron = true).apply {
                    setOnClickListener { open(android.provider.Settings.ACTION_WIRELESS_SETTINGS, "com.android.settings.TetherSettings") }
                }, lp(MATCH, Dim.TAP))
            }, lp(MATCH, 0, 1f))
            addView(c.row(Dim.GAP).apply {
                addView(Btn(c, "Volver a Ajustes", Icons.chevronLeft, 28, vertical = false, textSize = Ty.LABEL).apply {
                    setOnClickListener { host.go("settings") }
                }, lp(0, Dim.TAP, 1f))
                addView(Btn(c, "Ajustes de Android", Icons.settings, 28, vertical = false, textSize = Ty.LABEL, chevron = true).apply {
                    setOnClickListener { open(android.provider.Settings.ACTION_SETTINGS) }
                }, lp(0, Dim.TAP, 1f))
            }, lp(MATCH, Dim.TAP))
        }, w(704))
    }

    private fun open(action: String, component: String? = null) {
        if (!Launch.androidSettings(c, action, component)) host.toast("Los ajustes de Android no están disponibles", tone = Pal.alert)
    }

    override fun onShow() {
        BtControl.refresh(); Hotspot.refresh()
        job?.cancel()
        val owner = c as? LifecycleOwner ?: return
        job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(BtControl.state, Hotspot.state) { _, _ -> }.collect { render() }
            }
        }
    }

    override fun onHide() { job?.cancel(); job = null }

    override fun bind(s: VehicleState) {}

    private fun render() {
        val bt = BtControl.state.value
        val cur = bt.connected
        btPill.set(when {
            !bt.available -> "No disponible"
            !bt.on -> "Apagado"
            cur != null -> "Conectado · ${cur.name}"
            else -> "Sin teléfono"
        }, when { cur != null -> Pal.energy; bt.on -> Pal.heat; else -> Pal.text3 })
        btPower.on = bt.on
        btPower.setLabel(if (bt.on) "Activado" else "Apagado")
        reconnect.on = bt.autoReconnect
        empty.put(when {
            !bt.available -> "Este equipo no tiene Bluetooth."
            !bt.on -> "Enciende el Bluetooth para ver los teléfonos."
            bt.phones.isEmpty() -> "No hay teléfonos emparejados. Empareja uno en los ajustes de Android."
            else -> ""
        })
        empty.visibility = if (empty.text.isEmpty()) View.GONE else View.VISIBLE

        val key = bt.phones.joinToString("|") { it.address + it.name }
        if (key != listKey) {
            listKey = key
            list.removeAllViews(); rows.clear()
            for (p in bt.phones) {
                val sub = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }
                val pref = Btn(c, "Preferido", null, vertical = false, textSize = Ty.CAPTION, led = true).apply {
                    accent = Pal.energy
                    setOnClickListener { BtControl.setPreferred(if (BtControl.preferred == p.address) null else p.address) }
                }
                val action = Btn(c, "Conectar", null, textSize = Ty.LABEL).apply {
                    setOnClickListener {
                        val now = BtControl.state.value.phones.firstOrNull { it.address == p.address }
                        val ok = if (now?.connected == true) BtControl.disconnect(p.address) else BtControl.connect(p.address)
                        if (!ok) host.toast("El coche no aceptó la orden", tone = Pal.alert)
                    }
                }
                val v = c.row(14).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Pal.surface2, Dim.BTN_R); setPadding(20, 0, 12, 0)
                    addView(IconView(c, Icons.phone, 30).apply { color = Pal.text2 })
                    addView(c.col(2).apply {
                        addView(c.text(p.name, Ty.LABEL, Pal.text, Ty.uiMedium).apply { maxLines = 1 })
                        addView(sub)
                    }, lp(0, WRAP, 1f))
                    addView(pref, lp(190, 64))
                    addView(action, lp(200, 64))
                }
                list.addView(v, lp(MATCH, Dim.TAP))
                rows[p.address] = Row(v, sub, pref, action)
            }
        }
        for (p in bt.phones) {
            val r = rows[p.address] ?: continue
            val busy = bt.busy == p.address
            r.sub.put(when {
                busy -> "Un momento…"
                p.audio && p.calls -> "Conectado · audio y llamadas"
                p.audio -> "Conectado · audio"
                p.calls -> "Conectado · llamadas"
                else -> "Emparejado"
            })
            r.sub.setTextColor(if (p.connected) Pal.energy else Pal.text2)
            r.pref.on = p.preferred
            r.action.setLabel(if (p.connected) "Desconectar" else "Conectar")
            r.action.look = when { busy -> Look.DISABLED; p.connected -> Look.NEUTRAL; else -> Look.PRIMARY }
        }

        val ap = Hotspot.state.value
        apPill.set(when {
            !ap.available -> "No disponible"
            ap.changing -> "Cambiando…"
            ap.failed -> "Error"
            ap.on -> "Activo"
            else -> "Apagado"
        }, when { ap.on -> Pal.energy; ap.failed -> Pal.alert; ap.changing -> Pal.heat; else -> Pal.text3 })
        apSwitch.on = ap.on
        apSwitch.setLabel(if (ap.on) "Punto de acceso activo" else "Activar punto de acceso")
        apSwitch.look = if (!ap.available || ap.changing) Look.DISABLED else Look.NEUTRAL
        ssid.put(ap.ssid.ifEmpty { "—" })
        pass.put(when { ap.password.isEmpty() -> "—"; passShown -> ap.password; else -> "•".repeat(ap.password.length.coerceIn(8, 12)) })
        showPass.setLabel(if (passShown) "Ocultar" else "Ver")
        showPass.visibility = if (ap.password.isEmpty()) View.GONE else View.VISIBLE
    }
}
