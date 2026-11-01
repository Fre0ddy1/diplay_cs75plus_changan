package com.shilapi.xcertplay

/**
 * The head unit's own day/night sensor (SENSOR_TYPE_NIGHT = 12).
 *
 * The panel dims itself in tunnels and brightens again on exit from this sensor, not from the
 * headlight switch, which is why the old "follow the headlights" approach never lined up. Reading
 * it makes CarPlay's night mode follow the same signal the car uses.
 *
 * `android.car` is a device framework jar, not part of the compile SDK, so everything here goes
 * through reflection - the same way InCallClusterBridge reaches `android.car.Car`.
 */
internal class CarNightSensor(
    private val context: android.content.Context,
    private val log: (String) -> Unit,
    private val onNight: (Boolean) -> Unit,
) {
    private companion object {
        const val SENSOR_TYPE_NIGHT = 12
        const val TAG = "CarNightSensor"
    }

    private var car: Any? = null
    private var sensorManager: Any? = null
    private var listener: Any? = null
    private var lastNight: Boolean? = null
    /** Sensor callbacks arrive on a binder thread; the night-mode controller touches UI. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (sensorManager != null) return
        runCatching {
            val carClass = Class.forName("android.car.Car")
            car = carClass.getMethod("createCar", android.content.Context::class.java)
                .invoke(null, context)
            val manager = carClass.getMethod("getSensorManager").invoke(car) ?: return
            sensorManager = manager
            val managerClass = manager.javaClass
            val listenerClass = Class.forName("android.car.hardware.CarSensorManager\$CarSensorEventListener")
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onSensorChanged") {
                    val event = args?.firstOrNull() ?: return@newProxyInstance null
                    handleEvent(event)
                }
                null
            }
            listener = callback
            val registered = managerClass.getMethod(
                "registerListener",
                listenerClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(manager, callback, SENSOR_TYPE_NIGHT, 0) as? Int
            if (registered != null && registered <= 0) {
                log("Night: sensor rejected, registerListener=$registered")
                return
            }
            // Report the current value at once so a session started after dark is not stuck.
            runCatching {
                val event = managerClass.getMethod("getLatestSensorEvent", Int::class.javaPrimitiveType)
                    .invoke(manager, SENSOR_TYPE_NIGHT)
                event?.let(::handleEvent)
            }
            log("Night: sensor registered")
        }.onFailure {
            log("Night: sensor unavailable ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun stop() {
        val manager = sensorManager ?: return
        runCatching {
            manager.javaClass.getMethod(
                "unregisterListener",
                Class.forName("android.car.hardware.CarSensorManager\$CarSensorEventListener"),
            ).invoke(manager, listener)
        }
        sensorManager = null
        listener = null
        // Car.createCar hands out a framework client; release it or it keeps a binder alive.
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
    }

    /**
     * CarSensorEvent carries a NightData payload. Its field is `isNightMode` (taken from the head
     * unit's own android.car jar), and it is reached through getNightData(out) rather than a field
     * read - reading a `nightData` field silently returned null on every event, which is why the
     * headlights changed nothing (field 2026-11-01 15:49).
     */
    private fun handleEvent(event: Any) {
        val night = runCatching {
            val dataClass = Class.forName("android.car.hardware.CarSensorEvent\$NightData")
            val out = dataClass.getDeclaredConstructor().newInstance()
            event.javaClass.getMethod("getNightData", dataClass).invoke(event, out)
            dataClass.getField("isNightMode").getBoolean(out)
        }.onFailure {
            log("Night: event parse failed ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return
        if (lastNight == night) return
        lastNight = night
        log("Night: car night=$night")
        main.post { onNight(night) }
    }
}
