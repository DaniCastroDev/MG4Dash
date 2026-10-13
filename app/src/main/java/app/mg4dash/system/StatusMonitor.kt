package app.mg4dash.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** État Bluetooth / Wi-Fi pour la barre supérieure. Lecture seule. */
object StatusMonitor {
    data class Status(val btOn: Boolean, val btConnected: Boolean, val wifiOn: Boolean, val wifiConnected: Boolean)

    // Profils côté voiture (rôle « puits ») : A2DP_SINK = 11, HEADSET_CLIENT = 16 (constantes masquées).
    private val PROFILES = intArrayOf(11, 16, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    fun read(context: Context): Status {
        val bt = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Exception) { null }
        val btOn = bt?.isEnabled == true
        val btConnected = btOn && PROFILES.any { p ->
            try { bt?.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
        return Status(
            btOn = btOn,
            btConnected = btConnected,
            wifiOn = wifi?.isWifiEnabled == true,
            wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        )
    }

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
