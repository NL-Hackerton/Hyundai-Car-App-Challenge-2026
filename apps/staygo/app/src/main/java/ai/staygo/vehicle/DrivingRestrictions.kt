package ai.staygo.vehicle

import ai.staygo.core.RestrictionSource
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * The platform's UX restriction state through CarUxRestrictionsManager (reflection, android.car is provided
 * by the platform). It needs no permission, so it runs before consent and before the vehicle permissions.
 *
 * Fail-closed: a restriction object that is missing or cannot be read counts as "restricted", and when the
 * manager itself is unavailable nothing is reported at all, which the engine treats as locked.
 */
class DrivingRestrictions(private val context: Context) : RestrictionSource {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var onRestricted: ((Boolean) -> Unit)? = null
    private var car: Any? = null
    private var manager: Any? = null

    override fun start(onRestricted: (Boolean) -> Unit) {
        this.onRestricted = onRestricted
        try {
            val carClass = Class.forName("android.car.Car")
            val c = carClass.getMethod("createCar", Context::class.java).invoke(null, context) ?: error("no car service")
            car = c
            if (!(carClass.getMethod("isConnected").invoke(c) as Boolean)) carClass.getMethod("connect").invoke(c)
            val name = carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            val m = carClass.getMethod("getCarManager", String::class.java).invoke(c, name) ?: error("no UX restriction service")
            manager = m
            emit(requiresDo(m.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(m)))
            val lClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val l = Proxy.newProxyInstance(lClass.classLoader, arrayOf(lClass)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "StayGoUxListener"
                    else -> {
                        if (method.name == "onUxRestrictionsChanged") emit(requiresDo(args?.getOrNull(0)))
                        null
                    }
                }
            }
            m.javaClass.getMethod("registerListener", lClass).invoke(m, l)
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "UX restrictions unavailable: ${cause.javaClass.simpleName}")
        }
    }

    override fun stop() {
        onRestricted = null
        main.removeCallbacksAndMessages(null)
        try { manager?.let { it.javaClass.getMethod("unregisterListener").invoke(it) } } catch (_: Throwable) {}
        try { car?.let { it.javaClass.getMethod("disconnect").invoke(it) } } catch (_: Throwable) {}
        manager = null
        car = null
    }

    /** Anything other than an explicit "not required" is restricted. */
    private fun requiresDo(restrictions: Any?): Boolean = try {
        (restrictions?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(restrictions) as? Boolean) ?: true
    } catch (t: Throwable) {
        true
    }

    private fun emit(restricted: Boolean) {
        main.post { onRestricted?.invoke(restricted) }
    }

    private companion object {
        const val TAG = "StayGo"
    }
}
