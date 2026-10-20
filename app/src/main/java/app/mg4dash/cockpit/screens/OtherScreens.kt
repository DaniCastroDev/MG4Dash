package app.mg4dash.cockpit.screens

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.mg4dash.ClassicActivity
import app.mg4dash.car.VehicleState
import app.mg4dash.cockpit.Btn
import app.mg4dash.cockpit.Dim
import app.mg4dash.cockpit.Fmt
import app.mg4dash.cockpit.Host
import app.mg4dash.cockpit.Icons
import app.mg4dash.cockpit.Look
import app.mg4dash.cockpit.MATCH
import app.mg4dash.cockpit.Nav
import app.mg4dash.cockpit.Pal
import app.mg4dash.cockpit.Pill
import app.mg4dash.cockpit.Screen
import app.mg4dash.cockpit.Segmented
import app.mg4dash.cockpit.Ty
import app.mg4dash.cockpit.WRAP
import app.mg4dash.cockpit.card
import app.mg4dash.cockpit.header
import app.mg4dash.cockpit.label
import app.mg4dash.cockpit.lp
import app.mg4dash.cockpit.put
import app.mg4dash.cockpit.row
import app.mg4dash.cockpit.strip.StripService
import app.mg4dash.cockpit.text
import app.mg4dash.cockpit.w
import app.mg4dash.media.MediaHub
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Prefs

// Medios (radio y fuentes) : MediaScreen.kt

/** Android Auto : estado de la conexión y ayuda (lienzo : AA-Connecting, AA-Failed). */
class AaScreen(host: Host) : Screen(host) {
    override val nav = Nav.AA
    private lateinit var pill: Pill
    private lateinit var title: TextView

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card().apply {
            pill = Pill(c, "—", Pal.text3)
            addView(c.header("Android Auto", pill, 48))
            title = c.text("—", Ty.VALUE, Pal.text, Ty.uiMedium); addView(title)
            addView(step("1", "Punto de acceso del coche activo"))
            addView(step("2", "El teléfono se une al Wi-Fi del coche"))
            addView(step("3", "En el teléfono: Android Auto › Ajustes de desarrollador › Iniciar servidor de unidad principal"))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(c.row(Dim.GAP).apply {
                addView(Btn(c, "Abrir Android Auto", Icons.phone, 30, vertical = false, textSize = Ty.BODY).apply {
                    look = Look.PRIMARY
                    setOnClickListener { if (!Launch.open(c, listOf(StripService.DIAUTO_PKG, "com.allgo.app.androidauto"))) host.toast("DiAuto no está instalado", tone = Pal.alert) }
                }, lp(0, Dim.PRIMARY, 1f))
                addView(Btn(c, "Usar sin Android Auto", Icons.fullscreen, 30, vertical = false).apply { setOnClickListener { host.go("home") } }, lp(0, Dim.PRIMARY, 1f))
            })
        }, w(1000))
        addView(c.card().apply {
            addView(c.label("Franja con Android Auto"))
            addView(c.text("Con Android Auto conectado, el launcher muestra una franja de 640 px junto a la proyección.", Ty.LABEL, Pal.text2).apply { maxLines = 4 })
            addView(c.text("En DiAuto › Ajustes › Márgenes, pon 640 px en el lado de la franja (izquierdo por defecto).", Ty.LABEL, Pal.text).apply { maxLines = 4 })
            addView(View(c), lp(MATCH, 0, 1f))
            addView(Btn(c, "Ajustes de la franja", Icons.settings, 30, vertical = false).apply { setOnClickListener { host.go("settings") } }, lp(MATCH, Dim.TAP))
        }, w(744))
    }

    private fun step(n: String, t: String) = c.row(16).apply {
        addView(c.text(n, Ty.LABEL, Pal.text, Ty.num).apply {
            gravity = Gravity.CENTER; background = app.mg4dash.cockpit.rounded(Pal.surface3, 22f)
        }, lp(44, 44))
        addView(c.text(t, Ty.LABEL, Pal.text).apply { maxLines = 2 }, lp(0, WRAP, 1f))
    }

    override fun bind(s: VehicleState) {
        val on = MediaHub.androidAutoAvailable()
        pill.set(if (on) "Conectado" else "Buscando teléfono…", if (on) Pal.energy else Pal.heat)
        title.put(if (on) "Android Auto conectado" else "Conectando con tu teléfono")
    }
}

/** Ajustes del launcher (lienzo : Settings.dc.html). */
class SettingsScreen(host: Host) : Screen(host) {
    override val nav = Nav.SETTINGS
    override val climateBar = false
    private val prefs by lazy { Prefs(c) }
    private lateinit var priceHome: TextView
    private lateinit var priceDc: TextView

    override fun build(): View = c.row(Dim.GAP).apply {
        gravity = Gravity.TOP
        addView(c.card(gap = 12).apply {
            addView(c.label("Disposición"))
            addView(switch("Franja con Android Auto", "Muestra la franja de 640 px sobre DiAuto", { prefs.raw.getBoolean("strip_enabled", true) }) {
                prefs.raw.edit().putBoolean("strip_enabled", it).apply(); StripService.sync(c)
            })
            addView(choice("Lado de la franja", listOf("Izquierda", "Derecha"), { if (prefs.raw.getBoolean("strip_right", false)) 1 else 0 }) {
                prefs.raw.edit().putBoolean("strip_right", it == 1).apply()
            })
            addView(switch("Pantalla completa", "Oculta las barras del sistema", { prefs.fullscreen }) { prefs.fullscreen = it })
            addView(c.label("Tema"))
            addView(choice("Tema", listOf("Oscuro", "Claro"), { if (prefs.themeMode == 2) 1 else 0 }) {
                prefs.themeMode = if (it == 1) 2 else 1; (c as? android.app.Activity)?.recreate()
            })
            addView(c.label("Clima"))
            addView(switch("Ocultar el panel de clima del sistema", "Se cierra al encender el aire", { prefs.hideStockHvac }) { prefs.hideStockHvac = it })
        }, w(860))
        addView(c.card(gap = 12).apply {
            addView(c.label("Energía"))
            priceHome = c.text("", Ty.TITLE, Pal.text, Ty.num)
            addView(priceStepper("Precio en casa (€/kWh)", "price_home", 0.15f, priceHome))
            priceDc = c.text("", Ty.TITLE, Pal.text, Ty.num)
            addView(priceStepper("Precio carga rápida (€/kWh)", "price_dc", 0.49f, priceDc))
            addView(choice("Batería (kWh brutos)", listOf("51", "64", "77"), { listOf(51, 64, 77).indexOf(prefs.batteryModel).coerceAtLeast(0) }) { prefs.batteryModel = listOf(51, 64, 77)[it] })
            addView(c.label("Sistema"))
            addView(c.row(12).apply {
                addView(Btn(c, "Bluetooth y Wi-Fi", Icons.bluetooth, 30, vertical = false, chevron = true).apply { setOnClickListener { host.go("connections") } }, lp(0, Dim.TAP_MIN, 1f))
                addView(Btn(c, "Ajustes de Android", Icons.settings, 30, vertical = false, chevron = true).apply {
                    setOnClickListener { if (!Launch.androidSettings(c)) host.toast("Los ajustes de Android no están disponibles", tone = Pal.alert) }
                }, lp(0, Dim.TAP_MIN, 1f))
            }, lp(MATCH, Dim.TAP_MIN))
            addView(c.row(12).apply {
                addView(Btn(c, "Usar como inicio", Icons.home, 30, vertical = false).apply { setOnClickListener { HomeSwitcher.replaceStock(c) } }, lp(0, Dim.TAP_MIN, 1f))
                addView(Btn(c, "Interfaz clásica", Icons.apps, 30, vertical = false).apply { setOnClickListener { c.startActivity(Intent(c, ClassicActivity::class.java)) } }, lp(0, Dim.TAP_MIN, 1f))
            }, lp(MATCH, Dim.TAP_MIN))
            addView(View(c), lp(MATCH, 0, 1f))
            addView(c.text("Versión " + app.mg4dash.BuildConfig.VERSION_NAME, Ty.CAPTION, Pal.text2))
            // Diagnóstico de espacio : pantalla real, ventana disponible y fuente condensada presente
            addView(c.text("", Ty.CAPTION, Pal.text3).apply { maxLines = 2; post { text = screenInfo(this) } })
        }, w(884))
    }

    private fun screenInfo(v: View): String {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(real)
        val root = v.rootView
        // Sans police condensée, Android retombe sur la police normale, plus large : mêmes largeurs = absente
        val sample = "0123456789"
        val cond = android.graphics.Paint().apply { typeface = Ty.num; textSize = 40f }.measureText(sample)
        val plain = android.graphics.Paint().apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); textSize = 40f }.measureText(sample)
        return "Pantalla ${real.widthPixels}×${real.heightPixels} · ${real.densityDpi} dpi · ventana ${root.width}×${root.height}" +
            " · fuente condensada ${if (cond < plain * 0.97f) "sí" else "no"}"
    }

    private fun switch(title: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Btn(c, title, null, vertical = false, sub = sub, led = true).apply {
            accent = Pal.action; on = get()
            setOnClickListener { val v = !get(); set(v); on = v }
        }.also { it.layoutParams = lp(MATCH, Dim.TAP) }

    private fun choice(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) = c.row(16).apply {
        addView(c.text(title, Ty.LABEL, Pal.text), lp(0, WRAP, 1f))
        lateinit var seg: Segmented
        seg = Segmented(c, options, height = Dim.TAP_MIN, textSize = Ty.CAPTION, gap = 8) { i -> set(i); seg.select(i) }
        seg.select(get())
        addView(seg, lp(380, Dim.TAP_MIN))
    }.also { it.layoutParams = lp(MATCH, WRAP) }

    private fun priceStepper(title: String, key: String, def: Float, value: TextView) = c.row(12).apply {
        fun show() { value.text = Fmt.dec(prefs.raw.getFloat(key, def), 2) }
        fun step(d: Float) { prefs.raw.edit().putFloat(key, (prefs.raw.getFloat(key, def) + d).coerceIn(0.05f, 0.95f)).apply(); show() }
        addView(c.text(title, Ty.LABEL, Pal.text), lp(0, WRAP, 1f))
        addView(Btn(c, null, Icons.minus, 30).apply { setOnClickListener { step(-0.01f) } }, lp(Dim.TAP_MIN, Dim.TAP_MIN))
        addView(value.apply { gravity = Gravity.CENTER }, lp(110, WRAP))
        addView(Btn(c, null, Icons.plus, 30).apply { setOnClickListener { step(0.01f) } }, lp(Dim.TAP_MIN, Dim.TAP_MIN))
        show()
    }.also { it.layoutParams = lp(MATCH, WRAP) }
}
