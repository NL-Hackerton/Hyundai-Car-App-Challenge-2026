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
 * - Pleos Vehicle SDK: integer SOC (capability check, query, subscription). Without it the vehicle link is reported as failed.
 * - AAOS CarPropertyManager (reflection, android.car is provided by the platform): battery level (Wh),
 *   charge state, instantaneous charge rate (mW), battery capacity (Wh). These are optional: when they cannot
 *   be read the engine falls back to driver confirmation and the SOC-based rule.
 * - CarUxRestrictionsManager: whether the current driving state requires distraction optimization.
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
    private var uxManager: Any? = null

    override fun start(listener: VehicleSource.Listener) {
        this.listener = listener
        startPleosSoc()
        startAaos()
        startUxRestrictions()
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
        try { uxManager?.let { it.javaClass.getMethod("unregisterListener").invoke(it) } } catch (_: Throwable) {}
        try { car?.let { it.javaClass.getMethod("disconnect").invoke(it) } } catch (_: Throwable) {}
        vehicle = null; socListener = null; car = null; cpm = null; propertyCallback = null; uxManager = null
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
                }
            }, { e -> Log.w(TAG, "SOC capability check failed: ${e.message}") })
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
            val getProperty = m.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            fun read(n: String): Any? = try {
                val v = getProperty.invoke(m, id(n), 0)
                v?.javaClass?.getMethod("getValue")?.invoke(v)
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
                        val value = ev?.javaClass?.getMethod("getValue")?.invoke(ev)
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
            for (pid in listOf(levelId, stateId, rateId)) {
                try { register.invoke(m, cb, pid, 1f) } catch (t: Throwable) {
                    Log.w(TAG, "register $pid: ${cause(t).javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAOS car properties unavailable: ${cause(t).javaClass.simpleName}: ${cause(t).message}")
        }
    }

    private fun startUxRestrictions() {
        val c = car ?: return
        try {
            val name = c.javaClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            val manager = c.javaClass.getMethod("getCarManager", String::class.java).invoke(c, name) ?: return
            uxManager = manager
            fun requiresDo(r: Any?): Boolean =
                (r?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(r) as? Boolean) ?: false
            val current = requiresDo(manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager))
            emit { onDrivingRestricted(current) }
            val lClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val l = Proxy.newProxyInstance(lClass.classLoader, arrayOf(lClass)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "StayGoUxListener"
                    else -> {
                        if (method.name == "onUxRestrictionsChanged") {
                            val required = requiresDo(args?.get(0))
                            emit { onDrivingRestricted(required) }
                        }
                        null
                    }
                }
            }
            manager.javaClass.getMethod("registerListener", lClass).invoke(manager, l)
        } catch (t: Throwable) {
            Log.w(TAG, "UX restrictions unavailable: ${cause(t).message}")
        }
    }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    companion object {
        const val TAG = "StayGo"
    }
}
