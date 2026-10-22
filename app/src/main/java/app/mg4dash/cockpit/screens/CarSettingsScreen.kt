package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.CarSettings
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.col
import app.mg4dash.cockpit.header
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.system.WheelKeys
import app.mg4dash.system.WheelKeys.Action
import app.mg4dash.system.WheelKeys.Press
import app.mg4dash.system.WheelKeys.Star

/** Ajustes › Volante y luces : boutons étoile du volant, feux et mode remorque. */
class CarSettingsScreen(host: Host) : Screen(host) {
    override val nav = Nav.SETTINGS
    override val climateBar = false

    private val bindings = LinkedHashMap<Pair<Star, Press>, Btn>()
    private val actions = LinkedHashMap<Action, Btn>()
    private val toggles = LinkedHashMap<CarSettings.Toggle, Btn>()
    private var selected = Star.RIGHT to Press.DOUBLE

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card(gap = 12).apply {
            addView(c.header("Botones del volante", Btn(c, "Volver", Icons.chevronLeft, 24, vertical = false, textSize = Ty.CAPTION).apply {
                setPadding(14, 0, 20, 0); setOnClickListener { host.go("settings") }
            }, 56))
            addView(c.row(Dim.GAP).apply {
                // à gauche : les six appuis ; à droite : ce que fait celui qui est choisi
                addView(c.col(8).apply {
                    Star.entries.forEach { star ->
                        addView(c.label(star.title))
                        Press.entries.forEach { press ->
                            val b = Btn(c, press.title, null, vertical = false, sub = "—").apply { setOnClickListener { select(star to press) } }
                            bindings[star to press] = b
                            addView(b, lp(MATCH, 64))
                        }
                    }
                }, lp(0, WRAP, 1f))
                addView(c.col(8).apply {
                    addView(c.label("Hace"))
                    Action.entries.chunked(2).forEach { pair ->
                        addView(c.row(8).apply {
                            pair.forEach { a ->
                                val b = Btn(c, a.title, null, vertical = false, textSize = Ty.CAPTION).apply { setOnClickListener { assign(a) } }
                                actions[a] = b
                                addView(b, lp(0, MATCH, 1f))
                            }
                            if (pair.size == 1) addView(View(c), lp(0, MATCH, 1f))
                        }, lp(MATCH, 64))
                    }
                }, lp(0, WRAP, 1.2f))
            }, lp(MATCH, 0, 1f))
            addView(c.text("La función de MG de cada estrella sigue activa: ponla en «Desactivar» en Ajustes MG para que solo haga lo de aquí.", Ty.CAPTION, Pal.text2).apply { maxLines = 2 })
        }, w(1080))
        addView(c.card(gap = 12).apply {
            addView(c.label("Luces"))
            CarSettings.LIGHTS.forEach { addView(toggle(it), lp(MATCH, Dim.TAP)) }
            addView(c.label("Remolque"))
            addView(toggle(CarSettings.Toggle.TOWING), lp(MATCH, Dim.TAP))
            addView(View(c), lp(MATCH, 0, 1f))
        }, w(660))
    }

    private fun toggle(t: CarSettings.Toggle) = Btn(c, t.title, null, vertical = false, sub = t.sub, led = true).apply {
        accent = Pal.action
        setOnClickListener {
            val cur = CarSettings.state.value[t] ?: run { host.toast("${t.title}: sin respuesta del coche", tone = Pal.alert); return@setOnClickListener }
            CarSettings.set(t, !cur); paint()
        }
        toggles[t] = this
    }

    private fun select(b: Pair<Star, Press>) { selected = b; paint() }

    private fun assign(a: Action) {
        WheelKeys.bind(c, selected.first, selected.second, a)
        paint()
    }

    override fun onShow() { CarSettings.refresh(); paint() }
    override fun bind(s: VehicleState) = paint()

    private fun paint() {
        bindings.forEach { (k, b) ->
            b.look = if (k == selected) Look.SELECTED else Look.NEUTRAL
            b.setSub(WheelKeys.binding(c, k.first, k.second).title)
        }
        val current = WheelKeys.binding(c, selected.first, selected.second)
        actions.forEach { (a, b) -> b.look = if (a == current) Look.SELECTED else Look.NEUTRAL }
        val st = CarSettings.state.value
        toggles.forEach { (t, b) ->
            val v = st[t]
            b.on = v == true
            b.alpha = if (v == null) 0.5f else 1f
            if (v == null) b.setSub("Sin respuesta del coche") else b.setSub(t.sub)
        }
    }
}
