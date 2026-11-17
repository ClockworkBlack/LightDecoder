package com.lightdecoder.discord

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.roundToInt

/**
 * Discord's "sign in with a QR code", for the Discord tab.
 *
 * Discord's sign-in page shows a QR code (only when the page is wide, as on a
 * computer) that the Discord app on a signed-in phone approves. Its content
 * is a link, https://discord.com/ra/<code>, valid for two minutes. This reads
 * the code off the screen, as a camera would, and hands the link to the
 * official Discord app on this phone, where the person approves the sign-in
 * (or cancels it); or shows it large for the Discord app on another device to
 * scan. Nothing else of the page is read, and the link goes nowhere else.
 */
object DiscordQr {

    private const val DISCORD_PACKAGE = "com.discord"
    private val LOGIN_LINK = Regex("^https://discord\\.com/ra/[A-Za-z0-9_-]{16,128}$")

    /** The width, in CSS pixels, the page is given while showing its QR code: a computer's. */
    const val DESKTOP_WIDTH_CSS = 1100

    /** [text] if it is a Discord sign-in QR code's link, else null. */
    fun loginLinkOrNull(text: String?): String? = text?.trim()?.takeIf { LOGIN_LINK.matches(it) }

    /**
     * How the page view is laid out while wide: its width and height in
     * pixels, and the scale that fits it into [parentWidth] by [parentHeight].
     */
    data class Wide(val width: Int, val height: Int, val scale: Float)

    fun wide(parentWidth: Int, parentHeight: Int, density: Float, cssWidth: Int = DESKTOP_WIDTH_CSS): Wide {
        // Never narrower than the screen: a page is not stretched larger this way (text size does that).
        val width = (cssWidth * density).roundToInt().coerceAtLeast(parentWidth)
        val scale = parentWidth.toFloat() / width
        return Wide(width, (parentHeight / scale).roundToInt(), scale)
    }

    /** The Discord sign-in link in a QR code anywhere in [picture], or null. */
    fun find(picture: Bitmap): String? {
        val w = picture.width
        val h = picture.height
        if (w < 21 || h < 21) return null
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        val source: LuminanceSource = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        // Dark modules on light (as Discord draws it), then the other way round.
        for (luminance in listOf(source, source.invert())) {
            for (binary in listOf(BinaryBitmap(HybridBinarizer(luminance)), BinaryBitmap(GlobalHistogramBinarizer(luminance)))) {
                val text = try {
                    QRCodeReader().decode(binary, hints).text
                } catch (e: NotFoundException) {
                    null
                } catch (e: ReaderException) {
                    null
                }
                loginLinkOrNull(text)?.let { return it }
            }
        }
        return null
    }

    /** [link] as a QR code [size] pixels square, black on white, to show large. */
    fun draw(link: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Hands [link] to the official Discord app, which asks the person to
     * approve the sign-in. False when the Discord app is not installed.
     */
    fun approveInDiscordApp(context: Context, link: String): Boolean {
        val checked = loginLinkOrNull(link) ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(checked)).setPackage(DISCORD_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
