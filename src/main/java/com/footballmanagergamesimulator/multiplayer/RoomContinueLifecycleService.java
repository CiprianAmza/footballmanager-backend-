package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoomContinueLifecycleService {
    private final GameRoomRepository rooms; private final GameRoomMemberRepository members; private final RoomContinueCycleRepository cycles; private final RoomContinueVoteRepository votes; private final GameCalendarRepository calendars;
    public RoomContinueLifecycleService(GameRoomRepository rooms, GameRoomMemberRepository members, RoomContinueCycleRepository cycles, RoomContinueVoteRepository votes, GameCalendarRepository calendars) { this.rooms = rooms; this.members = members; this.cycles = cycles; this.votes = votes; this.calendars = calendars; }

    @Transactional
    public boolean beginExecution(AdvanceClaim claim) {
        RoomContinueCycle snapshot = cycles.findById(claim.cycleId()).orElse(null); if (snapshot == null) return false;
        if (rooms.findByIdForUpdate(snapshot.getRoomId()).isEmpty()) return false;
        RoomContinueCycle current = cycles.findByIdForUpdate(claim.cycleId()).orElse(null);
        if (current == null || current.getStatus() != CycleStatus.ADVANCING || !claim.token().equals(current.getAdvanceToken())) return false;
        Instant now = Instant.now();
        if (current.getAdvanceExecutionStartedAt() != null && current.getAdvanceLeaseUntil() != null && now.isBefore(current.getAdvanceLeaseUntil())) return false;
        current.setAdvanceExecutionStartedAt(now); current.setAdvanceLeaseUntil(now.plusSeconds(30)); cycles.save(current); return true;
    }

    @Transactional
    public void completeAndOpen(AdvanceClaim claim, GameCalendar before, GameCalendar after) {
        RoomContinueCycle current = cycles.findById(claim.cycleId()).orElse(null); if (current == null) return;
        GameRoom room = rooms.findByIdForUpdate(current.getRoomId()).orElse(null); if (room == null) return;
        current = cycles.findByIdForUpdate(claim.cycleId()).orElse(null); if (current == null || current.getStatus() == CycleStatus.COMPLETED) return;
        if (current.getStatus() != CycleStatus.ADVANCING || !claim.token().equals(current.getAdvanceToken())) return;
        if (RoomContinueCoordinator.absolute(after) < RoomContinueCoordinator.absolute(before)) throw new IllegalStateException("calendar moved backwards");
        current.setStatus(CycleStatus.COMPLETED); current.setCompletedAt(Instant.now()); current.setAdvanceLeaseUntil(null); current.setAdvanceExecutionStartedAt(null); cycles.save(current);
        RoomContinueCycle next = new RoomContinueCycle(); Instant now = Instant.now(); next.setRoomId(room.getId()); next.setSeason(after.getSeason()); next.setGameDay(after.getCurrentDay()); next.setOpenedAt(now); next.setDayDeadline(now.plusSeconds(room.getDayTimeoutSeconds())); next = cycles.save(next);
        for (GameRoomMember member : members.findActiveForUpdate(room.getId())) if (member.isFastForwardEnabled() && member.getFastForwardUntilAbsoluteDay() != null && RoomContinueCoordinator.absolute(after) < member.getFastForwardUntilAbsoluteDay()) { RoomContinueVote vote = new RoomContinueVote(); vote.setCycleId(next.getId()); vote.setUserId(member.getUserId()); vote.setSource(VoteSource.FAST_FORWARD); vote.setVotedAt(now); votes.save(vote); }
    }

    @Transactional public void markFailed(Long cycleId, String code) { cycles.findByIdForUpdate(cycleId).ifPresent(c -> { if (c.getStatus() == CycleStatus.ADVANCING) { c.setStatus(CycleStatus.FAILED); c.setFailureCode(code); c.setAdvanceLeaseUntil(null); c.setAdvanceExecutionStartedAt(null); cycles.save(c); } }); }
    @Transactional public void reopen(AdvanceClaim claim) { cycles.findByIdForUpdate(claim.cycleId()).ifPresent(c -> { if (c.getStatus() == CycleStatus.ADVANCING && claim.token().equals(c.getAdvanceToken())) { c.setStatus(CycleStatus.OPEN); c.setAdvanceToken(null); c.setAdvanceLeaseUntil(null); c.setAdvanceStartedAt(null); cycles.save(c); } }); }
}
