package wa

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func TestNewWABridgeNeedsDir(t *testing.T) {
	_, err := NewWABridge("  ", newRecorder())
	mustCode(t, err, codeInvalidArgument)
}

func TestFreshBridgeIsUnpaired(t *testing.T) {
	installFakeNet(t)
	b, _ := newTestBridge(t)
	if b.IsPaired() {
		t.Fatal("fresh store must be unpaired")
	}
	var st statusDTO
	if err := json.Unmarshal([]byte(b.StatusJSON()), &st); err != nil || st.Paired || st.Connected || st.OwnID != "" {
		t.Fatalf("status %s", b.StatusJSON())
	}
	_, err := b.ListConversations("inbox", 10)
	mustCode(t, err, codeNotPaired)
	_, err = b.SendText(aliceJID.String(), "t", "hi", "")
	mustCode(t, err, codeNotPaired)
	mustCode(t, b.MarkRead(aliceJID.String(), "M", ""), codeNotPaired)
	if _, err := os.Stat(b.dbPath()); err != nil {
		t.Fatalf("store file not created: %v", err)
	}
}

func TestPairingFlowQRAndCode(t *testing.T) {
	f := installFakeNet(t)
	b, rec := newTestBridge(t)

	_, err := b.PairPhone("+1 555 000 1111")
	mustCode(t, err, codeNotConnected) // Connect first

	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	drain(t, b)
	if got := rec.states(); len(got) != 1 || got[0] != stateConnecting {
		t.Fatalf("states %v", got)
	}
	if err := b.StartQR(); err != nil {
		t.Fatal(err)
	}
	if f.connects != 1 {
		t.Fatalf("StartQR must reuse the linking connection, connects=%d", f.connects)
	}
	feed(t, b, &events.QR{Codes: []string{"code-1", "code-2"}})
	waitFor(t, "qr event", func() bool { return len(rec.eventsOf(kindQR)) == 1 })
	var qr qrDTO
	_ = json.Unmarshal(rec.eventsOf(kindQR)[0], &qr)
	if qr.Code != "code-1" {
		t.Fatalf("qr %+v", qr)
	}

	code, err := b.PairPhone("+1 (555) 000-1111")
	if err != nil {
		t.Fatal(err)
	}
	if code != "ABCDEFGH" || f.pairPhones[0] != "15550001111" {
		t.Fatalf("code %q phone %v", code, f.pairPhones)
	}

	// The phone confirms: pair_success, then whatsmeow reconnects.
	pair(t, b)
	feed(t, b, &events.PairSuccess{ID: meJID})
	if ev := rec.eventsOf(kindPairSuccess); len(ev) != 1 || !strings.Contains(string(ev[0]), `"ownId":"15550001111"`) {
		t.Fatalf("pair_success %q", ev)
	}
	feed(t, b, &events.Connected{})
	if st := rec.lastState(); st.State != stateConnected {
		t.Fatalf("state %+v", st)
	}
	if !b.IsPaired() {
		t.Fatal("paired after success")
	}
	var st statusDTO
	_ = json.Unmarshal([]byte(b.StatusJSON()), &st)
	if !st.Paired || !st.Connected || st.OwnID != "15550001111" || st.PushName != "Me" {
		t.Fatalf("status %+v", st)
	}
	_, err = b.PairPhone("+15550001111")
	mustCode(t, err, codeAlreadyPaired)
	mustCode(t, b.StartQR(), codeAlreadyPaired)
}

func TestCancelPairing(t *testing.T) {
	installFakeNet(t)
	b, rec := newTestBridge(t)
	if err := b.StartQR(); err != nil {
		t.Fatal(err)
	}
	b.CancelPairing()
	drain(t, b)
	if st := rec.lastState(); st.State != stateDisconnected {
		t.Fatalf("state %+v", st)
	}
	// Events of the abandoned attempt change nothing.
	feed(t, b, &events.QR{Codes: []string{"late"}})
	drain(t, b)
	if n := len(rec.eventsOf(kindQR)); n != 0 {
		t.Fatalf("qr after cancel: %d", n)
	}
	b.CancelPairing() // no attempt: nothing happens
}

func TestPairingSocketClosedIsTimeout(t *testing.T) {
	installFakeNet(t)
	b, rec := newTestBridge(t)
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	feed(t, b, &events.Disconnected{})
	waitFor(t, "pair_error", func() bool { return len(rec.eventsOf(kindPairError)) == 1 })
	var pe pairErrorDTO
	_ = json.Unmarshal(rec.eventsOf(kindPairError)[0], &pe)
	if pe.Code != codePairingTimeout {
		t.Fatalf("pair_error %+v", pe)
	}
	waitFor(t, "disconnected", func() bool { return rec.lastState().State == stateDisconnected })
}

func TestPairErrorEndsAttempt(t *testing.T) {
	installFakeNet(t)
	b, rec := newTestBridge(t)
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	feed(t, b, &events.PairError{ID: meJID, Error: errors.New("bad things for 15550001111@s.whatsapp.net")})
	waitFor(t, "pair_error", func() bool { return len(rec.eventsOf(kindPairError)) == 1 })
	raw := string(rec.eventsOf(kindPairError)[0])
	if !strings.Contains(raw, codePairFailed) || strings.Contains(raw, "15550001111") {
		t.Fatalf("pair_error %s", raw)
	}
}

func TestConnectStates(t *testing.T) {
	b, rec, f := connected(t)
	feed(t, b, &events.Connected{})
	feed(t, b, &events.Disconnected{})
	got := rec.states()
	want := []string{stateConnecting, stateConnected, stateConnecting}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("states %v, want %v", got, want)
	}
	if err := b.Connect(); err != nil { // reconnecting: try again now
		t.Fatal(err)
	}
	b.Disconnect()
	drain(t, b)
	if st := rec.lastState(); st.State != stateDisconnected {
		t.Fatalf("state %+v", st)
	}
	_, err := b.SendText(aliceJID.String(), "t", "hi", "")
	mustCode(t, err, codeNotConnected)
	_ = f
}

func TestConnectFailure(t *testing.T) {
	f := installFakeNet(t)
	f.connectErr = whatsmeow.ErrClientIsNil
	b, rec := newTestBridge(t)
	pair(t, b)
	mustCode(t, b.Connect(), codeNotPaired)
	drain(t, b)
	if st := rec.lastState(); st.State != stateError || st.Code != codeNotPaired {
		t.Fatalf("state %+v", st)
	}
	_, err := b.SendText(aliceJID.String(), "t", "hi", "")
	mustCode(t, err, codeNotConnected)
}

func TestStreamReplacedDropsConnection(t *testing.T) {
	b, rec, _ := connected(t)
	feed(t, b, &events.StreamReplaced{})
	if st := rec.lastState(); st.State != stateError || st.Code != codeStreamReplaced {
		t.Fatalf("state %+v", st)
	}
	waitFor(t, "connection dropped", func() bool { return !b.wantedNow() })
}

func TestLiveMessageFlow(t *testing.T) {
	b, rec, _ := connected(t)
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("hello")}))
	m := lastMessage(t, rec)
	if m.ID != "IN1" || m.Text != "hello" || m.Direction != directionIn {
		t.Fatalf("message %+v", m)
	}
	c := lastConversation(t, rec)
	if c.ID != aliceJID.String() || !c.Unread || c.Snippet != "hello" || c.LatestMessageID != "IN1" || c.Folder != folderInbox {
		t.Fatalf("conversation %+v", c)
	}

	// Reaction, edit and revoke update the stored message.
	key := &waCommon.MessageKey{ID: new("IN1"), RemoteJID: new(aliceJID.String())}
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "R1", &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Key: key, Text: new("👍")}}))
	if m := lastMessage(t, rec); len(m.Reactions) != 1 || m.Reactions[0].Emoji != "👍" || m.Text != "hello" {
		t.Fatalf("reaction %+v", m)
	}
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "E1", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(), Key: key, EditedMessage: &waE2E.Message{Conversation: new("hello!")}}}))
	if m := lastMessage(t, rec); m.Text != "hello!" || len(m.Reactions) != 1 {
		t.Fatalf("edit %+v", m)
	}
	if c := lastConversation(t, rec); c.Snippet != "hello!" {
		t.Fatalf("snippet after edit %q", c.Snippet)
	}
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "X1", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_REVOKE.Enum(), Key: key}}))
	if m := lastMessage(t, rec); m.Direction != directionDeleted || m.Text != "" || m.ID != "IN1" {
		t.Fatalf("revoke %+v", m)
	}
	// A late redelivery does not bring a revoked message back.
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("hello")}))
	if m := lastMessage(t, rec); m.Direction != directionDeleted {
		t.Fatalf("redelivery revived %+v", m)
	}

	// Typing and presence.
	feed(t, b, &events.ChatPresence{MessageSource: types.MessageSource{Chat: aliceJID, Sender: aliceJID}, State: types.ChatPresenceComposing})
	ty := rec.eventsOf(kindTyping)
	if len(ty) != 1 || !strings.Contains(string(ty[0]), `"typing":true`) || !strings.Contains(string(ty[0]), `"number":"+15550002222"`) {
		t.Fatalf("typing %q", ty)
	}
	feed(t, b, &events.Presence{From: aliceJID, Unavailable: true, LastSeen: testTime})
	pr := rec.eventsOf(kindPresence)
	if len(pr) != 1 || !strings.Contains(string(pr[0]), `"online":false`) || !strings.Contains(string(pr[0]), `"lastSeenMs":1700000123000`) {
		t.Fatalf("presence %q", pr)
	}
}

func TestSendTextAndReceipts(t *testing.T) {
	b, rec, f := connected(t)
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("question")}))
	out, err := b.SendText(aliceJID.String(), "tmp-1", "answer", "IN1")
	if err != nil {
		t.Fatal(err)
	}
	m := decodeMessage(t, out)
	if m.TmpID != "tmp-1" || m.ID == "" || m.TimestampMs != 1_700_000_000_000 || !m.FromMe || m.ReplyToID != "IN1" ||
		m.Status != "OUTGOING_COMPLETE" || m.SenderID != meJID.String() {
		t.Fatalf("sent %+v", m)
	}
	sent := f.sent[0]
	ci := sent.GetExtendedTextMessage().GetContextInfo()
	if sent.GetExtendedTextMessage().GetText() != "answer" || ci.GetStanzaID() != "IN1" || ci.GetParticipant() != aliceJID.String() ||
		ci.GetQuotedMessage().GetConversation() != "question" {
		t.Fatalf("reply wire message %v", sent)
	}

	// Receipts move the status forward only, and come as receipt events.
	feed(t, b, &events.Receipt{MessageSource: types.MessageSource{Chat: aliceJID, Sender: aliceJID}, MessageIDs: []string{m.ID},
		Type: types.ReceiptTypeRead, Timestamp: testTime})
	feed(t, b, &events.Receipt{MessageSource: types.MessageSource{Chat: aliceJID, Sender: aliceJID}, MessageIDs: []string{m.ID},
		Type: types.ReceiptTypeDelivered, Timestamp: testTime})
	rc := rec.eventsOf(kindReceipt)
	if len(rc) != 2 || !strings.Contains(string(rc[0]), `"status":"read"`) || !strings.Contains(string(rc[0]), m.ID) {
		t.Fatalf("receipts %q", rc)
	}
	page, err := b.FetchMessages(aliceJID.String(), 10, nil)
	if err != nil {
		t.Fatal(err)
	}
	var p messagePageDTO
	_ = json.Unmarshal(page, &p)
	if len(p.Messages) != 2 || p.Messages[0].ID != m.ID || p.Messages[0].Status != "OUTGOING_DISPLAYED" || p.Messages[0].TmpID != "tmp-1" {
		t.Fatalf("page %+v", p.Messages)
	}

	// Send errors keep their code; unknown ones are send_failed.
	f.sendErr = whatsmeow.ErrRecipientNotOnWhatsApp
	_, err = b.SendText(aliceJID.String(), "t2", "x", "")
	mustCode(t, err, codeNotOnWhatsApp)
	f.sendErr = errors.New("server said no")
	_, err = b.SendText(aliceJID.String(), "t3", "x", "")
	mustCode(t, err, codeSendFailed)
	_, err = b.SendText("not a jid", "t4", "x", "")
	mustCode(t, err, codeInvalidArgument)
	_, err = b.SendText("status@broadcast", "t4", "x", "")
	mustCode(t, err, codeUnsupported)
	_, err = b.SendText(aliceJID.String(), "t5", "  ", "")
	mustCode(t, err, codeInvalidArgument)
}

func TestReactionReadTypingDelete(t *testing.T) {
	b, rec, f := connected(t)
	feed(t, b, msgEvent(groupJID, bobJID, false, "G1", &waE2E.Message{Conversation: new("hi all")}))
	if c := lastConversation(t, rec); !c.IsGroup || !c.Unread {
		t.Fatalf("group conversation %+v", c)
	}
	if err := b.SendReaction(groupJID.String(), "G1", "", "🎉"); err != nil {
		t.Fatal(err)
	}
	rm := f.sent[0].GetReactionMessage()
	if rm.GetText() != "🎉" || rm.GetKey().GetFromMe() || rm.GetKey().GetParticipant() != bobJID.String() {
		t.Fatalf("reaction key %v", rm)
	}
	waitFor(t, "reaction echo", func() bool {
		all := rec.eventsOf(kindMessage)
		return len(all) > 1 && strings.Contains(string(all[len(all)-1]), "🎉")
	})
	if err := b.MarkRead(groupJID.String(), "G1", ""); err != nil {
		t.Fatal(err)
	}
	if len(f.reads) != 1 || f.reads[0][0] != "G1" {
		t.Fatalf("reads %v", f.reads)
	}
	waitFor(t, "read conversation", func() bool { return !lastConversation(t, rec).Unread })
	if err := b.SetTyping(groupJID.String(), true); err != nil {
		t.Fatal(err)
	}
	if err := b.SubscribePresence(aliceJID.String()); err != nil {
		t.Fatal(err)
	}
	mustCode(t, b.SubscribePresence(groupJID.String()), codeInvalidArgument)
	if err := b.SetOnline(true); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(f.presence, ","); got != "chat:composing,subscribe:"+aliceJID.String()+",presence:available" {
		t.Fatalf("presence calls %s", got)
	}
	if err := b.DeleteMessage(groupJID.String(), "G1", false); err != nil {
		t.Fatal(err)
	}
	if len(f.patches) != 1 || f.patches[0].Mutations[0].Index[0] != "deleteMessageForMe" ||
		f.patches[0].Mutations[0].Index[4] != bobJID.String() {
		t.Fatalf("delete-for-me patch %+v", f.patches)
	}
	waitFor(t, "deleted event", func() bool { return lastMessage(t, rec).Direction == directionDeleted })
	mustCode(t, b.DeleteMessage(groupJID.String(), "G1", false), codeNotFound)
	if err := b.DeleteMessage(groupJID.String(), "G2", true); err != nil {
		t.Fatal(err)
	}
	if f.sent[len(f.sent)-1].GetProtocolMessage().GetType() != waE2E.ProtocolMessage_REVOKE {
		t.Fatal("revoke not sent")
	}
}

func TestArchiveMuteAndAppState(t *testing.T) {
	b, rec, f := connected(t)
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("x")}))
	if err := b.SetConversationArchived(aliceJID.String(), true); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "archived", func() bool { return lastConversation(t, rec).Folder == folderArchive })
	if err := b.SetConversationMuted(aliceJID.String(), true); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "muted", func() bool { return lastConversation(t, rec).Muted })
	if len(f.patches) != 2 {
		t.Fatalf("patches %d", len(f.patches))
	}
	list, err := b.ListConversations("archive", 0)
	if err != nil || !strings.Contains(string(list), aliceJID.String()) {
		t.Fatalf("archive list %s %v", list, err)
	}
	list, _ = b.ListConversations("inbox", 0)
	if string(list) != "[]" {
		t.Fatalf("inbox list %s", list)
	}
	// Changes made on the phone arrive as app-state events.
	feed(t, b, &events.Archive{JID: aliceJID, Action: &waSyncAction.ArchiveChatAction{Archived: new(false)}})
	feed(t, b, &events.Mute{JID: aliceJID, Action: &waSyncAction.MuteAction{Muted: new(false)}})
	feed(t, b, &events.Pin{JID: aliceJID, Action: &waSyncAction.PinAction{Pinned: new(true)}})
	feed(t, b, &events.MarkChatAsRead{JID: aliceJID, Action: &waSyncAction.MarkChatAsReadAction{Read: new(true)}})
	c := lastConversation(t, rec)
	if c.Folder != folderInbox || c.Muted || !c.Pinned || c.Unread {
		t.Fatalf("after app state %+v", c)
	}
	_, err = b.ListConversations("bogus", 1)
	mustCode(t, err, codeInvalidArgument)
	if list, _ := b.ListConversations("spam", 1); string(list) != "[]" {
		t.Fatalf("spam %s", list)
	}
}

func TestHistorySyncAndPaging(t *testing.T) {
	b, rec, f := connected(t)
	var msgs []*waHistorySync.HistorySyncMsg
	for i := 0; i < 5; i++ {
		id := "H" + string(rune('0'+i))
		msgs = append(msgs, &waHistorySync.HistorySyncMsg{Message: &waWeb.WebMessageInfo{
			Key:              &waCommon.MessageKey{ID: new(id), FromMe: new(i%2 == 0), RemoteJID: new(aliceJID.String())},
			MessageTimestamp: new(uint64(1_600_000_000 + i)),
			Message:          &waE2E.Message{Conversation: new("history " + id)},
		}})
	}
	feed(t, b, &events.HistorySync{Data: &waHistorySync.HistorySync{
		SyncType: waHistorySync.HistorySync_INITIAL_BOOTSTRAP.Enum(),
		Progress: new(uint32(40)),
		Conversations: []*waHistorySync.Conversation{{
			ID: new(aliceJID.String()), Messages: msgs, Archived: new(true), UnreadCount: new(uint32(2)), MuteEndTime: new(uint64(4_000_000_000)),
		}, {
			ID: new(groupJID.String()), Name: new("Team"),
			Participant: []*waHistorySync.GroupParticipant{{UserJID: new(meJID.String())}, {UserJID: new(bobJID.String())}},
		}},
	}})
	if n := len(rec.eventsOf(kindMessage)); n != 5 {
		t.Fatalf("history messages %d", n)
	}
	for _, raw := range rec.eventsOf(kindMessage) {
		if !decodeMessage(t, raw).IsOld {
			t.Fatal("history must be isOld")
		}
	}
	hs := rec.eventsOf(kindHistorySync)
	if len(hs) != 1 || string(hs[0]) != `{"progress":40,"done":false}` {
		t.Fatalf("history_sync %q", hs)
	}
	archived, _ := b.ListConversations("archive", 10)
	var convs []conversationDTO
	_ = json.Unmarshal(archived, &convs)
	if len(convs) != 1 || !convs[0].Unread || !convs[0].Muted || convs[0].LatestMessageID != "H4" || convs[0].Snippet != "history H4" {
		t.Fatalf("archived %+v", convs)
	}
	inbox, _ := b.ListConversations("inbox", 10)
	if !strings.Contains(string(inbox), `"name":"Team"`) {
		t.Fatalf("group not listed: %s", inbox)
	}

	// Pages: newest first page, then older, oldest first within a page.
	raw, err := b.FetchMessages(aliceJID.String(), 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	var p messagePageDTO
	_ = json.Unmarshal(raw, &p)
	if len(p.Messages) != 2 || p.Messages[0].ID != "H3" || p.Messages[1].ID != "H4" || p.Total != 5 || p.Cursor.LastItemID != "H3" {
		t.Fatalf("page 1 %+v", p)
	}
	cur, _ := json.Marshal(p.Cursor)
	raw, _ = b.FetchMessages(aliceJID.String(), 2, cur)
	p = messagePageDTO{}
	_ = json.Unmarshal(raw, &p)
	if len(p.Messages) != 2 || p.Messages[0].ID != "H1" || p.Messages[1].ID != "H2" {
		t.Fatalf("page 2 %+v", p)
	}
	cur, _ = json.Marshal(p.Cursor)
	raw, _ = b.FetchMessages(aliceJID.String(), 2, cur)
	p = messagePageDTO{}
	_ = json.Unmarshal(raw, &p)
	if len(p.Messages) != 1 || p.Messages[0].ID != "H0" {
		t.Fatalf("page 3 %+v", p)
	}
	// The cache ran out: an on-demand history request went to the phone,
	// anchored on the oldest message, once per interval.
	waitFor(t, "history request", func() bool { f.mu.Lock(); defer f.mu.Unlock(); return len(f.peer) == 1 })
	req := f.peer[0].GetProtocolMessage().GetPeerDataOperationRequestMessage().GetHistorySyncOnDemandRequest()
	if req.GetOldestMsgID() != "H0" || req.GetChatJID() != aliceJID.String() || !req.GetOldestMsgFromMe() {
		t.Fatalf("history request %v", req)
	}
	cur, _ = json.Marshal(p.Cursor)
	raw, _ = b.FetchMessages(aliceJID.String(), 2, cur)
	if !strings.Contains(string(raw), `"messages":[]`) || !strings.Contains(string(raw), `"cursor":null`) {
		t.Fatalf("empty page %s", raw)
	}
	time.Sleep(20 * time.Millisecond)
	f.mu.Lock()
	n := len(f.peer)
	f.mu.Unlock()
	if n != 1 {
		t.Fatalf("history requests not throttled: %d", n)
	}
	_, err = b.FetchMessages(aliceJID.String(), 2, []byte("{bad"))
	mustCode(t, err, codeInvalidArgument)

	// The answer comes as an on-demand sync: messages only, the chat's
	// settings stay.
	feed(t, b, &events.HistorySync{Data: &waHistorySync.HistorySync{
		SyncType: waHistorySync.HistorySync_ON_DEMAND.Enum(),
		Conversations: []*waHistorySync.Conversation{{ID: new(aliceJID.String()), Messages: []*waHistorySync.HistorySyncMsg{{Message: &waWeb.WebMessageInfo{
			Key:              &waCommon.MessageKey{ID: new("OLD"), FromMe: new(false), RemoteJID: new(aliceJID.String())},
			MessageTimestamp: new(uint64(1_500_000_000)),
			Message:          &waE2E.Message{Conversation: new("ancient")},
		}}}}},
	}})
	if c := lastConversation(t, rec); c.Folder != folderArchive || c.LatestMessageID != "H4" {
		t.Fatalf("on-demand changed the chat: %+v", c)
	}
	if n := len(rec.eventsOf(kindHistorySync)); n != 1 {
		t.Fatalf("on-demand must not report progress (%d)", n)
	}
}

func TestGroupMetadataFetchedOnce(t *testing.T) {
	b, rec, _ := connected(t)
	other := types.NewJID("120363000000000002", types.GroupServer)
	feed(t, b, msgEvent(other, bobJID, false, "G1", &waE2E.Message{Conversation: new("hi")}))
	waitFor(t, "group name", func() bool {
		drain(t, b)
		return lastConversation(t, rec).Name == "Team"
	})
	c := lastConversation(t, rec)
	if len(c.Participants) != 3 || len(c.OtherParticipantIDs) != 2 {
		t.Fatalf("group %+v", c)
	}
	feed(t, b, &events.GroupInfo{JID: other, Name: &types.GroupName{Name: "Renamed"}, Leave: []types.JID{bobJID}})
	c = lastConversation(t, rec)
	if c.Name != "Renamed" || len(c.Participants) != 2 {
		t.Fatalf("group after change %+v", c)
	}
}

func TestLoggedOutOnPhoneWipes(t *testing.T) {
	b, rec, _ := connected(t)
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("x")}))
	feed(t, b, &events.LoggedOut{Reason: events.ConnectFailureLoggedOut})
	waitFor(t, "logged_out", func() bool { return rec.lastState().State == stateLoggedOut })
	if b.IsPaired() {
		t.Fatal("still paired after logout")
	}
	pair(t, b) // a new link must not see the old chats
	list, err := b.ListConversations("inbox", 10)
	if err != nil || string(list) != "[]" {
		t.Fatalf("index survived the wipe: %s %v", list, err)
	}
}

func TestLogout(t *testing.T) {
	b, rec, f := connected(t)
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("x")}))
	if err := b.Logout(); err != nil {
		t.Fatal(err)
	}
	drain(t, b)
	if f.logouts != 1 || b.IsPaired() {
		t.Fatalf("logouts %d paired %v", f.logouts, b.IsPaired())
	}
	if st := rec.lastState(); st.State != stateUnpaired || st.Code != "" {
		t.Fatalf("state %+v", st)
	}
	// Not linked: Logout is a plain wipe.
	if err := b.Logout(); err != nil {
		t.Fatal(err)
	}
	if f.logouts != 1 {
		t.Fatal("nothing to tell WhatsApp")
	}
}

func TestLogoutRemoteFailure(t *testing.T) {
	b, rec, f := connected(t)
	f.logoutErr = whatsmeow.ErrIQTimedOut
	err := b.Logout()
	mustCode(t, err, codeUnpairRemoteFailed)
	drain(t, b)
	if b.IsPaired() {
		t.Fatal("local wipe must happen anyway")
	}
	if st := rec.lastState(); st.State != stateUnpaired || st.Code != codeUnpairRemoteFailed {
		t.Fatalf("state %+v", st)
	}
	// Offline and WhatsApp unreachable: the same.
	b2, _, f2 := connected(t)
	b2.Disconnect()
	f2.mu.Lock()
	f2.loggedIn = false
	f2.mu.Unlock()
	mustCode(t, b2.Logout(), codeUnpairRemoteFailed)
}

func TestGetOrCreateConversation(t *testing.T) {
	b, _, f := connected(t)
	f.onWhatsApp = map[string]types.JID{"+15550002222": aliceJID}
	raw, err := b.GetOrCreateConversation([]byte(`["+1 555 000 2222"]`), "")
	if err != nil {
		t.Fatal(err)
	}
	var c conversationDTO
	_ = json.Unmarshal(raw, &c)
	if c.ID != aliceJID.String() || c.IsGroup {
		t.Fatalf("conversation %+v", c)
	}
	_, err = b.GetOrCreateConversation([]byte(`["+15559999999"]`), "")
	mustCode(t, err, codeNotOnWhatsApp)
	_, err = b.GetOrCreateConversation([]byte(`["+15550002222","+15550003333"]`), "")
	mustCode(t, err, codeUnsupported)
	_, err = b.GetOrCreateConversation([]byte(`["+15550002222"]`), "Club")
	mustCode(t, err, codeUnsupported)
	_, err = b.GetOrCreateConversation([]byte(`nope`), "")
	mustCode(t, err, codeInvalidArgument)
}

func TestMedia(t *testing.T) {
	b, _, f := connected(t)
	dir := t.TempDir()
	png := filepath.Join(dir, "p.png")
	// A 1x1 PNG.
	data := []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82")
	if err := os.WriteFile(png, data, 0o600); err != nil {
		t.Fatal(err)
	}
	out, err := b.SendMediaFile(aliceJID.String(), "tmp-m", png, "", "", "a caption", "")
	if err != nil {
		t.Fatal(err)
	}
	m := decodeMessage(t, out)
	if len(m.Parts) != 2 || m.Parts[0].Mime != "image/png" || m.Parts[0].Width != 1 || m.Text != "a caption" || m.TmpID != "tmp-m" {
		t.Fatalf("media message %+v", m)
	}
	im := f.sent[0].GetImageMessage()
	if im.GetCaption() != "a caption" || im.GetDirectPath() != "/v/t62/x" || im.GetFileLength() != uint64(len(data)) {
		t.Fatalf("wire image %v", im)
	}
	entries, _ := os.ReadDir(filepath.Join(b.dataDir, tmpDirName))
	if len(entries) != 0 {
		t.Fatalf("upload temp files left: %d", len(entries))
	}

	doc := filepath.Join(dir, "notes.bin")
	_ = os.WriteFile(doc, []byte("hello"), 0o600)
	out, err = b.SendMediaFile(aliceJID.String(), "tmp-d", doc, "Notes.txt", "text/plain", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if m := decodeMessage(t, out); m.Parts[0].FileName != "Notes.txt" || f.sent[1].GetDocumentMessage().GetFileName() != "Notes.txt" {
		t.Fatalf("document %+v", m)
	}

	// The token in the sent DTO downloads.
	f.download = []byte("plain bytes")
	got, err := b.DownloadMedia(m.Parts[0].MediaID, nil)
	if err != nil || !bytes.Equal(got, f.download) {
		t.Fatalf("download %q %v", got, err)
	}
	_, err = b.DownloadMedia("junk", nil)
	mustCode(t, err, codeInvalidArgument)

	_, err = b.SendMediaFile(aliceJID.String(), "t", filepath.Join(dir, "missing"), "", "", "", "")
	mustCode(t, err, codeInvalidArgument)
	big := filepath.Join(dir, "big")
	fh, _ := os.Create(big)
	_ = fh.Truncate(maxUploadSize + 1)
	_ = fh.Close()
	_, err = b.SendMediaFile(aliceJID.String(), "t", big, "", "", "", "")
	mustCode(t, err, codeTooLarge)
	if f.uploads != 2 {
		t.Fatalf("uploads %d", f.uploads)
	}
}

func TestMediaKind(t *testing.T) {
	cases := map[string]string{
		"image/jpeg": "image", "image/png": "image", "image/gif": "document", "video/mp4": "video",
		"audio/ogg; codecs=opus": "audio", "audio/mpeg": "audio", "application/pdf": "document", "": "document",
	}
	for in, want := range cases {
		if got := mediaKind(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestCallbackPanicIsContained(t *testing.T) {
	b, rec, _ := connected(t)
	rec.panicOn = kindMessage
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("x")}))
	// The conversation event after the panicking one still arrives.
	if len(rec.eventsOf(kindConversation)) == 0 {
		t.Fatal("dispatcher died with the callback")
	}
}

func TestCloseIsFinal(t *testing.T) {
	installFakeNet(t)
	b, _ := newTestBridge(t)
	b.Close()
	b.Close()
	mustCode(t, b.Connect(), codeInternal)
	_, err := b.ListConversations("inbox", 1)
	mustCode(t, err, codeInternal)
	if b.IsPaired() {
		t.Fatal("closed bridge is not paired")
	}
	if b.StatusJSON() == "" {
		t.Fatal("status must stay valid JSON")
	}
}

func TestReopenKeepsPairingAndIndex(t *testing.T) {
	installFakeNet(t)
	dir := t.TempDir()
	b, err := NewWABridge(dir, newRecorder())
	if err != nil {
		t.Fatal(err)
	}
	pair(t, b)
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	feed(t, b, msgEvent(aliceJID, aliceJID, false, "IN1", &waE2E.Message{Conversation: new("kept")}))
	b.Close()
	b2, err := NewWABridge(dir, newRecorder())
	if err != nil {
		t.Fatal(err)
	}
	defer b2.Close()
	if !b2.IsPaired() {
		t.Fatal("pairing lost on reopen")
	}
	raw, err := b2.FetchMessages(aliceJID.String(), 10, nil)
	if err != nil || !strings.Contains(string(raw), "kept") {
		t.Fatalf("messages lost on reopen: %s %v", raw, err)
	}
}
