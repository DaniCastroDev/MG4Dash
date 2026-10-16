package app.mg4dash.system

import android.content.Context
import android.content.SharedPreferences

/** Réglages persistants du launcher. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    /** Capacité utile de la batterie, pour l'estimation de consommation (MG4 Standard 51 kWh : ~49 utiles). */
    var batteryKwh: Float
        get() = sp.getFloat("battery_kwh", 49f)
        set(v) = sp.edit().putFloat("battery_kwh", v).apply()

    /** Position météo de repli quand le véhicule ne fournit pas de localisation. */
    var weatherLat: Float
        get() = sp.getFloat("weather_lat", 48.8566f)
        set(v) = sp.edit().putFloat("weather_lat", v).apply()
    var weatherLon: Float
        get() = sp.getFloat("weather_lon", 2.3522f)
        set(v) = sp.edit().putFloat("weather_lon", v).apply()
    var weatherCity: String
        get() = sp.getString("weather_city", "Paris") ?: "Paris"
        set(v) = sp.edit().putString("weather_city", v).apply()

    /** Widget secondaire de la colonne droite : 0 = trajet, 1 = statut, 2 = météo compacte. */
    var secondaryWidget: Int
        get() = sp.getInt("secondary_widget", 0)
        set(v) = sp.edit().putInt("secondary_widget", v).apply()

    /** Ouvre brièvement le launcher SAIC au démarrage pour qu'il enregistre son interface. */
    var warmUpStock: Boolean
        get() = sp.getBoolean("warmup_stock", true)
        set(v) = sp.edit().putBoolean("warmup_stock", v).apply()

    /** Mode Entretien : le launcher SAIC reste l'accueil, MG4Dash ne reprend pas la main. */
    var valetMode: Boolean
        get() = sp.getBoolean("valet_mode", false)
        set(v) = sp.edit().putBoolean("valet_mode", v).commit().let { }

    /** Limite de charge voulue (%), appliquée par MG4Dash si la voiture l'ignore. */
    var chargeLimit: Int
        get() = sp.getInt("charge_limit", 100)
        set(v) = sp.edit().putInt("charge_limit", v).apply()

    /** Arrêt automatique de la charge par MG4Dash quand la limite est atteinte. */
    var enforceChargeLimit: Boolean
        get() = sp.getBoolean("enforce_charge_limit", true)
        set(v) = sp.edit().putBoolean("enforce_charge_limit", v).apply()

    /** Masque la barre latérale et le bandeau SAIC (mode immersif). */
    var fullscreen: Boolean
        get() = sp.getBoolean("fullscreen", true)
        set(v) = sp.edit().putBoolean("fullscreen", v).apply()

    /** Widget central haut : 0 = carte OpenStreetMap, 1 = raccourcis. */
    var homeWidget: Int
        get() = sp.getInt("home_widget", 0)
        set(v) = sp.edit().putInt("home_widget", v).apply()

    /** Bouton Android Auto du dock : composant d'une autre appli, ou null = Android Auto d'origine. */
    var dockAaTarget: String?
        get() = sp.getString("dock_aa_target", null)
        set(v) = sp.edit().putString("dock_aa_target", v).apply()

    /** Home Assistant : adresse (ex. http://192.168.1.10:8123) et jeton d'accès longue durée. */
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim()).apply()
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(v) = sp.edit().putString("ha_token", v.trim()).apply()

    /** Enregistreur de données énergie (diagnostic, fichier CSV). */
    /** Style « Carte » : page affichée dans la pile (0 énergie, 1 trajet, 2 puissance, 3 téléphone). */
    var stackPage: Int
        get() = sp.getInt("stack_page", 0)
        set(v) = sp.edit().putInt("stack_page", v).apply()

    /** Pile automatique : revient sur la batterie quand la voiture est branchée. */
    var stackAuto: Boolean
        get() = sp.getBoolean("stack_auto", true)
        set(v) = sp.edit().putBoolean("stack_auto", v).apply()

    /** Case de gauche du dock : 0 = horloge, 1 = réglages Wi-Fi. */
    var dockSlot: Int
        get() = sp.getInt("dock_slot", 0)
        set(v) = sp.edit().putInt("dock_slot", v).apply()

    /** Carte de l'accueil orientée selon le cap de la voiture (sinon nord en haut). */
    var mapHeadingUp: Boolean
        get() = sp.getBoolean("map_heading_up", false)
        set(v) = sp.edit().putBoolean("map_heading_up", v).apply()

    /** Mise à jour : proposer aussi les versions bêta (pré-versions GitHub). */
    var updateBeta: Boolean
        get() = sp.getBoolean("update_beta", true)
        set(v) = sp.edit().putBoolean("update_beta", v).apply()

    var energyLog: Boolean
        get() = sp.getBoolean("energy_log", true)
        set(v) = sp.edit().putBoolean("energy_log", v).apply()

    /** Style de l'accueil : 0 = complet (3 colonnes), 1 = épuré (3 volets + 2 raccourcis + météo). */
    var homeStyle: Int
        get() = sp.getInt("home_style", 0)
        set(v) = sp.edit().putInt("home_style", v).apply()

    /** Thème : fond (0 bleu nuit, 1 noir, 2 anthracite, 3 vert sombre, 4 bordeaux) et boutons (0 bleu, 1 gris, 2 rouge, 3 vert, 4 orange). */
    var themeBackground: Int
        get() = sp.getInt("theme_bg", 0)
        set(v) = sp.edit().putInt("theme_bg", v).commit().let { }
    /** Clair / sombre : 0 = auto (jour / nuit de la voiture), 1 = sombre, 2 = clair. */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) = sp.edit().putInt("theme_mode", v).commit().let { }
    var themeAccent: Int
        get() = sp.getInt("theme_accent", 0)
        set(v) = sp.edit().putInt("theme_accent", v).commit().let { }

    val raw: SharedPreferences get() = sp
}
