package app.mg4dash.car

import android.os.IBinder
import android.os.Parcel

/**
 * Appels binder vers les services « carapi » de CarAdapterService (famille A9 / SWI69).
 *
 * Écrit pour MG4Dash : seuls l'interface (descripteur) et le numéro de transaction de chaque
 * méthode utilisée sont repris, relevés sur la voiture par analyse du launcher d'origine.
 * Tous les appels sont synchrones (two-way) et suivent le format AIDL standard :
 * jeton d'interface, arguments, puis exception éventuelle et valeur de retour.
 */
open class RemoteService(private val binder: IBinder, private val descriptor: String) {

    val alive: Boolean get() = binder.isBinderAlive

    private inline fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    protected fun int(code: Int): Int = call(code) { it.readInt() }
    protected fun bool(code: Int): Boolean = call(code) { it.readInt() != 0 }
    protected fun float(code: Int): Float = call(code) { it.readFloat() }
    protected fun void(code: Int) = call(code) { }
    protected fun voidInt(code: Int, value: Int) = call(code, { it.writeInt(value) }) { }
    protected fun voidFloat(code: Int, value: Float) = call(code, { it.writeFloat(value) }) { }
    protected fun binderInt(code: Int, value: Int): IBinder? = call(code, { it.writeInt(value) }) { it.readStrongBinder() }
}

/** CarAdapterService : distribue un binder par domaine (queryClient). */
class AdapterService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.ICarAdapterService") {
    fun queryClient(code: Int): IBinder? = binderInt(1, code)
}

/** Climatisation (queryClient 7). Les « switch » avancent d'un cran, sans argument. */
class HvacService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.hvac.ICarHvacService") {
    val hvacPowerStatus get() = bool(6)
    val acStatus get() = bool(8)
    val autoStatus get() = bool(10)
    val ecoStatus get() = bool(14)
    val airCirculationStatus get() = int(16)
    val fanDirection get() = int(18)
    val fanSpeed get() = int(20)
    val driverTemperature get() = float(22)
    val outSideTemperature get() = float(25)
    val frontDefrostStatus get() = bool(28)
    val rearDefrostStatus get() = bool(30)

    fun switchHvacPowerStatus() = void(5)
    fun switchACStatus() = void(7)
    fun switchAutoStatus() = void(9)
    fun switchEcoStatus() = void(13)
    fun switchAirCirculationStatus() = void(15)
    fun switchFrontDefrostStatus() = void(27)
    fun switchRearDefrostStatus() = void(29)
    fun setFanDirection(v: Int) = voidInt(17, v)
    fun setFanSpeed(v: Int) = voidInt(19, v)
    fun setDriverTemperature(v: Float) = voidFloat(21, v)
}

/** Énergie / recharge (queryClient 4). */
class EvService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.ev.ICarEvService") {
    val chargePercentChange get() = int(3)
    val chargeStatus get() = int(4)
    val chargingRemainTime get() = int(6)
    val disChargingRemainTime get() = int(7)
    val currentRange get() = int(12)
    val chargeCtrlStatus get() = int(20)
    val disChargeCtrl get() = int(24)

    /** 1 = démarrer, 2 = arrêter (comme le bouton du launcher d'origine). */
    fun chargeCtrl(v: Int) = voidInt(5, v)
    fun disChargeCtrl(v: Int) = voidInt(8, v)
}

/** Réglages véhicule (queryClient 8), lecture seule dans MG4Dash. */
class VehicleSettingService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.vs.ICarVehicleSettingService") {
    val fcwState get() = int(20)
    val fcwAutoBrakeMode get() = int(22)
    val lasMode get() = int(31)
    val drivingMode get() = int(63)
    val steeringMode get() = int(155)
    val brakePedalMode get() = int(157)
    val regenerativeLevel get() = int(160)
    val signalPedalState get() = int(163)
    val enduranceMode get() = int(166)
    val overSpeedSoundMode get() = int(180)
    val speedLimitSoundMode get() = int(182)
}

/** Informations générales (queryClient 1). */
class GeneralService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.general.ICarGeneralService") {
    val latestSpeed get() = float(17)
    val totalMileage get() = int(23)
}
