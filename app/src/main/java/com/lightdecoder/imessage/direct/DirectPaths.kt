package com.lightdecoder.imessage.direct

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Which files a request from the main process ([DirectService]) may name: a file to send (a
 * message's attachment, an iMessage app's picture) or where to put a download. Only the app's own
 * files and cache, never the iMessage library's own state (its keys and journal, under
 * files/imessage), and never anything outside the app: read as the path really is
 * ([File.getCanonicalFile]: no "..", no link out). Pure apart from [forApp]; tested in DirectPathsTest.
 */
object DirectPaths {

    /** The folders under which [file] must be, and those under which it must not. */
    class Rules(val roots: List<File>, val excluded: List<File>)

    /** This app's: its files and cache folders, without the library's state. */
    fun forApp(context: Context): Rules = Rules(
        roots = listOf(context.filesDir, context.cacheDir),
        excluded = listOf(File(context.filesDir, "imessage")),
    )

    /**
     * Where the main process keeps iMessages' own files ([com.lightdecoder.archive.MediaCache]'s iMessage
     * media and outbox folders): what a link preview's picture that goes with a message may be.
     */
    fun forImessageMedia(context: Context): Rules = Rules(
        roots = listOf(
            com.lightdecoder.archive.MediaCache.mediaDir(context, com.lightdecoder.chat.ChatService.IMESSAGE),
            com.lightdecoder.archive.MediaCache.outboxDir(context, com.lightdecoder.chat.ChatService.IMESSAGE),
        ),
        excluded = emptyList(),
    )

    /** Whether [path] is a file under one of [rules]' roots and none of its excluded folders. */
    fun isAllowed(path: String, rules: Rules): Boolean {
        if (path.isEmpty() || '\u0000' in path) return false
        val file = canonical(File(path)) ?: return false
        val roots = rules.roots.mapNotNull(::canonical)
        val excluded = rules.excluded.map { canonical(it) ?: return false }
        fun inside(dir: File) = file.path.startsWith(dir.path + File.separator)
        return roots.any { inside(it) } && excluded.none { inside(it) || file == it }
    }

    private fun canonical(file: File): File? = try {
        file.canonicalFile
    } catch (e: IOException) {
        null
    }

    /** [isAllowed] for this app, or the request is refused with [refusal] (shown as the send's or download's error). */
    fun require(context: Context, path: String, refusal: String) {
        if (!isAllowed(path, forApp(context))) throw DirectEngine.ImessageError(refusal)
    }
}
