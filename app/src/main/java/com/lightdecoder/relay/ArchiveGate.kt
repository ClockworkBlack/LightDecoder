package com.lightdecoder.relay

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Keeps what the phone sends back from landing in an archive the user has
 * since deleted.
 *
 * A call to the phone (a page of history, the conversation list, a media
 * download) can take a minute and cannot be interrupted. Every such result is
 * written through [ifCurrent] with the [epoch] taken before the call; a wipe
 * ([wipe]) bumps the epoch while no such write is in progress, so a result
 * that comes back afterwards is dropped instead of putting names, numbers,
 * text or files back.
 */
internal class ArchiveGate {
    private val lock = ReentrantReadWriteLock()
    private val epoch = AtomicLong()

    /** Take this before asking the phone for something that will be stored. */
    fun epoch(): Long = epoch.get()

    /** Runs [block] unless the archive was wiped since [since] was taken; null if it was. */
    fun <T> ifCurrent(since: Long, block: () -> T): T? = lock.read {
        if (epoch.get() != since) null else block()
    }

    /** Wipes the archive with [block], after any write in progress and before any later one. */
    fun wipe(block: () -> Unit) = lock.write {
        epoch.incrementAndGet()
        block()
    }
}
