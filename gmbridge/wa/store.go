package wa

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sync"
	"time"
)

// localStore is Go's own chat index and message cache, in the same SQLite
// file as whatsmeow's device store (so Logout wipes both at once). WhatsApp
// has no "list my chats" or "page through history" request: the phone pushes
// history once (history sync) and live messages as they come, so Go keeps
// what it was given for ListConversations and FetchMessages. Messages are
// kept as their MessageDTO JSON, the exact shape Kotlin parses.
type localStore struct {
	db *sql.DB
	// mu serialises read-modify-write of rows (reactions, edits, receipts
	// arrive on the dispatcher while calls update the same rows).
	mu sync.Mutex
}

const localSchema = `
CREATE TABLE IF NOT EXISTS lightdecoder_chat (
	jid             TEXT PRIMARY KEY,
	name            TEXT NOT NULL DEFAULT '',
	is_group        INTEGER NOT NULL DEFAULT 0,
	last_ts         INTEGER NOT NULL DEFAULT 0,
	last_id         TEXT NOT NULL DEFAULT '',
	snippet         TEXT NOT NULL DEFAULT '',
	snippet_from_me INTEGER NOT NULL DEFAULT 0,
	snippet_sender  TEXT NOT NULL DEFAULT '',
	unread          INTEGER NOT NULL DEFAULT 0,
	archived        INTEGER NOT NULL DEFAULT 0,
	mute_end        INTEGER NOT NULL DEFAULT 0,
	pinned          INTEGER NOT NULL DEFAULT 0,
	read_only       INTEGER NOT NULL DEFAULT 0,
	participants    TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS lightdecoder_chat_last ON lightdecoder_chat (archived, last_ts);
CREATE TABLE IF NOT EXISTS lightdecoder_message (
	chat     TEXT NOT NULL,
	id       TEXT NOT NULL,
	ts       INTEGER NOT NULL,
	from_me  INTEGER NOT NULL DEFAULT 0,
	sender   TEXT NOT NULL DEFAULT '',
	raw_chat TEXT NOT NULL DEFAULT '',
	dto      TEXT NOT NULL,
	PRIMARY KEY (chat, id)
);
CREATE INDEX IF NOT EXISTS lightdecoder_message_page ON lightdecoder_message (chat, ts, id);
`

// storeTimeout bounds one local query.
const storeTimeout = 30 * time.Second

func openLocalStore(db *sql.DB) (*localStore, error) {
	ctx, cancel := context.WithTimeout(context.Background(), storeTimeout)
	defer cancel()
	if _, err := db.ExecContext(ctx, localSchema); err != nil {
		return nil, err
	}
	return &localStore{db: db}, nil
}

func dbCtx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), storeTimeout)
}

// chatRow is one chat of the index.
type chatRow struct {
	JID           string
	Name          string
	IsGroup       bool
	LastTs        int64
	LastID        string
	Snippet       string
	SnippetFromMe bool
	SnippetSender string
	Unread        bool
	Archived      bool
	// MuteEnd is epoch ms; -1 means muted forever, 0 not muted.
	MuteEnd      int64
	Pinned       bool
	ReadOnly     bool
	Participants []string
}

func (r chatRow) muted(now time.Time) bool {
	return r.MuteEnd == -1 || r.MuteEnd > now.UnixMilli()
}

const chatColumns = `jid, name, is_group, last_ts, last_id, snippet, snippet_from_me, snippet_sender,
	unread, archived, mute_end, pinned, read_only, participants`

type scanner interface{ Scan(dest ...any) error }

func scanChat(s scanner) (chatRow, error) {
	var r chatRow
	var parts string
	err := s.Scan(&r.JID, &r.Name, &r.IsGroup, &r.LastTs, &r.LastID, &r.Snippet, &r.SnippetFromMe, &r.SnippetSender,
		&r.Unread, &r.Archived, &r.MuteEnd, &r.Pinned, &r.ReadOnly, &parts)
	if err != nil {
		return r, err
	}
	_ = json.Unmarshal([]byte(parts), &r.Participants)
	return r, nil
}

func (s *localStore) getChat(jid string) (chatRow, bool, error) {
	ctx, cancel := dbCtx()
	defer cancel()
	r, err := scanChat(s.db.QueryRowContext(ctx, `SELECT `+chatColumns+` FROM lightdecoder_chat WHERE jid = ?`, jid))
	if errors.Is(err, sql.ErrNoRows) {
		return chatRow{JID: jid}, false, nil
	}
	return r, err == nil, err
}

func (s *localStore) putChat(r chatRow) error {
	if r.Participants == nil {
		r.Participants = []string{}
	}
	parts, _ := json.Marshal(r.Participants)
	ctx, cancel := dbCtx()
	defer cancel()
	_, err := s.db.ExecContext(ctx, `INSERT OR REPLACE INTO lightdecoder_chat (`+chatColumns+`)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		r.JID, r.Name, r.IsGroup, r.LastTs, r.LastID, r.Snippet, r.SnippetFromMe, r.SnippetSender,
		r.Unread, r.Archived, r.MuteEnd, r.Pinned, r.ReadOnly, string(parts))
	return err
}

// updateChat applies fn to the chat's row (a new row if there is none) and
// stores the result. fn returns false to leave the row unchanged.
func (s *localStore) updateChat(jid string, fn func(r *chatRow, existed bool) bool) (chatRow, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, existed, err := s.getChat(jid)
	if err != nil {
		return r, false, err
	}
	if !fn(&r, existed) {
		return r, false, nil
	}
	return r, true, s.putChat(r)
}

// listChats returns the chats of one folder, the most recent first.
func (s *localStore) listChats(archived bool, limit int) ([]chatRow, error) {
	ctx, cancel := dbCtx()
	defer cancel()
	rows, err := s.db.QueryContext(ctx, `SELECT `+chatColumns+` FROM lightdecoder_chat
		WHERE archived = ? ORDER BY pinned DESC, last_ts DESC, jid LIMIT ?`, archived, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []chatRow
	for rows.Next() {
		r, err := scanChat(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// --- messages ------------------------------------------------------------------

// msgRef is what history requests, replies and app-state patches need to
// know about a stored message.
type msgRef struct {
	ID      string
	TsMs    int64
	FromMe  bool
	Sender  string
	RawChat string
}

func (s *localStore) getMessage(chat, id string) (messageDTO, msgRef, bool, error) {
	ctx, cancel := dbCtx()
	defer cancel()
	var raw string
	var ref msgRef
	err := s.db.QueryRowContext(ctx, `SELECT dto, id, ts, from_me, sender, raw_chat FROM lightdecoder_message
		WHERE chat = ? AND id = ?`, chat, id).Scan(&raw, &ref.ID, &ref.TsMs, &ref.FromMe, &ref.Sender, &ref.RawChat)
	if errors.Is(err, sql.ErrNoRows) {
		return messageDTO{}, ref, false, nil
	}
	if err != nil {
		return messageDTO{}, ref, false, err
	}
	var m messageDTO
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return messageDTO{}, ref, false, err
	}
	normalizeMessage(&m)
	return m, ref, true, nil
}

func (s *localStore) putMessage(m messageDTO, rawChat string) error {
	normalizeMessage(&m)
	ctx, cancel := dbCtx()
	defer cancel()
	_, err := s.db.ExecContext(ctx, `INSERT OR REPLACE INTO lightdecoder_message (chat, id, ts, from_me, sender, raw_chat, dto)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, m.ConversationID, m.ID, m.TimestampMs, m.FromMe, m.SenderID, rawChat, string(toJSON(m)))
	return err
}

// updateMessage applies fn to a stored message (exists=false and a zero
// DTO when it is not stored) and stores the result. fn returns false to leave
// the store unchanged. rawChat is kept from the stored row unless given.
func (s *localStore) updateMessage(chat, id, rawChat string, fn func(m *messageDTO, exists bool) bool) (messageDTO, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ref, exists, err := s.getMessage(chat, id)
	if err != nil {
		return m, false, err
	}
	if !fn(&m, exists) {
		return m, false, nil
	}
	if rawChat == "" {
		rawChat = ref.RawChat
	}
	return m, true, s.putMessage(m, rawChat)
}

func (s *localStore) deleteMessage(chat, id string) error {
	ctx, cancel := dbCtx()
	defer cancel()
	_, err := s.db.ExecContext(ctx, `DELETE FROM lightdecoder_message WHERE chat = ? AND id = ?`, chat, id)
	return err
}

// page returns up to count messages of chat older than cursor (the newest
// ones when cursor is nil), oldest first.
func (s *localStore) page(chat string, cursor *cursorDTO, count int) ([]messageDTO, error) {
	ctx, cancel := dbCtx()
	defer cancel()
	var rows *sql.Rows
	var err error
	if cursor == nil {
		rows, err = s.db.QueryContext(ctx, `SELECT dto FROM lightdecoder_message WHERE chat = ?
			ORDER BY ts DESC, id DESC LIMIT ?`, chat, count)
	} else {
		rows, err = s.db.QueryContext(ctx, `SELECT dto FROM lightdecoder_message WHERE chat = ?
			AND (ts < ? OR (ts = ? AND id < ?)) ORDER BY ts DESC, id DESC LIMIT ?`,
			chat, cursor.LastItemTimestampMs, cursor.LastItemTimestampMs, cursor.LastItemID, count)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []messageDTO
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var m messageDTO
		if err := json.Unmarshal([]byte(raw), &m); err != nil {
			continue
		}
		normalizeMessage(&m)
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, nil
}

func (s *localStore) countMessages(chat string) (int64, error) {
	ctx, cancel := dbCtx()
	defer cancel()
	var n int64
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM lightdecoder_message WHERE chat = ?`, chat).Scan(&n)
	return n, err
}

// edge returns the oldest (or newest) stored message of chat.
func (s *localStore) edge(chat string, newest bool) (msgRef, bool, error) {
	order := "ASC"
	if newest {
		order = "DESC"
	}
	ctx, cancel := dbCtx()
	defer cancel()
	var ref msgRef
	err := s.db.QueryRowContext(ctx, `SELECT id, ts, from_me, sender, raw_chat FROM lightdecoder_message
		WHERE chat = ? ORDER BY ts `+order+`, id `+order+` LIMIT 1`, chat).
		Scan(&ref.ID, &ref.TsMs, &ref.FromMe, &ref.Sender, &ref.RawChat)
	if errors.Is(err, sql.ErrNoRows) {
		return ref, false, nil
	}
	return ref, err == nil, err
}
