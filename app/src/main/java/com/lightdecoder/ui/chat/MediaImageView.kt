package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.color.MaterialColors
import com.lightdecoder.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture in a conversation, sized the way messaging apps size them.
 *
 * The size comes from the picture's real dimensions when the phone reported
 * them ([setMediaSize]). So the loading placeholder and a blurry preview
 * already take the final space, and nothing jumps when the full picture
 * arrives. Without them, the size comes from the picture shown.
 *
 * Large pictures shrink to fit [maxWidthPx] × [maxHeightPx], and never get
 * wider than the space the bubble offers. Small ones grow until their longer
 * side is [minSidePx]: a 64-pixel sticker is not a speck.
 *
 * Photos get rounded corners. Pictures with transparency (stickers) are drawn
 * as they are, straight onto the conversation.
 */
class MediaImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private var mediaWidth = 0
    private var mediaHeight = 0

    var maxWidthPx: Int = (resources.displayMetrics.widthPixels * 0.72f).roundToInt()
    var maxHeightPx: Int = min((resources.displayMetrics.heightPixels * 0.55f).roundToInt(), (520 * density).roundToInt())
    var minSidePx: Int = (120 * density).roundToInt()

    /** The loading box (no picture yet): its size when nothing says otherwise. */
    private val placeholderWidth = (200 * density).roundToInt()
    private val placeholderHeight = (150 * density).roundToInt()

    private val corner = 18 * density
    private val loadingBackground = GradientDrawable().apply {
        cornerRadius = corner
        setColor(MaterialColors.getColor(this@MediaImageView, com.google.android.material.R.attr.colorSurfaceContainerHigh))
    }

    /** True while a preview (inline or thumbnail) shows in place of the picture. */
    var showingPreview = false
        private set

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true
    }

    /** The picture's real size in pixels, as the phone reported it; 0 when it did not. */
    fun setMediaSize(width: Int, height: Int) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w == mediaWidth && h == mediaHeight) return
        mediaWidth = w
        mediaHeight = h
        requestLayout()
    }

    /** Nothing to show yet: a rounded box with the picture icon in its middle. */
    fun showLoading() {
        setPreviewEffect(false)
        showingPreview = false
        scaleType = ScaleType.CENTER
        setImageResource(R.drawable.ic_image_placeholder)
        background = loadingBackground
        clipToOutline = true
    }

    /**
     * Shows [bitmap]. A [preview] is the phone's small stand-in while the
     * picture itself loads; it is blurred (Android 12+) so it reads as
     * "loading" rather than as a bad picture.
     */
    fun showPicture(bitmap: Bitmap, preview: Boolean) {
        scaleType = ScaleType.FIT_CENTER
        background = null
        setImageBitmap(bitmap)
        // Photos are rounded; a sticker with see-through parts sits on the chat as it is.
        clipToOutline = !bitmap.hasAlpha()
        showingPreview = preview
        setPreviewEffect(preview)
    }

    /** The preview stays as the best there is (the full picture could not be loaded): stop blurring it. */
    fun settlePreview() {
        setPreviewEffect(false)
    }

    private fun setPreviewEffect(on: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val radius = 10 * density
            setRenderEffect(if (on) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
        } catch (t: Throwable) {
            // Some test environments have no render effects; the preview just stays sharp.
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bitmapDrawable = drawable as? BitmapDrawable
        val (naturalW, naturalH) = when {
            mediaWidth > 0 && mediaHeight > 0 -> mediaWidth to mediaHeight
            bitmapDrawable?.bitmap != null -> bitmapDrawable.bitmap.width to bitmapDrawable.bitmap.height
            else -> placeholderWidth to placeholderHeight
        }
        var capW = maxWidthPx
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            capW = min(capW, MeasureSpec.getSize(widthMeasureSpec))
        }
        var capH = maxHeightPx
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            capH = min(capH, MeasureSpec.getSize(heightMeasureSpec))
        }
        val size = fit(naturalW, naturalH, capW, capH, minSidePx)
        setMeasuredDimension(size.first, size.second)
    }

    companion object {
        /**
         * The display size of a [width] × [height] picture: as large as it is
         * (in pixels), shrunk to fit [maxW] × [maxH], grown so that its longer
         * side is at least [minSide] (within the same limits). Keeps the shape.
         */
        internal fun fit(width: Int, height: Int, maxW: Int, maxH: Int, minSide: Int): Pair<Int, Int> {
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            var scale = 1f
            if (max(w, h) * scale < minSide) scale = minSide / max(w, h)
            scale = min(scale, min(maxW.coerceAtLeast(1) / w, maxH.coerceAtLeast(1) / h))
            return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
        }
    }
}
