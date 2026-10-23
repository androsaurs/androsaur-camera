package dev.hram.mobileledger

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.*
import android.util.Log
import android.util.Size
import android.util.Base64
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.hram.mobileledger.protocol.CaptureLedger
import dev.hram.mobileledger.calibration.CalibrationGeometry
import dev.hram.mobileledger.calibration.CalibrationSessions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture

class CameraService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var server: PhoneServer
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var meteringAnalysis: ImageAnalysis? = null
    private var selectedSize = Size(4080, 3060)
    private val gate = Any()
    private val ledger = CaptureLedger()
    private var result: File? = null
    private var resultMeta = JSONObject()
    private var failure: String? = null
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var lastSeen = 0L
    @Volatile private var cameraState = "starting"
    @Volatile private var cameraError: String? = null
    private var lastEncoded = 0L
    private val focusController by lazy { FocusController(main, ContextCompat.getMainExecutor(this)) }
    fun focusStatus(): JSONObject = focusController.json()
    @Volatile private var torch = false
    @Volatile private var hasFlash = false
    private var closing = false
    private val binder = LocalBinder()
    private var calibrationView: PreviewView? = null
    private var calibrationCallback: ((CalibrationFrame) -> Unit)? = null
    private var calibrationAnalyzer: CalibrationAnalyzer? = null
    private val calibrationPreferences by lazy { getSharedPreferences("calibration", MODE_PRIVATE) }
    @Volatile private var calibrationTolerance = CalibrationGeometry.TOLERANCE
    private val calibrationSessions = CalibrationSessions()
    @Volatile private var remoteCalibrationActive = false
    @Volatile private var calibrationMode = false
    @Volatile private var calibrationFrame: CalibrationFrame? = null
    private var calibrationGeneration = 0
    inner class LocalBinder : Binder() { val service: CameraService get() = this@CameraService }
    private val photoDir by lazy { File(filesDir, "captures").apply { mkdirs() } }
    val agentStore by lazy { AgentStore(File(filesDir, "agent")) }
    private val idleCheck = object : Runnable {
        override fun run() {
            val changed = synchronized(gate) { updateCalibrationModeLocked() }
            if (changed) reconfigureCalibration()
            agentStore.sweep()
            val idle = synchronized(gate) { !ledger.capturing }
            if (idle && !calibrationMode && SystemClock.elapsedRealtime() - lastSeen > 15_000 && camera != null) {
                focusController.detach(); provider?.unbindAll(); camera = null; capture = null; latestFrame = null
                cameraState = "idle"
            }
            if (!closing) main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        calibrationPreferences.getString("tolerancePercent", null)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.1..5.0 }?.let { calibrationTolerance = it / 100 }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("camera", "Документ-камера", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "camera").setContentTitle("Mobile Ledger")
            .setContentText("Документ-камера · управление с компьютера")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pending).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, notification)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        photoDir.listFiles()?.forEach { it.delete() }
        agentStore
        server =PhoneServer(this, MainActivity.token(this))
        server.start()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!closing) {
                try {
                    provider = future.get(); cameraState = "idle"
                    if (calibrationMode) bindCamera()
                }
                catch (e: Exception) { cameraState = "needs_user"; cameraError = e.message }
            }
        }, ContextCompat.getMainExecutor(this))
        main.postDelayed(idleCheck, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        if (::server.isInitialized) server.close()
        calibrationAnalyzer?.close()
        calibrationView = null; calibrationCallback = null
        focusController.detach()
        provider?.unbindAll()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        executor.shutdownNow()
        synchronized(gate) { result?.delete() }
        agentStore.clear()
        super.onDestroy()
    }

    fun touch() {
        lastSeen = SystemClock.elapsedRealtime()
        main.post { if (!closing && camera == null && provider != null) bindCamera() }
    }
    fun frame(): ByteArray? = latestFrame

    fun setCalibrationTolerancePercent(percent: Double): Boolean {
        if (!percent.isFinite() || percent !in 0.1..5.0) return false
        synchronized(gate) {
            calibrationPreferences.edit().putString("tolerancePercent", percent.toString()).apply()
            calibrationTolerance = percent / 100
        }
        return true
    }

    private fun updateCalibrationModeLocked(): Boolean {
        remoteCalibrationActive = calibrationSessions.hasClients(SystemClock.elapsedRealtime())
        val wanted = calibrationView != null || remoteCalibrationActive
        if (wanted == calibrationMode) return false
        calibrationMode = wanted
        cameraState = if (wanted) "calibrating" else "idle"
        calibrationFrame = null
        latestFrame = null
        return true
    }

    private fun reconfigureCalibration() {
        if (closing) return
        if (calibrationMode) {
            if (provider != null) bindCamera()
        } else {
            calibrationGeneration++
            calibrationAnalyzer?.close(); calibrationAnalyzer = null
            focusController.detach(); provider?.unbindAll(); camera = null; capture = null; latestFrame = null
            calibrationFrame = null
            if (SystemClock.elapsedRealtime() - lastSeen < 15_000) bindCamera()
        }
    }

    fun calibrationSession(client: String, active: Boolean): Pair<Int, JSONObject> {
        synchronized(gate) {
            if (active && ledger.capturing) return 409 to JSONObject().put("error", "camera_busy")
            if (!calibrationSessions.update(client, active, SystemClock.elapsedRealtime()))
                return 409 to JSONObject().put("error", "calibration_clients_limit")
            if (updateCalibrationModeLocked()) main.post { reconfigureCalibration() }
        }
        return 200 to JSONObject().put("active", active).put("leaseMs", 12_000)
    }

    fun calibrationPacket(): Pair<Int, JSONObject> {
        if (!calibrationMode) return 409 to JSONObject().put("error", "calibration_not_active")
        val frame = calibrationFrame
        val age = frame?.let { SystemClock.elapsedRealtime() - it.capturedAt } ?: Long.MAX_VALUE
        val packet = JSONObject().put("tolerancePercent", calibrationTolerance * 100).put("focus", focusStatus())
        if (frame?.jpeg == null || age > 1500 || frame.tolerance != calibrationTolerance) {
            return 202 to packet.put("state", cameraState).put("message", cameraError ?: frame?.message ?: "Ожидаем кадр камеры")
        }
        val markers = JSONObject()
        frame.markers.forEach { (corner, point) -> markers.put(corner.name, JSONObject().put("x", point.x).put("y", point.y)) }
        val reading = frame.reading?.let { JSONObject().put("liftRight", it.liftRight).put("liftBottom", it.liftBottom)
            .put("errorPercent", it.error * 100).put("aligned", it.aligned) }
        return 200 to packet.put("state", "calibrating").put("frameId", frame.capturedAt).put("ageMs", age)
            .put("width", frame.width).put("height", frame.height).put("markers", markers)
            .put("reading", reading ?: JSONObject.NULL).put("stable", frame.stable)
            .put("message", frame.message ?: JSONObject.NULL)
            .put("jpegBase64", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP))
    }

    /** Main-thread only; a calibration session never interrupts an exposure or deletes a result. */
    fun beginCalibration(view: PreviewView, callback: (CalibrationFrame) -> Unit): Boolean {
        synchronized(gate) {
            if (ledger.capturing || calibrationView != null) return false
            calibrationView = view
            calibrationCallback = callback
            updateCalibrationModeLocked()
        }
        latestFrame = null
        if (provider != null) bindCamera()
        return true
    }

    fun endCalibration(view: PreviewView) {
        if (calibrationView !== view) return
        synchronized(gate) {
            calibrationView = null; calibrationCallback = null
            updateCalibrationModeLocked()
        }
        reconfigureCalibration()
    }

    fun status(): JSONObject {
        val sizes = JSONArray()
        // Camera metadata access is marshalled to main by caching capabilities at bind time.
        supportedSizes.forEach { sizes.put(JSONObject().put("width", it.width).put("height", it.height)) }
        return synchronized(gate) {
            JSONObject().put("state", cameraState).put("error", cameraError ?: JSONObject.NULL)
                .put("busy", ledger.capturing).put("torch", torch).put("hasFlash", hasFlash).put("resolutions", sizes)
                .put("pendingRequest", ledger.current?.id ?: JSONObject.NULL)
                .put("calibrationTolerancePercent", calibrationTolerance * 100)
                .put("focus", focusStatus())
                .put("capabilities", JSONArray().put("calibration-v1").put("focus-v1"))
                .put("protocol", "prototype-http-v1").put("session", server.session)
        }
    }
    @Volatile private var supportedSizes: List<Size> = emptyList()

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val p = provider ?: return
        try {
            focusController.detach()
            p.unbindAll()
            calibrationAnalyzer?.close(); calibrationAnalyzer = null
            val analysisGeneration = ++calibrationGeneration
            if (calibrationMode) {
                val view = calibrationView
                val rotation = view?.display?.rotation ?: Surface.ROTATION_0
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val preview = view?.let { Preview.Builder().setTargetRotation(rotation).setResolutionSelector(selector).build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) } }
                val analysisBuilder = ImageAnalysis.Builder().setTargetRotation(rotation).setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                focusController.configure(analysisBuilder)
                val analysis = analysisBuilder.build()
                val analyzer = CalibrationAnalyzer(executor, { calibrationTolerance }, { image ->
                    // Only the transport preview is smaller; ML Kit still analyzes the full source frame.
                    if (remoteCalibrationActive) previewJpeg(image, maxEdge = 640) else null
                }) { frame ->
                    main.post {
                        if (calibrationMode && analysisGeneration == calibrationGeneration && !closing) {
                            calibrationFrame = frame
                            latestFrame = frame.jpeg
                            calibrationCallback?.invoke(frame)
                        }
                    }
                }
                calibrationAnalyzer = analyzer
                analysis.setAnalyzer(executor, analyzer)
                val group = UseCaseGroup.Builder().addUseCase(analysis)
                preview?.let { group.addUseCase(it) }
                view?.viewPort?.let { group.setViewPort(it) }
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                meteringAnalysis = analysis
                focusController.attach(camera!!)
                capture = null
                hasFlash = camera!!.cameraInfo.hasFlashUnit()
                camera!!.cameraControl.enableTorch(torch)
                cameraState = "calibrating"; cameraError = null
                return
            }
            val photo = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(selectedSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()).build()
            val analysisBuilder = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            focusController.configure(analysisBuilder)
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEncoded >= 350) {
                        lastEncoded = now
                        latestFrame = previewJpeg(image)
                    }
                } catch (e: Exception) { Log.w("MobileLedger", "Preview encoding failed", e) }
                finally { image.close() }
            }
            camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, photo, analysis)
            meteringAnalysis = analysis
            focusController.attach(camera!!)
            capture = photo
            hasFlash = camera!!.cameraInfo.hasFlashUnit()
            val info = Camera2CameraInfo.from(camera!!.cameraInfo)
            val streamMap = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            supportedSizes = ((streamMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()) +
                (streamMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()))
                .distinct().filter { it.width.toLong() * it.height <= 50_000_000 }
                .sortedByDescending { it.width * it.height }
            camera!!.cameraControl.enableTorch(torch)
            cameraState = "ready"; cameraError = null
        } catch (e: Exception) {
            Log.e("MobileLedger", "Camera bind failed", e)
            camera = null; capture = null; cameraState = "needs_user"; cameraError = e.message
            calibrationCallback?.invoke(CalibrationFrame(message = "Камера недоступна. Вернитесь назад и откройте калибровку снова"))
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        if (enabled && !hasFlash) return false
        torch = enabled
        main.post { camera?.cameraControl?.enableTorch(enabled) }
        return true
    }

    fun request(id: String, client: String, width: Int?, height: Int?): Pair<Int, JSONObject> {
        val request = CaptureLedger.Request(id, client)
        synchronized(gate) {
            // A cached result can be replayed even after the physical camera was released.
            ledger.existing(request)?.let { existing ->
                return when (existing) {
                    CaptureLedger.Decision.REPLAY -> 202 to JSONObject().put("requestId", id)
                    CaptureLedger.Decision.FINISHED -> 410 to JSONObject().put("error", "request_finished")
                    else -> 409 to JSONObject().put("error", "request_conflict")
                }
            }
            if (cameraState != "ready") return 503 to JSONObject().put("error", "camera_unavailable").put("state", cameraState)
            if (focusController.busy) return 409 to JSONObject().put("error", "focus_busy")
            if (width != null && height != null && supportedSizes.none { it.width == width && it.height == height })
                return 400 to JSONObject().put("error", "unsupported_resolution")
            when (ledger.accept(request)) {
                CaptureLedger.Decision.BUSY -> return 409 to JSONObject().put("error", "camera_busy")
                CaptureLedger.Decision.CONFLICT -> return 409 to JSONObject().put("error", "request_conflict")
                CaptureLedger.Decision.FINISHED -> return 410 to JSONObject().put("error", "request_finished")
                CaptureLedger.Decision.REPLAY -> return 202 to JSONObject().put("requestId", id)
                CaptureLedger.Decision.START -> { result?.delete(); result = null; failure = null }
            }
        }
        main.post {
            if (width != null && height != null && selectedSize != Size(width, height)) {
                selectedSize = Size(width, height); bindCamera()
            }
            focusController.prepareCapture({ shoot(request) }, { fail(request, "focus_restore_failed") })
        }
        return 202 to JSONObject().put("requestId", id)
    }

    private fun shoot(request: CaptureLedger.Request) {
        val activeCamera = camera
        val photo = capture
        if (activeCamera == null || photo == null) { fail(request, "camera_unavailable"); return }
        val started = SystemClock.elapsedRealtime()
        var tookPicture = false
        fun take(focus: String) {
            if (closing || tookPicture) return
            tookPicture = true
            synchronized(gate) {
                if (ledger.current != request) { ledger.exposureEnded(); return }
            }
            val file = File(photoDir, "${request.id}.jpg")
            photo.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        synchronized(gate) {
                            ledger.exposureEnded()
                            if (closing || !ledger.captured(request)) { file.delete(); return }
                            result = file
                            resultMeta = JSONObject().put("focus", focus).put("captureMs", SystemClock.elapsedRealtime() - started)
                                .put("width", photo.resolutionInfo?.resolution?.width)
                                .put("height", photo.resolutionInfo?.resolution?.height)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete(); fail(request, exception.message ?: "capture_failed")
                    }
                })
        }
        take(if (focusController.state.state == "locked") "locked" else "on-demand")
    }

    /** Shared by HTTP and native UI. Capture and focus commands cannot move the lens concurrently. */
    fun focus(action: String, x: Float = .5f, y: Float = .5f, point: MeteringPoint? = null): Pair<Int, JSONObject> {
        if (action !in setOf("focus", "lock") || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f)
            return 400 to JSONObject().put("error", "invalid_focus")
        fun run(): Pair<Int, JSONObject> = synchronized(gate) {
            if (ledger.capturing) 409 to JSONObject().put("error", "camera_busy")
            else {
                lastSeen = SystemClock.elapsedRealtime()
                if (camera == null && provider != null && !closing) bindCamera()
                val analysis = meteringAnalysis
                val target = point ?: analysis?.takeIf { camera != null }?.let {
                    val raw = dev.hram.mobileledger.focus.FocusPoint.toSurface(x, y, it.resolutionInfo?.rotationDegrees ?: 0)
                    SurfaceOrientedMeteringPointFactory(1f, 1f, it).createPoint(raw.first, raw.second)
                }
                focusController.request(action, x, y, target)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return run()
        val reply = CompletableFuture<Pair<Int, JSONObject>>()
        main.post { try { reply.complete(run()) } catch (e: Exception) { reply.completeExceptionally(e) } }
        return reply.get(3, TimeUnit.SECONDS)
    }

    private fun fail(request: CaptureLedger.Request, message: String) = synchronized(gate) {
        ledger.exposureEnded()
        if (ledger.current == request) { ledger.captured(request); failure = message }
    }

    data class Result(val code: Int, val jpeg: ByteArray? = null, val meta: JSONObject = JSONObject())
    fun result(id: String, client: String): Result = synchronized(gate) {
        if (ledger.current != CaptureLedger.Request(id, client)) return Result(410, meta = JSONObject().put("error", "request_gone"))
        failure?.let { return Result(500, meta = JSONObject().put("error", it)) }
        val file = result ?: return Result(202)
        Result(200, file.readBytes(), resultMeta)
    }
    fun finish(id: String, client: String): Boolean = synchronized(gate) {
        if (!ledger.finish(CaptureLedger.Request(id, client))) return false
        result?.delete(); result = null; failure = null
        true
    }

    private fun previewJpeg(image: ImageProxy, maxEdge: Int = Int.MAX_VALUE): ByteArray {
        val width = image.width; val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        fun sample(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Byte =
            plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride)
        val luminance = image.planes[0]
        if (luminance.pixelStride == 1) {
            val buffer = luminance.buffer.duplicate()
            val start = buffer.position()
            for (y in 0 until height) {
                buffer.position(start + y * luminance.rowStride)
                buffer.get(nv21, y * width, width)
            }
        } else {
            for (y in 0 until height) for (x in 0 until width) nv21[y * width + x] = sample(luminance, x, y)
        }
        // Copy chroma rows in bulk too; per-pixel ByteBuffer calls stall high-resolution calibration previews.
        val u = image.planes[1]; val v = image.planes[2]
        val uBuffer = u.buffer.duplicate(); val vBuffer = v.buffer.duplicate()
        val uStart = uBuffer.position(); val vStart = vBuffer.position()
        val uRow = ByteArray((width / 2 - 1) * u.pixelStride + 1)
        val vRow = ByteArray((width / 2 - 1) * v.pixelStride + 1)
        var offset = width * height
        for (y in 0 until height / 2) {
            uBuffer.position(uStart + y * u.rowStride); uBuffer.get(uRow)
            vBuffer.position(vStart + y * v.rowStride); vBuffer.get(vRow)
            for (x in 0 until width / 2) {
                nv21[offset++] = vRow[x * v.pixelStride]
                nv21[offset++] = uRow[x * u.pixelStride]
            }
        }
        val jpeg = ByteArrayOutputStream().use {
            YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 65, it)
            it.toByteArray()
        }
        val rotation = image.imageInfo.rotationDegrees
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
        if (rotation == 0 && scale == 1f) return jpeg
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }, true)
        return try {
            ByteArrayOutputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 65, it); it.toByteArray() }
        } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
    }
}
