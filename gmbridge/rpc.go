package gmbridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptrace"
	"net/url"
	"slices"
	"strings"
	"sync/atomic"

	"go.mau.fi/util/ptr"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Reads and writes that go to the phone. Each one needs a live connection
// (responses come back over the long poll) and may take up to 60 s, so Kotlin
// calls them off the main thread.

const (
	defaultConversationCount = 25
	defaultMessageCount      = 50
)

// --- reads -------------------------------------------------------------------

// ListConversations lists one folder (inbox|archive|spam), newest first, and
// returns a JSON array of ConversationDTO.
func (b *GMBridge) ListConversations(folder string, count int64) (out []byte, err error) {
	defer recoverInto(&err)
	f, ok := map[string]gmproto.ListConversationsRequest_Folder{
		"inbox":   gmproto.ListConversationsRequest_INBOX,
		"archive": gmproto.ListConversationsRequest_ARCHIVE,
		"spam":    gmproto.ListConversationsRequest_SPAM_BLOCKED,
	}[strings.ToLower(strings.TrimSpace(folder))]
	if !ok {
		return nil, newError(codeInvalidArgument, "folder must be inbox, archive or spam")
	}
	if count <= 0 {
		count = defaultConversationCount
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	var resp *gmproto.ListConversationsResponse
	b.whileSyncingConversations(func() {
		resp, err = cli.ListConversations(ctx, &gmproto.ListConversationsRequest{Count: count, Folder: f})
	})
	if err != nil {
		return nil, exported(err)
	}
	dtos := make([]conversationDTO, 0, len(resp.GetConversations()))
	for _, conv := range resp.GetConversations() {
		b.cache.putConversation(conv)
	}
	for _, conv := range resp.GetConversations() {
		dtos = append(dtos, toConversationDTO(conv, b.cache))
	}
	return toJSON(dtos), nil
}

// whileSyncingConversations runs fn (a ListConversations call) with
// syncingConversations raised, so MESSAGE_DELETED updates the phone sends
// meanwhile are dropped (see handlerFor). The connector does the same around
// its ListConversations sync. The deferred decrement keeps the count right
// even if fn panics.
func (b *GMBridge) whileSyncingConversations(fn func()) {
	b.syncingConversations.Add(1)
	defer b.syncingConversations.Add(-1)
	fn()
}

// GetConversation fetches one conversation as a ConversationDTO.
func (b *GMBridge) GetConversation(conversationID string) (out []byte, err error) {
	defer recoverInto(&err)
	if conversationID == "" {
		return nil, newError(codeInvalidArgument, "conversation ID is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	conv, err := b.fetchConversation(ctx, cli, conversationID)
	if err != nil {
		return nil, exported(err)
	}
	return toJSON(toConversationDTO(conv, b.cache)), nil
}

func (b *GMBridge) fetchConversation(ctx context.Context, cli *libgm.Client, conversationID string) (*gmproto.Conversation, error) {
	conv, err := cli.GetConversation(ctx, conversationID)
	if err != nil {
		return nil, err
	}
	if conv.GetConversationID() == "" {
		return nil, newError(codeNotFound, "conversation not found")
	}
	b.cache.putConversation(conv)
	return conv, nil
}

// conversationForWrite returns the cached conversation, fetching it once if
// it is not cached (writes need its DefaultOutgoingID and SIM).
func (b *GMBridge) conversationForWrite(ctx context.Context, cli *libgm.Client, conversationID string) (*gmproto.Conversation, error) {
	if conv := b.cache.conversation(conversationID); conv != nil {
		return conv, nil
	}
	return b.fetchConversation(ctx, cli, conversationID)
}

// FetchMessages returns a MessagePageDTO: up to count messages sorted oldest
// to newest, and a cursor to pass back for the next older page (null when
// there is nothing older). A null/empty cursorJSON fetches the newest page.
func (b *GMBridge) FetchMessages(conversationID string, count int64, cursorJSON []byte) (out []byte, err error) {
	defer recoverInto(&err)
	if conversationID == "" {
		return nil, newError(codeInvalidArgument, "conversation ID is empty")
	}
	cursor, err := parseCursor(cursorJSON)
	if err != nil {
		return nil, err
	}
	if count <= 0 {
		count = defaultMessageCount
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	resp, err := cli.FetchMessages(ctx, conversationID, count, cursor)
	if err != nil {
		return nil, exported(err)
	}
	return toJSON(b.messagePage(resp, cursor)), nil
}

// SeedProtocolSwitches tells the bridge which chats the archive knows to be
// end-to-end encrypted, so that after a restart a send keeps such a chat on
// RCS (ForceRCS) before its protocol-switch tombstone shows up again. That
// tombstone may lie far back in the history, where neither live events nor
// the newest page reach. seedJSON is a JSON array of the protocol-switch
// tombstones stored in the archive, {"conversationId","status","statusCode",
// "timestampMs"} as in MessageDTO (status or statusCode is enough; other
// statuses are ignored). Passing only each chat's newest one is enough. A
// chat follows its newest tombstone, whether seeded, live or in a history
// page. Call it after NewGMBridge; it never touches the network.
func (b *GMBridge) SeedProtocolSwitches(seedJSON []byte) (err error) {
	defer recoverInto(&err)
	if len(strings.TrimSpace(string(seedJSON))) == 0 {
		return nil
	}
	var seeds []struct {
		ConversationID string `json:"conversationId"`
		Status         string `json:"status"`
		StatusCode     int32  `json:"statusCode"`
		TimestampMs    int64  `json:"timestampMs"`
	}
	if err := json.Unmarshal(seedJSON, &seeds); err != nil {
		return newError(codeInvalidArgument, "protocol switches must be a JSON array of messages")
	}
	for _, s := range seeds {
		st := gmproto.MessageStatusType(s.StatusCode)
		if code, ok := gmproto.MessageStatusType_value[s.Status]; ok {
			st = gmproto.MessageStatusType(code)
		}
		if force, ok := protocolSwitchForce(st); ok {
			b.cache.recordProtocolSwitch(s.ConversationID, force, s.TimestampMs)
		}
	}
	return nil
}

// messagePage turns a ListMessagesResponse (newest first) into a page sorted
// oldest first. Like the connector's backfill it drops the overlap with the
// previous page and derives a cursor from the oldest message when the phone
// sends none. One difference: a message at exactly the cursor's millisecond
// is kept unless it is the cursor message itself, since a duplicate is
// harmless (Kotlin upserts by ID) but a dropped message is lost.
func (b *GMBridge) messagePage(resp *gmproto.ListMessagesResponse, cursor *gmproto.Cursor) messagePageDTO {
	raw := slices.Clone(resp.GetMessages())
	slices.Reverse(raw)
	page := messagePageDTO{Messages: make([]messageDTO, 0, len(raw)), Total: resp.GetTotalMessages()}
	var oldest *gmproto.Message
	for _, msg := range raw {
		// Older pages count too (the switch may lie far back, and the cache
		// starts empty after a restart); a tombstone never overrides a newer
		// one already recorded, whatever order the pages come in.
		b.cache.noteProtocolSwitch(msg)
	}
	for _, msg := range raw {
		if cursor != nil {
			ms := microsToMillis(msg.GetTimestamp())
			if ms > cursor.GetLastItemTimestamp() || msg.GetMessageID() == cursor.GetLastItemID() {
				continue
			}
		}
		if oldest == nil {
			oldest = msg
		}
		b.cache.indexMessage(msg)
		conv := b.cache.conversation(msg.GetConversationID())
		page.Messages = append(page.Messages, toMessageDTO(msg, true, conv, b.cache))
	}
	if len(page.Messages) == 0 {
		return page // nothing (new) came back: history is complete
	}
	fromOldest := &cursorDTO{
		LastItemID:          oldest.GetMessageID(),
		LastItemTimestampMs: microsToMillis(oldest.GetTimestamp()),
	}
	page.Cursor = toCursorDTO(resp.GetCursor())
	if page.Cursor == nil {
		page.Cursor = fromOldest
	}
	// Keeping same-millisecond messages means a page can consist only of
	// messages at the cursor's own millisecond. A cursor that does not move
	// back in time could then make the caller fetch the same messages
	// forever, so the next cursor must be strictly older than this one;
	// failing that, nothing older exists and history is complete.
	if cursor != nil && page.Cursor.LastItemTimestampMs >= cursor.GetLastItemTimestamp() {
		page.Cursor = nil
		if fromOldest.LastItemTimestampMs < cursor.GetLastItemTimestamp() {
			page.Cursor = fromOldest
		}
	}
	return page
}

// ListContacts returns the phone's contacts as a JSON array of ContactDTO.
func (b *GMBridge) ListContacts() (out []byte, err error) {
	defer recoverInto(&err)
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	resp, err := cli.ListContacts(ctx)
	if err != nil {
		return nil, exported(err)
	}
	dtos := make([]contactDTO, 0, len(resp.GetContacts()))
	for _, c := range resp.GetContacts() {
		dtos = append(dtos, toContactDTO(c))
	}
	return toJSON(dtos), nil
}

// ParticipantThumbnails takes ["participantId",…] and returns a JSON array of
// ThumbnailDTO for the participants that have a picture.
func (b *GMBridge) ParticipantThumbnails(idsJSON []byte) (out []byte, err error) {
	defer recoverInto(&err)
	ids, err := parseStringList(idsJSON, "participant IDs")
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return []byte("[]"), nil
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	resp, err := cli.GetParticipantThumbnail(ctx, ids...)
	if err != nil {
		return nil, exported(err)
	}
	return toJSON(toThumbnailDTOs(resp)), nil
}

// parseStringList reads a JSON array of strings, dropping blanks.
func parseStringList(raw []byte, what string) ([]string, error) {
	var in []string
	if len(strings.TrimSpace(string(raw))) == 0 {
		return nil, nil
	}
	if err := json.Unmarshal(raw, &in); err != nil {
		return nil, newError(codeInvalidArgument, what+" must be a JSON array of strings")
	}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out, nil
}

// --- writes ------------------------------------------------------------------

// SendText sends a text message and returns a SendResultDTO. tmpID is the
// outbox's idempotency key; the phone echoes it on the resulting message.
// Transient phone statuses are reported, not retried: Kotlin's outbox owns
// retries.
func (b *GMBridge) SendText(conversationID, tmpID, text, replyToID string) (out []byte, err error) {
	defer recoverInto(&err)
	if conversationID == "" || tmpID == "" {
		return nil, newError(codeInvalidArgument, "conversation ID and tmp ID are required")
	}
	if strings.TrimSpace(text) == "" {
		return nil, newError(codeInvalidArgument, "text is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	conv, err := b.conversationForWrite(ctx, cli, conversationID)
	if err != nil {
		return nil, exported(err)
	}
	infos := []*gmproto.MessageInfo{textInfo(text)}
	return b.send(ctx, cli, conv, tmpID, infos, replyToID)
}

func textInfo(text string) *gmproto.MessageInfo {
	return &gmproto.MessageInfo{Data: &gmproto.MessageInfo_MessageContent{
		MessageContent: &gmproto.MessageContent{Content: text},
	}}
}

// send sends one message and reports the phone's verdict.
func (b *GMBridge) send(ctx context.Context, cli *libgm.Client, conv *gmproto.Conversation, tmpID string, infos []*gmproto.MessageInfo, replyToID string) ([]byte, error) {
	req := b.buildSendRequest(conv, tmpID, infos, replyToID)
	// Nothing has gone to Google yet, so whatever happens here is safe to
	// send again.
	if cerr := ctx.Err(); cerr != nil {
		if errors.Is(cerr, context.DeadlineExceeded) {
			return nil, newError(codeSendFailed, "timed out before sending")
		}
		return nil, newError(codeConnectionClosed, "disconnected before sending")
	}
	ctx, wrote := traceRequestWrites(ctx)
	resp, err := sendMessage(ctx, cli, req)
	if err != nil {
		return nil, sendError(err, wrote.Load())
	}
	result := sendResultDTO{TmpID: tmpID, Status: resp.GetStatus().String()}
	if resp.GetStatus() == gmproto.SendMessageResponse_SUCCESS {
		result.OK = true
	} else {
		result.Code = codeSendFailed
	}
	return toJSON(result), nil
}

// sendError maps a SendMessage failure; wrote says whether any HTTP request
// of the call started going out (see traceRequestWrites).
//
// Once the POST may have reached Google, the phone may still send the
// message from the server's queue, so the failure is reported like
// phone_not_responding, which the outbox does not retry by itself (a retry
// could send it twice); if the phone does send it, the tmpId echo clears the
// failure. That covers:
//   - ErrConnectionClosed, which libgm returns only from its wait for the
//     phone's reply, and a bare context.Canceled from that wait or from
//     reading the POST's response body: Google had accepted the POST. (The
//     one other bare context.Canceled, libgm's pause before retrying a 5xx,
//     is ambiguous too and treated the same.)
//   - any failure without an HTTP status after the request's headers were
//     written: a response-header timeout (over HTTP/2 not a
//     context.DeadlineExceeded), a stream or connection reset, a cancel or
//     deadline while waiting for the response. Google may have the whole
//     request.
//
// A failure before anything was written (dial, DNS, TLS, a retired client, a
// cancel before sending) keeps the plain mapping, e.g. connection_closed or
// internal, which the outbox sends again. So does a status Google answered
// with. classify is left alone: pairing also gets ErrConnectionClosed, and
// there it does mean connection_closed.
func sendError(err error, wrote bool) error {
	var ue *url.Error
	if errors.Is(err, libgm.ErrConnectionClosed) || (errors.Is(err, context.Canceled) && !errors.As(err, &ue)) {
		return &bridgeError{code: codePhoneNotResponding, detail: "disconnected after Google accepted the message", cause: err}
	}
	be := toBridgeError(err)
	if wrote && httpStatus(err) == 0 && be.code != codePhoneNotResponding {
		return &bridgeError{code: codePhoneNotResponding, detail: "connection lost after the message went out", cause: err}
	}
	return be
}

// sendMessage is cli.SendMessage; tests replace it.
var sendMessage = func(ctx context.Context, cli *libgm.Client, req *gmproto.SendMessageRequest) (*gmproto.SendMessageResponse, error) {
	return cli.SendMessage(ctx, req)
}

// traceRequestWrites returns ctx with an HTTP client trace that sets the
// returned flag once a request made with it has its headers written to a
// connection (HTTP/1 and HTTP/2 both report it). libgm builds its requests
// with the call's context, so the trace reaches the transport.
func traceRequestWrites(ctx context.Context) (context.Context, *atomic.Bool) {
	wrote := new(atomic.Bool)
	return httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{WroteHeaders: func() { wrote.Store(true) }}), wrote
}

// buildSendRequest builds the SendMessageRequest as the connector's
// ConvertMatrixMessage does (minus its Matrix-only parts).
func (b *GMBridge) buildSendRequest(conv *gmproto.Conversation, tmpID string, infos []*gmproto.MessageInfo, replyToID string) *gmproto.SendMessageRequest {
	convID := conv.GetConversationID()
	req := &gmproto.SendMessageRequest{
		ConversationID: convID,
		MessagePayload: &gmproto.MessagePayload{
			TmpID:          tmpID,
			TmpID2:         tmpID,
			ConversationID: convID,
			ParticipantID:  conv.GetDefaultOutgoingID(),
			MessageInfo:    infos,
		},
		SIMPayload: b.cache.simPayloadFor(conv),
		TmpID:      tmpID,
		// Keep an end-to-end encrypted RCS chat on RCS instead of leaving it
		// to the phone's automatic choice, which may fall back to SMS/MMS.
		ForceRCS: conv.GetType() == gmproto.ConversationType_RCS &&
			conv.GetSendMode() == gmproto.ConversationSendMode_SEND_MODE_AUTO &&
			b.cache.shouldForceRCS(convID),
	}
	if replyToID != "" && b.replySupported(replyToID, conv) {
		req.Reply = &gmproto.ReplyPayload{MessageID: replyToID}
	}
	return req
}

// replySupported follows the connector: SMS/MMS messages cannot carry reply
// metadata, so a reply to one is sent as a plain message.
func (b *GMBridge) replySupported(replyToID string, conv *gmproto.Conversation) bool {
	if m, ok := b.cache.message(replyToID); ok {
		return m.msgType != 1 && m.msgType != 2 && m.msgType != 3
	}
	return conv.GetType() != gmproto.ConversationType_SMS
}

// SendReaction adds, removes or switches (action add|remove|switch) our
// reaction on a message.
func (b *GMBridge) SendReaction(messageID, emoji, action string) (err error) {
	defer recoverInto(&err)
	act, ok := map[string]gmproto.SendReactionRequest_Action{
		"add":    gmproto.SendReactionRequest_ADD,
		"remove": gmproto.SendReactionRequest_REMOVE,
		"switch": gmproto.SendReactionRequest_SWITCH,
	}[strings.ToLower(strings.TrimSpace(action))]
	if !ok {
		return newError(codeInvalidArgument, "action must be add, remove or switch")
	}
	if messageID == "" || emoji == "" {
		return newError(codeInvalidArgument, "message ID and emoji are required")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	req := &gmproto.SendReactionRequest{
		MessageID:    messageID,
		ReactionData: gmproto.MakeReactionData(emoji),
		Action:       act,
	}
	if m, ok := b.cache.message(messageID); ok {
		req.SIMPayload = b.cache.simPayloadFor(b.cache.conversation(m.conversationID))
	}
	resp, err := cli.SendReaction(ctx, req)
	if err != nil {
		return exported(err)
	}
	if !resp.GetSuccess() {
		return newError(codeSendFailed, "the phone rejected the reaction")
	}
	return nil
}

// MarkRead marks the conversation read up to messageID on the phone.
func (b *GMBridge) MarkRead(conversationID, messageID string) (err error) {
	defer recoverInto(&err)
	if conversationID == "" || messageID == "" {
		return newError(codeInvalidArgument, "conversation ID and message ID are required")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	return exported(cli.MarkRead(ctx, conversationID, messageID))
}

// SetTyping sends a "started typing" ping (Kotlin throttles it).
func (b *GMBridge) SetTyping(conversationID string) (err error) {
	defer recoverInto(&err)
	if conversationID == "" {
		return newError(codeInvalidArgument, "conversation ID is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	conv, err := b.conversationForWrite(ctx, cli, conversationID)
	if err != nil {
		return exported(err)
	}
	return exported(cli.SetTyping(ctx, conversationID, b.cache.simPayloadFor(conv)))
}

// DeleteMessage deletes a message on the phone (for everyone the phone can).
func (b *GMBridge) DeleteMessage(messageID string) (err error) {
	defer recoverInto(&err)
	if messageID == "" {
		return newError(codeInvalidArgument, "message ID is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	resp, err := cli.DeleteMessage(ctx, messageID)
	if err != nil {
		return exported(err)
	}
	if !resp.GetSuccess() {
		return newError(codeSendFailed, "the phone rejected the deletion")
	}
	return nil
}

// SetConversationStatus archives ("archived") or unarchives ("active") a
// conversation. Those are the two statuses UpdateConversation is known to
// accept from a web client.
func (b *GMBridge) SetConversationStatus(conversationID, status string) (err error) {
	defer recoverInto(&err)
	st, ok := map[string]gmproto.ConversationStatus{
		"archived":   gmproto.ConversationStatus_ARCHIVED,
		"archive":    gmproto.ConversationStatus_ARCHIVED,
		"active":     gmproto.ConversationStatus_ACTIVE,
		"unarchive":  gmproto.ConversationStatus_ACTIVE,
		"unarchived": gmproto.ConversationStatus_ACTIVE,
	}[strings.ToLower(strings.TrimSpace(status))]
	if !ok {
		return newError(codeInvalidArgument, "status must be archived or active")
	}
	return b.updateConversation(conversationID, &gmproto.UpdateConversationData_Status{Status: st})
}

// SetConversationMuted mutes or unmutes notifications for a conversation on
// the phone.
func (b *GMBridge) SetConversationMuted(conversationID string, muted bool) (err error) {
	defer recoverInto(&err)
	mute := gmproto.ConversationMuteStatus_UNMUTE
	if muted {
		mute = gmproto.ConversationMuteStatus_MUTE
	}
	return b.updateConversation(conversationID, &gmproto.UpdateConversationData_Mute{Mute: mute})
}

// updateConversation sends an UpdateConversationData; data is one of the
// oneof wrappers (the oneof interface itself is unexported in gmproto).
func (b *GMBridge) updateConversation(conversationID string, data any) error {
	if conversationID == "" {
		return newError(codeInvalidArgument, "conversation ID is empty")
	}
	update := &gmproto.UpdateConversationData{ConversationID: conversationID}
	switch d := data.(type) {
	case *gmproto.UpdateConversationData_Status:
		update.Data = d
	case *gmproto.UpdateConversationData_Mute:
		update.Data = d
	default:
		return newError(codeInternal, "unsupported conversation update")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	resp, err := cli.UpdateConversation(ctx, &gmproto.UpdateConversationRequest{
		ConversationID: conversationID,
		Data:           &gmproto.UpdateConversationRequest_UpdateData{UpdateData: update},
	})
	if err != nil {
		return exported(err)
	}
	if !resp.GetSuccess() {
		return newError(codeSendFailed, "the phone rejected the change")
	}
	return nil
}

// DeleteConversation deletes a conversation on the phone. Like the connector
// it passes the other participant's number for one-to-one chats and treats a
// conversation the phone no longer has as already deleted.
func (b *GMBridge) DeleteConversation(conversationID string) (err error) {
	defer recoverInto(&err)
	if conversationID == "" {
		return newError(codeInvalidArgument, "conversation ID is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	conv, err := b.conversationForWrite(ctx, cli, conversationID)
	if be := toBridgeError(err); be != nil {
		if be.code == codeNotFound {
			return nil
		}
		return be
	}
	if conv.GetStatus() == gmproto.ConversationStatus_DELETED {
		return nil
	}
	resp, err := cli.UpdateConversation(ctx, &gmproto.UpdateConversationRequest{
		Action:         gmproto.ConversationActionStatus_DELETE,
		ConversationID: conversationID,
		Data: &gmproto.UpdateConversationRequest_DeleteData{DeleteData: &gmproto.DeleteConversationData{
			ConversationID: conversationID,
			Phone:          ptr.NonZero(otherParticipantNumber(conv)),
		}},
	})
	if err != nil {
		return exported(err)
	}
	if !resp.GetSuccess() {
		return newError(codeSendFailed, "the phone rejected the deletion")
	}
	return nil
}

// otherParticipantNumber is the connector's findPhoneForConversation for
// one-to-one chats; groups get no number.
func otherParticipantNumber(conv *gmproto.Conversation) string {
	if conv.GetIsGroupChat() {
		return ""
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsVisible() && !p.GetIsMe() && p.GetID().GetNumber() != "" {
			return p.GetID().GetNumber()
		}
	}
	return ""
}

// GetOrCreateConversation opens (or creates) the conversation with the given
// numbers (["+1555…",…]) and returns its ConversationDTO. For groups, an RCS
// group is created when the phone asks for it, as the connector does.
func (b *GMBridge) GetOrCreateConversation(numbersJSON []byte, rcsGroupName string) (out []byte, err error) {
	defer recoverInto(&err)
	numbers, err := parseStringList(numbersJSON, "numbers")
	if err != nil {
		return nil, err
	}
	if len(numbers) == 0 {
		return nil, newError(codeInvalidArgument, "at least one number is required")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	req := &gmproto.GetOrCreateConversationRequest{Numbers: make([]*gmproto.ContactNumber, 0, len(numbers))}
	for _, n := range numbers {
		req.Numbers = append(req.Numbers, &gmproto.ContactNumber{MysteriousInt: 2, Number: n, Number2: n})
	}
	if len(numbers) > 1 {
		req.RCSGroupName = ptr.NonZero(strings.TrimSpace(rcsGroupName))
	}
	resp, err := cli.GetOrCreateConversation(ctx, req)
	if err == nil && resp.GetStatus() == gmproto.GetOrCreateConversationResponse_CREATE_RCS {
		if req.RCSGroupName == nil {
			req.RCSGroupName = ptr.Ptr("")
		}
		req.CreateRCSGroup = ptr.Ptr(true)
		resp, err = cli.GetOrCreateConversation(ctx, req)
	}
	if err != nil {
		return nil, exported(err)
	}
	conv := resp.GetConversation()
	if conv.GetConversationID() == "" {
		return nil, newError(codeNotFound, "the phone returned no conversation (status "+resp.GetStatus().String()+")")
	}
	b.cache.putConversation(conv)
	return toJSON(toConversationDTO(conv, b.cache)), nil
}
