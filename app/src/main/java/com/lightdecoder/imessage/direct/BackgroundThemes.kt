package com.lightdecoder.imessage.direct

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.StringRes
import com.lightdecoder.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The app's own chat background designs, in the spirit of Messenger's themes: each drawn with
 * Canvas from nothing but code (no picture files), the same every time for the same design and
 * size (its own fixed random seed), and sent as a photo background ([BackgroundChoice.Theme]).
 */
object BackgroundThemes {

    data class Theme(val id: String, @StringRes val name: Int)

    val ALL: List<Theme> = listOf(
        Theme("bokeh", R.string.imessage_background_theme_bokeh),
        Theme("stars", R.string.imessage_background_theme_stars),
        Theme("waves", R.string.imessage_background_theme_waves),
        Theme("confetti", R.string.imessage_background_theme_confetti),
        Theme("triangles", R.string.imessage_background_theme_triangles),
        Theme("paper", R.string.imessage_background_theme_paper),
        Theme("city", R.string.imessage_background_theme_city),
        Theme("hearts", R.string.imessage_background_theme_hearts),
        Theme("leaves", R.string.imessage_background_theme_leaves),
        Theme("galaxy", R.string.imessage_background_theme_galaxy),
    )

    fun of(id: String): Theme? = ALL.firstOrNull { it.id == id }

    /** Design [id] drawn at [width] x [height]; null for a design not known here. */
    fun render(id: String, width: Int, height: Int): Bitmap? {
        if (of(id) == null) return null
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val r = Random(seedOf(id))
        val s = w / 1080f
        when (id) {
            "bokeh" -> bokeh(c, w.toFloat(), h.toFloat(), s, r)
            "stars" -> stars(c, w.toFloat(), h.toFloat(), s, r)
            "waves" -> waves(c, w.toFloat(), h.toFloat(), s)
            "confetti" -> confetti(c, w.toFloat(), h.toFloat(), s, r)
            "triangles" -> triangles(c, w.toFloat(), h.toFloat(), r)
            "paper" -> paper(c, w.toFloat(), h.toFloat(), s, r)
            "city" -> city(c, w.toFloat(), h.toFloat(), s, r)
            "hearts" -> hearts(c, w.toFloat(), h.toFloat(), s)
            "leaves" -> leaves(c, w.toFloat(), h.toFloat(), s, r)
            "galaxy" -> galaxy(c, w.toFloat(), h.toFloat(), s, r)
        }
        return bitmap
    }

    /** A stable seed per design (String.hashCode is fixed by the language). */
    private fun seedOf(id: String): Int = ("ld-theme:" + id).hashCode()

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private fun vertical(c: Canvas, w: Float, h: Float, vararg colors: Int) {
        val p = paint()
        p.shader = LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    private fun alpha(color: Int, a: Int) = BackgroundArt.withAlpha(color, a)

    private fun softCircle(c: Canvas, x: Float, y: Float, radius: Float, color: Int, a: Int) {
        val p = paint()
        p.shader = RadialGradient(x, y, radius, intArrayOf(alpha(color, a), alpha(color, a * 3 / 4), alpha(color, 0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, p)
    }

    // --- the designs -------------------------------------------------------------------------------

    private fun bokeh(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFFFFD9C2.toInt(), 0xFFF6B8D0.toInt(), 0xFFB7A6F0.toInt())
        val tones = intArrayOf(Color.WHITE, 0xFFFFF1B8.toInt(), 0xFFFFC6E0.toInt(), 0xFFD8E8FF.toInt())
        repeat(46) {
            softCircle(c, r.nextFloat() * w, r.nextFloat() * h, (30 + r.nextFloat() * 150) * s, tones[r.nextInt(tones.size)], 50 + r.nextInt(80))
        }
    }

    private fun stars(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFF0B1030.toInt(), 0xFF070A1C.toInt(), 0xFF020308.toInt())
        softCircle(c, w * 0.75f, h * 0.3f, w * 0.7f, 0xFF4B3A8C.toInt(), 70)
        softCircle(c, w * 0.2f, h * 0.7f, w * 0.6f, 0xFF1D4E7A.toInt(), 60)
        val p = paint().apply { color = Color.WHITE }
        repeat(520) {
            p.alpha = 60 + r.nextInt(195)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (0.8f + r.nextFloat() * 2.2f) * s, p)
        }
        repeat(14) { softCircle(c, r.nextFloat() * w, r.nextFloat() * h, (10 + r.nextFloat() * 14) * s, 0xFFDDE8FF.toInt(), 200) }
    }

    private fun waves(c: Canvas, w: Float, h: Float, s: Float) {
        vertical(c, w, h, 0xFFEAF7FF.toInt(), 0xFFBFE6F7.toInt())
        val blues = intArrayOf(0xFFA6DDF2.toInt(), 0xFF7FC8E8.toInt(), 0xFF56AEDB.toInt(), 0xFF3A8FC7.toInt(), 0xFF2A6FAE.toInt(), 0xFF1D5290.toInt())
        val p = paint()
        blues.forEachIndexed { i, color ->
            val base = h * (0.28f + i * 0.12f)
            val amp = (26 + i * 6) * s
            val len = w / (1.2f + i * 0.15f)
            val path = Path().apply {
                moveTo(0f, h)
                var x = 0f
                lineTo(0f, base)
                while (x <= w + 8) {
                    lineTo(x, base + amp * sin((x / len) * 2 * PI.toFloat() + i * 1.3f))
                    x += 8f
                }
                lineTo(w, h)
                close()
            }
            p.color = color
            c.drawPath(path, p)
        }
    }

    private fun confetti(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFFFFFBF2.toInt(), 0xFFFFF1E0.toInt())
        val colors = intArrayOf(0xFFFF5C8A.toInt(), 0xFFFFB627.toInt(), 0xFF2EC4B6.toInt(), 0xFF5B7CFA.toInt(), 0xFFA66CFF.toInt(), 0xFF7BD389.toInt())
        val p = paint()
        repeat(170) {
            p.color = colors[r.nextInt(colors.size)]
            p.alpha = 170 + r.nextInt(85)
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            c.save()
            c.rotate(r.nextFloat() * 360f, x, y)
            if (r.nextBoolean()) {
                c.drawCircle(x, y, (6 + r.nextFloat() * 9) * s, p)
            } else {
                val lw = (8 + r.nextFloat() * 10) * s
                val lh = (18 + r.nextFloat() * 18) * s
                c.drawRoundRect(RectF(x - lw / 2, y - lh / 2, x + lw / 2, y + lh / 2), 4 * s, 4 * s, p)
            }
            c.restore()
        }
    }

    private fun triangles(c: Canvas, w: Float, h: Float, r: Random) {
        val cols = 7
        val rows = 14
        val cw = w / (cols - 1)
        val ch = h / (rows - 1)
        val pts = Array(rows) { j ->
            Array(cols) { i ->
                val jitter = if (i in 1 until cols - 1 && j in 1 until rows - 1) 0.35f else 0f
                floatArrayOf(i * cw + (r.nextFloat() - 0.5f) * cw * jitter * 2, j * ch + (r.nextFloat() - 0.5f) * ch * jitter * 2)
            }
        }
        val top = 0xFF2EC5CE.toInt()
        val bottom = 0xFF3B2A8F.toInt()
        // Under the facets, so their anti-aliased seams show the same colours.
        vertical(c, w, h, top, bottom)
        val p = paint().apply { style = Paint.Style.FILL }
        val path = Path()
        fun tri(a: FloatArray, b: FloatArray, d: FloatArray) {
            val t = ((a[1] + b[1] + d[1]) / 3f / h).coerceIn(0f, 1f)
            val base = mix(top, bottom, t)
            val light = (r.nextFloat() - 0.5f) * 0.18f
            p.color = lighten(base, light)
            path.reset()
            path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]); path.lineTo(d[0], d[1]); path.close()
            c.drawPath(path, p)
        }
        for (j in 0 until rows - 1) for (i in 0 until cols - 1) {
            val a = pts[j][i]; val b = pts[j][i + 1]; val d = pts[j + 1][i]; val e = pts[j + 1][i + 1]
            tri(a, b, d)
            tri(b, e, d)
        }
    }

    private fun paper(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFFE6F7EE.toInt(), 0xFFF3E9F7.toInt(), 0xFFFCE6E6.toInt())
        softCircle(c, w * 0.1f, h * 0.15f, w * 0.7f, 0xFFFFF6D6.toInt(), 120)
        softCircle(c, w * 0.9f, h * 0.8f, w * 0.8f, 0xFFDCE8FF.toInt(), 110)
        // Grain: many faint specks, dark and light.
        val count = (w * h / 90f).toInt().coerceIn(500, 60_000)
        val dark = FloatArray(count)
        val light = FloatArray(count)
        for (k in 0 until count / 2) {
            dark[2 * k] = r.nextFloat() * w; dark[2 * k + 1] = r.nextFloat() * h
            light[2 * k] = r.nextFloat() * w; light[2 * k + 1] = r.nextFloat() * h
        }
        val p = Paint().apply { strokeWidth = maxOf(1f, 1.6f * s) }
        p.color = alpha(0xFF6B5B4B.toInt(), 22)
        c.drawPoints(dark, p)
        p.color = alpha(Color.WHITE, 60)
        c.drawPoints(light, p)
    }

    private fun city(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFF120B2E.toInt(), 0xFF2B1650.toInt(), 0xFF5A2A5E.toInt(), 0xFF8A4A4F.toInt())
        val warm = intArrayOf(0xFFFFC46B.toInt(), 0xFFFF8F6B.toInt(), 0xFFFFE3A3.toInt(), 0xFFFF6FA8.toInt(), 0xFF8FD3FF.toInt())
        repeat(40) {
            softCircle(c, r.nextFloat() * w, h * (0.35f + r.nextFloat() * 0.45f), (24 + r.nextFloat() * 90) * s, warm[r.nextInt(warm.size)], 70 + r.nextInt(80))
        }
        // The skyline, dark, along the bottom, with a few lit windows.
        val p = paint().apply { color = 0xFF0A0716.toInt() }
        val win = paint()
        var x = 0f
        while (x < w) {
            val bw = (60 + r.nextFloat() * 110) * s
            val bh = h * (0.08f + r.nextFloat() * 0.16f)
            c.drawRect(x, h - bh, x + bw, h, p)
            var wy = h - bh + 14 * s
            while (wy < h - 12 * s) {
                var wx = x + 10 * s
                while (wx < x + bw - 14 * s) {
                    if (r.nextFloat() < 0.28f) {
                        win.color = alpha(warm[r.nextInt(3)], 150 + r.nextInt(90))
                        c.drawRect(wx, wy, wx + 8 * s, wy + 12 * s, win)
                    }
                    wx += 20 * s
                }
                wy += 26 * s
            }
            x += bw + 4 * s
        }
    }

    private fun heart(x: Float, y: Float, size: Float): Path = Path().apply {
        moveTo(x, y + size * 0.35f)
        cubicTo(x - size * 0.9f, y - size * 0.35f, x - size * 0.35f, y - size * 0.95f, x, y - size * 0.4f)
        cubicTo(x + size * 0.35f, y - size * 0.95f, x + size * 0.9f, y - size * 0.35f, x, y + size * 0.35f)
        close()
    }

    private fun hearts(c: Canvas, w: Float, h: Float, s: Float) {
        vertical(c, w, h, 0xFFFFE4EE.toInt(), 0xFFFFC9DC.toInt())
        val p = paint()
        val step = 150 * s
        var row = 0
        var y = step / 2
        while (y < h + step) {
            var x = if (row % 2 == 0) step / 2 else step
            var col = 0
            while (x < w + step) {
                val big = (row + col) % 3 == 0
                p.color = if (big) alpha(0xFFFF6F9C.toInt(), 120) else alpha(Color.WHITE, 150)
                c.save()
                c.rotate(if ((row + col) % 2 == 0) -14f else 12f, x, y)
                c.drawPath(heart(x, y, if (big) 46 * s else 30 * s), p)
                c.restore()
                x += step
                col++
            }
            y += step * 0.85f
            row++
        }
    }

    private fun leaves(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFFE6F4DA.toInt(), 0xFFBFE0A8.toInt(), 0xFF8CC47E.toInt())
        val greens = intArrayOf(0xFF3F7D4A.toInt(), 0xFF2F6B3E.toInt(), 0xFF5C9A5A.toInt(), 0xFF24543A.toInt())
        val p = paint()
        val rib = paint().apply { style = Paint.Style.STROKE; strokeWidth = 3 * s }
        repeat(34) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val len = (90 + r.nextFloat() * 140) * s
            val g = greens[r.nextInt(greens.size)]
            p.color = alpha(g, 110 + r.nextInt(90))
            rib.color = alpha(Color.WHITE, 70)
            c.save()
            c.rotate(r.nextFloat() * 360f, x, y)
            val leaf = Path().apply {
                moveTo(x, y - len / 2)
                quadTo(x + len * 0.38f, y, x, y + len / 2)
                quadTo(x - len * 0.38f, y, x, y - len / 2)
                close()
            }
            c.drawPath(leaf, p)
            c.drawLine(x, y - len / 2, x, y + len / 2, rib)
            c.restore()
        }
    }

    private fun galaxy(c: Canvas, w: Float, h: Float, s: Float, r: Random) {
        vertical(c, w, h, 0xFF05030F.toInt(), 0xFF0B0620.toInt())
        val cx = w / 2
        val cy = h * 0.45f
        softCircle(c, cx, cy, w * 0.9f, 0xFF3A1E6E.toInt(), 120)
        softCircle(c, cx, cy, w * 0.35f, 0xFFB48CFF.toInt(), 150)
        val p = paint()
        val colors = intArrayOf(0xFFE7DBFF.toInt(), 0xFF9FB6FF.toInt(), 0xFFC58CFF.toInt(), Color.WHITE, 0xFFFFB8E6.toInt())
        // Two arms of a logarithmic spiral, stars scattered about them.
        for (arm in 0 until 2) repeat(900) {
            val t = r.nextFloat() * 3.2f * PI.toFloat()
            val radius = w * 0.04f * exp(0.24f * t)
            val spread = radius * 0.22f
            val a = t + arm * PI.toFloat()
            val x = cx + radius * cos(a) + (r.nextFloat() - 0.5f) * spread * 2
            val y = cy + radius * sin(a) * 0.85f + (r.nextFloat() - 0.5f) * spread * 2
            p.color = colors[r.nextInt(colors.size)]
            p.alpha = 90 + r.nextInt(165)
            c.drawCircle(x, y, (0.8f + r.nextFloat() * 2f) * s, p)
        }
        val glow = paint().apply { color = 0xFFFFF4FF.toInt(); maskFilter = BlurMaskFilter(30 * s.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL) }
        c.drawCircle(cx, cy, 40 * s, glow)
        val dust = paint().apply { color = Color.WHITE }
        repeat(260) {
            dust.alpha = 40 + r.nextInt(150)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (0.6f + r.nextFloat() * 1.4f) * s, dust)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun lighten(color: Int, by: Float): Int {
        fun ch(x: Int) = (if (by >= 0) x + (255 - x) * by else x * (1 + by)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }
}
