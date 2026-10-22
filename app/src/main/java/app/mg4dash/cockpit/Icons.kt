package app.mg4dash.cockpit

/**
 * Icônes du lienzo de diseño (viewBox 32 × 32, trait arrondi), en données de tracé SVG.
 * Dessinées par [IconView] : pas de PNG ni de VectorDrawable à charger.
 */
class Icon(val parts: List<Part>, val mirror: Boolean = false) {
    class Part(val d: String, val fill: Boolean = false, val rotate: Float = 0f)
}

private fun p(vararg d: String) = Icon(d.map { Icon.Part(it) })
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"

object Icons {
    val home = p("M5 15 16 6l11 9", "M8.5 13v13h15V13", "M13.5 26v-6.5h5V26")
    val climate = p("M13 18V7a3 3 0 0 1 6 0v11a6 6 0 1 1-6 0Z", "M16 11v12", "M23 8h4M23 13h4")
    val energy = p("M18 3.5 7.5 18H16l-2 10.5L24.5 14H16Z")
    val car = p("M5 23v-8l3.2-7h15.6l3.2 7v8Z", "M5 15h22", "M7.5 23v3.5M24.5 23v3.5", "M9.5 19h2.5M20 19h2.5")
    val media = Icon(listOf(Icon.Part("M12 23V7.5l14-3V20"), Icon.Part(circle(8.5f, 23f, 3.5f)), Icon.Part(circle(22.5f, 20f, 3.5f))))
    val phone = Icon(listOf(Icon.Part(rect(9f, 3.5f, 14f, 25f, 3f)), Icon.Part("M14 24h4")))
    val settings = Icon(listOf(Icon.Part("M5 9h12M23 9h4M5 16h4M15 16h12M5 23h14M25 23h2"), Icon.Part(circle(20f, 9f, 3f)), Icon.Part(circle(12f, 16f, 3f)), Icon.Part(circle(22f, 23f, 3f))))
    val minus = p("M8 16h16")
    val plus = p("M8 16h16M16 8v16")
    val chevronRight = p("M12.5 7.5 21 16l-8.5 8.5")
    val chevronLeft = p("M19.5 7.5 11 16l8.5 8.5")
    val chevronUp = p("M8 20l8-8 8 8")
    val close = p("M9 9l14 14M23 9 9 23")
    val check = p("M7 16.5l6 6L25 10")
    val download = p("M16 5v15", "M9.5 13.5 16 20l6.5-6.5", "M6 26h20")
    val arrowUp = p("M16 26V7", "M9 14l7-7 7 7")
    val arrowDown = p("M16 6v19", "M9 18l7 7 7-7")
    val power = p("M16 5v10", "M9.6 9.2a9 9 0 1 0 12.8 0")
    val defrostFront = p("M4 11.5C8 8.5 12 7.5 16 7.5s8 1 12 4l-2.5 13c-3-1.3-6.3-2-9.5-2s-6.5.7-9.5 2Z", "M12 20c-1.2-1.5 1.2-3 0-4.5M16 19.5c-1.2-1.5 1.2-3 0-4.5M20 20c-1.2-1.5 1.2-3 0-4.5")
    val defrostRear = Icon(listOf(Icon.Part(rect(4f, 8f, 24f, 16f, 3f)), Icon.Part("M12 20c-1.2-1.5 1.2-3 0-4.5M16 19.5c-1.2-1.5 1.2-3 0-4.5M20 20c-1.2-1.5 1.2-3 0-4.5")))
    /** Antiniebla trasera (símbolo ISO 7000) : lámpara a la derecha, haces a la izquierda cruzados por una onda. */
    val rearFog = p("M20 8c5 0 8 3.6 8 8s-3 8-8 8Z", "M4 11h11M4 16h11M4 21h11", "M10 6.5c-2 3 2 6.5 0 9.5s2 6.5 0 9.5")
    val wheel = Icon(listOf(Icon.Part(circle(16f, 16f, 11f)), Icon.Part(circle(16f, 16f, 3f)), Icon.Part("M5 16h8M19 16h8M16 19v8")))
    private val seatD = listOf("M17.5 5h3a1.5 1.5 0 0 1 1.5 1.3l1.7 12.2a2 2 0 0 1-2 2.5H9a2 2 0 0 1 0-4h10.5L16 6.8A1.5 1.5 0 0 1 17.5 5Z", "M15 21v5M10.5 26h9")
    val seatLeft = Icon(seatD.map { Icon.Part(it) })
    val seatRight = Icon(seatD.map { Icon.Part(it) }, mirror = true)
    private const val BLADE = "M16 13.5c-1.5-4-.5-8.5 3-8.5 3 0 3.5 4.5-3 8.5Z"
    val fan = Icon(listOf(Icon.Part(circle(16f, 16f, 2.5f)), Icon.Part(BLADE), Icon.Part(BLADE, rotate = 120f), Icon.Part(BLADE, rotate = 240f)))
    val recirc = p("M10 14.5c0-4 2.7-6.5 6-6.5s6 2.5 6 6.5", "M19.5 12 22 14.5l2.5-2.5", "M4 26h24", "M6 26v-4l3.5-4h13l3.5 4v4")
    private val person = listOf(circle(22f, 7f, 2.8f), "M21 11.5 19.5 19.5h-7V27")
    private const val FACE = "M3 10h9M9 7l3 3-3 3"
    private const val FEET = "M3 24h6M6.5 21.5 9 24l-2.5 2.5"
    private const val SCREEN = "M2.5 15c1-4.5 3.5-8.5 7.5-11"
    private const val SCREEN2 = "M13 12 9.5 8.5M9.5 12V8.5H13"
    val airFace = Icon((person + FACE).map { Icon.Part(it) })
    val airFaceFeet = Icon((person + FACE + FEET).map { Icon.Part(it) })
    val airFeet = Icon((person + FEET).map { Icon.Part(it) })
    val airFeetScreen = Icon((person + SCREEN + SCREEN2 + FEET).map { Icon.Part(it) })
    val airScreen = Icon((person + SCREEN + SCREEN2).map { Icon.Part(it) })
    val play = Icon(listOf(Icon.Part("M11 6.5 25.5 16 11 25.5Z", fill = true)))
    val pause = p("M11 7.5v17M21 7.5v17")
    val next = Icon(listOf(Icon.Part("M23 7v18"), Icon.Part("M8 7.5 19 16 8 24.5Z", fill = true)))
    val prev = Icon(listOf(Icon.Part("M9 7v18"), Icon.Part("M24 7.5 13 16l11 8.5Z", fill = true)))
    val seekNext = Icon(listOf(Icon.Part("M6 8.5 15 16l-9 7.5Z", fill = true), Icon.Part("M17 8.5 26 16l-9 7.5Z", fill = true)))
    val seekPrev = Icon(listOf(Icon.Part("M15 8.5 6 16l9 7.5Z", fill = true), Icon.Part("M26 8.5 17 16l9 7.5Z", fill = true)))
    val stop = Icon(listOf(Icon.Part(rect(8f, 8f, 16f, 16f, 2.5f), fill = true)))
    val radio = Icon(listOf(Icon.Part(rect(4f, 11f, 24f, 16f, 3f)), Icon.Part("M8 11 23 4.5"), Icon.Part(circle(21f, 19f, 3.5f)), Icon.Part("M8.5 16.5h5M8.5 21.5h5")))
    val bluetooth = p("M9.5 10.5 22 21.5 16 27V5l6 5.5L9.5 21.5")
    val wifi = p("M4 12.5a17 17 0 0 1 24 0", "M8.5 17a10.5 10.5 0 0 1 15 0", "M12.8 21.4a4.6 4.6 0 0 1 6.4 0", "M16 26v.01")
    val lock = Icon(listOf(Icon.Part(rect(7f, 14f, 18f, 13f, 3f)), Icon.Part("M11 14v-4a5 5 0 0 1 10 0v4")))
    val unlock = Icon(listOf(Icon.Part(rect(7f, 14f, 18f, 13f, 3f)), Icon.Part("M11 14v-3.5a5 5 0 0 1 9.6-2")))
    val screenOff = Icon(listOf(Icon.Part(rect(4f, 6f, 24f, 16f, 2.5f)), Icon.Part("M12 27h8"), Icon.Part("M5 4l22 22")))
    val shield = p("M16 4 26 8v7.5c0 6-4.2 10.5-10 12.5C10.2 26 6 21.5 6 15.5V8Z", "M11.5 16 15 19.5l6-6.5")
    val shieldAlert = p("M16 4 26 8v7.5c0 6-4.2 10.5-10 12.5C10.2 26 6 21.5 6 15.5V8Z", "M16 11v6", "M16 21.5v.01")
    val esc = p("M7 18v-4.5L9.5 8h13l2.5 5.5V18Z", "M7 13.5h18", "M6 25c1.5-1.5 3-1.5 4.5 0s3 1.5 4.5 0", "M17 25c1.5-1.5 3-1.5 4.5 0s3 1.5 4.5 0")
    val plug = p("M12 4v6M20 4v6", "M9 10h14v4a7 7 0 0 1-14 0Z", "M16 21v7")
    val regen = p("M6.5 15A9.5 9.5 0 0 1 23 9", "M23.5 4v5.5H18", "M25.5 17A9.5 9.5 0 0 1 9 23", "M8.5 28v-5.5H14")
    val camera = Icon(listOf(Icon.Part("M5 11.5h5l2-3h8l2 3h5V24H5Z"), Icon.Part(circle(16f, 17.5f, 4f)), Icon.Part("M4 28c3.5 1 7.5 1.5 12 1.5s8.5-.5 12-1.5")))
    val fullscreen = Icon(listOf(Icon.Part("M5 11V6h5M27 11V6h-5M5 21v5h5M27 21v5h-5"), Icon.Part(rect(10.5f, 11.5f, 11f, 9f, 1.5f))))
    val apps = Icon(listOf(Icon.Part(rect(5f, 5f, 9f, 9f, 2f)), Icon.Part(rect(18f, 5f, 9f, 9f, 2f)), Icon.Part(rect(5f, 18f, 9f, 9f, 2f)), Icon.Part(rect(18f, 18f, 9f, 9f, 2f))))
    val retry = p("M26 16a10 10 0 1 1-2.9-7.1", "M24 4v5.5h-5.5")
    val swap = p("M6 11h19M20 6l5 5-5 5", "M26 21H7M12 16l-5 5 5 5")
    val info = Icon(listOf(Icon.Part(circle(16f, 16f, 12f)), Icon.Part("M16 15v7"), Icon.Part("M16 10.5v.01")))
    val alert = p("M16 4 29 27H3Z", "M16 13v6", "M16 23v.01")
    val calendar = Icon(listOf(Icon.Part(rect(4.5f, 6.5f, 23f, 21f, 3f)), Icon.Part("M4.5 13h23"), Icon.Part("M10.5 4v5M21.5 4v5"), Icon.Part("M16 17v4l2.5 2")))
    val search = Icon(listOf(Icon.Part(circle(14f, 14f, 8f)), Icon.Part("M20 20l7 7")))
    val battery = Icon(listOf(Icon.Part(rect(3f, 9f, 23f, 14f, 3f)), Icon.Part("M29 14v4")))
    val tyre = Icon(listOf(Icon.Part(circle(16f, 16f, 11f)), Icon.Part(circle(16f, 16f, 5f))))
    val fog = p("M14 9c-5 0-9 3-9 7s4 7 9 7Z", "M18 10h10M18 16h10M18 22h10", "M22 7v18")
}
