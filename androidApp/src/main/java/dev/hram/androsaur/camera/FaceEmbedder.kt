package dev.hram.androsaur.camera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import dev.hram.androsaur.camera.face.Embeddings
import java.nio.FloatBuffer

/** Turns an aligned 112x112 face into a unit vector of 128 numbers with the SFace model (ONNX Runtime, CPU). */
class FaceEmbedder(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val loadMs: Long

    init {
        val began = System.nanoTime()
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        inputName = session.inputNames.first()
        loadMs = (System.nanoTime() - began) / 1_000_000
    }

    /** [face] must be [SIZE] x [SIZE]. The model takes RGB 0..255 as planes (NCHW). */
    fun embed(face: Bitmap): FloatArray {
        require(face.width == SIZE && face.height == SIZE) { "Aligned face must be ${SIZE}x$SIZE" }
        val pixels = IntArray(SIZE * SIZE)
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xff).toFloat(); data[plane + i] = ((p shr 8) and 0xff).toFloat(); data[2 * plane + i] = (p and 0xff).toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { output ->
                @Suppress("UNCHECKED_CAST")
                return Embeddings.normalize((output[0].value as Array<FloatArray>)[0])
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val SIZE = 112
        const val MODEL = "face_recognition/face_recognition_sface_2021dec_int8.onnx"
    }
}
