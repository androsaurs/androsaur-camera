package dev.hram.androsaur.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral QR text only: no frame, credential or image is saved or logged. */
class SetupQrAnalyzer(private val executor: Executor, private val deliver: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private fun release() { if (released.compareAndSet(false, true)) scanner.close() }
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        if (closed.get() || !busy.compareAndSet(false, true)) { image.close(); return }
        val media = image.image
        if (media == null) { image.close(); busy.set(false); if (closed.get()) release(); return }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener(executor) { codes ->
                if (!closed.get()) codes.firstNotNullOfOrNull { it.rawValue?.takeIf { value -> value.length in 1..512 } }?.let(deliver)
            }
            .addOnCompleteListener(executor) {
                image.close(); busy.set(false)
                if (closed.get()) release()
            }
    }
    fun close() { if (!closed.getAndSet(true) && !busy.get()) release() }
}
