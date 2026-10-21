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
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.hram.mobileledger.protocol.CaptureLedger
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var server: PhoneServer
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
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
    private var lastFocus = 0L
    @Volatile private var torch = false
    @Volatile private var hasFlash = false
    private var closing = false
    private val photoDir by lazy { File(filesDir, "captures").apply { mkdirs() } }
    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = synchronized(gate) { !ledger.capturing }
            if (idle && SystemClock.elapsedRealtime() - lastSeen > 15_000 && camera != null) {
                provider?.unbindAll(); camera = null; capture = null; latestFrame = null
                cameraState = "idle"; lastFocus = 0
            }
            if (!closing) main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
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
        server = PhoneServer(this, MainActivity.token(this))
        server.start()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!closing) {
                try { provider = future.get(); cameraState = "idle" }
                catch (e: Exception) { cameraState = "needs_user"; cameraError = e.message }
            }
        }, ContextCompat.getMainExecutor(this))
        main.postDelayed(idleCheck, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        if (::server.isInitialized) server.close()
        provider?.unbindAll()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        executor.shutdownNow()
        synchronized(gate) { result?.delete() }
        super.onDestroy()
    }

    fun touch() {
        lastSeen = SystemClock.elapsedRealtime()
        main.post { if (!closing && camera == null && provider != null) bindCamera() }
    }
    fun frame(): ByteArray? = latestFrame

    fun status(): JSONObject {
        val sizes = JSONArray()
        // Camera metadata access is marshalled to main by caching capabilities at bind time.
        supportedSizes.forEach { sizes.put(JSONObject().put("width", it.width).put("height", it.height)) }
        return synchronized(gate) {
            JSONObject().put("state", cameraState).put("error", cameraError ?: JSONObject.NULL)
                .put("busy", ledger.capturing).put("torch", torch).put("hasFlash", hasFlash).put("resolutions", sizes)
                .put("pendingRequest", ledger.current?.id ?: JSONObject.NULL)
                .put("protocol", "prototype-http-v1").put("session", server.session)
        }
    }
    @Volatile private var supportedSizes: List<Size> = emptyList()

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val p = provider ?: return
        try {
            p.unbindAll()
            lastFocus = 0
            val photo = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(selectedSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()).build()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .build()
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
            shoot(request)
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
        if (started - lastFocus < 5000) { take("recent"); return }
        try {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            val focus = activeCamera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
            focus.addListener({
                val outcome = try { if (focus.get().isFocusSuccessful) { lastFocus = SystemClock.elapsedRealtime(); "focused" } else "failed" }
                    catch (_: Exception) { "failed" }
                take(outcome)
            }, ContextCompat.getMainExecutor(this))
            main.postDelayed({ take("timeout") }, 1800)
        } catch (_: Exception) { take("unsupported") }
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

    private fun previewJpeg(image: ImageProxy): ByteArray {
        val width = image.width; val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        fun sample(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Byte =
            plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride)
        for (y in 0 until height) for (x in 0 until width) nv21[y * width + x] = sample(image.planes[0], x, y)
        var offset = width * height
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            nv21[offset++] = sample(image.planes[2], x, y)
            nv21[offset++] = sample(image.planes[1], x, y)
        }
        val jpeg = ByteArrayOutputStream().use {
            YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 65, it)
            it.toByteArray()
        }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return jpeg
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return try {
            ByteArrayOutputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 65, it); it.toByteArray() }
        } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
    }
}
