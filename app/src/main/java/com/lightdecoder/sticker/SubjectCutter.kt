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
 * How sure a model is, pixel by pixel, that the pixel belongs to the
 * picture's subject: 0 is background, 255 is subject. Where the line is
 * drawn between them is the sticker maker's choice ([StickerMaker.keepSubject]).
 */
class SubjectMask(val width: Int, val height: Int, val confidence: ByteArray) {

    init {
        require(confidence.size == width * height)
    }

    /** True when at least [share] of the picture is subject. */
    fun covers(share: Float): Boolean {
        var kept = 0
        for (c in confidence) if ((c.toInt() and 0xFF) > 127) kept++
        return kept > 0 && kept >= confidence.size * share
    }

    /** True when the mask found something worth keeping (not an empty or all-background picture). */
    val hasSubject: Boolean get() = covers(0.005f)

    companion object {
        /** A mask from a model's confidence per pixel (0..1). */
        fun fromConfidence(width: Int, height: Int, confidence: FloatArray): SubjectMask =
            SubjectMask(width, height, ByteArray(width * height) { (confidence[it].coerceIn(0f, 1f) * 255).roundToInt().toByte() })
    }
}

/**
 * What the two models found in one picture: the people in it, and whatever
 * stands out in it (objects, pets, and often people too). Either is null if
 * its model could not be run.
 */
class Subjects(val people: SubjectMask?, val objects: SubjectMask?) {

    private val both: SubjectMask? by lazy {
        val p = people
        val o = objects
        if (p == null || o == null || p.width != o.width || p.height != o.height) p ?: o
        else SubjectMask(p.width, p.height, ByteArray(p.confidence.size) {
            max(p.confidence[it].toInt() and 0xFF, o.confidence[it].toInt() and 0xFF).toByte()
        })
    }

    /**
     * The people, with what they are holding or wearing that their own model
     * left out (a glass, a phone, a bag, a hat): every piece the objects
     * model found that touches a person comes along, unless it is so large
     * next to the people that it is more likely the furniture or the room.
     * The people's own mask when nothing is added.
     */
    private val peopleHolding: SubjectMask? by lazy {
        val p = people ?: return@lazy null
        val o = objects
        if (o == null || o.width != p.width || o.height != p.height) return@lazy p
        val w = p.width
        val h = p.height
        val n = w * h
        fun person(i: Int) = (p.confidence[i].toInt() and 0xFF) > 127
        fun thing(i: Int) = (o.confidence[i].toInt() and 0xFF) > 127
        var peopleArea = 0
        for (i in 0 until n) if (person(i)) peopleArea++
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        var added: ByteArray? = null
        for (start in 0 until n) {
            if (seen[start] || !thing(start)) continue
            // One piece of what the objects model found.
            var head = 0
            var tail = 0
            seen[start] = true
            queue[tail++] = start
            var beyond = 0
            var touches = false
            while (head < tail) {
                val i = queue[head++]
                if (person(i)) touches = true else beyond++
                val x = i % w
                fun visit(next: Int) {
                    if (seen[next]) return
                    if (thing(next)) {
                        seen[next] = true
                        queue[tail++] = next
                    } else if (person(next)) {
                        touches = true
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (i >= w) visit(i - w)
                if (i < n - w) visit(i + w)
            }
            if (touches && beyond > 0 && beyond <= peopleArea * HELD_SHARE) {
                val out = added ?: p.confidence.copyOf().also { added = it }
                for (k in 0 until tail) {
                    val i = queue[k]
                    out[i] = max(out[i].toInt() and 0xFF, o.confidence[i].toInt() and 0xFF).toByte()
                }
            }
        }
        added?.let { SubjectMask(w, h, it) } ?: p
    }

    /** The mask to cut with for [kind], or null when there is nothing of that kind in the picture. */
    fun pick(kind: StickerMaker.SmartKind): SubjectMask? = when (kind) {
        StickerMaker.SmartKind.PEOPLE -> people
        StickerMaker.SmartKind.OBJECTS -> objects
        StickerMaker.SmartKind.BOTH -> both
        // People are cut whole by their own model, with what they hold; without any people,
        // whatever stands out.
        StickerMaker.SmartKind.AUTO -> if (people?.covers(PEOPLE_SHARE) == true) peopleHolding else objects
    }?.takeIf { it.hasSubject }

    private companion object {
        const val PEOPLE_SHARE = 0.02f

        /** A piece beyond the people is theirs if it is at most this much of their own size. */
        const val HELD_SHARE = 0.5f
    }
}

/**
 * Finds the subject of a picture the way a phone's own sticker maker does:
 * with models that run on the phone (shipped inside the app; no network, no
 * Google services, the picture goes nowhere). Two of them, because neither
 * does everything: Robust Video Matting cuts people out whole (dark clothes
 * in a dim room included) with their hair, and finds nothing else; U-2-Net
 * finds whatever stands out, objects and pets too, but loses parts of
 * people. U-2-Net sees the picture small, so its answer is then fitted to
 * the full-size picture's own edges. Takes a second or two. Call off the
 * main thread.
 */
object SubjectCutter {

    sealed interface Result {
        class Found(val subjects: Subjects) : Result

        /** Neither model could be run on this phone. */
        data object Unavailable : Result
    }

    fun interface Engine {
        fun find(context: Context, picture: Bitmap): Result
    }

    /** Replaceable in tests. */
    @Volatile
    var engine: Engine = Engine(::findWithModels)

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

    private fun findWithModels(context: Context, picture: Bitmap): Result = synchronized(lock) {
        // One failing (too little memory for it, say) still leaves the other.
        val people = attempt { people(context, picture) }
        val objects = attempt { objects(context, picture) }
        if (people == null && objects == null) Result.Unavailable else Result.Found(Subjects(people, objects))
    }

    private inline fun attempt(find: () -> SubjectMask?): SubjectMask? = try {
        find()
    } catch (e: OrtException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun <T> withModel(context: Context, asset: String, use: (OrtEnvironment, OrtSession) -> T): T {
        val model = context.assets.open(asset).use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(model, OrtSession.SessionOptions()).use { use(env, it) }
    }

    /** Whatever stands out in the picture (U-2-Net). */
    private fun objects(context: Context, picture: Bitmap): SubjectMask? = withModel(context, OBJECTS_MODEL) { env, session ->
        OnnxTensor.createTensor(env, modelInput(picture), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { output ->
                val buffer = (output[0] as OnnxTensor).floatBuffer
                if (buffer.remaining() < INPUT * INPUT) null
                else maskFor(picture, FloatArray(INPUT * INPUT).also { buffer.get(it) }, INPUT)
            }
        }
    }

    /**
     * The people in the picture (Robust Video Matting, given one frame). It
     * takes the picture whole and looks at it at about [PEOPLE_SIDE] pixels,
     * which is where it judges whole bodies best, then draws the edge at
     * full size itself.
     */
    private fun people(context: Context, picture: Bitmap): SubjectMask? = withModel(context, PEOPLE_MODEL) { env, session ->
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        val planes = FloatBuffer.allocate(3 * w * h)
        for (channel in 0 until 3) {
            val shift = 16 - 8 * channel
            for (p in pixels) planes.put(((p shr shift) and 0xFF) / 255f)
        }
        planes.rewind()
        val ratio = min(1f, PEOPLE_SIDE.toFloat() / max(w, h))
        val opened = ArrayList<OnnxTensor>()
        try {
            fun tensor(buffer: FloatBuffer, vararg shape: Long) = OnnxTensor.createTensor(env, buffer, shape).also { opened += it }
            val inputs = HashMap<String, OnnxTensor>()
            inputs["src"] = tensor(planes, 1, 3, h.toLong(), w.toLong())
            // No earlier frames: the model's memory starts empty.
            for (memory in listOf("r1i", "r2i", "r3i", "r4i")) inputs[memory] = tensor(FloatBuffer.allocate(1), 1, 1, 1, 1)
            inputs["downsample_ratio"] = tensor(FloatBuffer.wrap(floatArrayOf(ratio)), 1)
            session.run(inputs, setOf("pha")).use { output ->
                val buffer = (output[0] as OnnxTensor).floatBuffer
                if (buffer.remaining() < w * h) null
                else SubjectMask.fromConfidence(w, h, FloatArray(w * h).also { buffer.get(it) })
            }
        } finally {
            opened.forEach { it.close() }
        }
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

    private const val OBJECTS_MODEL = "models/u2netp.onnx"
    private const val PEOPLE_MODEL = "models/rvm_mobilenetv3.onnx"
    private const val PEOPLE_SIDE = 256
    private const val INPUT = 320
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DEVIATION = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val EDGE_EPSILON = 1e-3f
}
