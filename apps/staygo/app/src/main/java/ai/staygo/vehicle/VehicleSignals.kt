package ai.staygo.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Vehicle inputs for the decision model.
 * - Pleos Vehicle SDK: integer SOC (query + subscription).
 * - AAOS CarPropertyManager (reflection, android.car is provided by the platform): battery level (Wh),
 *   charge state, instantaneous charge rate (mW), battery capacity (Wh).
 * - CarUxRestrictionsManager: whether the current driving state requires distraction optimization.
 */
class VehicleSignals(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onSoc(soc: Int)
        fun onBatteryLevelWh(wh: Double)
        fun onChargeState(state: Int)
        fun onInstantaneousRateKw(kw: Double)
        fun onCapacityWh(wh: Double)
        fun onRequiresDistractionOptimization(required: Boolean)
        fun onSignalError(source: String, message: String)
    }

    private var vehicle: Vehicle? = null
    private var car: Any? = null
    private var cpm: Any? = null
    private var propertyCallback: Any? = null
    private var uxManager: Any? = null
    private var uxListener: Any? = null

    fun start() {
        startPleosSoc()
        startAaos()
        startUxRestrictions()
    }

    fun stop() {
        try { vehicle?.release() } catch (_: Throwable) {}
        try {
            val cb = propertyCallback
            val m = cpm
            if (cb != null && m != null) {
                val cbClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                m.javaClass.getMethod("unregisterCallback", cbClass).invoke(m, cb)
            }
        } catch (_: Throwable) {}
        try {
            val l = uxListener
            if (l != null) uxManager?.javaClass?.getMethod("unregisterListener")?.invoke(uxManager)
        } catch (_: Throwable) {}
        try { car?.javaClass?.getMethod("disconnect")?.invoke(car) } catch (_: Throwable) {}
    }

    private fun startPleosSoc() {
        try {
            val v = Vehicle(context)
            vehicle = v
            v.initialize()
            val battery = v.getEvBattery()
            battery.getEvBatteryStateOfCharge({ soc -> if (soc != null) listener.onSoc(soc) }, { e -> listener.onSignalError("SOC", e.message ?: "get failed") })
            battery.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    if (value != null) listener.onSoc(value)
                }
                override fun onFailed(e: Exception) {
                    listener.onSignalError("SOC", e.message ?: "listener failed")
                }
            })
        } catch (t: Throwable) {
            listener.onSignalError("SOC", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startAaos() {
        try {
            val carClass = Class.forName("android.car.Car")
            val c = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
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
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                listener.onSignalError(n, cause.javaClass.simpleName); null
            }
            (read("INFO_EV_BATTERY_CAPACITY") as? Float)?.let { listener.onCapacityWh(it.toDouble()) }
            (read("EV_BATTERY_LEVEL") as? Float)?.let { listener.onBatteryLevelWh(it.toDouble()) }
            (read("EV_CHARGE_STATE") as? Int)?.let { listener.onChargeState(it) }
            (read("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE") as? Float)?.let { listener.onInstantaneousRateKw(it / 1_000_000.0) }

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
                            levelId -> (value as? Float)?.let { listener.onBatteryLevelWh(it.toDouble()) }
                            stateId -> (value as? Int)?.let { listener.onChargeState(it) }
                            rateId -> (value as? Float)?.let { listener.onInstantaneousRateKw(it / 1_000_000.0) }
                        }
                    }
                }
                null
            }
            propertyCallback = cb
            val register = m.javaClass.getMethod("registerCallback", cbClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            for (pid in listOf(levelId, stateId, rateId)) {
                try { register.invoke(m, cb, pid, 1f) } catch (t: Throwable) {
                    val cause = (t as? InvocationTargetException)?.targetException ?: t
                    listener.onSignalError("register $pid", cause.javaClass.simpleName)
                }
            }
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            listener.onSignalError("AAOS", "${cause.javaClass.simpleName}: ${cause.message}")
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
            listener.onRequiresDistractionOptimization(requiresDo(manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager)))
            val lClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val l = Proxy.newProxyInstance(lClass.classLoader, arrayOf(lClass)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "StayGoUxListener"
                    else -> {
                        if (method.name == "onUxRestrictionsChanged") listener.onRequiresDistractionOptimization(requiresDo(args?.get(0)))
                        null
                    }
                }
            }
            uxListener = l
            manager.javaClass.getMethod("registerListener", lClass).invoke(manager, l)
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "UX restrictions unavailable: ${cause.message}")
            listener.onSignalError("UX", cause.javaClass.simpleName)
        }
    }

    companion object {
        const val TAG = "StayGo"
        const val CHARGE_STATE_CHARGING = 1
        const val CHARGE_STATE_FULLY_CHARGED = 2
    }
}
