package wa

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "github.com/mattn/go-sqlite3"
)

func openTestStore(t *testing.T) *localStore {
	t.Helper()
	db, err := sql.Open("sqlite3", "file:"+filepath.Join(t.TempDir(), "t.db")+"?_journal_mode=WAL")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	s, err := openLocalStore(db)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// Messages of the same millisecond page by id, so no message is skipped or
// repeated across page boundaries.
func TestPageSameMillisecond(t *testing.T) {
	s := openTestStore(t)
	for _, id := range []string{"A", "B", "C", "D", "E"} {
		if err := s.putMessage(messageDTO{ID: id, ConversationID: "c", TimestampMs: 1000}, "c"); err != nil {
			t.Fatal(err)
		}
	}
	_ = s.putMessage(messageDTO{ID: "Z", ConversationID: "other", TimestampMs: 5000}, "other")
	var seen []string
	var cur *cursorDTO
	for i := 0; i < 5; i++ {
		page, err := s.page("c", cur, 2)
		if err != nil {
			t.Fatal(err)
		}
		if len(page) == 0 {
			break
		}
		for j := len(page) - 1; j >= 0; j-- {
			seen = append(seen, page[j].ID)
		}
		cur = &cursorDTO{LastItemID: page[0].ID, LastItemTimestampMs: page[0].TimestampMs}
	}
	if got := len(seen); got != 5 || seen[0] != "E" || seen[4] != "A" {
		t.Fatalf("paged %v", seen)
	}
	if n, _ := s.countMessages("c"); n != 5 {
		t.Fatalf("count %d", n)
	}
	oldest, ok, _ := s.edge("c", false)
	newest, _, _ := s.edge("c", true)
	if !ok || oldest.ID != "A" || newest.ID != "E" || oldest.RawChat != "c" {
		t.Fatalf("edges %+v %+v", oldest, newest)
	}
	if _, ok, _ := s.edge("none", false); ok {
		t.Fatal("edge of an empty chat")
	}
}

func TestChatListOrder(t *testing.T) {
	s := openTestStore(t)
	_ = s.putChat(chatRow{JID: "a", LastTs: 10})
	_ = s.putChat(chatRow{JID: "b", LastTs: 30})
	_ = s.putChat(chatRow{JID: "c", LastTs: 20, Pinned: true})
	_ = s.putChat(chatRow{JID: "d", LastTs: 40, Archived: true, Participants: []string{"x", "y"}})
	rows, err := s.listChats(false, 10)
	if err != nil {
		t.Fatal(err)
	}
	var ids string
	for _, r := range rows {
		ids += r.JID
	}
	if ids != "cba" {
		t.Fatalf("order %q", ids)
	}
	rows, _ = s.listChats(true, 10)
	if len(rows) != 1 || len(rows[0].Participants) != 2 {
		t.Fatalf("archive %+v", rows)
	}
	if rows, _ := s.listChats(false, 1); len(rows) != 1 {
		t.Fatal("limit ignored")
	}
}
