package com.lightdecoder.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageErrors
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.relay.RelayException
import com.lightdecoder.wa.WaErrors
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Through the iMessage library no text sends the user to the OpenBubbles app; without it, the words are as they were. */
@RunWith(AndroidJUnit4::class)
class DirectWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
    }

    @Test
    fun throughTheLibraryNoTextMentionsOpenBubbles() {
        val im = ChatService.IMESSAGE
        val direct = listOf(
            ServiceUi.emptyInbox(im, true), ServiceUi.failedOnPhone(im, true), ServiceUi.resendBody(im, true),
            ServiceUi.mediaNeedsConnection(im, true), ServiceUi.contactsNoMatch(im, true), ServiceUi.deleteConversationBody(im, true),
        ) + listOf(
            RelayException.NOT_PAIRED, IMessageErrors.NO_REPLY_BUTTON, IMessageErrors.HAND_OFF, WaErrors.UNSUPPORTED, "internal",
        ).map { ChatErrors.messageRes(im, it, direct = true) } + listOf(
            R.string.imessage_channel_messages_description_direct, R.string.blue_bubbles_on_body_direct,
            R.string.imessage_delete_message_question_direct, R.string.imessage_light_group_direct, R.string.imessage_light_unreachable,
            R.string.imessage_light_direct, R.string.imessage_link_none_direct, R.string.imessage_check_new_chat_direct,
            R.string.imessage_check_step_sent_direct, R.string.imessage_check_step_confirmed_direct, R.string.imessage_check_not_confirmed_direct,
            R.string.imessage_read_receipts, R.string.imessage_read_receipts_help, R.string.imessage_status_retrying,
            R.string.imessage_service_retrying, R.string.imessage_service_on_title, R.string.imessage_service_on_body,
            R.string.imessage_service_quiet_channel, R.string.imessage_service_quiet_channel_description,
            R.string.imessage_hide_status, R.string.imessage_hide_status_help, R.string.imessage_receipt_delivered, R.string.imessage_receipt_read,
            R.string.imessage_media_waiting_direct, R.string.imessage_media_not_here_direct, R.string.imessage_direct_no_own_address,
            R.string.imessage_direct_attachment_too_big, R.string.imessage_direct_attachment_unreadable,
            R.string.imessage_direct_attachment_no_file, R.string.imessage_direct_attachment_not_saved,
            R.string.imessage_event_app_updated, R.string.imessage_event_app_updated_unnamed, R.string.imessage_sent_with,
            R.string.imessage_effect_pick_title, R.string.imessage_effect_tab_bubble, R.string.imessage_effect_tab_screen,
            R.string.imessage_effect_pick_hint, R.string.imessage_effect_not_imessage, R.string.imessage_effect_replay,
            R.string.imessage_effect_replay_action, R.string.imessage_app_games_loading,
            R.string.imessage_app_try_again, R.string.imessage_invisible_ink_hidden, R.string.imessage_app_only_on_apple,
            R.string.imessage_app_unnamed, R.string.imessage_link_preview, R.string.imessage_new_group, R.string.imessage_to_hint,
            R.string.imessage_group_body, R.string.imessage_group_nobody, R.string.imessage_not_on_imessage,
            R.string.imessage_attach_photos, R.string.imessage_attach_games, R.string.imessage_app_install,
            R.string.imessage_app_install_button, R.string.imessage_app_unverified, R.string.imessage_app_no_answer,
            R.string.imessage_app_games_not_loaded,
            R.string.imessage_app_move_failed, R.string.imessage_app_card_failed, R.string.imessage_app_card_invalid,
            R.string.imessage_app_send_title, R.string.imessage_app_send, R.string.imessage_app_no_store,
            R.string.imessage_app_still_sending, R.string.imessage_app_move_too_soon, R.string.imessage_app_move_invalid,
            R.string.imessage_games, R.string.imessage_games_help, R.string.imessage_games_not_installed,
            R.string.imessage_games_get, R.string.imessage_games_ready,
            R.string.imessage_direct_sticker_stays, R.string.imessage_not_in_group, R.string.imessage_light_left,
            R.string.imessage_text_effect_big, R.string.imessage_text_effect_small, R.string.imessage_text_effect_shake,
            R.string.imessage_text_effect_nod, R.string.imessage_text_effect_explode, R.string.imessage_text_effect_ripple,
            R.string.imessage_text_effect_bloom, R.string.imessage_text_effect_jitter, R.string.imessage_text_effects,
            R.string.imessage_effect_tab_text, R.string.imessage_format_bold, R.string.imessage_format_italic,
            R.string.imessage_format_underline, R.string.imessage_format_strikethrough, R.string.imessage_text_effect_described,
            R.string.imessage_text_effect_replay_action,
            R.string.imessage_event_background_changed, R.string.imessage_event_background_removed, R.string.imessage_background,
            R.string.imessage_background_choose, R.string.imessage_background_remove, R.string.imessage_background_sending,
            R.string.imessage_background_set, R.string.imessage_background_removed, R.string.imessage_background_failed,
            R.string.imessage_background_unreadable, R.string.imessage_background_too_big,
            R.string.imessage_background_section_colors, R.string.imessage_background_section_dynamic,
            R.string.imessage_background_section_themes, R.string.imessage_background_section_photos,
            R.string.imessage_background_photo, R.string.imessage_background_none, R.string.imessage_background_preview_set,
            R.string.imessage_background_preview_cancel, R.string.imessage_background_preview_hint,
            *com.lightdecoder.imessage.direct.BackgroundPalettes.ALL.map { it.name }.toTypedArray(),
            *com.lightdecoder.imessage.direct.BackgroundDynamics.ALL.map { it.name }.toTypedArray(),
            *com.lightdecoder.imessage.direct.BackgroundThemes.ALL.map { it.name }.toTypedArray(),
            R.string.imessage_direct_background_not_confirmed, R.string.imessage_direct_background_refused,
            R.string.imessage_direct_background_upload, R.string.imessage_send_later, R.string.imessage_send_later_pick_title,
            R.string.imessage_send_later_label, R.string.imessage_send_later_tomorrow, R.string.imessage_send_later_too_soon,
            R.string.imessage_send_later_too_far, R.string.imessage_send_later_scheduled, R.string.imessage_send_later_cancel,
            R.string.imessage_send_later_send_now, R.string.imessage_send_later_cancelled, R.string.imessage_send_later_cancel_failed,
            R.string.imessage_send_later_not_cancellable, R.string.imessage_direct_unschedule_not_confirmed,
            R.string.imessage_direct_unschedule_refused,
            R.string.imessage_phone_style, R.string.imessage_phone_style_help, R.string.imessage_phone_hint_text,
            R.string.imessage_phone_header, R.string.imessage_phone_add, R.string.imessage_attach_stickers,
            R.string.imessage_thread_quote_description, R.string.imessage_thread_open, R.string.imessage_thread_title,
            R.string.imessage_thread_reply_hint, R.string.imessage_thread_close, R.string.imessage_thread_send,
            R.string.imessage_reply_threads_in_chat, R.string.imessage_reply_threads_in_chat_help,
        )
        for (res in direct) {
            val text = context.getString(res)
            assertFalse(text, text.contains("OpenBubbles"))
        }
        for (count in listOf(1, 2)) {
            val text = context.resources.getQuantityString(R.plurals.imessage_group_not_on_imessage, count, "Bob")
            assertFalse(text, text.contains("OpenBubbles"))
            for (plural in listOf(R.plurals.imessage_thread_replies, R.plurals.imessage_thread_open_replies)) {
                val words = context.resources.getQuantityString(plural, count, count)
                assertFalse(words, words.contains("OpenBubbles"))
            }
        }
        assertEquals("1 Reply", context.resources.getQuantityString(R.plurals.imessage_thread_replies, 1, 1))
        assertEquals("3 Replies", context.resources.getQuantityString(R.plurals.imessage_thread_replies, 3, 3))
        assertEquals("Reply to Alice: Lunch?", context.getString(R.string.imessage_thread_quote_description, "Alice", "Lunch?"))
        assertEquals("Sent with Slam", context.getString(R.string.imessage_sent_with, context.getString(R.string.imessage_effect_slam)))
        assertEquals(
            "GamePigeon can only be opened on an iPhone, iPad or Mac.",
            context.getString(R.string.imessage_app_only_on_apple, "GamePigeon"),
        )
        assertEquals("Bob updated Polls.", context.getString(R.string.imessage_event_app_updated, "Bob", "Polls"))
        assertEquals(
            "Install OpenPigeon to play GamePigeon games with iPhone users.",
            context.getString(R.string.imessage_app_install, "OpenPigeon", "GamePigeon"),
        )
        assertEquals("iPhone-style chats", context.getString(R.string.imessage_phone_style))
        assertEquals("Text Message", context.getString(R.string.imessage_phone_hint_text))
        assertEquals("iMessage", context.getString(R.string.imessage_compose_hint))
        assertEquals("Delivered", context.getString(R.string.imessage_receipt_delivered))
        assertEquals("Read 9:41", context.getString(R.string.imessage_receipt_read_at, "9:41"))
        assertEquals(
            "When this is on, people are told when you have read their messages (one-to-one chats).",
            context.getString(R.string.imessage_read_receipts_help),
        )
    }

    @Test
    fun withoutTheLibraryTheWordsAreAsTheyWere() {
        val im = ChatService.IMESSAGE
        assertEquals(R.string.imessage_failed_detail, ServiceUi.failedOnPhone(im, false))
        assertEquals(R.string.imessage_resend_body, ServiceUi.resendBody(im, false))
        assertEquals(R.string.imessage_error_hand_off, ChatErrors.messageRes(im, IMessageErrors.HAND_OFF, direct = false))
        assertEquals(R.string.imessage_error_no_reply, ChatErrors.messageRes(im, IMessageErrors.NO_REPLY_BUTTON, direct = false))
        // Through the library nothing is handed to another app.
        assertEquals(R.string.imessage_error_unsupported, ChatErrors.messageRes(im, IMessageErrors.HAND_OFF, direct = true))
        // The other services are not iMessage's to change.
        assertEquals(R.string.chat_failed_phone_detail, ServiceUi.failedOnPhone(ChatService.MESSAGES, true))
        assertEquals(R.string.chat_resend_body, ServiceUi.resendBody(ChatService.MESSAGES, true))
        assertFalse(ServiceUi.direct(context, ChatService.MESSAGES))
        // And the message for a code follows the mode.
        assertEquals(context.getString(R.string.imessage_error_internal), ChatErrors.message(context, im, "internal"))
    }

    @Test
    fun iMessagesSettingsAreTheIMessageScreenThroughTheLibrary() {
        assertEquals(ConversationsActivity::class.java.name, ServiceUi.status(context, ChatService.IMESSAGE).component?.className)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        assertEquals(
            com.lightdecoder.ui.imessage.DirectSetupActivity::class.java.name,
            ServiceUi.status(context, ChatService.IMESSAGE).component?.className,
        )
        assertEquals(context.getString(R.string.imessage_error_internal_direct), ChatErrors.message(context, ChatService.IMESSAGE, "internal"))
        assertEquals(context.getString(R.string.imessage_error_unsupported), ChatErrors.message(context, ChatService.IMESSAGE, IMessageErrors.HAND_OFF))
    }

    @Test
    fun theTimeUnderAReadIMessageReadsAsOnAnIphone() {
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L
        val today = TimeFormat.receiptTime(context, now - 60_000, now, zone, java.util.Locale.US)
        assertEquals(TimeFormat.clock(context, now - 60_000, zone, java.util.Locale.US), today)
        assertEquals(context.getString(R.string.chat_day_yesterday), TimeFormat.receiptTime(context, now - 24 * 3_600_000L, now, zone, java.util.Locale.US))
        assertEquals(
            TimeFormat.listTime(context, now - 3 * 24 * 3_600_000L, now, zone, java.util.Locale.US),
            TimeFormat.receiptTime(context, now - 3 * 24 * 3_600_000L, now, zone, java.util.Locale.US),
        )
    }
}
