package com.lightdecoder.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.lightdecoder.R

/**
 * Letter avatars for people and conversations. The phone sends a colour per
 * conversation; when it does not, one is picked from [PALETTE] by a hash of
 * the conversation id, so the same chat keeps the same colour.
 */
object Avatars {

    /** Dark enough that white initials stay readable (large text, ≥ 3:1) in light and dark themes. */
    private val PALETTE = intArrayOf(
        0xFF1565C0.toInt(), // blue
        0xFF2E7D32.toInt(), // green
        0xFFC62828.toInt(), // red
        0xFF6A1B9A.toInt(), // purple
        0xFF00695C.toInt(), // teal
        0xFFAD1457.toInt(), // pink
        0xFF4527A0.toInt(), // deep purple
        0xFF283593.toInt(), // indigo
        0xFF5D4037.toInt(), // brown
        0xFF37474F.toInt(), // blue grey
        0xFF006064.toInt(), // cyan
        0xFFBF360C.toInt(), // deep orange
    )

    /**
     * Up to two letters: the first letter of the first and of the last word.
     * A name that is a phone number gets "#", a blank one "?".
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val first = words.first()
        val firstCp = first.codePointAt(0)
        if (!Character.isLetter(firstCp)) {
            return if (first.first() == '+' || Character.isDigit(firstCp) || first.first() == '(') "#" else "?"
        }
        val a = String(Character.toChars(firstCp)).uppercase()
        if (words.size == 1) return a
        val lastCp = words.last().codePointAt(0)
        if (!Character.isLetter(lastCp)) return a
        return a + String(Character.toChars(lastCp)).uppercase()
    }

    /** The phone's colour ("#RRGGBB") when it gave a usable one, else a stable pick for [key]. */
    fun colorFor(key: String, phoneColor: String): Int {
        parseColor(phoneColor)?.let { return it }
        return PALETTE[Math.floorMod(key.hashCode(), PALETTE.size)]
    }

    /** White or black, whichever reads better on [background]. */
    fun textColorOn(background: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(background, 255)
        val white = ColorUtils.calculateContrast(Color.WHITE, opaque)
        val black = ColorUtils.calculateContrast(Color.BLACK, opaque)
        return if (white >= black) Color.WHITE else Color.BLACK
    }

    /** Shows [name]'s initials on a circle in its colour. The view needs @drawable/bg_avatar. */
    fun bind(view: TextView, name: String, key: String, phoneColor: String) {
        val color = colorFor(key, phoneColor)
        view.text = initials(name)
        view.setBackgroundResource(R.drawable.bg_avatar)
        view.backgroundTintList = ColorStateList.valueOf(color)
        view.setTextColor(textColorOn(color))
    }

    internal fun parseColor(text: String): Int? {
        val t = text.trim()
        if (!t.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
        return try {
            Color.parseColor(t)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
