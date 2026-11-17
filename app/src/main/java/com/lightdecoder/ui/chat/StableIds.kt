package com.lightdecoder.ui.chat

/**
 * Gives each string key its own Long for RecyclerView's stable ids. Unlike a
 * hash, two keys never share an id, so animations never mix up two rows.
 * Main thread only (adapters are).
 */
class StableIds {
    private val ids = HashMap<String, Long>()

    fun of(key: String): Long = ids.getOrPut(key) { ids.size.toLong() + 1 }
}
