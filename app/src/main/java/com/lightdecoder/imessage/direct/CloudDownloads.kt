package com.lightdecoder.imessage.direct

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An imported iCloud attachment's download (B6.5), as the user's other downloads are: the library
 * reports no progress for it, so the file on disk is watched while it comes. Past its cap the
 * download is stopped and what came deleted; meanwhile its size so far is passed on now and then,
 * so the main process knows it is still under way (its wait ends only once it hears nothing).
 */
internal object CloudDownloads {
    const val WATCH_MS = 250L
    const val HEARTBEAT_MS = 2_000L

    /**
     * Runs [fetch], which writes [partial], never letting it grow past [maxBytes]: false when it did
     * (stopped, [partial] deleted). [onProgress] (never thrown out of) is given [partial]'s size at
     * the start and every [heartbeatMs] after. A cancellation of the caller (the user stopped it) goes on up.
     */
    suspend fun capped(
        partial: File,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)?,
        watchMs: Long = WATCH_MS,
        heartbeatMs: Long = HEARTBEAT_MS,
        fetch: suspend () -> Unit,
    ): Boolean {
        val over = AtomicBoolean(false)
        try {
            coroutineScope {
                val work = async { fetch() }
                val watch = launch {
                    var sinceBeat = heartbeatMs
                    while (isActive) {
                        val size = partial.length()
                        if (size > maxBytes) {
                            over.set(true)
                            work.cancel()
                            break
                        }
                        if (onProgress != null && sinceBeat >= heartbeatMs) {
                            sinceBeat = 0
                            try {
                                onProgress(size)
                            } catch (e: Throwable) {
                                // Only progress.
                            }
                        }
                        delay(watchMs)
                        sinceBeat += watchMs
                    }
                }
                try {
                    work.await()
                } finally {
                    watch.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (!over.get()) throw e
        } finally {
            // Also one that grew past it after it was last looked at.
            if (partial.length() > maxBytes) over.set(true)
            if (over.get()) partial.delete()
        }
        return !over.get()
    }
}
