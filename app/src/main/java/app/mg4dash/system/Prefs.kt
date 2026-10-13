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

    val raw: SharedPreferences get() = sp
}
