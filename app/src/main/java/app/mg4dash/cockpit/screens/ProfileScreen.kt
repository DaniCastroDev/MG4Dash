package app.mg4dash.cockpit.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Adas
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.IconView
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
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
import java.util.Calendar

/**
 * Perfiles de conducción y asistentes (lienzo : ADAS.dc.html). À gauche, côté conducteur, la carte
 * d'action : choix du perfil, « Aplicar », puis « Aplicado · Deshacer » ou « Ya coincide ». À droite,
 * le tableau « ahora » face au perfil. Applicable à l'arrêt comme en roulant.
 */
class AdasScreen(host: Host) : Screen(host) {
    override val nav = Nav.VEHICLE
    override val climateBar = false

    private class Row(val now: TextView, val seg: Segmented)
    private val driveRows = HashMap<Adas.Drive, Row>()
    private val adasRows = HashMap<Adas.Item, Row>()
    private lateinit var profiles: Segmented
    private lateinit var applyBtn: Btn
    private lateinit var appliedRow: View
    private lateinit var appliedSub: TextView
    private lateinit var matchBox: View

    /** Titres courts et sous-titres du lienzo. */
    private val titles = mapOf(
        Adas.Item.SPEED_SOUND to ("Aviso de límite de velocidad" to "Sonido del aviso"),
        Adas.Item.LANE to ("Mantenimiento de carril" to ""),
        Adas.Item.DRIVER_MONITOR to ("Monitorización del conductor" to "Avisos"),
        Adas.Item.UNSTEADY to ("Aviso de conducción inestable" to ""),
        Adas.Item.REAR_TRAFFIC to ("Alerta de tráfico trasero" to "Al dar marcha atrás"),
    )
    private val driveSubs = mapOf(
        Adas.Drive.MODE to "Eco, Normal, Sport o Nieve",
        Adas.Drive.REGEN to "Frenada al soltar el acelerador",
        Adas.Drive.ONE_PEDAL to "",
    )

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(actionCard(), w(440))
        addView(table(), w(1304))
    }

    // ───────────── carte d'action (côté conducteur) ─────────────

    private fun actionCard() = c.card(gap = 16).apply {
        addView(c.label("Perfiles"))
        profiles = Segmented(c, (1..Adas.PROFILES).map { "Perfil $it" }, height = Dim.TAP_MIN) { i ->
            Adas.setActive(c, i); bind(CarBridge.state.value)
        }
        addView(profiles, lp(MATCH, Dim.TAP_MIN))

        applyBtn = Btn(c, "Aplicar perfil", Icons.shield, 32, vertical = false, textSize = Ty.BODY, sub = "").apply {
            look = Look.PRIMARY
            setOnClickListener { result(Adas.apply(c), "Aplicando el perfil") }
        }
        addView(applyBtn, lp(MATCH, Dim.PRIMARY))

        appliedSub = c.text("", Ty.CAPTION, Pal.text2).apply { maxLines = 1 }
        appliedRow = c.row(12).apply {
            addView(c.row(10).apply {
                background = rounded(Pal.surface2, Dim.BTN_R.toFloat())
                setPadding(16, 0, 16, 0)
                addView(IconView(c, Icons.check, 30).apply { color = Pal.energy })
                addView(c.col(2).apply {
                    addView(c.text("Aplicado", Ty.BODY, Pal.energy, Ty.uiMedium).apply { maxLines = 1 })
                    addView(appliedSub)
                }, lp(0, WRAP, 1f))
            }, lp(0, MATCH, 1f))
            addView(Btn(c, "Deshacer", null, textSize = Ty.BODY).apply {
                setOnClickListener { result(Adas.undo(), "Deshaciendo") }
            }, lp(150, MATCH))
            visibility = View.GONE
        }
        addView(appliedRow, lp(MATCH, Dim.PRIMARY))

        matchBox = c.row(12).apply {
            background = rounded(Pal.surface2, Dim.BTN_R.toFloat())
            setPadding(20, 0, 20, 0)
            addView(IconView(c, Icons.check, 30).apply { color = Pal.energy })
            addView(c.text("Ya coincide con tu perfil", Ty.BODY, Pal.energy, Ty.uiMedium).apply { maxLines = 1 }, lp(0, WRAP, 1f))
            visibility = View.GONE
        }
        addView(matchBox, lp(MATCH, Dim.PRIMARY))

        addView(View(c), lp(MATCH, 0, 1f))
        addView(c.text("La normativa de la UE vuelve a activar los asistentes en cada arranque: aplica tu perfil con un toque, también en marcha.", Ty.LABEL, Pal.text2).apply { maxLines = 5 })
    }

    private fun result(r: CarBridge.Write, ok: String) {
        when (r) {
            CarBridge.Write.SENT -> host.toast(ok)
            CarBridge.Write.UNAVAILABLE -> host.toast("Ajustes del coche no disponibles", tone = Pal.alert)
            else -> host.toast("No se pudo aplicar", tone = Pal.heat)
        }
        bind(CarBridge.state.value)
    }

    // ───────────── tableau « ahora » face au perfil ─────────────

    private fun table() = c.card(gap = 0).apply {
        addView(gridRow(c.label("Ajuste"), c.label("Ahora"), View(c), c.label("Mi perfil"), WRAP), lp(MATCH, 30))
        for (d in Adas.Drive.values()) {
            val seg = Segmented(c, d.options.map { it.second }, height = ROW_H, textSize = Ty.CAPTION, gap = 8) { i ->
                Adas.setDrive(c, d, d.options[i].first); bind(CarBridge.state.value)
            }
            val now = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium).apply { maxLines = 1 }
            driveRows[d] = Row(now, seg)
            addView(divider()); addView(gridRow(titleCell(d.title, driveSubs[d].orEmpty()), now, arrow(), seg), lp(MATCH, ROW_H + 8))
        }
        for (item in Adas.Item.values()) {
            val seg = Segmented(c, item.options.map { it.second }, height = ROW_H, textSize = Ty.CAPTION, gap = 8) { i ->
                Adas.setProfile(c, item, item.options[i].first); bind(CarBridge.state.value)
            }
            val now = c.text("—", Ty.LABEL, Pal.text, Ty.uiMedium).apply { maxLines = 1 }
            adasRows[item] = Row(now, seg)
            val (t, sub) = titles.getValue(item)
            addView(divider()); addView(gridRow(titleCell(t, sub), now, arrow(), seg), lp(MATCH, ROW_H + 8))
        }
        addView(divider())
        addView(View(c), lp(MATCH, 0, 1f))
        addView(c.row(10).apply {
            addView(IconView(c, Icons.shield, 26).apply { color = Pal.energy })
            addView(c.text("Frenada automática de emergencia ", Ty.LABEL, Pal.text2))
            addView(c.text("Siempre activa", Ty.LABEL, Pal.energy, Ty.uiMedium))
            addView(View(c), lp(32, 1))
            addView(c.text("Aviso de colisión frontal ", Ty.LABEL, Pal.text2))
            addView(c.text("Siempre activo", Ty.LABEL, Pal.energy, Ty.uiMedium))
        }, lp(MATCH, 32))
    }

    /** Colonnes du lienzo : 384 · 170 · 32 · reste. */
    private fun gridRow(a: View, b: View, arrow: View, d: View, h: Int = ROW_H) = c.row(16).apply {
        addView(a, lp(384, WRAP)); addView(b, lp(170, WRAP)); addView(arrow, lp(32, WRAP)); addView(d, lp(0, h, 1f))
    }

    private fun titleCell(title: String, sub: String) = c.col(0).apply {
        addView(c.text(title, Ty.BODY, Pal.text, Ty.uiMedium).apply { maxLines = 1 })
        if (sub.isNotEmpty()) addView(c.text(sub, Ty.CAPTION, Pal.text2).apply { maxLines = 1 })
    }

    private fun arrow() = IconView(c, Icons.chevronRight, 26).apply { color = Pal.text3 }

    private fun divider() = View(c).apply { setBackgroundColor(Pal.line) }.also { it.layoutParams = lp(MATCH, 1) }

    override fun bind(s: VehicleState) {
        val st = Adas.state.value
        profiles.select(Adas.active(c))
        val drive = Adas.driveProfile(c)
        for ((d, r) in driveRows) {
            r.now.put(d.options.firstOrNull { it.first == Adas.driveNow(d) && it.first != -1 }?.second ?: "—")
            r.seg.select(d.options.indexOfFirst { it.first == drive[d] }.takeIf { it >= 0 })
        }
        val prof = Adas.profile(c)
        for ((item, r) in adasRows) {
            r.now.put(item.options.firstOrNull { it.first == st.current[item] }?.second ?: "—")
            r.seg.select(item.options.indexOfFirst { it.first == prof[item] }.takeIf { it >= 0 })
        }

        // action : aplicar / aplicado + deshacer / ya coincide
        val recent = st.appliedAt > 0 && System.currentTimeMillis() - st.appliedAt < 15 * 60_000L && Adas.canUndo
        val match = Adas.matches(c) == true
        val name = "perfil ${Adas.active(c) + 1}"
        applyBtn.visibility = if (st.busy || (!recent && !match)) View.VISIBLE else View.GONE
        appliedRow.visibility = if (!st.busy && recent) View.VISIBLE else View.GONE
        matchBox.visibility = if (!st.busy && !recent && match) View.VISIBLE else View.GONE
        applyBtn.setLabel(if (st.busy) "Aplicando…" else "Aplicar $name")
        val n = Adas.changes(c).size
        applyBtn.setSub(if (st.busy) "" else if (n == 0) "Sin datos del coche" else "$n cambio" + if (n == 1) "" else "s")
        if (recent) {
            val cal = Calendar.getInstance().apply { timeInMillis = st.appliedAt }
            appliedSub.put("${name.replaceFirstChar { it.uppercase() }} · a las " + Fmt.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
        }
    }

    private companion object { const val ROW_H = 58 }
}
