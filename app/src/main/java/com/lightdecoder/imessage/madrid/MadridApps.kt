package com.lightdecoder.imessage.madrid

import android.content.pm.PackageManager
import com.lightdecoder.imessage.AppSigning
import com.lightdecoder.imessage.direct.IMessageExtra

/**
 * The iMessage-app extensions LightDecoder hosts: separately installed apps that play an iMessage
 * app (today OpenPigeon, for GamePigeon games) through BlueBubbles' "Madrid extension" interface.
 * LightDecoder only carries their messages. Each is pinned by its signing certificate, checked
 * before every bind ([status]).
 *
 * Pure apart from [status]. Tested in MadridAppsTest.
 */
object MadridApps {

    /**
     * An extension app: the iMessage app it plays ([appId], its App Store id, and [bundleId], as
     * Apple's balloons name it, and [name]), the Android app ([pkg], [service]) and the SHA-256 of
     * its signing certificate ([certSha256], "AA:BB:…"). [label] is what the screens call it.
     */
    data class MadridApp(
        val appId: Long,
        val name: String,
        val bundleId: String,
        val pkg: String,
        val service: String,
        val certSha256: String,
        val label: String,
    ) {
        /** Its Play Store page, for the store app. */
        val marketUri: String get() = "market://details?id=$pkg"

        /** Its Play Store page, on the web. */
        val webStoreUri: String get() = "https://play.google.com/store/apps/details?id=$pkg"
    }

    /** OpenPigeon: GamePigeon's games with iPhone users. */
    val OPENPIGEON = MadridApp(
        appId = 1124197642L,
        name = "GamePigeon",
        bundleId = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext",
        pkg = "com.openbubbles.openpigeon",
        service = "com.openbubbles.openpigeon.MadridExtensionService",
        certSha256 = "CB:77:DB:97:3F:D8:CE:CD:55:75:B0:85:92:F4:4C:F5:4C:25:DE:21:B7:95:3F:ED:45:7C:BD:F4:36:D4:01:1D",
        label = "OpenPigeon",
    )

    /** Every extension app LightDecoder knows. */
    val REGISTRY: List<MadridApp> = listOf(OPENPIGEON)

    /**
     * The intent action an extension's service answers (OpenPigeon's own intent filter). A string,
     * never a class name: R8 may rename classes.
     */
    const val ACTION = "com.openbubbles.messaging.MadridExtensionService"

    /** The extension app that plays [app]'s cards: by its App Store id, else by its bundle id; null for none. */
    fun forApp(app: IMessageExtra.App): MadridApp? =
        REGISTRY.firstOrNull { app.appId != null && it.appId == app.appId }
            ?: REGISTRY.firstOrNull { app.bundleId.isNotEmpty() && it.bundleId == app.bundleId }

    /** Whether an extension app is here and may be used. */
    enum class Status {
        /** Installed, and signed with the pinned certificate. */
        VERIFIED,

        /** Installed, but not signed as pinned (a copy built by someone else): never bound. */
        UNVERIFIED,
        NOT_INSTALLED,
    }

    /** "AA:BB:…" as bytes; null when it is not colon-separated hex ([AppSigning.hexToBytes]). */
    fun hexToBytes(hex: String): ByteArray? = AppSigning.hexToBytes(hex)

    /** The SHA-256 of [cert] (a certificate's encoded bytes), as "AA:BB:…". */
    fun fingerprint(cert: ByteArray): String = AppSigning.fingerprint(cert)

    /** Whether one of [certs] (certificates' encoded bytes) is the one [pin] names. */
    fun matches(pin: String, certs: List<ByteArray>): Boolean = AppSigning.matches(pin, certs)

    /** What a tap on an app's card does here. */
    sealed class Route {
        /** Open it in the extension app [app]. */
        data class Engage(val app: MadridApp) : Route()

        /** Ask the user to install [app], which plays it. */
        data class Install(val app: MadridApp) : Route()

        /** [app] is installed but is not the real one: it is not opened. */
        data class Unverified(val app: MadridApp) : Route()

        /** No extension app plays it: the card's other ways apply ([com.lightdecoder.imessage.direct.IMessageApps]). */
        object None : Route()
    }

    /** What a tap on [app]'s card does, with [statusOf] saying whether its extension app is here. */
    fun route(app: IMessageExtra.App, statusOf: (MadridApp) -> Status): Route {
        val ext = forApp(app) ?: return Route.None
        return when (statusOf(ext)) {
            Status.VERIFIED -> Route.Engage(ext)
            Status.UNVERIFIED -> Route.Unverified(ext)
            Status.NOT_INSTALLED -> Route.Install(ext)
        }
    }

    /** Checked certificates, by package: (its last update time, verified). */
    private val checked = HashMap<String, Pair<Long, Boolean>>()

    /**
     * Whether [app] is installed and signed with its pinned certificate. The check is kept until
     * the app is updated.
     */
    fun status(pm: PackageManager, app: MadridApp): Status {
        val updated = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.pkg, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            return Status.NOT_INSTALLED
        }
        synchronized(checked) { checked[app.pkg]?.takeIf { it.first == updated }?.let { return if (it.second) Status.VERIFIED else Status.UNVERIFIED } }
        val ok = try {
            verifier(pm, app)
        } catch (e: PackageManager.NameNotFoundException) {
            return Status.NOT_INSTALLED
        } catch (e: RuntimeException) {
            false
        }
        synchronized(checked) { checked[app.pkg] = updated to ok }
        return if (ok) Status.VERIFIED else Status.UNVERIFIED
    }

    /** How a certificate is checked: [verify]; tests replace it (Robolectric fakes no signing certificate). */
    @Volatile
    internal var verifier: (PackageManager, MadridApp) -> Boolean = ::verify

    private fun verify(pm: PackageManager, app: MadridApp): Boolean = AppSigning.isSignedWith(pm, app.pkg, app.certSha256)

    /** The extension apps installed and verified, for the composer's "Games". */
    fun installed(pm: PackageManager): List<MadridApp> = REGISTRY.filter { status(pm, it) == Status.VERIFIED }

    /** Tests: forget the checks made. */
    internal fun resetForTesting() {
        synchronized(checked) { checked.clear() }
        verifier = ::verify
    }
}
