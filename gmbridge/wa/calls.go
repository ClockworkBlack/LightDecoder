package wa

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/types"
)

// whatsmeow calls that touch the network; tests replace them.
var (
	sendMessage = func(ctx context.Context, cli *whatsmeow.Client, to types.JID, msg *waE2E.Message, extra whatsmeow.SendRequestExtra) (whatsmeow.SendResponse, error) {
		return cli.SendMessage(ctx, to, msg, extra)
	}
	sendPeerMessage = func(ctx context.Context, cli *whatsmeow.Client, msg *waE2E.Message) (whatsmeow.SendResponse, error) {
		return cli.SendPeerMessage(ctx, msg)
	}
	markRead = func(ctx context.Context, cli *whatsmeow.Client, ids []types.MessageID, ts time.Time, chat, sender types.JID) error {
		return cli.MarkRead(ctx, ids, ts, chat, sender)
	}
	sendChatPresence = func(ctx context.Context, cli *whatsmeow.Client, chat types.JID, state types.ChatPresence) error {
		return cli.SendChatPresence(ctx, chat, state, types.ChatPresenceMediaText)
	}
	subscribePresence = func(ctx context.Context, cli *whatsmeow.Client, jid types.JID) error {
		return cli.SubscribePresence(ctx, jid)
	}
	sendPresence = func(ctx context.Context, cli *whatsmeow.Client, state types.Presence) error {
		return cli.SendPresence(ctx, state)
	}
	sendAppState = func(ctx context.Context, cli *whatsmeow.Client, patch appstate.PatchInfo) error {
		return cli.SendAppState(ctx, patch)
	}
	isOnWhatsApp = func(ctx context.Context, cli *whatsmeow.Client, phones []string) ([]types.IsOnWhatsAppResponse, error) {
		return cli.IsOnWhatsApp(ctx, phones)
	}
	newMessageID = func(cli *whatsmeow.Client) types.MessageID { return cli.GenerateMessageID() }
)

const (
	// historyRequestCount is how many older messages one on-demand history
	// request asks the phone for (whatsmeow's recommendation).
	historyRequestCount = 50
	// historyRequestInterval: at most one on-demand request per chat per
	// interval, however often Kotlin pages.
	historyRequestInterval = time.Minute
)

// parseChat reads a conversation id (a chat JID).
func parseChat(s string) (types.JID, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return types.EmptyJID, newError(codeInvalidArgument, "conversation id is empty")
	}
	j, err := types.ParseJID(s)
	if err != nil || j.User == "" || !strings.Contains(s, "@") {
		return types.EmptyJID, newError(codeInvalidArgument, "conversation id is not a WhatsApp chat")
	}
	switch j.Server {
	case types.DefaultUserServer, types.GroupServer, types.HiddenUserServer:
		return j.ToNonAD(), nil
	case types.LegacyUserServer:
		j.Server = types.DefaultUserServer
		return j.ToNonAD(), nil
	}
	return types.EmptyJID, newError(codeUnsupported, "this kind of chat is not supported")
}

// parseUser reads a participant id ("" gives the empty JID).
func parseUser(s string) (types.JID, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return types.EmptyJID, nil
	}
	j, err := types.ParseJID(s)
	if err != nil || j.User == "" || !strings.Contains(s, "@") {
		return types.EmptyJID, newError(codeInvalidArgument, "participant id is not a WhatsApp user")
	}
	return j.ToNonAD(), nil
}

// sendFailed maps a send error; anything unclassified is send_failed.
func sendFailed(err error) error {
	be := toBridgeError(err)
	if be != nil && be.code == codeInternal {
		return &bridgeError{code: codeSendFailed, detail: be.detail, cause: err}
	}
	return be
}

// targetJID is the JID WhatsApp addressed a chat with (LID or phone
// number), as recorded on its newest stored message; app-state patches must
// use the same one as the phone.
func targetJID(local *localStore, chat types.JID) types.JID {
	ref, ok, err := local.edge(chat.String(), true)
	if err == nil && ok && ref.RawChat != "" {
		if j, err := types.ParseJID(ref.RawChat); err == nil && !j.IsEmpty() {
			return j
		}
	}
	return chat
}

// --- local index ---------------------------------------------------------------

// ListConversations returns a JSON array of ConversationDTO from Go's own
// chat index (history sync plus everything since), the most recent first.
// folder is "inbox" or "archive" ("spam" is accepted and always empty).
func (b *WABridge) ListConversations(folder string, count int64) (out []byte, err error) {
	defer recoverInto(&err)
	var archived bool
	switch folder {
	case folderInbox, "":
	case folderArchive:
		archived = true
	case "spam":
		return []byte("[]"), nil
	default:
		return nil, newError(codeInvalidArgument, "unknown folder")
	}
	if count <= 0 {
		count = defaultConversationCount
	}
	cli, local, err := b.linked()
	if err != nil {
		return nil, err
	}
	rows, err := local.listChats(archived, int(min(count, 10*maxPageCount)))
	if err != nil {
		return nil, newError(codeInternal, "could not read the chat index")
	}
	id, now := resolver{cli: cli}, time.Now()
	list := make([]conversationDTO, 0, len(rows))
	for _, r := range rows {
		list = append(list, conversationOf(r, id, now))
	}
	return toJSON(list), nil
}

// ListContacts returns a JSON array of ContactDTO: the people whatsmeow
// knows a name for (the phone's address book, and push names).
func (b *WABridge) ListContacts() (out []byte, err error) {
	defer recoverInto(&err)
	cli, _, err := b.linked()
	if err != nil {
		return nil, err
	}
	ctx, cancel := dbCtx()
	defer cancel()
	all, err := cli.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return nil, newError(codeInternal, "could not read the contacts")
	}
	id := resolver{cli: cli}
	seen := make(map[string]bool, len(all))
	list := make([]contactDTO, 0, len(all))
	for j, c := range all {
		j = id.norm(j)
		name := contactName(c)
		if j.Server != types.DefaultUserServer || name == "" || seen[j.String()] || id.isMe(j) {
			continue
		}
		seen[j.String()] = true
		num := userNumber(j)
		list = append(list, contactDTO{ID: j.String(), Name: name, Number: num, FormattedNumber: num})
	}
	slices.SortFunc(list, func(a, b contactDTO) int {
		if c := strings.Compare(strings.ToLower(a.Name), strings.ToLower(b.Name)); c != 0 {
			return c
		}
		return strings.Compare(a.ID, b.ID)
	})
	return toJSON(list), nil
}

// FetchMessages returns a MessagePageDTO of the messages Go has stored for
// the chat (from history sync and live messages): up to count, oldest first,
// older than cursorJSON (the newest when it is empty), with the cursor for
// the next older page (null when nothing older is stored). When the stored
// messages run out and the device is connected, it also asks the phone for
// older history; those messages arrive later as "message" events.
func (b *WABridge) FetchMessages(conversationID string, count int64, cursorJSON []byte) (out []byte, err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return nil, err
	}
	cursor, err := parseCursor(cursorJSON)
	if err != nil {
		return nil, err
	}
	if count <= 0 {
		count = defaultMessageCount
	}
	count = min(count, maxPageCount)
	cli, local, err := b.linked()
	if err != nil {
		return nil, err
	}
	key := resolver{cli: cli}.norm(chat).String()
	msgs, err := local.page(key, cursor, int(count))
	if err != nil {
		return nil, newError(codeInternal, "could not read the message cache")
	}
	total, _ := local.countMessages(key)
	page := messagePageDTO{Messages: msgs, Total: total}
	if page.Messages == nil {
		page.Messages = []messageDTO{}
	}
	if len(msgs) > 0 {
		page.Cursor = &cursorDTO{LastItemID: msgs[0].ID, LastItemTimestampMs: msgs[0].TimestampMs}
	}
	if int64(len(msgs)) < count {
		b.requestOlderHistory(cli, local, key)
	}
	return toJSON(page), nil
}

// requestOlderHistory asks the phone (in the background) for the messages
// before the oldest one stored for chat.
func (b *WABridge) requestOlderHistory(cli *whatsmeow.Client, local *localStore, chat string) {
	ref, ok, err := local.edge(chat, false)
	if err != nil || !ok || ref.RawChat == "" {
		return // nothing to anchor the request on
	}
	raw, err := types.ParseJID(ref.RawChat)
	if err != nil {
		return
	}
	b.mu.Lock()
	connCtx, wanted := b.connCtx, b.wanted
	last := b.historyAsked[chat]
	if !wanted || connCtx == nil || time.Since(last) < historyRequestInterval {
		b.mu.Unlock()
		return
	}
	b.historyAsked[chat] = time.Now()
	b.mu.Unlock()
	if !isLoggedIn(cli) {
		return
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: raw, IsFromMe: ref.FromMe, IsGroup: raw.Server == types.GroupServer},
		ID:            ref.ID,
		Timestamp:     time.UnixMilli(ref.TsMs),
	}
	go func() {
		defer func() { _ = recover() }()
		ctx, cancel := context.WithTimeout(connCtx, rpcTimeout)
		defer cancel()
		_, _ = sendPeerMessage(ctx, cli, cli.BuildHistorySyncRequest(info, historyRequestCount))
	}()
}

// --- sending ------------------------------------------------------------------------

// replyContext is the ContextInfo that makes a message a reply to replyToID.
func replyContext(local *localStore, id identity, chat types.JID, replyToID string) *waE2E.ContextInfo {
	if replyToID == "" {
		return nil
	}
	ci := &waE2E.ContextInfo{StanzaID: new(replyToID)}
	m, _, ok, err := local.getMessage(id.norm(chat).String(), replyToID)
	if err == nil && ok {
		if m.SenderID != "" {
			ci.Participant = new(m.SenderID)
		}
		ci.QuotedMessage = &waE2E.Message{Conversation: new(m.Text)}
	} else if chat.Server != types.GroupServer {
		// Unknown message in a one-to-one chat: assume the other side's.
		ci.Participant = new(chat.String())
	}
	return ci
}

// sent records a message this device sent and returns its DTO JSON.
func (b *WABridge) sent(local *localStore, id identity, chat types.JID, m messageDTO) []byte {
	normalizeMessage(&m)
	stored, _, err := local.updateMessage(m.ConversationID, m.ID, chat.String(), func(cur *messageDTO, exists bool) bool {
		if exists {
			*cur = mergeNew(*cur, m)
		} else {
			*cur = m
		}
		return true
	})
	if err != nil {
		stored = m
	}
	if b.noteMessage(local, id, stored, chat, false, false) {
		if r, ok, err := local.getChat(stored.ConversationID); err == nil && ok {
			b.emit(kindConversation, conversationOf(r, id, time.Now()))
		}
	}
	return toJSON(stored)
}

func outgoing(id identity, chat types.JID, msgID, tmpID string, ts time.Time) messageDTO {
	if ts.IsZero() {
		ts = time.Now()
	}
	m := messageDTO{
		ID:             msgID,
		TmpID:          tmpID,
		ConversationID: id.norm(chat).String(),
		SenderID:       id.me().String(),
		FromMe:         true,
		TimestampMs:    ts.UnixMilli(),
		Direction:      directionOut,
		Transport:      transportWhatsApp,
	}
	setStatus(&m, statusSent)
	return m
}

// SendText sends a text message (a reply when replyToID is set) and returns
// the sent MessageDTO: WhatsApp's message id and timestamp, with tmpId set.
func (b *WABridge) SendText(conversationID, tmpID, text, replyToID string) (out []byte, err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(text) == "" {
		return nil, newError(codeInvalidArgument, "message is empty")
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	id := resolver{cli: cli}
	msg := &waE2E.Message{}
	if ci := replyContext(local, id, chat, replyToID); ci != nil {
		msg.ExtendedTextMessage = &waE2E.ExtendedTextMessage{Text: new(text), ContextInfo: ci}
	} else {
		msg.Conversation = new(text)
	}
	resp, err := sendMessage(ctx, cli, chat, msg, whatsmeow.SendRequestExtra{ID: newMessageID(cli)})
	if err != nil {
		return nil, sendFailed(err)
	}
	m := outgoing(id, chat, resp.ID, tmpID, resp.Timestamp)
	m.Text, m.ReplyToID = text, replyToID
	m.Parts = []partDTO{{PartID: partText, Kind: partText, Text: text}}
	return b.sent(local, id, chat, m), nil
}

// senderOf is the JID of the sender of a stored message, for message keys:
// empty for our own messages (whatsmeow then marks the key fromMe).
func senderOf(local *localStore, id identity, chat types.JID, messageID string, given string) (types.JID, bool, error) {
	if s, err := parseUser(given); err != nil {
		return s, false, err
	} else if !s.IsEmpty() {
		return s, true, nil
	}
	m, _, ok, err := local.getMessage(id.norm(chat).String(), messageID)
	if err == nil && ok {
		if m.FromMe {
			return types.EmptyJID, true, nil
		}
		if s, err := types.ParseJID(m.SenderID); err == nil {
			return s, true, nil
		}
	}
	if chat.Server != types.GroupServer {
		return chat, false, nil
	}
	return types.EmptyJID, false, nil
}

// SendReaction reacts to a message with emoji ("" removes this account's
// reaction). senderID is the message's sender (empty: look it up, or this
// account's own message).
func (b *WABridge) SendReaction(conversationID, messageID, senderID, emoji string) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	if messageID == "" {
		return newError(codeInvalidArgument, "message id is empty")
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	id := resolver{cli: cli}
	sender, _, err := senderOf(local, id, chat, messageID, senderID)
	if err != nil {
		return err
	}
	if _, err := sendMessage(ctx, cli, chat, cli.BuildReaction(chat, sender, messageID, emoji), whatsmeow.SendRequestExtra{}); err != nil {
		return sendFailed(err)
	}
	m, changed, err := local.updateMessage(id.norm(chat).String(), messageID, "", func(m *messageDTO, exists bool) bool {
		if !exists {
			return false
		}
		applyReaction(m, id.me().String(), emoji, true)
		return true
	})
	if err == nil && changed {
		b.emit(kindMessage, m)
	}
	return nil
}

// MarkRead sends a read receipt up to messageID (senderID: its sender; may
// be empty in one-to-one chats) and marks the chat read in the index.
func (b *WABridge) MarkRead(conversationID, messageID, senderID string) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	if messageID == "" {
		return newError(codeInvalidArgument, "message id is empty")
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	id := resolver{cli: cli}
	sender, known, err := senderOf(local, id, chat, messageID, senderID)
	if err != nil {
		return err
	}
	ownMessage := known && sender.IsEmpty()
	if !ownMessage {
		if chat.Server == types.GroupServer && sender.IsEmpty() {
			return newError(codeInvalidArgument, "the sender is needed in a group")
		}
		if chat.Server != types.GroupServer {
			sender = types.EmptyJID
		}
		if err := markRead(ctx, cli, []types.MessageID{messageID}, time.Now(), chat, sender); err != nil {
			return exported(err)
		}
	}
	key := id.norm(chat).String()
	r, changed, err := local.updateChat(key, func(r *chatRow, existed bool) bool {
		if !existed || !r.Unread {
			return false
		}
		r.Unread = false
		return true
	})
	if err == nil && changed {
		b.emit(kindConversation, conversationOf(r, id, time.Now()))
	}
	return nil
}

// SetTyping tells the chat this account is typing (or stopped).
func (b *WABridge) SetTyping(conversationID string, typing bool) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	cli, _, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	state := types.ChatPresencePaused
	if typing {
		state = types.ChatPresenceComposing
	}
	return exported(sendChatPresence(ctx, cli, chat, state))
}

// SubscribePresence asks for "presence" events of one user. WhatsApp only
// sends them while this account is online (SetOnline(true)).
func (b *WABridge) SubscribePresence(participantID string) (err error) {
	defer recoverInto(&err)
	user, err := parseUser(participantID)
	if err != nil {
		return err
	}
	if user.IsEmpty() || user.Server == types.GroupServer {
		return newError(codeInvalidArgument, "participant id is not a WhatsApp user")
	}
	cli, _, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	return exported(subscribePresence(ctx, cli, user))
}

// SetOnline sets this account's own presence: available while the app is in
// front, unavailable otherwise (so the phone keeps getting notifications).
func (b *WABridge) SetOnline(online bool) (err error) {
	defer recoverInto(&err)
	cli, _, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	state := types.PresenceUnavailable
	if online {
		state = types.PresenceAvailable
	}
	return exported(sendPresence(ctx, cli, state))
}

// DeleteMessage deletes a message for everyone (revoke: this account's own
// messages, or any as a group admin) or only on this account's devices.
// A "message" event with direction "deleted" follows.
func (b *WABridge) DeleteMessage(conversationID, messageID string, forEveryone bool) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	if messageID == "" {
		return newError(codeInvalidArgument, "message id is empty")
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	id := resolver{cli: cli}
	key := id.norm(chat).String()
	old, ref, exists, err := local.getMessage(key, messageID)
	if err != nil {
		return newError(codeInternal, "could not read the message cache")
	}
	if forEveryone {
		sender := types.EmptyJID
		if exists && !old.FromMe {
			sender, _ = types.ParseJID(old.SenderID)
		}
		if _, err := sendMessage(ctx, cli, chat, cli.BuildRevoke(chat, sender, messageID), whatsmeow.SendRequestExtra{}); err != nil {
			return sendFailed(err)
		}
	} else {
		if !exists {
			return newError(codeNotFound, "message not found")
		}
		target := chat
		if ref.RawChat != "" {
			if j, err := types.ParseJID(ref.RawChat); err == nil {
				target = j
			}
		}
		if err := sendAppState(ctx, cli, buildDeleteForMe(target, ref)); err != nil {
			return exported(err)
		}
	}
	var base *messageDTO
	if exists {
		base = &old
	}
	m := deletedMessage(base, messageID, key, 0)
	if forEveryone {
		_ = local.putMessage(m, ref.RawChat)
	} else {
		_ = local.deleteMessage(key, messageID)
	}
	b.emit(kindMessage, m)
	return nil
}

// buildDeleteForMe is the app-state patch WhatsApp uses for "delete for
// me" (whatsmeow has no builder for it; the index layout is the one its
// decoder reads: chat, message id, from me, participant).
func buildDeleteForMe(chat types.JID, ref msgRef) appstate.PatchInfo {
	fromMe, participant := "0", "0"
	if ref.FromMe {
		fromMe = "1"
	}
	if chat.Server == types.GroupServer && !ref.FromMe && ref.Sender != "" {
		participant = ref.Sender
	}
	return appstate.PatchInfo{
		Type: appstate.WAPatchRegularHigh,
		Mutations: []appstate.MutationInfo{{
			Index:   []string{appstate.IndexDeleteMessageForMe, chat.String(), ref.ID, fromMe, participant},
			Version: 3,
			Value: &waSyncAction.SyncActionValue{
				DeleteMessageForMeAction: &waSyncAction.DeleteMessageForMeAction{
					DeleteMedia:      new(false),
					MessageTimestamp: new(ref.TsMs / 1000),
				},
			},
		}},
	}
}

// lastMessageKey is the key and time of a chat's newest stored message, which
// archive patches carry.
func lastMessageKey(cli *whatsmeow.Client, local *localStore, chat types.JID) (time.Time, *waCommon.MessageKey) {
	ref, ok, err := local.edge(chat.String(), true)
	if err != nil || !ok {
		return time.Time{}, nil
	}
	target := chat
	if ref.RawChat != "" {
		if j, err := types.ParseJID(ref.RawChat); err == nil {
			target = j
		}
	}
	sender := types.EmptyJID
	if !ref.FromMe {
		sender, _ = types.ParseJID(ref.Sender)
	}
	return time.UnixMilli(ref.TsMs), cli.BuildMessageKey(target, sender, ref.ID)
}

// SetConversationArchived archives or unarchives a chat on all of this
// account's devices.
func (b *WABridge) SetConversationArchived(conversationID string, archived bool) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	id := resolver{cli: cli}
	key := id.norm(chat)
	ts, lastKey := lastMessageKey(cli, local, key)
	if err := sendAppState(ctx, cli, appstate.BuildArchive(targetJID(local, key), archived, ts, lastKey)); err != nil {
		return exported(err)
	}
	b.setChatField(local, id, key, func(r *chatRow) {
		r.Archived = archived
		if archived {
			r.Pinned = false
		}
	})
	return nil
}

// SetConversationMuted mutes (until unmuted) or unmutes a chat on all of
// this account's devices.
func (b *WABridge) SetConversationMuted(conversationID string, muted bool) (err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return err
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	id := resolver{cli: cli}
	key := id.norm(chat)
	if err := sendAppState(ctx, cli, appstate.BuildMute(targetJID(local, key), muted, 0)); err != nil {
		return exported(err)
	}
	b.setChatField(local, id, key, func(r *chatRow) { r.MuteEnd = muteEnd(muted, 0) })
	return nil
}

func (b *WABridge) setChatField(local *localStore, id identity, chat types.JID, fn func(r *chatRow)) {
	r, _, err := local.updateChat(chat.String(), func(r *chatRow, existed bool) bool {
		if !existed {
			r.IsGroup = chat.Server == types.GroupServer
		}
		fn(r)
		return true
	})
	if err == nil {
		b.emit(kindConversation, conversationOf(r, id, time.Now()))
	}
}

// GetOrCreateConversation returns the ConversationDTO of the one-to-one chat
// with a phone number (numbersJSON: a JSON array with one number, in
// international format), after checking that the number is on WhatsApp.
// Creating groups is not supported (unsupported).
func (b *WABridge) GetOrCreateConversation(numbersJSON []byte, groupName string) (out []byte, err error) {
	defer recoverInto(&err)
	var numbers []string
	if err := json.Unmarshal(numbersJSON, &numbers); err != nil {
		return nil, newError(codeInvalidArgument, "numbers must be a JSON array of strings")
	}
	if len(numbers) == 0 {
		return nil, newError(codeInvalidArgument, "no number given")
	}
	if len(numbers) > 1 || strings.TrimSpace(groupName) != "" {
		return nil, newError(codeUnsupported, "WhatsApp groups cannot be created here")
	}
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, numbers[0])
	if len(digits) < 7 {
		return nil, newError(codeInvalidArgument, "phone number is too short")
	}
	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	resp, err := isOnWhatsApp(ctx, cli, []string{"+" + digits})
	if err != nil {
		return nil, exported(err)
	}
	var jid types.JID
	for _, r := range resp {
		if r.IsIn && !r.JID.IsEmpty() {
			jid = r.JID.ToNonAD()
			break
		}
	}
	if jid.IsEmpty() {
		return nil, newError(codeNotOnWhatsApp, "this number is not on WhatsApp")
	}
	id := resolver{cli: cli}
	jid = id.norm(jid)
	r, _, err := local.updateChat(jid.String(), func(r *chatRow, existed bool) bool { return !existed })
	if err != nil {
		return nil, newError(codeInternal, "could not update the chat index")
	}
	return toJSON(conversationOf(r, id, time.Now())), nil
}
