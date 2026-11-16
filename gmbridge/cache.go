package gmbridge

import (
	"sync"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// maxIndexedMessages bounds the message → conversation index. It only has to
// cover messages the user can still see and react to or reply to.
const maxIndexedMessages = 4096

// stateCache holds what the phone told us that later calls need: the
// conversations (for DefaultOutgoingID, participants and type), the SIM cards
// from settings (for SIMPayload), our own participant IDs, and which
// conversation each recently seen message belongs to. The connector keeps the
// same things in its portal and login metadata.
type stateCache struct {
	mu       sync.RWMutex
	convs    map[string]*gmproto.Conversation
	sims     map[string]*gmproto.SIMCard // by SIMParticipant.ID
	selfIDs  map[string]bool
	msgIndex map[string]indexedMessage
	msgOrder []string // FIFO eviction order for msgIndex
	// isDefaultSMSApp is optional in the settings proto and missing from some
	// events, so the last value seen is kept (connector mergeSettings).
	isDefaultSMSApp bool
	// forceRCS: conversations whose latest protocol-switch tombstone says
	// they became end-to-end encrypted (connector PortalMetadata.ForceRCS).
	forceRCS map[string]bool
}

type indexedMessage struct {
	conversationID string
	msgType        int64
}

func newStateCache() *stateCache {
	c := &stateCache{}
	c.reset()
	return c
}

func (c *stateCache) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.convs = make(map[string]*gmproto.Conversation)
	c.sims = make(map[string]*gmproto.SIMCard)
	c.selfIDs = make(map[string]bool)
	c.msgIndex = make(map[string]indexedMessage)
	c.msgOrder = nil
	c.isDefaultSMSApp = false
	c.forceRCS = make(map[string]bool)
}

func (c *stateCache) putConversation(conv *gmproto.Conversation) {
	if conv.GetConversationID() == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.convs[conv.GetConversationID()] = conv
	for _, p := range conv.GetParticipants() {
		if p.GetIsMe() && p.GetID().GetParticipantID() != "" {
			c.selfIDs[p.GetID().GetParticipantID()] = true
		}
	}
}

func (c *stateCache) conversation(id string) *gmproto.Conversation {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.convs[id]
}

// applySettings records SIM cards and returns the merged default-SMS flag.
func (c *stateCache) applySettings(s *gmproto.Settings) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	sims := make(map[string]*gmproto.SIMCard, len(s.GetSIMCards()))
	for _, sim := range s.GetSIMCards() {
		id := sim.GetSIMParticipant().GetID()
		if id == "" {
			continue
		}
		sims[id] = sim
		c.selfIDs[id] = true
	}
	c.sims = sims
	if rcs := s.GetRCSSettings(); rcs != nil && rcs.IsDefaultSMSApp != nil {
		c.isDefaultSMSApp = rcs.GetIsDefaultSMSApp()
	}
	return c.isDefaultSMSApp
}

func (c *stateCache) isSelf(participantID string) bool {
	if participantID == "1" {
		return true
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.selfIDs[participantID]
}

// simPayloadFor picks the SIM to send from, like the connector's GetSIM:
// the SIM whose participant is the conversation's DefaultOutgoingID, then the
// SIM the conversation itself names, then the only SIM if there is just one.
func (c *stateCache) simPayloadFor(conv *gmproto.Conversation) *gmproto.SIMPayload {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if conv != nil {
		if sim := c.sims[conv.GetDefaultOutgoingID()]; sim != nil {
			return sim.GetSIMData().GetSIMPayload()
		}
		if p := conv.GetSimCard().GetSIMData().GetSIMPayload(); p != nil {
			return p
		}
	}
	if len(c.sims) == 1 {
		for _, sim := range c.sims {
			return sim.GetSIMData().GetSIMPayload()
		}
	}
	return nil
}

func (c *stateCache) indexMessage(msg *gmproto.Message) {
	id := msg.GetMessageID()
	if id == "" || msg.GetConversationID() == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.msgIndex[id]; !ok {
		c.msgOrder = append(c.msgOrder, id)
		if len(c.msgOrder) > maxIndexedMessages {
			delete(c.msgIndex, c.msgOrder[0])
			c.msgOrder = c.msgOrder[1:]
		}
	}
	c.msgIndex[id] = indexedMessage{conversationID: msg.GetConversationID(), msgType: msg.GetType()}
}

func (c *stateCache) message(id string) (indexedMessage, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	m, ok := c.msgIndex[id]
	return m, ok
}

// noteProtocolSwitch follows the connector's ConvertMessage: a tombstone
// saying the chat switched to end-to-end encryption (235/237) turns ForceRCS
// on for it, one saying it switched away (236/238) turns it off. Like the
// connector it looks only at these one-to-one tombstones, not the group
// variants. Other messages change nothing.
func (c *stateCache) noteProtocolSwitch(msg *gmproto.Message) {
	var force bool
	switch msg.GetMessageStatus().GetStatus() {
	case gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE:
		force = true
	case gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT:
		force = false
	default:
		return
	}
	id := msg.GetConversationID()
	if id == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if force {
		c.forceRCS[id] = true
	} else {
		delete(c.forceRCS, id)
	}
}

// shouldForceRCS reports what noteProtocolSwitch last recorded for the
// conversation (false if nothing was seen, as for a new connector portal).
func (c *stateCache) shouldForceRCS(conversationID string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.forceRCS[conversationID]
}
