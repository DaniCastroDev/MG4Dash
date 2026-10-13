package app.mg4dash.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Raccourcis vers les applis SAIC (composants relevés sur la voiture le 2026-10-13). */
object Apps {
    private const val TAG = "Apps"

    val VEHICLE = ComponentName("com.saicmotor.vehiclesetting", "com.saicmotor.vehiclesetting.ui.VehicleSettingActivity")
    val HVAC = ComponentName("com.saicmotor.hvac", "com.saicmotor.hvac.ui.activity.MainActivity")
    val ENERGY = ComponentName("com.saicmotor.saicev", "com.saicmotor.saicev.activity.MainActivity")
    val ANDROID_AUTO = ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity")
    val SETTINGS = ComponentName("com.saicmotor.settings", "com.saicmotor.settings.ui.MainActivity")
    val RADIO = ComponentName("com.saicmotor.radio", "com.saicmotor.radio.view.RadioMainActivity")
    val MEDIA = ComponentName("com.saicmotor.media", "com.saicmotor.media.ui.activity.MainActivity")
    val MG4_CONTROL = ComponentName("com.mg4.control", "com.mg4.control.MainActivity")
    val BT_PHONE = ComponentName("com.saicmotor.btphone", "com.saicmotor.btphone.view.activity.BTPhoneMainActivity")

    fun open(context: Context, component: ComponentName, label: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "ouverture $label (${component.flattenToShortString()})")
            true
        } catch (e: ActivityNotFoundException) {
            fallback(context, component.packageName, label)
        } catch (e: SecurityException) {
            fallback(context, component.packageName, label)
        }
    }

    fun openPackage(context: Context, pkg: String, label: String = pkg): Boolean = fallback(context, pkg, label)

    private fun fallback(context: Context, pkg: String, label: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "ouverture $label via intent de lancement")
            true
        } else {
            Log.w(TAG, "$label introuvable ($pkg)")
            Toast.makeText(context, "$label indisponible", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
