package com.lightdecoder.imessage.direct

import androidx.annotation.StringRes
import com.lightdecoder.R
import java.net.URI
import java.net.URISyntaxException

/**
 * The registration relay's address the user typed on the iMessage setup screen ([DirectEngine.register]).
 * Only an https:// address of a real host is taken: never http:// (the relay code and Apple's
 * validation data would cross the network readable), another scheme, a user name or password in
 * the address, or a query or fragment. Pure ([check]); tested in RelayHostTest.
 */
object RelayHost {

    /** Why an address is not taken. */
    enum class Problem {
        /** Not https:// (http://, another scheme, or none). */
        NOT_HTTPS,

        /** No host, or not a valid host name or address. */
        BAD_HOST,

        /** A user name or password in it ("https://user:pass@host"). */
        CREDENTIALS,

        /** Anything else that is not a plain address (spaces, a query, a fragment, a bad port). */
        MALFORMED,
    }

    /** What the setup screen (and a refused registration) says of [problem]. */
    @StringRes
    fun messageRes(problem: Problem?): Int = when (problem) {
        Problem.NOT_HTTPS -> R.string.imessage_relay_host_not_https
        Problem.CREDENTIALS -> R.string.imessage_relay_host_credentials
        Problem.BAD_HOST -> R.string.imessage_relay_host_bad_host
        Problem.MALFORMED, null -> R.string.imessage_relay_host_malformed
    }

    /** [text] checked: the address to use ([value], without a trailing "/") or why not ([problem]). */
    data class Checked(val value: String?, val problem: Problem?)

    private val LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun check(text: String): Checked {
        val trimmed = text.trim()
        fun no(problem: Problem) = Checked(null, problem)
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return no(Problem.MALFORMED)
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0 || !trimmed.substring(0, schemeEnd).equals("https", ignoreCase = true)) return no(Problem.NOT_HTTPS)
        // Read from the text itself first: URI would take "https://a@b" for a user "a" at host "b".
        val authority = trimmed.substring(schemeEnd + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return no(Problem.CREDENTIALS)
        if (authority.isEmpty()) return no(Problem.BAD_HOST)
        val port: String? = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return no(Problem.BAD_HOST)
            val after = authority.substring(close + 1)
            when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return no(Problem.MALFORMED)
            }
        } else {
            if (':' in authority) authority.substringAfter(':') else null
        }
        if (port != null && (port.isEmpty() || !port.all { it in '0'..'9' } || port.length > 5 || port.toInt() !in 1..65535)) {
            return no(Problem.MALFORMED)
        }
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return no(Problem.MALFORMED)
        }
        if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) return no(Problem.NOT_HTTPS)
        if (uri.rawUserInfo != null) return no(Problem.CREDENTIALS)
        if (uri.rawQuery != null || uri.rawFragment != null) return no(Problem.MALFORMED)
        val host = uri.host ?: return no(Problem.BAD_HOST)
        if (!validHost(host)) return no(Problem.BAD_HOST)
        val clean = "https://" + uri.rawAuthority + uri.rawPath.orEmpty().trimEnd('/')
        return Checked(clean, null)
    }

    /** A DNS name (letters, digits and hyphens in dot-separated labels, not all digits) or an IP address. */
    private fun validHost(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) return host.length > 2 // an IPv6 literal URI already parsed
        if (host.length > 253) return false
        val labels = host.trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { !LABEL.matches(it) }) return false
        val ipv4 = labels.size == 4 && labels.all { l -> l.all { it.isDigit() } && l.length <= 3 && l.toInt() <= 255 }
        // A name of digits only that is not an IPv4 address ("https://123") is no host.
        return ipv4 || !labels.last().all { it.isDigit() }
    }
}
