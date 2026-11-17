package com.lightdecoder.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityLicensesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * About > Licences: the NOTICE file, then every licence text the build put
 * in the app's assets (licenses/, see bundleLicenses in app/build.gradle.kts),
 * in name order, in a fixed-width font.
 *
 * That is a few hundred KB of text, so it is read off the main thread and
 * shown as a list of short pieces: one huge TextView would lay all of it out
 * at once on the main thread.
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.list.layoutManager = LinearLayoutManager(this)

        val assets = assets
        lifecycleScope.launch {
            val pieces = withContext(Dispatchers.IO) {
                try {
                    Licenses.pieces(Licenses.read(assets))
                } catch (e: IOException) {
                    null
                }
            }
            binding.progress.visibility = View.GONE
            if (pieces.isNullOrEmpty()) {
                binding.failed.visibility = View.VISIBLE
            } else {
                binding.list.adapter = PiecesAdapter(pieces)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class PiecesAdapter(private val pieces: List<Licenses.Piece>) : RecyclerView.Adapter<PiecesAdapter.Holder>() {
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = pieces.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_license_text, parent, false) as TextView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val piece = pieces[position]
            val view = holder.text
            view.text = piece.text
            view.typeface = if (piece.heading) HEADING else BODY
            val top = (view.resources.displayMetrics.density * if (piece.heading) HEADING_GAP_DP else BODY_GAP_DP).toInt()
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            ViewCompat.setAccessibilityHeading(view, piece.heading)
        }

        private companion object {
            val BODY: Typeface = Typeface.MONOSPACE
            val HEADING: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            const val HEADING_GAP_DP = 28
            const val BODY_GAP_DP = 8
        }
    }
}

/** The licence texts in the app's assets, and how [LicensesActivity] cuts them up. */
internal object Licenses {

    /** NOTICE at the root of the assets: the summary of what is in the app and under which licence. */
    const val NOTICE = "NOTICE"

    /** The full licence texts, one file per component. */
    const val DIR = "licenses"

    /** A heading (what a text is for) or a few paragraphs of the text. */
    data class Piece(val text: String, val heading: Boolean)

    /** The licence text files, in name order (the build numbers them: the app, its native code, then Go, then the Android libraries). */
    fun files(assets: AssetManager): List<String> =
        assets.list(DIR).orEmpty().filter { it.endsWith(".txt") }.sorted()

    /**
     * NOTICE, then each file of [DIR], as (heading, text). A licence file's
     * first line says what it is for (the build writes it, e.g.
     * "go.mau.fi/mautrix-gmessages v0.2610.0 (Go module, in libgojni.so)")
     * and becomes the heading. Throws [IOException] if a file cannot be read.
     */
    fun read(assets: AssetManager): List<Pair<String, String>> {
        val texts = ArrayList<Pair<String, String>>()
        texts += NOTICE to assets.open(NOTICE).bufferedReader().use { it.readText() }
        for (name in files(assets)) {
            val text = assets.open("$DIR/$name").bufferedReader().use { it.readText() }
            val first = text.substringBefore('\n').trim()
            texts += if (first.isNotEmpty()) first to text.substringAfter('\n', "") else name to text
        }
        return texts
    }

    /**
     * Each text as its heading followed by its paragraphs, a few at a time
     * (at most about [MAX_PIECE] characters each, longer paragraphs split at
     * line ends), so no single view holds a whole licence.
     */
    fun pieces(texts: List<Pair<String, String>>): List<Piece> {
        val out = ArrayList<Piece>()
        for ((name, text) in texts) {
            out += Piece(name, heading = true)
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) out += Piece(current.toString().trimEnd(), heading = false)
                current.setLength(0)
            }
            for (paragraph in text.replace("\r\n", "\n").trim().split(Regex("\n[ \t]*\n"))) {
                for (part in splitLong(paragraph)) {
                    if (current.isNotEmpty() && current.length + part.length > MAX_PIECE) flush()
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(part)
                }
            }
            flush()
        }
        return out
    }

    /** A paragraph longer than [MAX_PIECE], cut at line ends (or hard, for one endless line). */
    private fun splitLong(paragraph: String): List<String> {
        if (paragraph.length <= MAX_PIECE) return listOf(paragraph)
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (line in paragraph.split('\n')) {
            for (chunk in line.chunked(MAX_PIECE)) {
                if (current.isNotEmpty() && current.length + chunk.length + 1 > MAX_PIECE) {
                    parts += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(chunk)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private const val MAX_PIECE = 2_000
}
