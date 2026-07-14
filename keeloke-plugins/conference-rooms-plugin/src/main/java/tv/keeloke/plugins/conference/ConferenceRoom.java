package tv.keeloke.plugins.conference;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A multi-participant conference room: the signaling/coordination state for a
 * many-to-many WebRTC session. This is the part a room-based conferencing
 * feature needs on the server - who is in the room, their role, capacity - and
 * it's what lets clients discover each other's stream ids to subscribe to.
 *
 * The actual media forwarding (each participant's WebRTC track fanned out to
 * the others) is done by the server's WebRTC engine; horizontal scale to large
 * audiences is done by distributing subscribers across edge nodes (see the
 * cluster plugin and SFU_SCALING.md). This class owns the room membership
 * model, with all mutation returning a clear outcome so callers/tests can
 * assert behavior.
 */
public class ConferenceRoom implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Role { PUBLISHER, SUBSCRIBER }

    public enum JoinOutcome { JOINED, ROOM_FULL, ALREADY_IN_ROOM }

    private String roomId;
    private int maxPublishers;
    private int maxParticipants;
    private long createdAtEpochMs;

    /** participantId -> role */
    private Map<String, Role> participants = new LinkedHashMap<>();

    public ConferenceRoom() {
    }

    public ConferenceRoom(String roomId, int maxPublishers, int maxParticipants, long createdAtEpochMs) {
        this.roomId = roomId;
        this.maxPublishers = maxPublishers;
        this.maxParticipants = maxParticipants;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public JoinOutcome join(String participantId, Role role) {
        if (participants.containsKey(participantId)) {
            return JoinOutcome.ALREADY_IN_ROOM;
        }
        if (participants.size() >= maxParticipants) {
            return JoinOutcome.ROOM_FULL;
        }
        if (role == Role.PUBLISHER && publisherCount() >= maxPublishers) {
            return JoinOutcome.ROOM_FULL;
        }
        participants.put(participantId, role);
        return JoinOutcome.JOINED;
    }

    public boolean leave(String participantId) {
        return participants.remove(participantId) != null;
    }

    public int publisherCount() {
        return (int) participants.values().stream().filter(r -> r == Role.PUBLISHER).count();
    }

    public int participantCount() {
        return participants.size();
    }

    public boolean isEmpty() {
        return participants.isEmpty();
    }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public int getMaxPublishers() { return maxPublishers; }
    public void setMaxPublishers(int maxPublishers) { this.maxPublishers = maxPublishers; }

    public int getMaxParticipants() { return maxParticipants; }
    public void setMaxParticipants(int maxParticipants) { this.maxParticipants = maxParticipants; }

    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public void setCreatedAtEpochMs(long createdAtEpochMs) { this.createdAtEpochMs = createdAtEpochMs; }

    public Map<String, Role> getParticipants() { return participants; }
    public void setParticipants(Map<String, Role> participants) { this.participants = participants; }
}
