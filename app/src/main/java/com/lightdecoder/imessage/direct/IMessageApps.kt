package com.lightdecoder.imessage.direct

import android.content.Context
import com.lightdecoder.archive.MessageRow

/**
 * What a tap on an iMessage app's card (a game, a poll) does. LightDecoder has no app of its own
 * to open it with: an installed extension app that plays it here ([host]) gets it first; else an
 * app that keeps its state in a web address opens in the browser, as a Mac opens it; else it can
 * only be opened on an Apple device.
 *
 * Pure apart from [host]. Tested in IMessageAppsTest.
 */
object IMessageApps {

    /**
     * An installed extension app that can open iMessage app cards here. [engage] opens [app] (the
     * card of [row]) in it and returns true, or returns false when it is not one it opens.
     */
    fun interface Host {
        fun engage(context: Context, row: MessageRow, app: IMessageExtra.App): Boolean
    }

    /** The extension host ([com.lightdecoder.imessage.madrid.MadridHost]), set at app start. */
    @Volatile
    var host: Host? = null

    /** What a tap on a card does. */
    sealed class Tap {
        /** An installed extension app took it. */
        object Engaged : Tap()

        /** Opened in the browser: [url]. */
        data class Open(val url: String) : Tap()

        /** Nothing here can open it: it opens on an iPhone, iPad or Mac. [name] is the app's, empty when unknown. */
        data class OnlyOnApple(val name: String) : Tap()
    }

    /** What a tap on [app]'s card does; [engage] asks the extension host to take it (true when it did). */
    fun tap(app: IMessageExtra.App, engage: (IMessageExtra.App) -> Boolean): Tap = when {
        engage(app) -> Tap.Engaged
        app.opensInBrowser -> Tap.Open(app.url)
        else -> Tap.OnlyOnApple(app.shownName)
    }
}
