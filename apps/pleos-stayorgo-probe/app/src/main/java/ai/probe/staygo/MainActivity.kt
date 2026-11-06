package ai.probe.staygo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical probe for Stay-or-Go unknowns:
 * 1) 3rd-party CAR_ENERGY grant + Pleos SOC + AAOS charge properties
 * 2) NaviHelper onDrivingInfo/onTripInfo while parked / with route
 */
class MainActivity : ComponentActivity() {

    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var carObj: Any? = null
    private var carPropertyManager: Any? = null

    private val socCallbackCount = AtomicInteger(0)
    private var lastSocMs: Long = 0L
    private val aaosCallbackCount = AtomicInteger(0)
    // EV_BATTERY_LEVEL (Wh) samples for the fallback charging-power estimate.
    private val levelSamples = ArrayDeque<Pair<Long, Float>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (perm, granted) ->
            log("PERM result: $perm => $granted")
        }
        startProbes()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(logView) })
        log("=== StayOrGo Probe start ===")
        log("package=${packageName} uid=${applicationInfo.uid} targetSdk=${applicationInfo.targetSdkVersion}")
        log("isSystemApp=${(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val needed = listOf(
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_INFO",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_INFO",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        needed.forEach { perm ->
            val state = ContextCompat.checkSelfPermission(this, perm)
            log("PERM check: $perm => ${if (state == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED/UNKNOWN"}")
        }
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (toAsk.isEmpty()) {
            startProbes()
        } else {
            log("PERM requesting: ${toAsk.joinToString()}")
            permissionLauncher.launch(toAsk)
        }
    }

    private fun startProbes() {
        probePleosSoc()
        probeAaosCarProperties()
        probeNaviHelper()
        probeUxRestrictions()
        // Periodic AAOS poll for 90s to estimate update rate under VHAL changes
        mainHandler.postDelayed({ summarizeRates() }, 90_000L)
    }

    private fun probeUxRestrictions() {
        log("--- CarUxRestrictions ---")
        try {
            val car = carObj ?: return log("UX skipped: Car not connected")
            val name = car.javaClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            val manager = car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, name)
                ?: return log("UX manager unavailable")
            fun describe(restrictions: Any?): String {
                val requiresDo = restrictions?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(restrictions)
                val active = restrictions?.javaClass?.getMethod("getActiveRestrictions")?.invoke(restrictions)
                return "requiresDO=$requiresDo active=$active"
            }
            log("UX current ${describe(manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager))}")
            val listenerClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "StayOrGoUxListener"
                    else -> {
                        if (method.name == "onUxRestrictionsChanged") log("UX changed ${describe(args?.get(0))}")
                        null
                    }
                }
            }
            manager.javaClass.getMethod("registerListener", listenerClass).invoke(manager, listener)
            log("UX registerListener OK")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            log("UX probe FAIL: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun probePleosSoc() {
        log("--- Pleos Vehicle SOC ---")
        try {
            val v = Vehicle(this)
            vehicle = v
            v.initialize()
            log("Pleos Vehicle.initialize OK")
            val battery = v.getEvBattery()
            battery.checkEvBatteryStateOfChargeCapabilities({ cfg ->
                log("SOC capability: $cfg")
            }, { e -> log("SOC capability FAIL: ${e.message}") })

            battery.getEvBatteryStateOfCharge({ soc ->
                log("SOC getEvBatteryStateOfCharge => $soc (Integer)")
            }, { e -> log("SOC get FAIL: ${e.javaClass.simpleName}: ${e.message}") })

            battery.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    val now = SystemClock.elapsedRealtime()
                    val n = socCallbackCount.incrementAndGet()
                    val dt = if (lastSocMs == 0L) -1L else (now - lastSocMs)
                    lastSocMs = now
                    log("SOC callback#$n value=$value dtMs=$dt")
                }
                override fun onFailed(e: Exception) {
                    log("SOC listener onFailed: ${e.message}")
                }
            })
            log("SOC registerEvBatteryStateOfCharge OK")
        } catch (t: Throwable) {
            log("Pleos Vehicle unavailable: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probeAaosCarProperties() {
        log("--- AAOS CarPropertyManager ---")
        try {
            val carClass = Class.forName("android.car.Car")
            val createMethod = carClass.getMethod("createCar", android.content.Context::class.java)
            val car = createMethod.invoke(null, this)
            carObj = car
            val connected = carClass.getMethod("isConnected").invoke(car) as Boolean
            log("Car.createCar isConnected=$connected")
            if (!connected) carClass.getMethod("connect").invoke(car)
            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            val cpmName = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val cpm = getCarManager.invoke(car, cpmName)
            carPropertyManager = cpm
            log("Car.createCar + PROPERTY_SERVICE OK class=${cpm?.javaClass?.name}")

            val idsClass = Class.forName("android.car.VehiclePropertyIds")
            val props = listOf(
                "EV_BATTERY_LEVEL",
                "EV_CHARGE_STATE",
                "EV_CHARGE_TIME_REMAINING",
                "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
                "INFO_EV_BATTERY_CAPACITY",
                "GEAR_SELECTION",
                "PERF_VEHICLE_SPEED",
            )
            // CarPropertyManager.getProperty(int propId, int areaId); areaId 0 = global
            val getProperty = cpm!!.javaClass.getMethod(
                "getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            for (name in props) {
                try {
                    val id = idsClass.getField(name).getInt(null)
                    val value = getProperty.invoke(cpm, id, 0)
                    // CarPropertyValue has getValue()
                    val raw = value?.javaClass?.getMethod("getValue")?.invoke(value)
                    log("AAOS get $name($id) => $raw")
                } catch (t: Throwable) {
                    val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    log("AAOS get $name FAIL: ${c.javaClass.simpleName}: ${c.message}")
                }
            }

            // Register continuous callback if API available
            tryRegisterAaosCallback(cpm, idsClass)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            log("AAOS Car unavailable: ${cause.javaClass.name}: ${cause.message}")
        }
    }

    private fun tryRegisterAaosCallback(cpm: Any, idsClass: Class<*>) {
        try {
            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { self, method, args ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                    "equals" -> return@newProxyInstance (self === args?.get(0))
                    "toString" -> return@newProxyInstance "StayOrGoProbeCallback"
                }
                if (method.name == "onChangeEvent" && args != null && args.isNotEmpty()) {
                    val n = aaosCallbackCount.incrementAndGet()
                    val prop = args[0]
                    val id = prop?.javaClass?.getMethod("getPropertyId")?.invoke(prop)
                    val raw = prop?.javaClass?.getMethod("getValue")?.invoke(prop)
                    log("AAOS callback#$n propId=$id value=$raw")
                    if (id == EV_BATTERY_LEVEL_ID && raw is Float) estimatePowerFromLevel(raw)
                } else if (method.name == "onErrorEvent") {
                    log("AAOS callback error: ${args?.joinToString()}")
                }
                null
            }
            val rate = 1f // 1 Hz
            val register = cpm.javaClass.getMethod(
                "registerCallback", callbackClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            for (name in listOf(
                "EV_CHARGE_TIME_REMAINING",
                "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
                "EV_CHARGE_STATE",
                "EV_BATTERY_LEVEL",
            )) {
                val id = idsClass.getField(name).getInt(null)
                try {
                    val ok = register.invoke(cpm, proxy, id, rate)
                    log("AAOS registerCallback $name@$rate Hz => $ok")
                } catch (t: Throwable) {
                    val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    log("AAOS registerCallback $name FAIL: ${c.javaClass.simpleName}: ${c.message}")
                }
            }
        } catch (t: Throwable) {
            log("AAOS callback setup FAIL: ${t.message}")
        }
    }

    private fun probeNaviHelper() {
        log("--- NaviHelper DrivingInfo/TripInfo ---")
        try {
            val helper = NaviHelper(this)
            navi = helper
            helper.initialize({
                log("NaviHelper.initialize OK")
                helper.addListener(object : NaviHelperEventListener {
                    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
                        log("NAV onDrivingInfo dest=${describe(drivingInfo.destination)} waypoints=${drivingInfo.waypoints.size}")
                        drivingInfo.waypoints.forEachIndexed { i, wp -> log("NAV   waypoint#$i ${describe(wp)}") }
                    }

                    override fun onTripInfo(tripInfo: TripInfo) {
                        val points = tripInfo.summary.routePoints
                        log("NAV onTripInfo distance=${tripInfo.distance} eta=${tripInfo.eta} estSoc=${tripInfo.estimatedSocAtDest} energy=${tripInfo.energyConsumption} points=${points.size}")
                        points.forEachIndexed { i, p ->
                            log("NAV   point#$i type=${p.poiType} kind=${p.pointType} ratio=${p.ratioOnRoute} name=${p.poiName} lat=${p.latitude} lng=${p.longitude}")
                        }
                    }

                    override fun onRouteStateInfo(routeStateInfo: ai.pleos.playground.navi.data.RouteStateInfo) {
                        log("NAV onRouteStateInfo routing=${routeStateInfo.isRouting} mode=${routeStateInfo.naviMode} remainChg=${routeStateInfo.remainDestinationChargingInt}")
                    }

                    override fun onDestinationInfo(destinationInfo: ai.pleos.playground.navi.data.DestinationInfo) {
                        log("NAV onDestinationInfo name=${destinationInfo.name} remainDist=${destinationInfo.remainDistance}")
                    }

                    override fun onCurrentLocationInfo(currentLocationInfo: ai.pleos.playground.navi.data.CurrentLocationInfo) {
                        log("NAV onCurrentLocationInfo lat=${currentLocationInfo.latitude} lng=${currentLocationInfo.longitude} routeId=${currentLocationInfo.routeId}")
                    }

                    override fun onError(errorCode: ai.pleos.playground.navi.constants.NaviErrorCode) {
                        log("NAV onError $errorCode")
                    }
                })
                // Pull once
                try { helper.getRouteStateInfo(); log("NAV getRouteStateInfo requested") } catch (t: Throwable) { log("NAV getRouteStateInfo: ${t.message}") }
                try { helper.getDestinationInfo(); log("NAV getDestinationInfo requested") } catch (t: Throwable) { log("NAV getDestinationInfo: ${t.message}") }
                try { helper.getCurrentLocationInfo(); log("NAV getCurrentLocationInfo requested") } catch (t: Throwable) { log("NAV getCurrentLocationInfo: ${t.message}") }
                // Destination comes from intent extras so long routes (EV charging stops) can be probed.
                val lat = intent.getDoubleExtra("destLat", 37.5665)
                val lng = intent.getDoubleExtra("destLng", 126.9780)
                try {
                    helper.requestTrip(ai.pleos.playground.navi.data.TripRequest(lat, lng, "", "ProbeDest", ""))
                    log("NAV requestTrip sent lat=$lat lng=$lng")
                } catch (t: Throwable) {
                    log("NAV requestTrip FAIL: ${t.message}")
                }
                if (intent.getBooleanExtra("route", false)) {
                    try {
                        helper.requestRoute(
                            ai.pleos.playground.navi.data.RouteInfo(
                                lat, lng, null, null, "ProbeDest", "", "", "",
                                ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false
                            )
                        )
                        log("NAV requestRoute sent lat=$lat lng=$lng")
                    } catch (t: Throwable) {
                        log("NAV requestRoute FAIL: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }, { err ->
                log("NaviHelper.initialize FAIL: ${err.message}")
            })
        } catch (t: Throwable) {
            log("NaviHelper unavailable: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun describe(s: ai.pleos.playground.navi.data.DrivingState): String =
        "name=${s.name} dist=${s.distance} dur=${s.duration} remainChg=${s.remainDestinationChargingValue} " +
            "charging=${s.isChargingStation} recommended=${s.isRecommendedChargingStation} maxPower=${s.maxChargingPower} " +
            "targetSoc=${s.targetSoc} chargers=${s.chargerCount} plan=${s.chargingPlan?.let { "${it.startSoc}->${it.endSoc}/${it.duration}" }}"

    private fun summarizeRates() {
        log("=== SUMMARY after ~90s ===")
        log("SOC callbacks=${socCallbackCount.get()} AAOS callbacks=${aaosCallbackCount.get()}")
        // Re-check permissions
        for (perm in listOf(
            "pleos.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY",
        )) {
            val g = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            log("FINAL PERM $perm => $g")
        }
        // Re-read AAOS once more
        probeAaosCarProperties()
        log("=== probe idle; leave app running for manual VHAL tweaks ===")
    }

    /** Charging power estimated from the rate of change of EV_BATTERY_LEVEL over a 10 s window. */
    private fun estimatePowerFromLevel(wh: Float) {
        val now = SystemClock.elapsedRealtime()
        levelSamples.addLast(now to wh)
        while (levelSamples.size > 1 && now - levelSamples.first().first > 10_000L) levelSamples.removeFirst()
        val (t0, e0) = levelSamples.first()
        if (now - t0 >= 5_000L) {
            val kw = (wh - e0) / ((now - t0) / 3_600_000.0) / 1000.0
            log("POWER estimate from EV_BATTERY_LEVEL: %.1f kW over %.1f s".format(kw, (now - t0) / 1000.0))
        }
    }

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        Log.i(TAG, line)
        runOnUiThread {
            logView.append(line + "\n")
        }
    }

    override fun onDestroy() {
        try { vehicle?.release() } catch (_: Throwable) {}
        try { navi?.release() } catch (_: Throwable) {}
        try {
            carObj?.javaClass?.getMethod("disconnect")?.invoke(carObj)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StayOrGoProbe"
        private const val EV_BATTERY_LEVEL_ID = 291504905
    }
}
