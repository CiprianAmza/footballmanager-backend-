package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomContinueLifecycleService {
    private final GameRoomRepository rooms; private final GameRoomMemberRepository members; private final RoomContinueCycleRepository cycles; private final RoomContinueVoteRepository votes; private final GameCalendarRepository calendars;
    public RoomContinueLifecycleService(GameRoomRepository rooms, GameRoomMemberRepository members, RoomContinueCycleRepository cycles, RoomContinueVoteRepository votes, GameCalendarRepository calendars) { this.rooms = rooms; this.members = members; this.cycles = cycles; this.votes = votes; this.calendars = calendars; }
    private final Set<String> activeExecutions = ConcurrentHashMap.newKeySet();

    @Transactional
    public boolean beginExecution(AdvanceClaim claim) {
        String key = claim.cycleId() + ":" + claim.token();
        if (!activeExecutions.add(key)) return false;
        RoomContinueCycle snapshot = cycles.findById(claim.cycleId()).orElse(null); if (snapshot == null) { activeExecutions.remove(key); return false; }
        if (rooms.findByIdForUpdate(snapshot.getRoomId()).isEmpty()) { activeExecutions.remove(key); return false; }
        RoomContinueCycle current = cycles.findByIdForUpdate(claim.cycleId()).orElse(null);
        if (current == null || current.getStatus() != CycleStatus.ADVANCING || !claim.token().equals(current.getAdvanceToken())) { activeExecutions.remove(key); return false; }
        Instant now = Instant.now();
        if (current.getAdvanceExecutionStartedAt() != null && current.getAdvanceLeaseUntil() != null && now.isBefore(current.getAdvanceLeaseUntil())) { activeExecutions.remove(key); return false; }
        current.setAdvanceExecutionStartedAt(now); current.setAdvanceLeaseUntil(now.plusSeconds(30)); cycles.save(current); return true;
    }

    /** Refreshes the execution lease while a legitimate worker is still alive. */
    @Transactional
    public boolean heartbeat(AdvanceClaim claim) {
        RoomContinueCycle snapshot = cycles.findById(claim.cycleId()).orElse(null);
        if (snapshot == null) return false;
        return rooms.findByIdForUpdate(snapshot.getRoomId()).map(room -> {
            RoomContinueCycle current = cycles.findByIdForUpdate(claim.cycleId()).orElse(null);
            if (current == null || current.getStatus() != CycleStatus.ADVANCING
                    || !claim.token().equals(current.getAdvanceToken())
                    || current.getAdvanceExecutionStartedAt() == null) return false;
            current.setAdvanceLeaseUntil(Instant.now().plusSeconds(30));
            cycles.save(current);
            return true;
        }).orElse(false);
    }

    @Transactional
    public void completeAndOpen(AdvanceClaim claim, GameCalendar after) {
        RoomContinueCycle current = cycles.findById(claim.cycleId()).orElse(null); if (current == null) return;
        GameRoom room = rooms.findByIdForUpdate(current.getRoomId()).orElse(null); if (room == null) return;
        current = cycles.findByIdForUpdate(claim.cycleId()).orElse(null); if (current == null || current.getStatus() == CycleStatus.COMPLETED) return;
        if (current.getStatus() != CycleStatus.ADVANCING || !claim.token().equals(current.getAdvanceToken())) return;
        if (RoomDate.of(after).compareTo(new RoomDate(current.getSeason(), current.getGameDay())) < 0) throw new IllegalStateException("calendar moved backwards");
        current.setStatus(CycleStatus.COMPLETED); current.setCompletedAt(Instant.now()); current.setAdvanceLeaseUntil(null); current.setAdvanceExecutionStartedAt(null); cycles.save(current);
        RoomContinueCycle next = new RoomContinueCycle(); Instant now = Instant.now(); next.setRoomId(room.getId()); next.setSeason(after.getSeason()); next.setGameDay(after.getCurrentDay()); next.setOpenedAt(now); next.setDayDeadline(now.plusSeconds(room.getDayTimeoutSeconds())); next = cycles.save(next);
        for (GameRoomMember member : members.findActiveForUpdate(room.getId())) if (member.isFastForwardEnabled() && member.getFastForwardTargetSeason() != null && member.getFastForwardTargetDay() != null && RoomDate.of(after).compareTo(new RoomDate(member.getFastForwardTargetSeason(), member.getFastForwardTargetDay())) < 0) { RoomContinueVote vote = new RoomContinueVote(); vote.setCycleId(next.getId()); vote.setUserId(member.getUserId()); vote.setSource(VoteSource.FAST_FORWARD); vote.setVotedAt(now); votes.save(vote); }
        room.setBlockerCode(null); room.setBlockerMessage(null); rooms.save(room);
        activeExecutions.remove(claim.cycleId() + ":" + claim.token());
    }

    @Transactional public void markFailed(Long cycleId, String code) {
        RoomContinueCycle snapshot = cycles.findById(cycleId).orElse(null);
        if (snapshot == null) return;
        rooms.findByIdForUpdate(snapshot.getRoomId()).ifPresent(room -> cycles.findByIdForUpdate(cycleId).ifPresent(c -> {
            if (c.getStatus() == CycleStatus.ADVANCING) { c.setStatus(CycleStatus.FAILED); c.setFailureCode(code); c.setAdvanceLeaseUntil(null); c.setAdvanceExecutionStartedAt(null); cycles.save(c); }
            activeExecutions.remove(cycleId + ":" + c.getAdvanceToken());
        }));
    }
    @Transactional public void reopen(AdvanceClaim claim) {
        RoomContinueCycle snapshot = cycles.findById(claim.cycleId()).orElse(null);
        if (snapshot != null) rooms.findByIdForUpdate(snapshot.getRoomId()).ifPresent(room -> {
            RoomContinueCycle cycle = cycles.findByIdForUpdate(claim.cycleId()).orElse(null);
            if (cycle != null && cycle.getStatus() == CycleStatus.ADVANCING && claim.token().equals(cycle.getAdvanceToken())) {
                cycle.setStatus(CycleStatus.OPEN); cycle.setAdvanceToken(null); cycle.setAdvanceLeaseUntil(null); cycle.setAdvanceStartedAt(null); cycle.setAdvanceExecutionStartedAt(null); cycle.setAdvanceForceContinue(false); cycles.save(cycle);
            }
        });
        activeExecutions.remove(claim.cycleId() + ":" + claim.token());
    }
    @Transactional public void reopenWithBlocker(AdvanceClaim claim, String code, String message) {
        RoomContinueCycle snapshot = cycles.findById(claim.cycleId()).orElse(null);
        if (snapshot != null) rooms.findByIdForUpdate(snapshot.getRoomId()).ifPresent(room -> {
            RoomContinueCycle cycle = cycles.findByIdForUpdate(claim.cycleId()).orElse(null);
            if (cycle != null && cycle.getStatus() == CycleStatus.ADVANCING && claim.token().equals(cycle.getAdvanceToken())) {
                room.setBlockerCode(code); room.setBlockerMessage(message); rooms.save(room);
                cycle.setStatus(CycleStatus.BLOCKED); cycle.setAdvanceToken(null); cycle.setAdvanceLeaseUntil(null); cycle.setAdvanceStartedAt(null); cycle.setAdvanceExecutionStartedAt(null); cycle.setAdvanceForceContinue(false); cycles.save(cycle);
            }
        });
        activeExecutions.remove(claim.cycleId() + ":" + claim.token());
    }
}
