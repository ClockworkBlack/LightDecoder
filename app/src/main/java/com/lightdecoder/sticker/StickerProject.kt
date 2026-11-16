package com.lightdecoder.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * How a sticker of the library was made, so it can be edited later with
 * every tool, or taken back to the picture it came from: the original
 * picture (as the maker loaded it) and the edits made to it (options,
 * traced outline, brush strokes). Kept next to the sticker in app-private
 * storage, deleted with it. Stickers made before this existed have none:
 * editing them starts from the sticker itself. Call off the main thread.
 */
object StickerProject {

    data class Edits(
        val options: StickerMaker.Options,
        /** The traced outline as x,y pairs in the original's pixels; null when nothing was traced. */
        val trace: FloatArray?,
        val strokes: List<StickerMaker.Stroke>,
    )

    class Project(val original: Bitmap, val edits: Edits)

    private const val VERSION = 1

    private fun dir(context: Context, sticker: File): File =
        File(File(context.filesDir, "my-stickers/projects"), sticker.nameWithoutExtension)

    fun save(context: Context, sticker: File, original: Bitmap, edits: Edits) {
        val dir = dir(context, sticker)
        dir.mkdirs()
        val png = original.hasAlpha()
        val picture = File(dir, if (png) "original.png" else "original.jpg")
        File(dir, if (png) "original.jpg" else "original.png").delete()
        val part = File(picture.path + ".part")
        FileOutputStream(part).use {
            original.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it)
        }
        part.renameTo(picture)
        File(dir, "edits.json").writeText(toJson(edits).toString())
    }

    fun load(context: Context, sticker: File): Project? {
        val dir = dir(context, sticker)
        val picture = listOf("original.png", "original.jpg").map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        val original = runCatching { BitmapFactory.decodeFile(picture.path) }.getOrNull() ?: return null
        val edits = runCatching { fromJson(JSONObject(File(dir, "edits.json").readText())) }.getOrNull()
            ?: Edits(StickerMaker.Options(), null, emptyList())
        return Project(original.copy(Bitmap.Config.ARGB_8888, false), edits)
    }

    fun delete(context: Context, sticker: File) {
        dir(context, sticker).deleteRecursively()
    }

    internal fun toJson(e: Edits): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("removeBackground", e.options.removeBackground)
        put("tolerance", e.options.tolerance)
        put("shape", e.options.shape.name)
        put("outline", e.options.outline)
        put("outlineWidth", e.options.outlineWidth)
        put("solid", e.options.solidBackground)
        e.trace?.let { put("trace", floats(it)) }
        put("strokes", JSONArray().apply {
            e.strokes.filter { it.points.isNotEmpty() }.forEach { s ->
                put(JSONObject().put("w", s.width.toDouble()).put("k", s.keep).put("p", floats(s.points)))
            }
        })
    }

    internal fun fromJson(o: JSONObject): Edits = Edits(
        options = StickerMaker.Options(
            removeBackground = o.optBoolean("removeBackground"),
            tolerance = o.optInt("tolerance", 24),
            shape = runCatching { StickerMaker.Shape.valueOf(o.optString("shape")) }.getOrDefault(StickerMaker.Shape.ORIGINAL),
            outline = o.optBoolean("outline", true),
            outlineWidth = o.optInt("outlineWidth", StickerMaker.OUTLINE),
            solidBackground = o.optBoolean("solid"),
        ),
        trace = o.optJSONArray("trace")?.let(::floats),
        strokes = o.optJSONArray("strokes")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val points = s.optJSONArray("p")?.let(::floats) ?: return@mapNotNull null
                StickerMaker.Stroke.of(points, s.optDouble("w", 10.0).toFloat(), s.optBoolean("k"))
            }
        }.orEmpty(),
    )

    private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.optDouble(it).toFloat() }

    /** A closed outline through x,y pairs. */
    fun pathOf(points: FloatArray, close: Boolean): Path = Path().apply {
        if (points.size < 2) return@apply
        moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) {
            lineTo(points[i], points[i + 1])
            i += 2
        }
        if (close) close()
    }
}
