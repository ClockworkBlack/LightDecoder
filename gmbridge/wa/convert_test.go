package wa

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"go.mau.fi/whatsmeow"
	"strings"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

var testTime = time.UnixMilli(1_700_000_123_000)

func msgEvent(chat, sender types.JID, fromMe bool, id string, m *waE2E.Message) *events.Message {
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{Chat: chat, Sender: sender, IsFromMe: fromMe, IsGroup: chat.Server == types.GroupServer},
			ID:            id,
			PushName:      "Push",
			Timestamp:     testTime,
		},
		Message: m,
	}
}

func TestExtractContent(t *testing.T) {
	key := bytes.Repeat([]byte{1}, 32)
	thumb := []byte{0xff, 0xd8, 0xff}
	cases := []struct {
		name   string
		msg    *waE2E.Message
		text   string
		kinds  []string
		check  func(t *testing.T, c content)
		hidden bool
	}{
		{name: "plain", msg: &waE2E.Message{Conversation: new("hi")}, text: "hi", kinds: []string{partText}},
		{name: "extended reply", msg: &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: new("re"), ContextInfo: &waE2E.ContextInfo{StanzaID: new("ORIG")}}},
			text: "re", kinds: []string{partText},
			check: func(t *testing.T, c content) {
				if c.replyTo != "ORIG" {
					t.Errorf("replyTo %q", c.replyTo)
				}
			}},
		{name: "image with caption", msg: &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
			DirectPath: new("/img"), MediaKey: key, FileEncSHA256: []byte{2}, FileSHA256: []byte{3}, FileLength: new(uint64(99)),
			Mimetype: new("image/jpeg"), Width: new(uint32(640)), Height: new(uint32(480)), JPEGThumbnail: thumb, Caption: new("look")}},
			text: "look", kinds: []string{partMedia, partText},
			check: func(t *testing.T, c content) {
				p := c.parts[0]
				if p.Mime != "image/jpeg" || p.Size != 99 || p.Width != 640 || p.Height != 480 {
					t.Errorf("media part %+v", p)
				}
				if p.InlineData != base64.StdEncoding.EncodeToString(thumb) {
					t.Errorf("thumbnail not inline: %q", p.InlineData)
				}
				tok, _, err := decodeMediaToken(p.MediaID, nil)
				if err != nil || tok.DirectPath != "/img" || tok.Type != "image" || !bytes.Equal(tok.MediaKey, key) {
					t.Errorf("token %+v %v", tok, err)
				}
			}},
		{name: "document", msg: &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
			DirectPath: new("/doc"), MediaKey: key, Mimetype: new("application/pdf"), FileName: new("a.pdf")}},
			kinds: []string{partMedia},
			check: func(t *testing.T, c content) {
				if c.parts[0].FileName != "a.pdf" {
					t.Errorf("file name %q", c.parts[0].FileName)
				}
			}},
		{name: "voice", msg: &waE2E.Message{AudioMessage: &waE2E.AudioMessage{DirectPath: new("/a"), MediaKey: key, Mimetype: new("audio/ogg; codecs=opus"), PTT: new(true)}},
			kinds: []string{partMedia}},
		{name: "sticker", msg: &waE2E.Message{StickerMessage: &waE2E.StickerMessage{DirectPath: new("/s"), MediaKey: key}},
			kinds: []string{partMedia},
			check: func(t *testing.T, c content) {
				if c.parts[0].Mime != "image/webp" {
					t.Errorf("sticker mime %q", c.parts[0].Mime)
				}
			}},
		{name: "location", msg: &waE2E.Message{LocationMessage: &waE2E.LocationMessage{DegreesLatitude: new(52.5), DegreesLongitude: new(13.4), Name: new("Cafe")}},
			kinds: []string{partText},
			check: func(t *testing.T, c content) {
				if !strings.Contains(c.text, "Cafe") || !strings.Contains(c.text, "52.500000,13.400000") {
					t.Errorf("location text %q", c.text)
				}
			}},
		{name: "contact", msg: &waE2E.Message{ContactMessage: &waE2E.ContactMessage{DisplayName: new("Bob"), Vcard: new("BEGIN:VCARD")}},
			text: "Bob", kinds: []string{partMedia, partText}},
		{name: "poll", msg: &waE2E.Message{PollCreationMessageV3: &waE2E.PollCreationMessage{Name: new("Lunch?"),
			Options: []*waE2E.PollCreationMessage_Option{{OptionName: new("Yes")}, {OptionName: new("No")}}}},
			text: "Lunch?\n• Yes\n• No", kinds: []string{partText}},
		{name: "key distribution only", msg: &waE2E.Message{SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{}}, hidden: true},
		{name: "nil", msg: nil, hidden: true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, ok := extractContent(c.msg)
			if ok == c.hidden {
				t.Fatalf("ok = %v", ok)
			}
			if c.hidden {
				return
			}
			if c.text != "" && got.text != c.text {
				t.Errorf("text %q, want %q", got.text, c.text)
			}
			var kinds []string
			for _, p := range got.parts {
				kinds = append(kinds, p.Kind)
			}
			if strings.Join(kinds, ",") != strings.Join(c.kinds, ",") {
				t.Errorf("parts %v, want %v", kinds, c.kinds)
			}
			if c.check != nil {
				c.check(t, got)
			}
		})
	}
}

func TestClassifyIncoming(t *testing.T) {
	id := newFakeID()
	// A one-to-one chat addressed by LID becomes the phone-number chat.
	evt := msgEvent(aliceLID, aliceLID.ToNonAD(), false, "M1", &waE2E.Message{Conversation: new("hello")})
	a := classifyMessage(evt, id, false)
	if a.kind != actNew {
		t.Fatalf("kind %v", a.kind)
	}
	m := a.dto
	if m.ConversationID != aliceJID.String() || m.SenderID != aliceJID.String() || m.SenderName != "Alice" {
		t.Errorf("ids/name: %+v", m)
	}
	if a.rawChat != aliceLID {
		t.Errorf("raw chat %v", a.rawChat)
	}
	if m.Direction != directionIn || m.Status != "INCOMING_COMPLETE" || m.FromMe || m.IsOld {
		t.Errorf("direction/status: %+v", m)
	}
	if m.TimestampMs != testTime.UnixMilli() || m.Transport != transportWhatsApp {
		t.Errorf("ts/transport: %+v", m)
	}
	// JSON shape: empty slices, not null.
	raw := string(toJSON(m))
	if !strings.Contains(raw, `"reactions":[]`) || !strings.Contains(raw, `"parts":[{`) {
		t.Errorf("json %s", raw)
	}
	// Push name when the contact store has no name.
	evt = msgEvent(bobJID, bobJID, false, "M2", &waE2E.Message{Conversation: new("x")})
	if n := classifyMessage(evt, id, false).dto.SenderName; n != "Push" {
		t.Errorf("sender name %q", n)
	}
}

func TestClassifyOwnFromOtherDevice(t *testing.T) {
	ad := meJID
	ad.Device = 3
	evt := msgEvent(aliceJID, ad, true, "M3", &waE2E.Message{Conversation: new("sent from phone")})
	a := classifyMessage(evt, newFakeID(), false)
	if !a.dto.FromMe || a.dto.Direction != directionOut || a.dto.Status != "OUTGOING_COMPLETE" || a.dto.SenderID != meJID.String() {
		t.Errorf("own message: %+v", a.dto)
	}
}

func TestClassifyProtocol(t *testing.T) {
	id := newFakeID()
	key := &waCommon.MessageKey{ID: new("TARGET"), RemoteJID: new(aliceJID.String())}
	revoke := msgEvent(aliceJID, aliceJID, false, "R", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_REVOKE.Enum(), Key: key}})
	if a := classifyMessage(revoke, id, false); a.kind != actRevoke || a.targetID != "TARGET" {
		t.Errorf("revoke: %+v", a)
	}
	edit := msgEvent(aliceJID, aliceJID, false, "E", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(), Key: key, EditedMessage: &waE2E.Message{Conversation: new("fixed")}}})
	if a := classifyMessage(edit, id, false); a.kind != actEdit || a.targetID != "TARGET" || a.content.text != "fixed" {
		t.Errorf("edit: %+v", a)
	}
	react := msgEvent(groupJID, bobJID, false, "X", &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Key: key, Text: new("👍")}})
	if a := classifyMessage(react, id, false); a.kind != actReaction || a.reactor != bobJID.String() || a.emoji != "👍" || a.fromMe {
		t.Errorf("reaction: %+v", a)
	}
	other := msgEvent(aliceJID, aliceJID, false, "P", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_APP_STATE_SYNC_KEY_SHARE.Enum()}})
	if a := classifyMessage(other, id, false); a.kind != actIgnore {
		t.Errorf("key share must be ignored: %+v", a)
	}
	status := msgEvent(types.StatusBroadcastJID, aliceJID, false, "S", &waE2E.Message{Conversation: new("story")})
	if a := classifyMessage(status, id, false); a.kind != actIgnore {
		t.Errorf("status broadcast must be ignored: %+v", a)
	}
}

func TestReactions(t *testing.T) {
	m := messageDTO{}
	normalizeMessage(&m)
	applyReaction(&m, aliceJID.String(), "👍", false)
	applyReaction(&m, meJID.String(), "👍", true)
	applyReaction(&m, bobJID.String(), "😂", false)
	if len(m.Reactions) != 2 || len(m.Reactions[0].ParticipantIDs) != 2 || !m.Reactions[0].FromMe {
		t.Fatalf("reactions %+v", m.Reactions)
	}
	// Changing a reaction moves the participant; "" removes it.
	applyReaction(&m, aliceJID.String(), "😂", false)
	applyReaction(&m, meJID.String(), "", true)
	if len(m.Reactions) != 1 || m.Reactions[0].Emoji != "😂" || m.Reactions[0].FromMe || len(m.Reactions[0].ParticipantIDs) != 2 {
		t.Fatalf("after changes %+v", m.Reactions)
	}
	applyReaction(&m, aliceJID.String(), "", false)
	applyReaction(&m, bobJID.String(), "", false)
	if raw := string(toJSON(m)); !strings.Contains(raw, `"reactions":[]`) {
		t.Fatalf("empty reactions encode as %s", raw)
	}
}

func TestEditKeepsMedia(t *testing.T) {
	m := messageDTO{Text: "old", Parts: []partDTO{{PartID: partMedia, Kind: partMedia, MediaID: "wa1.x"}, {PartID: partText, Kind: partText, Text: "old"}}}
	applyEdit(&m, content{text: "new"})
	if m.Text != "new" || len(m.Parts) != 2 || m.Parts[0].MediaID != "wa1.x" || m.Parts[1].Text != "new" {
		t.Fatalf("edit: %+v", m)
	}
}

func TestDeletedMessage(t *testing.T) {
	base := messageDTO{ID: "A", ConversationID: "c", Text: "secret", Parts: []partDTO{{Kind: partText, Text: "secret"}}, FromMe: true}
	m := deletedMessage(&base, "A", "c", 0)
	if m.Direction != directionDeleted || m.Text != "" || len(m.Parts) != 0 || m.Status != "MESSAGE_DELETED" || !m.FromMe {
		t.Fatalf("deleted %+v", m)
	}
	m = deletedMessage(nil, "B", "c", 5)
	if m.ID != "B" || m.ConversationID != "c" || m.TimestampMs != 5 || m.Direction != directionDeleted {
		t.Fatalf("deleted without base %+v", m)
	}
}

func TestMergeNew(t *testing.T) {
	old := messageDTO{ID: "A", TmpID: "tmp", FromMe: true, Reactions: []reactionDTO{{Emoji: "👍", ParticipantIDs: []string{"x"}}}}
	setStatus(&old, statusRead)
	again := messageDTO{ID: "A", FromMe: true, IsOld: true}
	setStatus(&again, statusSent)
	m := mergeNew(old, again)
	if m.TmpID != "tmp" || m.Status != "OUTGOING_DISPLAYED" || len(m.Reactions) != 1 || m.IsOld {
		t.Fatalf("merge %+v", m)
	}
	gone := deletedMessage(&old, "A", "", 0)
	if mergeNew(gone, again).Direction != directionDeleted {
		t.Fatal("a deletion must stick")
	}
}

func TestHistoryExtras(t *testing.T) {
	id := newFakeID()
	web := &waWeb.WebMessageInfo{
		Key:    &waCommon.MessageKey{ID: new("H1"), FromMe: new(true), RemoteJID: new(aliceJID.String())},
		Status: waWeb.WebMessageInfo_READ.Enum(),
		Reactions: []*waWeb.Reaction{
			{Key: &waCommon.MessageKey{FromMe: new(false), RemoteJID: new(aliceJID.String())}, Text: new("❤️")},
		},
	}
	evt := msgEvent(aliceJID, meJID, true, "H1", &waE2E.Message{Conversation: new("old one")})
	evt.SourceWebMsg = web
	a := historyAction(evt, web, id)
	if a.kind != actNew || !a.dto.IsOld || a.dto.Status != "OUTGOING_DISPLAYED" {
		t.Fatalf("history message %+v", a.dto)
	}
	if len(a.dto.Reactions) != 1 || a.dto.Reactions[0].ParticipantIDs[0] != aliceJID.String() {
		t.Fatalf("history reactions %+v", a.dto.Reactions)
	}
	web.MessageStubType = waWeb.WebMessageInfo_REVOKE.Enum()
	if a := historyAction(evt, web, id); a.kind != actRevoke || a.targetID != "H1" {
		t.Fatalf("revoked stub %+v", a)
	}
}

func TestConversationOf(t *testing.T) {
	id := newFakeID()
	now := time.Now()
	r := chatRow{JID: aliceJID.String(), LastTs: 5, LastID: "M", Snippet: "s", Unread: true, MuteEnd: -1, Archived: true}
	c := conversationOf(r, id, now)
	if c.Name != "Alice" || c.Folder != folderArchive || !c.Muted || !c.Unread || c.Type != transportWhatsApp {
		t.Errorf("one-to-one %+v", c)
	}
	if len(c.Participants) != 2 || !c.Participants[1].IsMe || c.Participants[0].Number != "+15550002222" ||
		len(c.OtherParticipantIDs) != 1 || c.OtherParticipantIDs[0] != aliceJID.String() {
		t.Errorf("participants %+v / %v", c.Participants, c.OtherParticipantIDs)
	}
	g := chatRow{JID: groupJID.String(), IsGroup: true, Name: "Team", Participants: []string{meJID.String(), bobJID.String()}, MuteEnd: now.Add(-time.Hour).UnixMilli()}
	c = conversationOf(g, id, now)
	if !c.IsGroup || c.Name != "Team" || c.Muted || c.Folder != folderInbox || len(c.OtherParticipantIDs) != 1 || c.Participants[1].Name != "+15550003333" {
		t.Errorf("group %+v", c)
	}
	var back map[string]any
	if err := json.Unmarshal(toJSON(c), &back); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"id", "name", "isGroup", "type", "folder", "unread", "pinned", "muted", "lastMessageTs", "participants", "otherParticipantIds"} {
		if _, ok := back[k]; !ok {
			t.Errorf("conversation JSON lacks %q", k)
		}
	}
}

func TestOnlyOwnRecentTextCanBeEdited(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)
	text := []partDTO{{PartID: partText, Kind: partText, Text: "hi"}}
	fresh := now.Add(-time.Minute).UnixMilli()
	cases := []struct {
		name string
		m    messageDTO
		want bool
	}{
		{"own recent text", messageDTO{FromMe: true, Text: "hi", Parts: text, TimestampMs: fresh}, true},
		{"someone else's", messageDTO{Text: "hi", Parts: text, TimestampMs: fresh}, false},
		{"too old", messageDTO{FromMe: true, Text: "hi", Parts: text, TimestampMs: now.Add(-whatsmeow.EditWindow).UnixMilli()}, false},
		{"with a picture", messageDTO{FromMe: true, Text: "hi", Parts: append([]partDTO{{PartID: "m", Kind: partMedia}}, text...), TimestampMs: fresh}, false},
		{"no text", messageDTO{FromMe: true, TimestampMs: fresh}, false},
	}
	for _, c := range cases {
		if got := editable(c.m, now); got != c.want {
			t.Errorf("%s: editable = %v, want %v", c.name, got, c.want)
		}
	}
}
