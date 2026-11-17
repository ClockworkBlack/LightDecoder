package com.lightdecoder.sticker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the one person or thing under a tap (EfficientSAM, shipped in the
 * app, run on the phone): a held bottle the other models do not see, one
 * person of two who touch. The picture is studied once ([open], a second or
 * more); each tap after that is answered quickly. Call off the main thread.
 */
object TapCutter {

    /** A picture that has been studied; [close] it when the picture is done with. */
    interface Taps {
        /** What is under [x], [y] (the picture's pixels), or null when nothing can be made out there. */
        fun at(x: Float, y: Float): SubjectMask?

        fun close()
    }

    fun interface Engine {
        /** Null when the model cannot be run on this phone. */
        fun open(context: Context, picture: Bitmap): Taps?
    }

    /** Replaceable in tests. */
    @Volatile
    var engine: Engine = Engine(::openWithModel)

    fun open(context: Context, picture: Bitmap): Taps? = try {
        engine.open(context.applicationContext, picture)
    } catch (e: OrtException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * What the cut keeps once the user has tapped: everything under a keep
     * tap (or, with no keep tap, what the smart cut found: [found]), less
     * everything under a remove tap. Null when that leaves nothing to go by.
     */
    fun combine(found: SubjectMask?, keep: List<SubjectMask>, remove: List<SubjectMask>): SubjectMask? {
        val first = keep.firstOrNull() ?: found ?: return null
        val out = if (keep.isEmpty()) first.confidence.copyOf() else ByteArray(first.confidence.size)
        fun same(m: SubjectMask) = m.width == first.width && m.height == first.height
        for (m in keep) if (same(m)) {
            for (i in out.indices) {
                val v = m.confidence[i].toInt() and 0xFF
                if (v > (out[i].toInt() and 0xFF)) out[i] = v.toByte()
            }
        }
        val w = first.width
        val h = first.height
        val removing = remove.filter(::same)
        if (removing.isEmpty()) return SubjectMask(w, h, out)
        // What is removed is taken a little wider than it was found, and slivers left along
        // its edge go with it: two models never agree on an edge to the pixel.
        val gone = BooleanArray(out.size)
        for (m in removing) for (i in out.indices) if ((m.confidence[i].toInt() and 0xFF) > 127) gone[i] = true
        val wider = spread(gone, w, h, max(w, h) / 60, grow = true)
        for (i in out.indices) if (wider[i]) out[i] = 0
        val thin = max(w, h) / 150
        if (thin > 0) {
            val solid = spread(spread(BooleanArray(out.size) { (out[it].toInt() and 0xFF) > 127 }, w, h, thin, grow = false), w, h, thin + 1, grow = true)
            for (i in out.indices) if (!solid[i]) out[i] = 0
        }
        return SubjectMask(w, h, out).cleaned(choke = false)
    }

    /** [cells] grown (or, with [grow] false, shrunk) by [radius] pixels in every direction. */
    private fun spread(cells: BooleanArray, w: Int, h: Int, radius: Int, grow: Boolean): BooleanArray {
        if (radius <= 0) return cells
        fun pass(src: BooleanArray, length: Int, lines: Int, index: (line: Int, at: Int) -> Int): BooleanArray {
            val out = BooleanArray(src.size)
            val counts = IntArray(length + 1)
            for (line in 0 until lines) {
                for (at in 0 until length) counts[at + 1] = counts[at] + if (src[index(line, at)]) 1 else 0
                for (at in 0 until length) {
                    val from = max(0, at - radius)
                    val to = min(length, at + radius + 1)
                    val set = counts[to] - counts[from]
                    out[index(line, at)] = if (grow) set > 0 else set == to - from
                }
            }
            return out
        }
        val across = pass(cells, w, h) { line, at -> line * w + at }
        return pass(across, h, w) { line, at -> at * w + line }
    }

    private val lock = Any()

    private fun openWithModel(context: Context, picture: Bitmap): Taps? = synchronized(lock) {
        val env = OrtEnvironment.getEnvironment()
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
        // The picture is studied once; what was learnt serves every tap.
        val shape: LongArray
        val learnt: FloatArray
        val encoder = context.assets.open(ENCODER).use { it.readBytes() }
        env.createSession(encoder, OrtSession.SessionOptions()).use { session ->
            OnnxTensor.createTensor(env, planes, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
                session.run(mapOf("batched_images" to input)).use { output ->
                    val tensor = output[0] as OnnxTensor
                    shape = tensor.info.shape
                    val buffer = tensor.floatBuffer
                    learnt = FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }
        val decoder = env.createSession(context.assets.open(DECODER).use { it.readBytes() }, OrtSession.SessionOptions())
        val guide = SubjectCutter.greyOf(picture)
        object : Taps {
            private var closed = false

            override fun at(x: Float, y: Float): SubjectMask? = synchronized(lock) {
                if (closed) return null
                val opened = ArrayList<OnnxTensor>()
                try {
                    val inputs = HashMap<String, OnnxTensor>()
                    inputs["image_embeddings"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(learnt), shape).also { opened += it }
                    inputs["batched_point_coords"] =
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(x, y)), longArrayOf(1, 1, 1, 2)).also { opened += it }
                    inputs["batched_point_labels"] =
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1, 1, 1)).also { opened += it }
                    inputs["orig_im_size"] =
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(h.toLong(), w.toLong())), longArrayOf(2)).also { opened += it }
                    decoder.run(inputs, setOf("output_masks")).use { output ->
                        // Several readings of the tap, the whole thing first.
                        val buffer = (output[0] as OnnxTensor).floatBuffer
                        if (buffer.remaining() < w * h) return null
                        val sure = FloatArray(w * h)
                        buffer.get(sure)
                        var any = 0
                        // In or out, nothing half-kept: the edge is then fitted to the picture's own.
                        for (i in sure.indices) {
                            sure[i] = if (sure[i] > 0f) 1f else 0f
                            if (sure[i] > 0.5f) any++
                        }
                        if (any < MIN_PIXELS) return null
                        val radius = max(2, max(w, h) / 200)
                        SubjectMask.fromConfidence(w, h, SubjectCutter.fittedToEdges(guide, sure, w, h, radius)).cleaned(mainOnly = MAIN_SHARE)
                    }
                } catch (e: OrtException) {
                    null
                } finally {
                    opened.forEach { it.close() }
                }
            }

            override fun close() = synchronized(lock) {
                if (!closed) {
                    closed = true
                    decoder.close()
                }
            }
        }
    }

    private const val ENCODER = "models/efficient_sam_vitt_encoder.onnx"
    private const val DECODER = "models/efficient_sam_vitt_decoder.onnx"
    private const val MIN_PIXELS = 24
    private const val MAIN_SHARE = 0.05f
}
