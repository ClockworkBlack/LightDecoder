package com.lightdecoder.imessage.direct

import android.content.Context
import com.lightdecoder.R

/** [DirectArchive.Texts] from the app's string resources (strings_imessage.xml), in either process. */
class DirectTexts(context: Context) : DirectArchive.Texts {
    private val res = context.applicationContext.resources

    override val you: String get() = res.getString(R.string.imessage_event_you)
    override val youObject: String get() = res.getString(R.string.imessage_event_you_object)

    override fun named(who: String, name: String): String = res.getString(R.string.imessage_event_named, who, name)
    override fun added(who: String, whom: String): String = res.getString(R.string.imessage_event_added, who, whom)
    override fun removed(who: String, whom: String): String = res.getString(R.string.imessage_event_removed, who, whom)
    override fun left(who: String): String = res.getString(R.string.imessage_event_left, who)
    override fun changedPhoto(who: String): String = res.getString(R.string.imessage_event_photo_changed, who)
    override fun removedPhoto(who: String): String = res.getString(R.string.imessage_event_photo_removed, who)
    override fun updated(who: String, app: String): String =
        if (app.isBlank()) res.getString(R.string.imessage_event_app_updated_unnamed, who) else res.getString(R.string.imessage_event_app_updated, who, app)
}
