package app.mg4dash.car

import java.util.Locale

/**
 * Instantané de l'état véhicule affiché par le launcher.
 * Un champ à null = valeur non lue (service absent ou getter en échec) : l'UI affiche « — ».
 */
data class VehicleState(
    val connected: Boolean = false,
    // Climatisation (CarHvacClient, code 7)
    val hvacOn: Boolean? = null,
    val acOn: Boolean? = null,
    val autoOn: Boolean? = null,
    /** 0 = air recyclé, 1 = air extérieur, 2 = auto (convention MG4Control, à confirmer sur A9). */
    val recirc: Int? = null,
    val fanSpeed: Int? = null,
    val fanDirection: Int? = null,
    val driverTemp: Float? = null,
    val outsideTemp: Float? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val ecoOn: Boolean? = null,
    // Énergie (CarEvClient, code 4)
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val chargeStatus: Int? = null,
    val chargeRemainMin: Int? = null,
    /** Bouton de charge : 1 = charge en cours (proposer « Arrêter »), 2 = arrêtée (« Charger »). */
    val chargeCtrl: Int? = null,
    /** Bouton V2L : 1 = décharge en cours, 2 = arrêtée. */
    val dischargeCtrl: Int? = null,
    val dischargeRemainMin: Int? = null,
    // Conduite (CarVehicleSettingClient, code 8)
    val driveMode: Int? = null,
    val regenLevel: Int? = null,
    val onePedal: Int? = null,
    val steeringMode: Int? = null,
    val brakePedalMode: Int? = null,
    val enduranceMode: Int? = null,
    // Aides à la conduite (lecture seule)
    val lasMode: Int? = null,
    val fcwState: Int? = null,
    val fcwBrakeMode: Int? = null,
    val overSpeedSound: Int? = null,
    val speedLimitSound: Int? = null,
    // Général (CarGeneralClient, code 1)
    val odometerKm: Int? = null,
    val speedKmh: Float? = null,
) {
    companion object {
        const val TEMP_MIN = 17.5f   // position LO
        const val TEMP_MAX = 32.5f   // position HI
        const val TEMP_STEP = 1f
        const val FAN_MIN = 1
        const val FAN_MAX = 11

        /**
         * État de charge (getChargeStatus), table a9_ev_status du launcher d'origine :
         * 0 = débranché, 1..10 et 12 = recharge, 11 et 13..15 = décharge (V2L).
         */
        fun chargeStatusLabel(v: Int?): String = when (v) {
            0 -> "Câble débranché"
            1 -> "Charge lente"
            2 -> "Charge terminée"
            3 -> "Charge d'entretien"
            4 -> "Échec de la charge"
            5 -> "Connexion…"
            6 -> "Connecté, non reconnu"
            7 -> "Connecté, ne charge pas"
            8 -> "Charge arrêtée"
            9 -> "Charge programmée"
            10 -> "Charge rapide"
            11 -> "Charge ultra-rapide"
            12 -> "Charge en cours"
            13 -> "V2L : décharge en cours"
            14 -> "V2L : décharge arrêtée"
            15 -> "V2L : décharge terminée"
            null -> "—"
            else -> "État $v"
        }

        /** Mode V2L : même règle que le launcher d'origine (11 et ≥ 13 = écran de décharge). */
        fun isDischargeMode(v: Int?): Boolean = v == 11 || (v != null && v >= 13)
        fun isPlugged(v: Int?): Boolean = v != null && v != 0
        fun isCharging(v: Int?): Boolean = v == 1 || v == 3 || v == 10 || v == 12

        fun driveModeLabel(v: Int?): String = when (v) {
            2 -> "Éco"
            3 -> "Normal"
            4 -> "Sport"
            6 -> "Neige"
            7 -> "Perso"
            null -> "—"
            else -> "Mode $v"
        }

        /** Niveau de récupération (KERS) en index de jauge 1..4, 0 si inconnu. */
        fun regenGauge(v: Int?): Int = when (v) {
            0 -> 1
            1 -> 2
            2 -> 3
            3 -> 4
            else -> 0
        }

        fun regenLabel(v: Int?, onePedal: Int?): String = when {
            onePedal == 1 -> "1 pédale"
            v == 0 -> "Faible"
            v == 1 -> "Moyen"
            v == 2 -> "Fort"
            v == 3 -> "Adaptatif"
            v == null -> "—"
            else -> "Niveau $v"
        }

        fun recircLabel(v: Int?): String = when (v) {
            0 -> "Recyclé"
            1 -> "Extérieur"
            2 -> "Auto"
            else -> "—"
        }

        /** Direction : 1 = confort, 2 = normal, 3 = sport (échelle A9 relevée par MG4Control). */
        fun steeringLabel(v: Int?): String = when (v) { 1 -> "Confort"; 2 -> "Normal"; 3 -> "Sport"; null -> "—"; else -> "Mode $v" }

        /** Pédale de frein : 1 = confort, 0 = normal, 2 = sport (normal vaut zéro). */
        fun pedalLabel(v: Int?): String = when (v) { 1 -> "Confort"; 0 -> "Normal"; 2 -> "Sport"; null -> "—"; else -> "Mode $v" }

        /** Aide au maintien de voie : 1 = arrêt, 2 = alerte, 3 = assistance, 5 = urgence. */
        fun lasLabel(v: Int?): String = when (v) { 1 -> "Arrêt"; 2 -> "Alerte"; 3 -> "Assistance"; 5 -> "Urgence"; null -> "—"; else -> "Mode $v" }

        /** Freinage d'urgence (A9) : état 1 = arrêt, 2 = actif ; mode 1 = alerte, 2 = alerte + freinage. */
        fun aebLabel(state: Int?, mode: Int?): String = when {
            state == 1 -> "Arrêt"
            state == 2 && mode == 2 -> "Alerte + freinage"
            state == 2 && mode == 1 -> "Alerte"
            state == 2 -> "Actif"
            else -> "—"
        }

        fun onOff(v: Int?): String = when (v) { 0 -> "Arrêt"; 1 -> "Marche"; null -> "—"; else -> "Valeur $v" }

        fun tempLabel(t: Float?): String = when {
            t == null || t.isNaN() || t <= 0f -> "—"
            t <= TEMP_MIN -> "LO"
            t >= TEMP_MAX -> "HI"
            t % 1f == 0f -> "${t.toInt()}°"
            else -> String.format(Locale.FRANCE, "%.1f°", t)
        }
    }
}
