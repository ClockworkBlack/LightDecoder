package com.lightdecoder.ui.share

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Which uris another app may hand LightDecoder (a share, "open with"): content uris of another
 * app's provider, never a file path and never one of this app's own (private) providers, which
 * would make LightDecoder read, show, copy or send its own files (message pictures, the archive's
 * media) for someone else.
 *
 * The provider is found as the content resolver finds it: from the uri's decoded authority, after
 * any "userId@" prefix ([providerAuthority]). Uri's host is not enough: in
 * `content://0%40com.lightdecoder.fileprovider/…` it is `0@com.lightdecoder.fileprovider` (no such
 * provider), while the resolver opens this app's own provider for user 0.
 */
object ExternalUris {

    /**
     * The provider [uri] is opened from, as ContentResolver reads it: the decoded authority without
     * its "userId@" prefix (ContentProvider.getAuthorityWithoutUserId). Null without an authority.
     */
    fun providerAuthority(uri: Uri): String? = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() }

    /** Whether [uri] is another app's content uri, which this app may read for whoever handed it over. */
    fun isSafeExternal(context: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        val authority = providerAuthority(uri) ?: return false
        // Uri's own reading of the host too (it drops a port, and a literal '@' prefix): neither may be ours.
        val names = setOfNotNull(authority, uri.host?.substringAfterLast('@')?.takeIf { it.isNotEmpty() })
        val own = ownAuthorities(context)
        if (names.any { name -> own.any { it.equals(name, ignoreCase = true) } }) return false
        return names.none { name ->
            runCatching { context.packageManager.resolveContentProvider(name, 0)?.packageName }.getOrNull() == context.packageName
        }
    }

    /**
     * Every authority this app's own providers answer to: those its package declares (the
     * FileProvider and the ones libraries merge in), and the FileProvider's by name in case the
     * package manager does not list it. Matched exactly, not by a "$packageName." prefix: the
     * debug build's provider (com.lightdecoder.debug.fileprovider) is another app's to the release
     * build.
     */
    fun ownAuthorities(context: Context): Set<String> {
        val pm = context.packageManager
        val declared = runCatching {
            val flags = PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
            }
            info.providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }
        }.getOrDefault(emptyList())
        return (declared + "${context.packageName}.fileprovider").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
