package dev.hram.androsaur.camera

import android.hardware.camera2.*
import android.os.Handler
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.UUID

/** Main-thread commands; immutable snapshots may be read by HTTP workers. Never runs an AF scan implicitly. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class FocusController(private val main: Handler, private val executor: Executor) {
    data class State(val state: String = "idle", val manualLock: Boolean = false,
        val message: String = "Фокус по запросу. Наведите резкость перед съёмкой.", val distance: Float? = null, val revision: Long = 0)
    @Volatile var state = State()
        private set(value) { field = value.copy(revision = field.revision + 1) }
    private val instance = UUID.randomUUID().toString()
    val busy get() = state.state in setOf("focusing", "restoring", "locking")
    private var camera: Camera? = null
    @Volatile private var generation = 0
    private var operation = 0
    private var rememberedDistance: Float? = null
    @Volatile private var lastDistance: Float? = null
    @Volatile private var focusedDistance: Float? = null
    @Volatile private var observedAfMode: Int? = null
    @Volatile private var lensMoving = false
    @Volatile private var scanCount = 0
    private var pendingOptions: CompletableFuture<Void>? = null

    fun json(): JSONObject {
        val value = state
        return JSONObject().put("state", value.state).put("manualLock", value.manualLock)
            .put("message", value.message).put("distance", value.distance ?: JSONObject.NULL)
            .put("mode", "on-demand")
            .put("revision", value.revision).put("instance", instance)
            .put("afMode", observedAfMode ?: JSONObject.NULL).put("scanCount", scanCount)
    }

    fun detach() {
        pendingOptions?.cancel(false)
        generation++; operation++; camera = null; lastDistance = null; focusedDistance = null; pendingOptions = null; observedAfMode = null
        state = State(message = "Камера выключена. Фокус будет проверен при подключении.")
    }

    fun configure(builder: ImageAnalysis.Builder) {
        val epoch = generation
        Camera2Interop.Extender(builder).apply {
            // Even initial/rebound repeating requests must not start continuous autofocus.
            setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            rememberedDistance?.let { setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
            setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (epoch != generation) return
                    val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf { it.isFinite() && it >= 0 }
                    lastDistance = distance
                    observedAfMode = result.get(CaptureResult.CONTROL_AF_MODE)
                    lensMoving = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING
                    if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
                        focusedDistance = distance
                }
            })
        }
    }

    fun attach(next: Camera) {
        camera = next
        val info = Camera2CameraInfo.from(next.cameraInfo)
        val manual = (info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f &&
            CaptureRequest.CONTROL_AF_MODE_OFF in (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())
        state = State(manualLock = manual)
        val distance = rememberedDistance.takeIf { manual }
        setStill(distance, if (distance != null) "restoring" else "idle")
    }

    private fun setStill(distance: Float?, transition: String, finalState: String = if (distance == null) "idle" else "locked") {
        val active = camera ?: return
        val epoch = generation; val request = ++operation
        val manual = state.manualLock
        state = State(transition, manual, if (transition == "restoring") "Восстанавливаем фиксированный фокус…" else "Фиксируем положение объектива…", distance)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        if (distance != null && manual) options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        try {
            val future = Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(options.build())
            val confirmed = CompletableFuture<Void>()
            pendingOptions = confirmed
            future.addListener({
                if (epoch != generation || request != operation) return@addListener
                try {
                    future.get()
                    fun verify() {
                        if (epoch != generation || request != operation) { confirmed.cancel(false); return }
                        val observed = lastDistance
                        val distanceMatches = distance == null || (observed != null && kotlin.math.abs(observed - distance) <= maxOf(.02f, distance * .02f))
                        if (observedAfMode == CaptureRequest.CONTROL_AF_MODE_OFF && distanceMatches && !lensMoving) {
                            if (finalState == "locked" && distance != null) rememberedDistance = distance
                            state = State(finalState, manual, when (finalState) {
                                "locked" -> "Фокус зафиксирован. Съёмка не запускает автофокус."
                                "failed" -> "Не удалось навести резкость. Повторите по контрастной области документа."
                                else -> "Фокус по запросу. Наведите резкость перед съёмкой."
                            }, distance)
                            confirmed.complete(null)
                        } else main.postDelayed({ verify() }, 50)
                    }
                    verify()
                } catch (e: Exception) {
                    confirmed.completeExceptionally(e)
                    state = State("failed", manual, "Не удалось подтвердить фиксацию фокуса. Повторите команду.")
                }
            }, executor)
            main.postDelayed({
                if (epoch == generation && request == operation && !confirmed.isDone) {
                    operation++
                    confirmed.completeExceptionally(IllegalStateException("Lens position was not confirmed"))
                    state = State("failed", manual, "Камера не подтвердила положение объектива. Повторите команду.")
                }
            }, 3000)
        } catch (_: Exception) { state = State("failed", manual, "Управление фокусом недоступно.") }
    }

    fun request(action: String, x: Float = .5f, y: Float = .5f, point: MeteringPoint? = null): Pair<Int, JSONObject> {
        val active = camera ?: return 503 to JSONObject().put("error", "camera_unavailable")
        if (busy) return 409 to JSONObject().put("error", "focus_busy")
        if (action == "lock") {
            val distance = lastDistance
            if (!state.manualLock || distance == null) return 409 to JSONObject().put("error", "focus_lock_unavailable")
            setStill(distance, "locking")
            return 202 to json()
        }
        val target = point ?: SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x, y)
        val actionConfig = FocusMeteringAction.Builder(target, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        if (!active.cameraInfo.isFocusMeteringSupported(actionConfig)) {
            state = State("unsupported", state.manualLock, "Камера не поддерживает наведение автофокуса в выбранной области.")
            return 409 to JSONObject().put("error", "focus_unsupported").put("focus", json())
        }
        val epoch = generation; val request = ++operation
        state = State("focusing", state.manualLock, "Наводим резкость и фиксируем фокус…")
        pendingOptions = null
        focusedDistance = null
        fun failed() {
            if (epoch != generation || request != operation) return
            active.cameraControl.cancelFocusAndMetering()
            setStill(rememberedDistance, "locking", "failed")
        }
        try {
            // Override OFF from the use-case config only while the user-requested scan is active.
            val options = Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO).build())
            options.addListener({
                if (epoch != generation || request != operation) return@addListener
                try {
                    options.get()
                    scanCount++
                    val future = active.cameraControl.startFocusAndMetering(actionConfig)
                    future.addListener({
                        if (epoch != generation || request != operation) return@addListener
                        try {
                            if (!future.get().isFocusSuccessful) { failed(); return@addListener }
                            val distance = focusedDistance
                            if (state.manualLock && distance != null) {
                                setStill(distance, "locking")
                            } else {
                                // CameraX's AF lock remains active until an explicit new request or camera rebind.
                                rememberedDistance = null
                                state = State("locked", state.manualLock, "Фокус зафиксирован до переоткрытия камеры.")
                            }
                        } catch (_: Exception) { failed() }
                    }, executor)
                } catch (_: Exception) { failed() }
            }, executor)
            main.postDelayed({ if (epoch == generation && request == operation && state.state == "focusing") failed() }, 5000)
        } catch (_: Exception) { failed() }
        return 202 to json()
    }

    fun prepareCapture(ready: () -> Unit, failed: () -> Unit) {
        val future = pendingOptions
        if (future == null) { ready(); return }
        val epoch = generation
        var completed = false
        future.whenCompleteAsync({ _, error ->
            if (completed) return@whenCompleteAsync
            completed = true
            if (epoch != generation || state.state == "failed" || error != null) failed() else ready()
        }, executor)
        main.postDelayed({ if (!completed) { completed = true; failed() } }, 3500)
    }
}
