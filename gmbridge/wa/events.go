package wa

import (
	"context"
	"strconv"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// handlerFor returns whatsmeow's event handler for client generation gen.
// It only enqueues: whatsmeow calls it synchronously from its receive loop,
// which must not block on SQLite or the JVM. All interpretation happens on
// the dispatcher goroutine, which drops events of a replaced client.
func (b *WABridge) handlerFor(gen uint64, cli *whatsmeow.Client, local *localStore) func(any) {
	return func(evt any) {
		defer func() { _ = recover() }()
		b.disp.push(func() {
			if !b.isGen(gen) {
				return
			}
			b.process(gen, cli, local, evt)
		})
	}
}

// process interprets one whatsmeow event (dispatcher goroutine only). Kotlin
// gets events with deliver, so they keep whatsmeow's order.
func (b *WABridge) process(gen uint64, cli *whatsmeow.Client, local *localStore, evt any) {
	id := resolver{cli: cli}
	switch e := evt.(type) {
	// --- connection and linking ---
	case *events.QR:
		b.onQR(gen, e)
	case *events.RotateADVSecret:
		b.onRotateADV(gen, e)
	case *events.PairSuccess:
		b.onPairSuccess(gen, e)
	case *events.PairError:
		b.failCurrentPairing(gen, codePairFailed, pairErrorDetail(e.Error))
	case *events.QRScannedWithoutMultidevice:
		b.failCurrentPairing(gen, codePairFailed, "WhatsApp on the phone is too old for linked devices")
	case *events.Connected:
		if b.wantedNow() {
			b.deliver(kindState, stateDTO{State: stateConnected})
		}
	case *events.Disconnected:
		b.onDisconnected(gen, cli)
	case *events.LoggedOut:
		detail := "this device was unlinked on the phone"
		if e.Reason != events.ConnectFailureLoggedOut && e.Reason != 0 {
			detail = "WhatsApp unlinked this device (reason " + strconv.Itoa(int(e.Reason)) + ")"
		}
		go b.handleLoggedOut(gen, detail)
	case *events.StreamReplaced:
		b.connectionEnded(gen, codeStreamReplaced, "another client took over this device's connection")
	case *events.TemporaryBan:
		b.connectionEnded(gen, codeTempBanned, "WhatsApp temporarily banned this account (ends in "+e.Expire.Round(time.Minute).String()+")")
	case *events.ClientOutdated:
		b.failCurrentPairing(gen, codeClientOutdated, "WhatsApp says this client is outdated")
		b.connectionEnded(gen, codeClientOutdated, "WhatsApp says this client is outdated; update the app")
	case *events.ConnectFailure:
		b.connectionEnded(gen, codeConnectFailure, "WhatsApp refused the connection (reason "+strconv.Itoa(int(e.Reason))+")")

	// --- messages ---
	case *events.Message:
		a := classifyMessage(e, id, false)
		if b.applyAction(local, id, a, true) {
			b.deliverChat(local, id, a.chat.String())
		}
		b.maybeFetchGroup(cli, local, a.chat)
	case *events.HistorySync:
		b.onHistorySync(cli, local, id, e)
	case *events.Receipt:
		b.onReceipt(local, id, e)
	case *events.DeleteForMe:
		chat := id.norm(e.ChatJID).String()
		old, _, exists, err := local.getMessage(chat, e.MessageID)
		if err == nil {
			_ = local.deleteMessage(chat, e.MessageID)
			var base *messageDTO
			if exists {
				base = &old
			}
			b.deliver(kindMessage, deletedMessage(base, e.MessageID, chat, millis(e.Timestamp)))
		}

	// --- presence ---
	case *events.ChatPresence:
		sender := id.norm(e.Sender)
		b.deliver(kindTyping, typingDTO{
			ConversationID: id.norm(e.Chat).String(),
			ParticipantID:  sender.String(),
			Number:         userNumber(sender),
			Typing:         e.State == types.ChatPresenceComposing,
		})
	case *events.Presence:
		b.deliver(kindPresence, presenceDTO{
			ParticipantID: id.norm(e.From).String(),
			Online:        !e.Unavailable,
			LastSeenMs:    millis(e.LastSeen),
		})

	// --- chat settings (app state) ---
	case *events.Archive:
		b.updateChatAndDeliver(local, id, id.norm(e.JID), func(r *chatRow) { r.Archived = e.Action.GetArchived() })
	case *events.Pin:
		b.updateChatAndDeliver(local, id, id.norm(e.JID), func(r *chatRow) { r.Pinned = e.Action.GetPinned() })
	case *events.Mute:
		b.updateChatAndDeliver(local, id, id.norm(e.JID), func(r *chatRow) {
			r.MuteEnd = muteEnd(e.Action.GetMuted(), e.Action.GetMuteEndTimestamp())
		})
	case *events.MarkChatAsRead:
		b.updateChatAndDeliver(local, id, id.norm(e.JID), func(r *chatRow) { r.Unread = !e.Action.GetRead() })
	case *events.DeleteChat:
		b.onDeleteChat(local, id, id.norm(e.JID))

	// --- names and groups ---
	case *events.Contact:
		b.deliverChatIfKnown(local, id, id.norm(e.JID))
	case *events.PushName:
		b.deliverChatIfKnown(local, id, id.norm(e.JID))
	case *events.GroupInfo:
		b.onGroupInfo(local, id, e)
	case *events.JoinedGroup:
		b.storeGroupInfo(local, id, &e.GroupInfo)
		b.deliverChat(local, id, id.norm(e.JID).String())
	}
}

func (b *WABridge) wantedNow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.wanted
}

// onDisconnected: whatsmeow only reports connections it did not close
// itself. A linked device reconnects by itself; a linking attempt is over.
func (b *WABridge) onDisconnected(gen uint64, cli *whatsmeow.Client) {
	b.mu.Lock()
	wanted, pairing := b.wanted && b.gen == gen, b.pair.active && b.gen == gen
	b.mu.Unlock()
	switch {
	case pairing && !isPaired(cli):
		b.failCurrentPairing(gen, codePairingTimeout, "WhatsApp closed the linking connection")
	case wanted:
		b.deliver(kindState, stateDTO{State: stateConnecting, Code: codeConnectionLost, Detail: "reconnecting"})
	}
}

// connectionEnded handles a verdict after which whatsmeow does not
// reconnect: the connection is dropped and "error" carries the reason.
func (b *WABridge) connectionEnded(gen uint64, code, detail string) {
	b.mu.Lock()
	// A linking attempt reports its own end (pair_error).
	wanted := b.wanted && b.gen == gen && !b.pair.active
	b.mu.Unlock()
	if !wanted {
		return
	}
	b.deliver(kindState, stateDTO{State: stateError, Code: code, Detail: detail})
	go func() {
		defer func() { _ = recover() }()
		b.lifecycle.Lock()
		defer b.lifecycle.Unlock()
		if b.isGen(gen) {
			b.dropConnectionLocked()
		}
	}()
}

// --- messages --------------------------------------------------------------------

// mergeNew combines a message that arrives again (history after live, a
// resend) with what is stored: reactions, the furthest status, the tmpId and
// a deletion are kept.
func mergeNew(old, m messageDTO) messageDTO {
	if old.Direction == directionDeleted {
		return old
	}
	if len(m.Reactions) == 0 {
		m.Reactions = old.Reactions
	}
	if m.TmpID == "" {
		m.TmpID = old.TmpID
	}
	if m.FromMe && statusRank(old.StatusCode) > statusRank(m.StatusCode) {
		m.Status, m.StatusCode = old.Status, old.StatusCode
	}
	if m.SenderName == "" {
		m.SenderName = old.SenderName
	}
	if !old.IsOld {
		m.IsOld = false
	}
	return m
}

// applyAction stores what a message does and delivers the resulting
// message event. It returns whether the chat's index row changed (the caller
// delivers the conversation). live is false for history.
func (b *WABridge) applyAction(local *localStore, id identity, a msgAction, live bool) bool {
	chat := a.chat.String()
	switch a.kind {
	case actNew:
		m, changed, err := local.updateMessage(chat, a.dto.ID, a.rawChat.String(), func(m *messageDTO, exists bool) bool {
			if exists {
				*m = mergeNew(*m, a.dto)
			} else {
				*m = a.dto
			}
			return true
		})
		if err != nil || !changed {
			return false
		}
		b.deliver(kindMessage, m)
		return b.noteMessage(local, id, m, a.rawChat, live && !m.FromMe && !m.IsOld, live && m.FromMe)
	case actRevoke:
		m, changed, err := local.updateMessage(chat, a.targetID, a.rawChat.String(), func(m *messageDTO, exists bool) bool {
			var base *messageDTO
			if exists {
				base = m
			}
			*m = deletedMessage(base, a.targetID, chat, a.tsMs)
			return true
		})
		if err != nil || !changed {
			return false
		}
		b.deliver(kindMessage, m)
		_, chatChanged, _ := local.updateChat(chat, func(r *chatRow, existed bool) bool {
			if !existed || r.LastID != a.targetID {
				return false
			}
			r.Snippet = ""
			return true
		})
		return chatChanged
	case actEdit:
		m, changed, err := local.updateMessage(chat, a.targetID, "", func(m *messageDTO, exists bool) bool {
			if !exists || m.Direction == directionDeleted {
				return false
			}
			applyEdit(m, a.content)
			return true
		})
		if err != nil || !changed {
			return false
		}
		b.deliver(kindMessage, m)
		_, chatChanged, _ := local.updateChat(chat, func(r *chatRow, existed bool) bool {
			if !existed || r.LastID != a.targetID {
				return false
			}
			r.Snippet = snippetOf(m)
			return true
		})
		return chatChanged
	case actReaction:
		m, changed, err := local.updateMessage(chat, a.targetID, "", func(m *messageDTO, exists bool) bool {
			if !exists {
				return false
			}
			applyReaction(m, a.reactor, a.emoji, a.fromMe)
			return true
		})
		if err == nil && changed {
			b.deliver(kindMessage, m)
		}
	}
	return false
}

// snippetOf is the preview text of a message: its text, else a document's
// file name. Kotlin labels other attachments itself.
func snippetOf(m messageDTO) string {
	if m.Text != "" {
		return m.Text
	}
	for _, p := range m.Parts {
		if p.FileName != "" {
			return p.FileName
		}
	}
	return ""
}

// noteMessage updates the chat index for a stored message: the newest
// message becomes the snippet, and a new incoming message marks the chat
// unread (markUnread) or our own reply from another device marks it read.
func (b *WABridge) noteMessage(local *localStore, id identity, m messageDTO, rawChat types.JID, markUnread, markRead bool) bool {
	_, changed, _ := local.updateChat(m.ConversationID, func(r *chatRow, existed bool) bool {
		changed := !existed
		if !existed {
			r.IsGroup = rawChat.Server == types.GroupServer
		}
		if m.TimestampMs >= r.LastTs && m.Direction != directionDeleted {
			r.LastTs, r.LastID = m.TimestampMs, m.ID
			r.Snippet, r.SnippetFromMe = snippetOf(m), m.FromMe
			r.SnippetSender = ""
			if r.IsGroup && !m.FromMe {
				r.SnippetSender = m.SenderName
			}
			changed = true
		}
		if markUnread && !r.Unread {
			r.Unread, changed = true, true
		}
		if markRead && r.Unread {
			r.Unread, changed = false, true
		}
		return changed
	})
	return changed
}

// --- chats -----------------------------------------------------------------------

// conversationOf builds the ConversationDTO of an index row.
func conversationOf(r chatRow, id identity, now time.Time) conversationDTO {
	jid, _ := types.ParseJID(r.JID)
	dto := conversationDTO{
		ID:                  r.JID,
		Name:                r.Name,
		IsGroup:             r.IsGroup,
		Type:                transportWhatsApp,
		Folder:              folderInbox,
		Unread:              r.Unread,
		Pinned:              r.Pinned,
		ReadOnly:            r.ReadOnly,
		Muted:               r.muted(now),
		LastMessageTs:       r.LastTs,
		LatestMessageID:     r.LastID,
		Snippet:             r.Snippet,
		SnippetFromMe:       r.SnippetFromMe,
		SnippetSender:       r.SnippetSender,
		Participants:        []participantDTO{},
		OtherParticipantIDs: []string{},
	}
	if r.Archived {
		dto.Folder = folderArchive
	}
	participant := func(j types.JID) participantDTO {
		num := userNumber(j)
		p := participantDTO{ID: j.String(), Name: id.name(j), Number: num, FormattedNumber: num, Visible: true}
		if id.isMe(j) {
			p.IsMe = true
		} else if p.Name == "" {
			p.Name = num
		}
		return p
	}
	me := id.me()
	if !r.IsGroup {
		if !jid.IsEmpty() {
			other := participant(jid)
			dto.Participants = append(dto.Participants, other)
			dto.OtherParticipantIDs = append(dto.OtherParticipantIDs, other.ID)
			if dto.Name == "" {
				dto.Name = other.Name
			}
		}
		if !me.IsEmpty() {
			dto.Participants = append(dto.Participants, participant(me))
		}
		return dto
	}
	for _, s := range r.Participants {
		j, err := types.ParseJID(s)
		if err != nil {
			continue
		}
		p := participant(j)
		dto.Participants = append(dto.Participants, p)
		if !p.IsMe {
			dto.OtherParticipantIDs = append(dto.OtherParticipantIDs, p.ID)
		}
	}
	return dto
}

// deliverChat delivers the conversation event of a chat in the index.
func (b *WABridge) deliverChat(local *localStore, id identity, chat string) {
	r, ok, err := local.getChat(chat)
	if err != nil || !ok {
		return
	}
	b.deliver(kindConversation, conversationOf(r, id, time.Now()))
}

func (b *WABridge) deliverChatIfKnown(local *localStore, id identity, chat types.JID) {
	if chat.Server == types.DefaultUserServer {
		b.deliverChat(local, id, chat.String())
	}
}

// updateChatAndDeliver applies an app-state change to a chat (creating its
// row: settings can arrive before any message) and delivers it.
func (b *WABridge) updateChatAndDeliver(local *localStore, id identity, chat types.JID, fn func(r *chatRow)) {
	if chat.IsEmpty() || chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
		return
	}
	r, _, err := local.updateChat(chat.String(), func(r *chatRow, existed bool) bool {
		if !existed {
			r.IsGroup = chat.Server == types.GroupServer
		}
		fn(r)
		return true
	})
	if err == nil {
		b.deliver(kindConversation, conversationOf(r, id, time.Now()))
	}
}

// onDeleteChat: the chat was deleted on the phone. As with gmbridge's
// "deleted" folder, Kotlin decides what to keep; Go forgets it.
func (b *WABridge) onDeleteChat(local *localStore, id identity, chat types.JID) {
	r, ok, err := local.getChat(chat.String())
	if err != nil || !ok {
		return
	}
	ctx, cancel := dbCtx()
	defer cancel()
	local.mu.Lock()
	_, _ = local.db.ExecContext(ctx, `DELETE FROM lightdecoder_message WHERE chat = ?`, r.JID)
	_, _ = local.db.ExecContext(ctx, `DELETE FROM lightdecoder_chat WHERE jid = ?`, r.JID)
	local.mu.Unlock()
	dto := conversationOf(r, id, time.Now())
	dto.Folder = "deleted"
	b.deliver(kindConversation, dto)
}

// muteEnd turns a mute action into the index's mute_end (ms; -1 forever).
func muteEnd(muted bool, endMs int64) int64 {
	switch {
	case !muted:
		return 0
	case endMs <= 0:
		return -1
	default:
		return endMs
	}
}

// --- groups --------------------------------------------------------------------

// storeGroupInfo puts a group's name and members into the index.
func (b *WABridge) storeGroupInfo(local *localStore, id identity, gi *types.GroupInfo) {
	if gi == nil || gi.JID.IsEmpty() {
		return
	}
	members := make([]string, 0, len(gi.Participants))
	for _, p := range gi.Participants {
		j := p.PhoneNumber
		if j.IsEmpty() {
			j = p.JID
		}
		members = append(members, id.norm(j).String())
	}
	_, _, _ = local.updateChat(id.norm(gi.JID).String(), func(r *chatRow, existed bool) bool {
		r.IsGroup = true
		if gi.Name != "" {
			r.Name = gi.Name
		}
		if len(members) > 0 {
			r.Participants = members
		}
		r.ReadOnly = gi.IsAnnounce && !isAdmin(gi, id)
		return true
	})
}

func isAdmin(gi *types.GroupInfo, id identity) bool {
	for _, p := range gi.Participants {
		if (p.IsAdmin || p.IsSuperAdmin) && (id.isMe(p.JID) || id.isMe(p.PhoneNumber) || id.isMe(p.LID)) {
			return true
		}
	}
	return false
}

func (b *WABridge) onGroupInfo(local *localStore, id identity, e *events.GroupInfo) {
	chat := id.norm(e.JID)
	r, existed, err := local.getChat(chat.String())
	if err != nil {
		return
	}
	changed := false
	if e.Name != nil && e.Name.Name != "" {
		r.Name, changed = e.Name.Name, true
	}
	set := make(map[string]bool, len(r.Participants))
	for _, p := range r.Participants {
		set[p] = true
	}
	for _, j := range e.Join {
		if s := id.norm(j).String(); !set[s] {
			set[s] = true
			r.Participants = append(r.Participants, s)
			changed = true
		}
	}
	for _, j := range e.Leave {
		s := id.norm(j).String()
		if set[s] {
			delete(set, s)
			out := r.Participants[:0]
			for _, p := range r.Participants {
				if p != s {
					out = append(out, p)
				}
			}
			r.Participants = out
			changed = true
		}
	}
	if e.Announce != nil {
		r.ReadOnly, changed = e.Announce.IsAnnounce, true
	}
	if !changed && existed {
		return
	}
	r.IsGroup = true
	_, _, err = local.updateChat(r.JID, func(cur *chatRow, _ bool) bool {
		cur.IsGroup, cur.Name, cur.Participants, cur.ReadOnly = true, r.Name, r.Participants, r.ReadOnly
		return true
	})
	if err == nil {
		b.deliverChat(local, id, r.JID)
	}
}

// fetchGroupInfo is cli.GetGroupInfo; tests replace it.
var fetchGroupInfo = func(ctx context.Context, cli *whatsmeow.Client, jid types.JID) (*types.GroupInfo, error) {
	return cli.GetGroupInfo(ctx, jid)
}

// maybeFetchGroup asks WhatsApp once per session for the name and members of
// a group the index knows nothing about (a message arrived before history).
func (b *WABridge) maybeFetchGroup(cli *whatsmeow.Client, local *localStore, chat types.JID) {
	if chat.Server != types.GroupServer {
		return
	}
	r, ok, err := local.getChat(chat.String())
	if err != nil || (ok && r.Name != "" && len(r.Participants) > 0) {
		return
	}
	b.mu.Lock()
	if b.groupFetched[chat.String()] {
		b.mu.Unlock()
		return
	}
	b.groupFetched[chat.String()] = true
	gen := b.gen
	b.mu.Unlock()
	go func() {
		defer func() { _ = recover() }()
		ctx, cancel := context.WithTimeout(context.Background(), rpcTimeout)
		defer cancel()
		gi, err := fetchGroupInfo(ctx, cli, chat)
		if err != nil || gi == nil {
			return
		}
		b.disp.push(func() {
			if !b.isGen(gen) {
				return
			}
			id := resolver{cli: cli}
			b.storeGroupInfo(local, id, gi)
			b.deliverChat(local, id, id.norm(chat).String())
		})
	}()
}

// --- receipts --------------------------------------------------------------------

func (b *WABridge) onReceipt(local *localStore, id identity, e *events.Receipt) {
	chat := id.norm(e.Chat).String()
	var status string
	var st = statusDelivered
	switch e.Type {
	case types.ReceiptTypeDelivered:
		status = "delivered"
	case types.ReceiptTypeRead:
		status, st = "read", statusRead
	case types.ReceiptTypePlayed:
		status, st = "played", statusRead
	case types.ReceiptTypeReadSelf, types.ReceiptTypePlayedSelf:
		// Read on one of this account's other devices (usually the phone).
		b.updateChatAndDeliverIfKnown(local, id, chat, func(r *chatRow) bool {
			if !r.Unread {
				return false
			}
			r.Unread = false
			return true
		})
		return
	default:
		return
	}
	if e.IsFromMe {
		// Our own other device confirmed delivery of a message to us.
		return
	}
	ids := make([]string, 0, len(e.MessageIDs))
	for _, mid := range e.MessageIDs {
		ids = append(ids, mid)
		_, _, _ = local.updateMessage(chat, mid, "", func(m *messageDTO, exists bool) bool {
			if !exists || !m.FromMe || m.Direction == directionDeleted || statusRank(int(st)) <= statusRank(m.StatusCode) {
				return false
			}
			setStatus(m, st)
			return true
		})
	}
	b.deliver(kindReceipt, receiptDTO{ConversationID: chat, MessageIDs: ids, Status: status, TimestampMs: millis(e.Timestamp)})
}

func (b *WABridge) updateChatAndDeliverIfKnown(local *localStore, id identity, chat string, fn func(r *chatRow) bool) {
	r, changed, err := local.updateChat(chat, func(r *chatRow, existed bool) bool { return existed && fn(r) })
	if err == nil && changed {
		b.deliver(kindConversation, conversationOf(r, id, time.Now()))
	}
}

// --- history sync -------------------------------------------------------------------

// onHistorySync stores what the phone sends after linking (and on demand):
// each chat's settings and messages. Messages are delivered with isOld true,
// then the chat, then the progress.
func (b *WABridge) onHistorySync(cli *whatsmeow.Client, local *localStore, id identity, e *events.HistorySync) {
	data := e.Data
	if data == nil {
		return
	}
	// An on-demand answer only carries messages; its chat fields are empty,
	// not "unarchived, unpinned, read".
	withMeta := data.GetSyncType() != waHistorySync.HistorySync_ON_DEMAND
	for _, conv := range data.GetConversations() {
		b.historyConversation(cli, local, id, conv, withMeta)
	}
	switch data.GetSyncType() {
	case waHistorySync.HistorySync_INITIAL_BOOTSTRAP, waHistorySync.HistorySync_RECENT, waHistorySync.HistorySync_FULL:
		p := int(data.GetProgress())
		b.deliver(kindHistorySync, historySyncDTO{Progress: min(p, 100), Done: p >= 100})
	}
}

func (b *WABridge) historyConversation(cli *whatsmeow.Client, local *localStore, id identity, conv *waHistorySync.Conversation, withMeta bool) {
	raw, err := types.ParseJID(conv.GetID())
	if err != nil || raw.IsEmpty() {
		return
	}
	raw = raw.ToNonAD()
	if raw.Server == types.BroadcastServer || raw.Server == types.NewsletterServer {
		return
	}
	chat := id.norm(raw)
	for _, hm := range conv.GetMessages() {
		web := hm.GetMessage()
		if web == nil {
			continue
		}
		evt, err := cli.ParseWebMessage(raw, web)
		if err != nil {
			continue
		}
		b.applyAction(local, id, historyAction(evt, web, id), false)
	}
	members := make([]string, 0, len(conv.GetParticipant()))
	for _, p := range conv.GetParticipant() {
		if j, err := types.ParseJID(p.GetUserJID()); err == nil {
			members = append(members, id.norm(j).String())
		}
	}
	r, _, err := local.updateChat(chat.String(), func(r *chatRow, existed bool) bool {
		r.IsGroup = raw.Server == types.GroupServer
		name := conv.GetName()
		if name == "" {
			name = conv.GetDisplayName()
		}
		if name != "" && r.IsGroup {
			r.Name = name
		}
		if len(members) > 0 {
			r.Participants = members
		}
		if ts := int64(conv.GetConversationTimestamp()) * 1000; ts > r.LastTs && r.LastID == "" {
			r.LastTs = ts
		}
		if !withMeta {
			return true
		}
		r.Archived = conv.GetArchived()
		r.Pinned = conv.GetPinned() > 0
		r.ReadOnly = conv.GetReadOnly()
		r.Unread = conv.GetUnreadCount() > 0 || conv.GetMarkedAsUnread()
		if end := conv.GetMuteEndTime(); end > 0 {
			r.MuteEnd = int64(end)
			if r.MuteEnd < 1e12 { // seconds
				r.MuteEnd *= 1000
			}
		}
		return true
	})
	if err == nil {
		b.deliver(kindConversation, conversationOf(r, id, time.Now()))
	}
}
