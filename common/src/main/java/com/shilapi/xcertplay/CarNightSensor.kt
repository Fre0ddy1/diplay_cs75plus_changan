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
    private var connection: Any? = null
    private var connecting = false
    private var connected = false
    /** Which reflection step last ran; the attach failure log names it. */
    private var step = "start"
    private var lastNight: Boolean? = null
    /** Sensor callbacks arrive on a binder thread; the night-mode controller touches UI. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Reflection wraps the real failure in InvocationTargetException; its cause is the answer. */
    private fun describe(error: Throwable): String {
        val cause = error.cause
        return if (cause == null) {
            "${error.javaClass.simpleName}: ${error.message}"
        } else {
            "${error.javaClass.simpleName} <- ${cause.javaClass.name}: ${cause.message}"
        }
    }

    fun start() {
        if (car != null || connecting) return
        connecting = true
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val connectionClass = Class.forName("android.content.ServiceConnection")
            // This head unit's android.car (Android 9) only has the two- and three-argument
            // createCar; the one-argument overload arrived later. Car.createCar(context) threw
            // NoSuchMethodException on every session (field 2026-11-01 16:40).
            val connection = java.lang.reflect.Proxy.newProxyInstance(
                connectionClass.classLoader,
                arrayOf(connectionClass),
            ) { _, method, args ->
                when (method.name) {
                    "onServiceConnected" -> {
                        connected = true
                        attach(carClass)
                    }
                    "onServiceDisconnected" -> {
                        connected = false
                        log("Night: car service disconnected")
                    }
                }
                null
            }
            this.connection = connection
            val car = carClass.getMethod(
                "createCar",
                android.content.Context::class.java,
                connectionClass,
            ).invoke(null, context, connection)
            this.car = car
            // createCar only builds the client; connect() is what starts the binding. The head
            // unit's own CarAudioManagerUtil calls createCar(...) then connect(), and the manager
            // is obtained in onServiceConnected.
            carClass.getMethod("connect").invoke(car)
            log("Night: car connecting")
            // createCar is asynchronous; if the service is already up, attach now instead of
            // waiting for a callback that may have raced past us.
            runCatching {
                val ready = carClass.getMethod("isConnected").invoke(car) as? Boolean
                if (ready == true) attach(carClass)
            }
        }.onFailure {
            connecting = false
            log("Night: car unavailable " + describe(it))
        }
    }

    /** getCarManager("sensor") - there is no getSensorManager() in this android.car build. */
    private fun attach(carClass: Class<*>) {
        if (sensorManager != null) return
        runCatching {
            val car = this.car ?: return
            step = "getCarManager"
            val manager = carClass.getMethod("getCarManager", String::class.java)
                .invoke(car, "sensor") ?: run { log("Night: getCarManager returned null"); return }
            sensorManager = manager
            val managerClass = manager.javaClass
            step = "listenerClass"
            val listenerClass = Class.forName("android.car.hardware.CarSensorManager\$OnSensorChangedListener")
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onSensorChanged") {
                    args?.firstOrNull()?.let(::handleEvent)
                }
                null
            }
            listener = callback
            step = "registerListener"
            managerClass.getMethod(
                "registerListener",
                listenerClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(manager, callback, SENSOR_TYPE_NIGHT, 0)
            step = "getLatestSensorEvent"
            runCatching {
                val event = managerClass.getMethod("getLatestSensorEvent", Int::class.javaPrimitiveType)
                    .invoke(manager, SENSOR_TYPE_NIGHT)
                event?.let(::handleEvent)
            }
            log("Night: sensor registered")
        }.onFailure {
            log("Night: sensor attach failed at " + step + " -> " + describe(it))
        }
    }

    fun stop() {
        val manager = sensorManager
        if (manager != null) {
            runCatching {
                manager.javaClass.getMethod(
                    "unregisterListener",
                    Class.forName("android.car.hardware.CarSensorManager\$OnSensorChangedListener"),
                ).invoke(manager, listener)
            }
        }
        sensorManager = null
        listener = null
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        connection = null
        connected = false
        connecting = false
    }

    /**
     * CarSensorEvent.intValues[0] for SENSOR_TYPE_NIGHT: 1 when the panel considers it night.
     *
     * Read straight from the public array instead of through NightData: its constructor is private
     * (only CarSensorEvent may build one through a synthetic accessor), so reflection had to bail
     * out with InvocationTargetException and never produced a value (field 2026-11-01 17:02).
     * getNightData() copies the same intValues[0] into NightData.isNightMode, so this is the same
     * reading one step earlier.
     */
    private fun handleEvent(event: Any) {
        val night = runCatching {
            val values = event.javaClass.getField("intValues").get(event) as? IntArray
            if (values == null || values.isEmpty()) return
            values[0] != 0
        }.onFailure {
            log("Night: event parse failed -> " + describe(it))
        }.getOrNull() ?: return
        if (lastNight == night) return
        lastNight = night
        log("Night: car night=" + night)
        main.post { onNight(night) }
    }
}
