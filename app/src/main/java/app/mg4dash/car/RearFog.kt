package app.mg4dash.car

/**
 * Antibrouillard arrière : VehicleSettingManager.getLightRearFogSwitch / setLightRearFogSwitch, le même
 * service que le bouton de la barre d'état SAIC (StatusBar.mRearFogSwitch, SystemUI SWI68). Cette
 * barre est cachée en plein écran, d'où un bouton propre au cockpit.
 *
 * Lecture : 1 = allumé (StatusBar.setRearFogEnable). Écriture : 1 = allumer, 2 = éteindre, comme les
 * autres interrupteurs du même SDK. Seul l'arrière se commande depuis l'écran : il n'y a pas de
 * setter pour l'avant.
 */
object RearFog {
    const val ON = 1
    const val OFF = 2

    /** État lu ; null quand la voiture ne répond pas ou renvoie une valeur inconnue (bouton masqué). */
    fun parse(raw: Int?): Boolean? = when (raw) {
        1 -> true
        0, 2 -> false
        else -> null
    }

    fun command(on: Boolean): Int = if (on) ON else OFF
}
