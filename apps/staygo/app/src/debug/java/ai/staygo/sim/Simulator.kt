package ai.staygo.sim

import ai.staygo.core.NaviSource
import ai.staygo.core.RestrictionSource
import ai.staygo.core.VehicleSource
import android.os.Handler
import android.os.Looper

/** Runs a [SimWorld] on the main thread: one virtual second every 1000/speed ms. */
class Simulator(scenario: Scenario, private val speed: Int) {
    private val world = SimWorld(scenario)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val stepper = object : Runnable {
        override fun run() {
            if (!running) return
            world.step()
            main.postDelayed(this, 1_000L / speed)
        }
    }

    fun nowMs(): Long = world.nowMs

    val vehicle = object : VehicleSource {
        override fun start(listener: VehicleSource.Listener) {
            world.vehicle.start(listener)
            if (!running) {
                running = true
                main.postDelayed(stepper, 1_000L / speed)
            }
        }

        override fun stop() {
            running = false
            main.removeCallbacks(stepper)
            world.vehicle.stop()
        }
    }

    val navi: NaviSource get() = world.navi
    val restrictions: RestrictionSource get() = world.restrictions
}
