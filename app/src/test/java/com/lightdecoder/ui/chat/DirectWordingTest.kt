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
import org.junit.Assert.assertTrue
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
            // A chat's details, and a group's name, members and photo changed from here.
            R.string.chat_details_title, R.string.chat_details_open, R.string.chat_details_change_name_photo,
            R.string.chat_details_name_hint, R.string.chat_details_choose_photo, R.string.chat_details_remove_photo,
            R.string.chat_details_done, R.string.chat_details_add_member, R.string.chat_details_add,
            R.string.chat_details_remove_member, R.string.chat_details_remove_title, R.string.chat_details_remove,
            R.string.chat_details_hide_alerts, R.string.chat_details_read_receipts, R.string.chat_details_leave,
            R.string.chat_details_leave_title, R.string.chat_details_leave_body, R.string.chat_details_leave_confirm,
            R.string.chat_details_failed, R.string.chat_details_photo, R.string.imessage_event_name_removed,
            R.string.imessage_direct_group_not_confirmed, R.string.imessage_direct_group_refused,
            R.string.imessage_group_not_a_group, R.string.imessage_group_photo_unreadable, R.string.imessage_group_photo_too_big,
            R.string.imessage_group_photo_upload, R.string.imessage_group_already_in, R.string.imessage_group_not_member,
            R.string.imessage_group_too_small, R.string.imessage_event_named, R.string.imessage_event_added,
            R.string.imessage_event_removed, R.string.imessage_event_left, R.string.imessage_event_photo_changed,
            R.string.imessage_event_photo_removed,
            // Message content: mentions, link previews, stickers, polls.
            R.string.imessage_mention_suggestion, R.string.imessage_mention_suggestions, R.string.imessage_notify_mentions,
            R.string.imessage_notify_mentions_help, R.string.imessage_link_previews, R.string.imessage_link_previews_help,
            R.string.imessage_link_preview_picture, R.string.imessage_sticker_by, R.string.imessage_sticker_tapback,
            R.string.imessage_attach_poll, R.string.imessage_poll_new_title, R.string.imessage_poll_question_hint,
            R.string.imessage_poll_choice_hint, R.string.imessage_poll_add_choice, R.string.imessage_poll_add_choice_hint,
            R.string.imessage_poll_send, R.string.imessage_poll_needs_two, R.string.imessage_poll_requires,
            R.string.imessage_poll_summary, R.string.imessage_poll_summary_untitled, R.string.imessage_poll_unreadable,
            R.string.imessage_poll_choice_not_added, R.string.imessage_poll_too_many, R.string.imessage_poll_vote_failed,
            R.string.imessage_poll_choice_failed, R.string.imessage_poll_you, R.string.imessage_poll_option_description,
            R.string.imessage_poll_option_mine_description, R.string.imessage_poll_vote_action, R.string.imessage_poll_unvote_action,
            // The transcript, the list and sync: edit history, Not Delivered, Mark as Unread, Pin, Report Junk.
            R.string.imessage_edit_history_show, R.string.imessage_edit_history_hide, R.string.imessage_edit_history_version,
            R.string.imessage_not_delivered, R.string.imessage_not_delivered_mark, R.string.imessage_not_delivered_title,
            R.string.imessage_not_delivered_maybe_sent, R.string.imessage_not_delivered_try_again, R.string.imessage_not_delivered_as_text,
            R.string.imessage_not_delivered_delete, R.string.imessage_not_delivered_again_failed, R.string.imessage_not_delivered_as_text_failed,
            R.string.chat_action_mark_unread, R.string.chat_action_mark_read, R.string.chat_action_pin, R.string.chat_action_unpin,
            R.string.chat_marked_unread, R.string.chat_marked_read, R.string.chat_pinned_done, R.string.chat_unpinned_done,
            R.string.imessage_junk_banner, R.string.imessage_junk_report, R.string.imessage_junk_confirm_title,
            R.string.imessage_junk_confirm_body, R.string.imessage_junk_reported, R.string.imessage_junk_failed,
            R.string.imessage_junk_nothing, R.string.imessage_junk_not_offered,
            // FaceTime calls: the call screen, the notices, a call's row, the settings.
            R.string.facetime_title, R.string.facetime_video, R.string.facetime_audio, R.string.facetime_channel_incoming,
            R.string.facetime_channel_incoming_description, R.string.facetime_channel_ongoing, R.string.facetime_channel_ongoing_description,
            R.string.facetime_channel_missed, R.string.facetime_channel_missed_description, R.string.facetime_incoming_video,
            R.string.facetime_incoming_audio, R.string.facetime_accept, R.string.facetime_decline, R.string.facetime_audio_only,
            R.string.facetime_missed_video, R.string.facetime_missed_audio, R.string.facetime_calling, R.string.facetime_connecting,
            R.string.facetime_no_answer, R.string.facetime_declined, R.string.facetime_failed, R.string.facetime_end,
            R.string.facetime_call_again, R.string.facetime_close, R.string.facetime_mute, R.string.facetime_unmute, R.string.facetime_camera,
            R.string.facetime_camera_on, R.string.facetime_camera_off, R.string.facetime_flip, R.string.facetime_flip_description,
            R.string.facetime_speaker, R.string.facetime_audio_route, R.string.facetime_route_earpiece, R.string.facetime_route_speaker,
            R.string.facetime_route_wired, R.string.facetime_route_bluetooth, R.string.facetime_message, R.string.facetime_minimize,
            R.string.facetime_own_camera, R.string.facetime_remote_video, R.string.facetime_mic_needed, R.string.facetime_open_settings,
            R.string.facetime_camera_denied, R.string.facetime_camera_unavailable, R.string.facetime_refused_off, R.string.facetime_refused_in_call,
            R.string.facetime_refused_phone_busy, R.string.facetime_refused_not_callable, R.string.facetime_refused_not_connected,
            R.string.facetime_refused_gone, R.string.facetime_register_in_call, R.string.facetime_button, R.string.facetime_details_audio,
            R.string.facetime_details_video, R.string.facetime_details_audio_description, R.string.facetime_details_video_description,
            R.string.facetime_not_available, R.string.facetime_row_answered, R.string.facetime_row_missed, R.string.facetime_row_cancelled,
            R.string.facetime_row_declined, R.string.facetime_row_unanswered, R.string.facetime_row_elsewhere, R.string.facetime_row_failed,
            R.string.facetime_duration_sec, R.string.facetime_duration_min, R.string.facetime_duration_hr,
            R.string.facetime_row_outgoing_description, R.string.facetime_row_incoming_description, R.string.facetime_setting,
            R.string.facetime_setting_help, R.string.facetime_silence_unknown, R.string.facetime_silence_unknown_help,
            R.string.facetime_full_screen_help, R.string.facetime_notifications_off,
            // The optional Apple Account: its sign-in screen and its section of the iMessage screen.
            R.string.apple_account_row, R.string.apple_account_row_help, R.string.apple_account_sign_in_row, R.string.apple_account_title,
            R.string.apple_account_bullet, R.string.apple_account_intro_title, R.string.apple_account_intro_optional,
            R.string.apple_account_intro_unlocks, R.string.apple_account_intro_where, R.string.apple_account_intro_relay,
            R.string.apple_account_intro_device, R.string.apple_account_intro_device_unknown, R.string.apple_account_intro_adp,
            R.string.apple_account_intro_key, R.string.apple_account_intro_limits, R.string.apple_account_intro_sign_out,
            R.string.apple_account_understand, R.string.apple_account_continue, R.string.apple_account_not_now,
            R.string.apple_account_id_hint, R.string.apple_account_password_hint, R.string.apple_account_sign_in,
            R.string.apple_account_continue_as, R.string.apple_account_again_body, R.string.apple_account_forgot,
            R.string.apple_account_device_code_title, R.string.apple_account_device_code_body, R.string.apple_account_code,
            R.string.apple_account_no_code, R.string.apple_account_text_me, R.string.apple_account_send_again,
            R.string.apple_account_try_again, R.string.apple_account_timed_out, R.string.apple_account_not_sent,
            R.string.apple_account_text_title, R.string.apple_account_text_pick, R.string.apple_account_text_send,
            R.string.apple_account_text_body, R.string.apple_account_text_body_unnamed, R.string.apple_account_resend,
            R.string.apple_account_resend_in, R.string.apple_account_verify, R.string.apple_account_terms, R.string.apple_account_ok,
            R.string.apple_account_done, R.string.apple_account_working, R.string.apple_account_missing, R.string.apple_account_too_many,
            R.string.apple_account_code_wait, R.string.apple_account_code_wait_long, R.string.apple_account_wrong_password,
            R.string.apple_account_enter_password, R.string.apple_account_expired, R.string.apple_account_incomplete,
            R.string.apple_account_no_numbers, R.string.apple_account_code_format, R.string.apple_account_failed,
            R.string.apple_account_busy, R.string.apple_account_not_registered, R.string.apple_account_already,
            R.string.apple_account_not_started, R.string.apple_account_signed_in_as, R.string.apple_account_signed_in,
            R.string.apple_account_icloud_on, R.string.apple_account_icloud_off, R.string.apple_account_sign_out,
            R.string.apple_account_sign_out_title, R.string.apple_account_sign_out_body, R.string.apple_account_signed_out,
            R.string.apple_account_notice_interrupted, R.string.apple_account_notice_signed_out,
            R.string.apple_account_notice_sign_in_again, R.string.apple_account_restart_pending, R.string.apple_account_restart_later,
            // FaceTime II: group calls, the people sheet, a shared screen, the "return to call" bar, the FaceTime list (SPEC2 B2).
            R.string.facetime_group, R.string.facetime_group_title, R.string.facetime_ignore, R.string.facetime_not_verified,
            R.string.facetime_not_verified_short, R.string.facetime_guest, R.string.facetime_someone, R.string.facetime_people_open,
            R.string.facetime_in_call, R.string.facetime_invited, R.string.facetime_ring, R.string.facetime_ring_description,
            R.string.facetime_rung, R.string.facetime_ring_failed, R.string.facetime_nobody_invited, R.string.facetime_add_hint, R.string.facetime_names_and,
            R.string.facetime_you, R.string.facetime_shared_screen, R.string.facetime_tile, R.string.facetime_return,
            R.string.facetime_return_time, R.string.facetime_return_description, R.string.facetime_calls, R.string.facetime_calls_missed,
            R.string.facetime_calls_new, R.string.facetime_calls_empty, R.string.facetime_calls_today, R.string.facetime_calls_yesterday,
            R.string.facetime_calls_open_chat, R.string.facetime_calls_delete, R.string.facetime_calls_deleted,
            R.string.facetime_calls_row_description, R.string.facetime_calls_new_hint, R.string.facetime_calls_start,
            // Blocking, Telecom and the cellular watcher (SPEC2 B3).
            R.string.block_caller, R.string.unblock_caller, R.string.block_caller_title, R.string.block_caller_body,
            R.string.block_caller_body_sms, R.string.block_contact, R.string.unblock, R.string.blocked_done, R.string.unblocked_done,
            R.string.blocked_row, R.string.blocked_contacts, R.string.blocked_contacts_help, R.string.blocked_contacts_empty,
            R.string.blocked_contacts_add, R.string.blocked_contacts_add_hint, R.string.blocked_contacts_sms_note,
            R.string.blocked_contacts_invalid, R.string.blocked_contacts_own, R.string.blocked_contacts_full,
            R.string.blocked_contacts_already, R.string.blocked_contacts_unblock_description, R.string.facetime_telecom,
            R.string.facetime_telecom_help, R.string.facetime_telecom_account, R.string.facetime_phone_call_incoming,
            R.string.facetime_ended_for_phone_call,
            // B4: media in the transcript.
            R.string.imessage_batch_too_many, R.string.transfer_stop_download, R.string.transfer_downloading, R.string.transfer_stopped, R.string.transfer_send_not_stopped, R.string.imessage_not_delivered_files_missing, R.string.imessage_not_delivered_as_text_partly,
            R.string.transfer_sending, R.string.media_stack_title, R.string.media_stack_description, R.string.media_stack_show_all,
            R.string.live_photo_badge, R.string.live_photo_hint, R.string.live_photo_unavailable, R.string.location_current,
            R.string.location_untitled, R.string.location_open_in_maps, R.string.location_coordinates, R.string.location_no_app,
            R.string.location_attach, R.string.location_send_title, R.string.location_send, R.string.location_finding,
            R.string.location_unavailable, R.string.location_unreadable, R.string.location_permission_denied, R.string.location_show_addresses,
            R.string.location_show_addresses_help, R.string.location_card_description,
        )
        for (res in direct) {
            val text = context.getString(res)
            assertFalse(text, text.contains("OpenBubbles"))
        }
        // iPhone's words for blocking.
        assertEquals("Block this Caller", context.getString(R.string.block_caller))
        assertEquals("Unblock this Caller", context.getString(R.string.unblock_caller))
        assertEquals("Blocked Contacts", context.getString(R.string.blocked_contacts))
        assertEquals(
            "You will not receive phone calls, messages, or FaceTime from people on the block list.",
            context.getString(R.string.block_caller_body),
        )
        assertTrue(context.getString(R.string.block_caller_body_sms).contains("Also block this number in Google Messages to stop receiving its texts."))
        // Nothing promises that a blocked sender sees no "Delivered" (the user's other devices still acknowledge).
        assertFalse(context.getString(R.string.block_caller_body).contains("Delivered"))
        assertEquals("Leave this Conversation", context.getString(R.string.chat_details_leave))
        assertEquals("Change Name and Photo", context.getString(R.string.chat_details_change_name_photo))
        assertEquals("Hide Alerts", context.getString(R.string.chat_details_hide_alerts))
        assertEquals("Send Read Receipts", context.getString(R.string.chat_details_read_receipts))
        assertEquals("You removed the name from the conversation.", context.getString(R.string.imessage_event_name_removed, "You"))
        assertEquals("Notify me when mentioned", context.getString(R.string.imessage_notify_mentions))
        assertEquals("Link previews", context.getString(R.string.imessage_link_previews))
        assertEquals("Poll: Dinner?", context.getString(R.string.imessage_poll_summary, "Dinner?"))
        assertEquals("Add Choice", context.getString(R.string.imessage_poll_add_choice))
        assertEquals("Mention Alice", context.getString(R.string.imessage_mention_suggestion, "Alice"))
        for (count in listOf(1, 2)) {
            val votes = context.resources.getQuantityString(R.plurals.imessage_poll_votes, count, count)
            assertFalse(votes, votes.contains("OpenBubbles"))
            val added = context.resources.getQuantityString(R.plurals.imessage_group_add_not_on_imessage, count, "Bob")
            assertFalse(added, added.contains("OpenBubbles"))
            val text = context.resources.getQuantityString(R.plurals.imessage_group_not_on_imessage, count, "Bob")
            assertFalse(text, text.contains("OpenBubbles"))
            for (plural in listOf(R.plurals.imessage_thread_replies, R.plurals.imessage_thread_open_replies)) {
                val words = context.resources.getQuantityString(plural, count, count)
                assertFalse(words, words.contains("OpenBubbles"))
            }
            val typing = context.resources.getQuantityString(R.plurals.imessage_typing_first_and_others, count, "Alice", count)
            assertFalse(typing, typing.contains("OpenBubbles"))
            for (plural in listOf(R.plurals.facetime_group_others, R.plurals.facetime_people)) {
                val words = context.resources.getQuantityString(plural, count, count)
                assertFalse(words, words.contains("OpenBubbles"))
            }
        }
        // Group FaceTime and the bar, as an iPhone words them.
        assertEquals("Alice · Group FaceTime", context.getString(R.string.facetime_group_title, "Alice"))
        assertEquals("Touch to return to call", context.getString(R.string.facetime_return))
        assertEquals("Touch to return to call · 01:05", context.getString(R.string.facetime_return_time, "01:05"))
        assertEquals("New FaceTime", context.getString(R.string.facetime_calls_new))
        assertEquals("Everyone you add can see who else is in the call.", context.getString(R.string.facetime_calls_new_hint))
        assertEquals("To add someone, start a new call with everyone.", context.getString(R.string.facetime_add_hint))
        assertEquals("Alice & Bob", context.getString(R.string.facetime_names_and, "Alice", "Bob"))
        assertEquals("and 2 others", context.resources.getQuantityString(R.plurals.facetime_group_others, 2, 2))
        // FaceTime, by its name; a call's row as it reads.
        assertEquals("FaceTime Video", context.getString(R.string.facetime_video))
        assertEquals("FaceTime Audio", context.getString(R.string.facetime_audio))
        val rows = com.lightdecoder.imessage.facetime.CallRows.ResourceTexts(context)
        assertEquals("FaceTime Video · 5 min", com.lightdecoder.imessage.facetime.CallRows.text(rows, true, com.lightdecoder.imessage.facetime.Outcome.ANSWERED, 5 * 60_000))
        assertEquals("FaceTime Audio · 12 sec", com.lightdecoder.imessage.facetime.CallRows.text(rows, false, com.lightdecoder.imessage.facetime.Outcome.ANSWERED, 12_000))
        assertEquals("FaceTime Video · 1 hr 5 min", com.lightdecoder.imessage.facetime.CallRows.text(rows, true, com.lightdecoder.imessage.facetime.Outcome.ANSWERED, 65 * 60_000))
        assertEquals("Missed FaceTime Video", com.lightdecoder.imessage.facetime.CallRows.text(rows, true, com.lightdecoder.imessage.facetime.Outcome.MISSED, 0))
        assertEquals("FaceTime Audio · Cancelled", com.lightdecoder.imessage.facetime.CallRows.text(rows, false, com.lightdecoder.imessage.facetime.Outcome.CANCELLED, 0))
        assertEquals("FaceTime Video · Declined", com.lightdecoder.imessage.facetime.CallRows.text(rows, true, com.lightdecoder.imessage.facetime.Outcome.DECLINED, 0))
        assertEquals("FaceTime Video · No answer", com.lightdecoder.imessage.facetime.CallRows.text(rows, true, com.lightdecoder.imessage.facetime.Outcome.UNANSWERED, 0))
        for (outcome in com.lightdecoder.imessage.facetime.Outcome.entries) {
            for (video in listOf(true, false)) {
                val row = com.lightdecoder.imessage.facetime.CallRows.text(rows, video, outcome, 90_000)
                assertFalse(row, row.contains("OpenBubbles"))
            }
        }
        assertEquals("Silence unknown FaceTime callers", context.getString(R.string.facetime_silence_unknown))
        // The Apple Account, as an iPhone words it.
        assertEquals("Apple Account (optional)", context.getString(R.string.apple_account_row))
        assertEquals("Sign in with your Apple Account", context.getString(R.string.apple_account_intro_title))
        assertEquals("Sign Out", context.getString(R.string.apple_account_sign_out))
        assertEquals("Signed in as j•••@icloud.com", context.getString(R.string.apple_account_signed_in_as, "j•••@icloud.com"))
        assertEquals("Finish your FaceTime call first.", context.getString(R.string.apple_account_busy))
        assertEquals("Signed in. iMessage restarts for a moment to turn on iCloud features.", context.getString(R.string.apple_account_done))
        // As an iPhone words them.
        assertEquals("Not Delivered", context.getString(R.string.imessage_not_delivered))
        assertEquals("Try Again", context.getString(R.string.imessage_not_delivered_try_again))
        assertEquals("Send as Text Message", context.getString(R.string.imessage_not_delivered_as_text))
        assertEquals("Mark as Unread", context.getString(R.string.chat_action_mark_unread))
        assertEquals("Report Junk", context.getString(R.string.imessage_junk_report))
        assertEquals("This sender is not in your contacts.", context.getString(R.string.imessage_junk_banner))
        assertEquals("Alice and 2 others are typing", context.resources.getQuantityString(R.plurals.imessage_typing_first_and_others, 2, "Alice", 2))
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
