package com.lightdecoder.relay

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the user's own Google cookies, pasted in whatever shape their browser
 * gave them:
 *
 *  - a JSON object: {"SID": "…", "HSID": "…"}
 *  - a JSON array of cookies, as cookie-export extensions and DevTools write
 *    them: [{"name": "SID", "value": "…", "domain": ".google.com"}, …]
 *  - a Cookie header: "Cookie: SID=…; HSID=…" (the "Cookie:" is optional)
 *  - a curl command ("Copy as cURL"), with -H 'cookie: …', -b '…' or --cookie '…'
 *
 * Only the cookies Google Messages for web needs are kept: the [REQUIRED] ones
 * and any "__Secure-…" ones. Nothing here logs or stores what it reads.
 */
object CookieParser {
    val REQUIRED = listOf("SID", "HSID", "SSID", "APISID", "SAPISID", "OSID")

    private const val SECURE_PREFIX = "__Secure-"

    /** The useful cookies in [input], in the order found; empty if none could be read. */
    fun parse(input: String): Map<String, String> {
        val text = input.trim().removePrefix("\uFEFF")
        val all = when {
            text.isEmpty() -> emptyList()
            text.startsWith("{") -> fromJsonObject(text)
            text.startsWith("[") -> fromJsonArray(text)
            text.startsWith("curl", ignoreCase = true) -> fromCurl(text)
            else -> fromHeader(text)
        }
        val result = LinkedHashMap<String, String>()
        for ((name, value) in all) {
            if (isWanted(name) && value.isNotEmpty() && name !in result) result[name] = value
        }
        return result
    }

    /** The [REQUIRED] cookie names missing from [cookies], in [REQUIRED] order. */
    fun missingRequired(cookies: Map<String, String>): List<String> =
        REQUIRED.filter { cookies[it].isNullOrEmpty() }

    private fun isWanted(name: String) = name in REQUIRED || name.startsWith(SECURE_PREFIX)

    private fun fromJsonObject(text: String): List<Pair<String, String>> {
        val o = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return emptyList()
        }
        // Some exporters wrap the list: {"cookies": [...]}.
        o.optJSONArray("cookies")?.let { return fromCookieList(it) }
        return o.keys().asSequence().mapNotNull { name ->
            val value = o.opt(name)
            if (value is String || value is Number || value is Boolean) name.trim() to value.toString().trim() else null
        }.toList()
    }

    private fun fromJsonArray(text: String): List<Pair<String, String>> = try {
        fromCookieList(JSONArray(text))
    } catch (e: JSONException) {
        emptyList()
    }

    /**
     * A full export holds the same name for several sites (OSID exists for
     * several Google services), so the most fitting domain wins:
     * messages.google.com, then google.com, then other Google sites.
     */
    private fun fromCookieList(arr: JSONArray): List<Pair<String, String>> {
        val best = LinkedHashMap<String, Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.text("name")
            val value = c.text("value")
            if (name.isEmpty() || value.isEmpty()) continue
            val rank = domainRank(c.text("domain").lowercase())
            if (rank < 0) continue
            val current = best[name]
            if (current == null || rank > current.first) best[name] = rank to value
        }
        return best.map { (name, ranked) -> name to ranked.second }
    }

    /** A field as trimmed text; missing or JSON null gives "" (optString would say "null"). */
    private fun JSONObject.text(key: String): String =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()

    private fun domainRank(domain: String): Int {
        val host = domain.removePrefix(".")
        return when {
            host.isEmpty() -> 2
            host == "messages.google.com" -> 3
            host == "google.com" -> 2
            host.endsWith(".google.com") -> 1
            else -> -1
        }
    }

    /** "Cookie: a=b; c=d", also split across lines. The first value of a repeated name wins. */
    private fun fromHeader(text: String): List<Pair<String, String>> {
        val body = text.lines().joinToString(";") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("cookie:", ignoreCase = true)) trimmed.substring("cookie:".length) else trimmed
        }
        return body.split(';').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim().removeSurrounding("\"")
            if (name.isEmpty() || name.any { it.isWhitespace() }) null else name to value
        }
    }

    /** Pulls the cookie strings out of a curl command line and reads them as headers. */
    private fun fromCurl(text: String): List<Pair<String, String>> {
        val args = shellWords(text)
        val result = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val next = args.getOrNull(i + 1)
            when {
                (arg == "-H" || arg == "--header") && next != null -> {
                    if (next.trimStart().startsWith("cookie:", ignoreCase = true)) result += fromHeader(next)
                    i++
                }
                (arg == "-b" || arg == "--cookie") && next != null -> {
                    // A value without "=" names a cookie file, which cannot be read from here.
                    if (next.contains('=')) result += fromHeader(next)
                    i++
                }
                arg.startsWith("--header=") || arg.startsWith("--cookie=") -> {
                    val value = arg.substringAfter('=')
                    if (arg.startsWith("--cookie=") || value.trimStart().startsWith("cookie:", ignoreCase = true)) {
                        result += fromHeader(value)
                    }
                }
            }
            i++
        }
        return result
    }

    /**
     * Splits a POSIX shell command line into words: 'single', "double" (with
     * backslash escapes), $'ANSI-C' quoting, and backslash line continuations,
     * which is what browsers produce for "Copy as cURL".
     */
    internal fun shellWords(line: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length && (line[i + 1] == '\n' || line[i + 1] == '\r') -> {
                    i++ // a continuation: skip the newline too
                    if (line[i] == '\r' && i + 1 < line.length && line[i + 1] == '\n') i++
                }
                c.isWhitespace() -> {
                    if (inWord) words.add(word.toString())
                    word.clear()
                    inWord = false
                }
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }
                    word.append(line, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '$' && i + 1 < line.length && line[i + 1] == '\'' -> {
                    i += 2
                    while (i < line.length && line[i] != '\'') {
                        if (line[i] == '\\' && i + 1 < line.length) {
                            i++
                            word.append(
                                when (line[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    else -> line[i]
                                }
                            )
                        } else {
                            word.append(line[i])
                        }
                        i++
                    }
                    inWord = true
                }
                c == '"' -> {
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") i++
                        word.append(line[i])
                        i++
                    }
                    inWord = true
                }
                c == '\\' && i + 1 < line.length -> {
                    i++
                    word.append(line[i])
                    inWord = true
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words.add(word.toString())
        return words
    }
}
