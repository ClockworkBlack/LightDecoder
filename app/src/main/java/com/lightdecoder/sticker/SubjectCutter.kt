package com.lightdecoder.sticker

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How much of each pixel of a picture belongs to its subject (a person, a
 * pet, an object): 0 is background, 255 is subject, in between along the
 * edge, which is what makes a cut-out's edge smooth instead of jagged.
 */
class SubjectMask(val width: Int, val height: Int, val alpha: ByteArray) {

    init {
        require(alpha.size == width * height)
    }

    /** True when the mask found something worth keeping (not an empty or all-background picture). */
    val hasSubject: Boolean
        get() {
            var kept = 0
            for (a in alpha) if ((a.toInt() and 0xFF) > 127) kept++
            return kept > 0 && kept >= alpha.size / 200
        }

    companion object {
        /**
         * A mask from a model's confidence per pixel (0..1). The ramp around
         * one half keeps the edge crisp but not stair-stepped.
         */
        fun fromConfidence(width: Int, height: Int, confidence: FloatArray): SubjectMask {
            val alpha = ByteArray(width * height)
            for (i in alpha.indices) {
                val t = ((confidence[i] - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
                alpha[i] = (t * t * (3 - 2 * t) * 255).roundToInt().toByte()
            }
            return SubjectMask(width, height, alpha)
        }

        private const val EDGE_LOW = 0.35f
        private const val EDGE_HIGH = 0.65f
    }
}

/**
 * Finds the subject of a picture the way a phone's own sticker maker does:
 * with a model that runs on the phone (U-2-Net, shipped inside the app; no
 * network, no Google services, the picture goes nowhere). The model sees
 * the picture small, so its answer is then fitted to the full-size
 * picture's own edges, which is what makes the cut crisp. Takes about a
 * second. Call off the main thread.
 */
object SubjectCutter {

    sealed interface Result {
        class Found(val mask: SubjectMask) : Result

        /** The model could not be run on this phone. */
        data object Unavailable : Result
    }

    fun interface Engine {
        fun find(context: Context, picture: Bitmap): Result
    }

    /** Replaceable in tests. */
    @Volatile
    var engine: Engine = Engine(::findWithModel)

    fun find(context: Context, picture: Bitmap): Result = try {
        engine.find(context.applicationContext, picture)
    } catch (e: RuntimeException) {
        Result.Unavailable
    } catch (e: LinkageError) {
        Result.Unavailable
    } catch (e: OutOfMemoryError) {
        Result.Unavailable
    }

    private val lock = Any()

    private fun findWithModel(context: Context, picture: Bitmap): Result = synchronized(lock) {
        val model = try {
            context.assets.open(MODEL).use { it.readBytes() }
        } catch (e: IOException) {
            return Result.Unavailable
        }
        val confidence = try {
            val env = OrtEnvironment.getEnvironment()
            env.createSession(model, OrtSession.SessionOptions()).use { session ->
                OnnxTensor.createTensor(env, modelInput(picture), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { input ->
                    session.run(mapOf(session.inputNames.first() to input)).use { output ->
                        val buffer = (output[0] as OnnxTensor).floatBuffer
                        if (buffer.remaining() < INPUT * INPUT) return Result.Unavailable
                        FloatArray(INPUT * INPUT).also { buffer.get(it) }
                    }
                }
            }
        } catch (e: OrtException) {
            return Result.Unavailable
        }
        Result.Found(maskFor(picture, confidence, INPUT))
    }

    /** The picture as the model takes it: [INPUT] pixels square, red, green and blue planes, normalised. */
    private fun modelInput(picture: Bitmap): FloatBuffer {
        val small = Bitmap.createScaledBitmap(picture, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        small.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (small !== picture) small.recycle()
        var top = 1
        for (p in pixels) top = max(top, max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)))
        val planes = FloatBuffer.allocate(3 * INPUT * INPUT)
        for (channel in 0 until 3) {
            val shift = 16 - 8 * channel
            for (p in pixels) planes.put((((p shr shift) and 0xFF).toFloat() / top - MEAN[channel]) / DEVIATION[channel])
        }
        planes.rewind()
        return planes
    }

    /**
     * The model's small answer ([side] pixels square, any range) as a mask
     * of [picture]'s size whose edge follows the picture's own edges.
     */
    internal fun maskFor(picture: Bitmap, answer: FloatArray, side: Int): SubjectMask {
        val w = picture.width
        val h = picture.height
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (v in answer) { low = min(low, v); high = max(high, v) }
        val range = (high - low).takeIf { it > 1e-6f } ?: 1f
        // Stretched over the picture, smoothly.
        val coarse = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * side / h - 0.5f).coerceIn(0f, side - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, side - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * side / w - 0.5f).coerceIn(0f, side - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, side - 1)
                val tx = fx - x0
                val a = answer[y0 * side + x0] * (1 - tx) + answer[y0 * side + x1] * tx
                val b = answer[y1 * side + x0] * (1 - tx) + answer[y1 * side + x1] * tx
                coarse[y * w + x] = ((a * (1 - ty) + b * ty) - low) / range
            }
        }
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        val grey = FloatArray(w * h) { i ->
            val p = pixels[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        val radius = max(2, max(w, h) / 100)
        return SubjectMask.fromConfidence(w, h, fittedToEdges(grey, coarse, w, h, radius))
    }

    /**
     * A guided filter (He, Sun, Tang): [mask] redrawn so that where it
     * changes, it changes along the edges of [guide] (the picture in grey).
     */
    private fun fittedToEdges(guide: FloatArray, mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val n = w * h
        val meanGuide = boxMean(guide, w, h, radius)
        val meanMask = boxMean(mask, w, h, radius)
        val meanBoth = boxMean(FloatArray(n) { guide[it] * mask[it] }, w, h, radius)
        val meanSquare = boxMean(FloatArray(n) { guide[it] * guide[it] }, w, h, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = meanSquare[i] - meanGuide[i] * meanGuide[i]
            a[i] = (meanBoth[i] - meanGuide[i] * meanMask[i]) / (variance + EDGE_EPSILON)
            b[i] = meanMask[i] - a[i] * meanGuide[i]
        }
        val meanA = boxMean(a, w, h, radius)
        val meanB = boxMean(b, w, h, radius)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** The mean of each pixel's square neighbourhood of [radius], by summed-area table. */
    private fun boxMean(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val stride = w + 1
        val sums = DoubleArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += values[y * w + x]
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = min(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = min(w, x + radius + 1)
                val sum = sums[y1 * stride + x1] - sums[y0 * stride + x1] - sums[y1 * stride + x0] + sums[y0 * stride + x0]
                out[y * w + x] = (sum / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }

    private const val MODEL = "models/u2netp.onnx"
    private const val INPUT = 320
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DEVIATION = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val EDGE_EPSILON = 1e-3f
}
