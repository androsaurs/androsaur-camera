package dev.hram.mobileledger

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.hram.mobileledger.calibration.*
import java.util.concurrent.Executor

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
data class CalibrationFrame(
    val markers: Map<CalibrationCorner, CalibrationPoint> = emptyMap(),
    val reading: CalibrationReading? = null,
    val stable: Boolean = false,
    val transform: OutputTransform? = null,
    val width: Int = 1,
    val height: Int = 1,
    val message: String? = null,
    val tolerance: Double = CalibrationGeometry.TOLERANCE,
    val capturedAt: Long = 0,
    val jpeg: ByteArray? = null,
)

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
class CalibrationAnalyzer(
    private val executor: Executor,
    private val tolerance: () -> Double,
    private val encodePreview: (ImageProxy) -> ByteArray?,
    private val deliver: (CalibrationFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private val stability = CalibrationStability()
    private var previousTolerance: Double? = null
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
        isUsingCropRect = true
    }
    @Volatile private var closed = false
    private val gate = Any()
    private var processing = false
    private var lastScan = 0L

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        synchronized(gate) {
            if (closed || processing || now - lastScan < 120) { image.close(); return }
            processing = true
        }
        val media = image.image
        if (media == null) { image.close(); complete(); return }
        lastScan = now
        val rotated = image.imageInfo.rotationDegrees % 180 != 0
        val width = if (rotated) image.height else image.width
        val height = if (rotated) image.width else image.height
        val crop = image.cropRect
        // ML Kit returns upright full-buffer coordinates; only accept QR codes visible in the shared viewport.
        val visible = when (image.imageInfo.rotationDegrees) {
            90 -> intArrayOf(image.height - crop.bottom, crop.left, image.height - crop.top, crop.right)
            180 -> intArrayOf(image.width - crop.right, image.height - crop.bottom, image.width - crop.left, image.height - crop.top)
            270 -> intArrayOf(crop.top, image.width - crop.right, crop.bottom, image.width - crop.left)
            else -> intArrayOf(crop.left, crop.top, crop.right, crop.bottom)
        }
        val transform = transformFactory.getOutputTransform(image)
        try {
            scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                .addOnSuccessListener(executor) { barcodes ->
                    if (!closed) {
                        val groups = barcodes.mapNotNull { barcode ->
                            val corner = CalibrationCorner.parse(barcode.rawValue) ?: return@mapNotNull null
                            val corners = barcode.cornerPoints?.map { CalibrationPoint(it.x.toDouble() / width, it.y.toDouble() / height) }
                                ?: return@mapNotNull null
                            if (corners.any { it.x * width < visible[0] || it.x * width > visible[2] ||
                                    it.y * height < visible[1] || it.y * height > visible[3] }) return@mapNotNull null
                            val centre = CalibrationGeometry.centre(corners) ?: return@mapNotNull null
                            corner to centre
                        }.groupBy({ it.first }, { it.second })
                        val duplicates = groups.any { it.value.size != 1 }
                        val markers = groups.filterValues { it.size == 1 }.mapValues { it.value.single() }
                        val currentTolerance = tolerance()
                        if (previousTolerance != currentTolerance) {
                            stability.reset()
                            previousTolerance = currentTolerance
                        }
                        val reading = if (duplicates) null else CalibrationGeometry.evaluate(markers, width.toDouble() / height, currentTolerance)
                        deliver(CalibrationFrame(markers, reading, stability.update(reading, SystemClock.elapsedRealtime()), transform, width, height,
                            when { duplicates -> "Обнаружены одинаковые метки. Нужны TL, TR, BR и BL"
                                markers.size == 4 && reading == null -> "Проверьте расположение меток и поднимите телефон, чтобы подложка вошла в кадр"
                                else -> null }, currentTolerance, now, runCatching { encodePreview(image) }.getOrNull()))
                    }
                }
                .addOnFailureListener(executor) {
                    stability.update(null, SystemClock.elapsedRealtime())
                    if (!closed) deliver(CalibrationFrame(message = "Не удалось прочитать QR-коды. Проверьте освещение"))
                }
                .addOnCompleteListener(executor) { image.close(); complete() }
        } catch (_: Exception) {
            image.close()
            complete()
            if (!closed) deliver(CalibrationFrame(message = "Анализ камеры недоступен"))
        }
    }

    private fun complete() = synchronized(gate) {
        processing = false
        if (closed) scanner.close()
    }

    fun close() = synchronized(gate) {
        closed = true
        if (!processing) scanner.close()
    }
}
