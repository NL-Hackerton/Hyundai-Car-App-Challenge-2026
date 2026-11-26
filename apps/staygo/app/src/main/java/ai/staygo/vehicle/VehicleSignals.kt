package ai.staygo.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.staygo.core.VehicleLinkFailure
import ai.staygo.core.VehicleSource
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Vehicle inputs for the decision model.
 * - Pleos Vehicle SDK: integer SOC. The capability is checked first; the value is queried and subscribed only
 *   when the vehicle reports read access. Without it the vehicle link is reported as failed.
 * - AAOS CarPropertyManager (reflection, android.car is provided by the platform): battery level (Wh),
 *   charge state, instantaneous charge rate (mW), battery capacity (Wh). Each property is used only when the
 *   vehicle has a configuration for it, and a value only when its status is "available". These are optional:
 *   when they cannot be read the engine falls back to driver confirmation and the SOC-based rule.
 *
 * SDK and binder callbacks arrive on arbitrary threads; everything is forwarded on the main thread.
 */
class VehicleSignals(private val context: Context) : VehicleSource {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: VehicleSource.Listener? = null

    private var vehicle: Vehicle? = null
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var car: Any? = null
    private var cpm: Any? = null
    private var propertyCallback: Any? = null

    override fun start(listener: VehicleSource.Listener) {
        this.listener = listener
        startPleosSoc()
        startAaos()
    }

    override fun stop() {
        listener = null
        main.removeCallbacksAndMessages(null)
        try { socListener?.let { vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(it) } } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
        try {
            val cb = propertyCallback
            val m = cpm
            if (cb != null && m != null) {
                val cbClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                m.javaClass.getMethod("unregisterCallback", cbClass).invoke(m, cb)
            }
        } catch (_: Throwable) {}
        try { car?.let { it.javaClass.getMethod("disconnect").invoke(it) } } catch (_: Throwable) {}
        vehicle = null; socListener = null; car = null; cpm = null; propertyCallback = null
    }

    private inline fun emit(crossinline block: VehicleSource.Listener.() -> Unit) {
        main.post { listener?.block() }
    }

    private fun socFailure(e: Throwable): VehicleLinkFailure =
        if (e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true) VehicleLinkFailure.PERMISSION_DENIED
        else VehicleLinkFailure.UNAVAILABLE

    private fun startPleosSoc() {
        try {
            val v = Vehicle(context)
            vehicle = v
            v.initialize()
            val battery = v.getEvBattery()
            battery.checkEvBatteryStateOfChargeCapabilities({ config ->
                val access = config.accessType
                if (access != AccessType.READ && access != AccessType.READ_WRITE) {
                    emit { onVehicleLinkFailed(VehicleLinkFailure.SOC_UNSUPPORTED, "accessType=$access") }
                    return@checkEvBatteryStateOfChargeCapabilities
                }
                // Supported: only now query and subscribe.
                try {
                    battery.getEvBatteryStateOfCharge(
                        { soc -> if (soc != null) emit { onSoc(soc) } },
                        { e -> emit { onVehicleLinkFailed(socFailure(e), "get: ${e.message}") } },
                    )
                    val l = object : EvBatteryStateOfChargeListener {
                        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                            if (value != null) emit { onSoc(value) }
                        }
                        override fun onFailed(e: Exception) {
                            emit { onVehicleLinkFailed(socFailure(e), "listener: ${e.message}") }
                        }
                    }
                    socListener = l
                    battery.registerEvBatteryStateOfCharge(l)
                } catch (t: Throwable) {
                    emit { onVehicleLinkFailed(socFailure(t), "${t.javaClass.simpleName}: ${t.message}") }
                }
            }, { e -> emit { onVehicleLinkFailed(socFailure(e), "capability: ${e.message}") } })
        } catch (t: Throwable) {
            emit { onVehicleLinkFailed(socFailure(t), "${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun startAaos() {
        try {
            val carClass = Class.forName("android.car.Car")
            val c = carClass.getMethod("createCar", Context::class.java).invoke(null, context) ?: error("no car service")
            car = c
            if (!(carClass.getMethod("isConnected").invoke(c) as Boolean)) carClass.getMethod("connect").invoke(c)
            val name = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val m = carClass.getMethod("getCarManager", String::class.java).invoke(c, name) ?: error("no property service")
            cpm = m
            val ids = Class.forName("android.car.VehiclePropertyIds")
            fun id(n: String) = ids.getField(n).getInt(null)
            val getConfig = m.javaClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType)
            /** A property the vehicle has no configuration for is not read and not subscribed. */
            fun supported(n: String): Boolean = try {
                getConfig.invoke(m, id(n)) != null
            } catch (t: Throwable) {
                Log.w(TAG, "config $n: ${cause(t).javaClass.simpleName}"); false
            }
            val getProperty = m.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            fun read(n: String): Any? = try {
                if (!supported(n)) null else availableValue(getProperty.invoke(m, id(n), 0))
            } catch (t: Throwable) {
                Log.w(TAG, "read $n: ${cause(t).javaClass.simpleName}"); null
            }
            (read("INFO_EV_BATTERY_CAPACITY") as? Float)?.let { emit { onCapacityWh(it.toDouble()) } }
            (read("EV_BATTERY_LEVEL") as? Float)?.let { emit { onBatteryLevelWh(it.toDouble()) } }
            (read("EV_CHARGE_STATE") as? Int)?.let { emit { onChargeState(it) } }
            (read("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE") as? Float)?.let { emit { onInstantaneousRateKw(it / 1_000_000.0) } }

            val levelId = id("EV_BATTERY_LEVEL")
            val stateId = id("EV_CHARGE_STATE")
            val rateId = id("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE")
            val cbClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                    "equals" -> return@newProxyInstance self === args?.get(0)
                    "toString" -> return@newProxyInstance "StayGoPropertyCallback"
                    "onChangeEvent" -> {
                        val ev = args?.getOrNull(0)
                        val pid = ev?.javaClass?.getMethod("getPropertyId")?.invoke(ev) as? Int
                        val value = availableValue(ev)
                        when (pid) {
                            levelId -> (value as? Float)?.let { emit { onBatteryLevelWh(it.toDouble()) } }
                            stateId -> (value as? Int)?.let { emit { onChargeState(it) } }
                            rateId -> (value as? Float)?.let { emit { onInstantaneousRateKw(it / 1_000_000.0) } }
                        }
                    }
                }
                null
            }
            propertyCallback = cb
            val register = m.javaClass.getMethod("registerCallback", cbClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            for (n in listOf("EV_BATTERY_LEVEL", "EV_CHARGE_STATE", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE")) {
                if (!supported(n)) continue
                try { register.invoke(m, cb, id(n), 1f) } catch (t: Throwable) {
                    Log.w(TAG, "register $n: ${cause(t).javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAOS car properties unavailable: ${cause(t).javaClass.simpleName}")
        }
    }

    /** The value of a CarPropertyValue, or null when its status is not "available" (error, unavailable). */
    private fun availableValue(propertyValue: Any?): Any? {
        if (propertyValue == null) return null
        val status = propertyValue.javaClass.getMethod("getStatus").invoke(propertyValue) as? Int
        if (status != STATUS_AVAILABLE) return null
        return propertyValue.javaClass.getMethod("getValue").invoke(propertyValue)
    }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    companion object {
        const val TAG = "StayGo"
        /** android.car.hardware.CarPropertyValue.STATUS_AVAILABLE */
        private const val STATUS_AVAILABLE = 0
    }
}
