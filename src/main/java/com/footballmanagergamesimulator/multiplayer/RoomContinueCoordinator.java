package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoomContinueCoordinator {
    private static final long LEASE_SECONDS = 30;
    private final MultiplayerRoomService rooms;
    private final RoomAdvanceService advance;
    private final GameCalendarRepository calendars;
    private final RoomContinueLifecycleService lifecycle;

    public RoomContinueCoordinator(MultiplayerRoomService rooms, RoomAdvanceService advance,
                                   GameCalendarRepository calendars, RoomContinueLifecycleService lifecycle) {
        this.rooms = rooms; this.advance = advance; this.calendars = calendars; this.lifecycle = lifecycle;
    }

    /** Locks room -> cycle -> member/vote and records an idempotent vote. */
    @Transactional
    public AdvanceClaim cast(int userId, VoteSource source) {
        GameRoom room = rooms.lockMemberRoom(userId);
        if (room.getStatus() != RoomStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_NOT_ACTIVE");
        GameRoomMember member = rooms.memberForUpdate(room, userId);
        RoomContinueCycle cycle = rooms.currentCycleForUpdate(room);
        if (cycle == null) return null;
        RoomContinueVote existing = rooms.votes().findForUpdate(cycle.getId(), userId).orElse(null);
        if (existing == null) {
            RoomContinueVote vote = new RoomContinueVote(); vote.setCycleId(cycle.getId()); vote.setUserId(member.getUserId()); vote.setSource(source); vote.setVotedAt(Instant.now()); rooms.votes().saveAndFlush(vote);
        }
        return claimIfReadyLocked(room, cycle);
    }

    @Transactional
    public boolean withdraw(int userId) {
        GameRoom room = rooms.lockMemberRoom(userId);
        if (room.getStatus() != RoomStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_NOT_ACTIVE");
        rooms.memberForUpdate(room, userId);
        RoomContinueCycle cycle = rooms.currentCycleForUpdate(room);
        if (cycle == null) return false;
        if (cycle.getMajorityDeadline() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTINUE_WINDOW_LOCKED");
        RoomContinueVote vote = rooms.votes().findForUpdate(cycle.getId(), userId).orElse(null);
        if (vote == null) return false;
        rooms.votes().delete(vote); return true;
    }

    /** Claims an immediately-ready or expired cycle. No browser/session is needed. */
    @Transactional
    public AdvanceClaim claimExpired() {
        GameRoom room = rooms.openRoomForUpdate();
        if (room.getStatus() != RoomStatus.ACTIVE) return null;
        RoomContinueCycle cycle = rooms.currentCycleForUpdate(room);
        if (cycle == null) return null;
        return claimIfReadyLocked(room, cycle);
    }

    /** Recovers exactly one expired lease; it never selects an unrelated ADVANCING cycle. */
    @Transactional
    public AdvanceClaim recoverExpired() {
        GameRoom room = rooms.openRoomForUpdate();
        if (room.getStatus() != RoomStatus.ACTIVE) return null;
        RoomContinueCycle cycle = rooms.cycles().findAdvancingForUpdate(room.getId()).orElse(null);
        if (cycle == null || cycle.getAdvanceLeaseUntil() == null || Instant.now().isBefore(cycle.getAdvanceLeaseUntil())) return null;
        return new AdvanceClaim(cycle.getId(), cycle.getAdvanceToken(), cycle.isAdvanceForceContinue());
    }

    /** Executes only the persisted claim token, outside the database transaction. */
    public void advanceClaimed(AdvanceClaim claim) {
        if (claim == null || claim.cycleId() == null || claim.token() == null) return;
        RoomContinueCycle cycle = rooms.cycles().findById(claim.cycleId()).orElse(null);
        if (cycle == null || cycle.getStatus() != CycleStatus.ADVANCING || !claim.token().equals(cycle.getAdvanceToken())) return;
        if (!lifecycle.beginExecution(claim)) return;
        GameRoom room = rooms.rooms().findById(cycle.getRoomId()).orElse(null);
        if (room == null || room.getStatus() != RoomStatus.ACTIVE) return;
        List<GameRoomMember> members = rooms.members(room);
        boolean rapidClaim = "RAPID".equals(cycle.getAdvanceMode());
        if (rapidClaim && !allFastForward(members)) { lifecycle.reopen(claim); return; }
        try {
            Set<Integer> userIds = members.stream().map(GameRoomMember::getUserId).collect(Collectors.toSet());
            RoomAdvanceResult result = advance.advanceOneDay(cycle.getSeason(), cycle.getGameDay(), userIds,
                    claim.forceContinue() || rapidClaim);
            if (result.status() == RoomAdvanceResult.Status.BLOCKED) {
                lifecycle.reopenWithBlocker(claim, result.blockerCode(), result.blockerMessage());
            } else {
                GameCalendar after = calendars.findTopByOrderBySeasonDesc().orElseThrow();
                lifecycle.completeAndOpen(claim, after);
            }
        } catch (RuntimeException e) {
            lifecycle.markFailed(claim.cycleId(), e.getClass().getSimpleName());
        }
    }

    /** Used by the HTTP preference endpoint after it has upserted a FAST_FORWARD vote. */
    @Transactional
    public AdvanceClaim tryClaim(int userId) { return claimForUser(userId); }

    @Transactional
    public AdvanceClaim claimForUser(int userId) {
        GameRoom room = rooms.lockMemberRoom(userId); if (room.getStatus() != RoomStatus.ACTIVE) return null;
        rooms.memberForUpdate(room, userId); RoomContinueCycle cycle = rooms.currentCycleForUpdate(room); return cycle == null ? null : claimIfReadyLocked(room, cycle);
    }

    private AdvanceClaim claimIfReadyLocked(GameRoom room, RoomContinueCycle cycle) {
        return claimIfReadyLocked(room, cycle, false);
    }

    private AdvanceClaim claimIfReadyLocked(GameRoom room, RoomContinueCycle cycle, boolean rapidWorker) {
        List<GameRoomMember> members = rooms.membersRepository().findActiveForUpdate(room.getId());
        expireReachedTargets(members, cycle);
        long votes = rooms.votes().countByCycleId(cycle.getId());
        boolean all = !members.isEmpty() && votes == members.size();
        boolean rapid = allFastForward(members) && !targetReached(members);
        if (rapid && !rapidWorker) return null;
        boolean deadline = !Instant.now().isBefore(cycle.getDayDeadline()) || (cycle.getMajorityDeadline() != null && !Instant.now().isBefore(cycle.getMajorityDeadline()));
        if (!all && !rapid) {
            if (votes >= requiredVotes(room, members.size()) && cycle.getMajorityDeadline() == null && !deadline) {
                Instant now = Instant.now(); cycle.setMajorityReachedAt(now); cycle.setMajorityDeadline(now.plusSeconds(room.getMajorityTimeoutSeconds())); rooms.cycles().save(cycle); return null;
            }
            if (votes < requiredVotes(room, members.size()) && !deadline) return null;
        }
        if (cycle.getStatus() != CycleStatus.OPEN) return null;
        boolean forceContinue = rapid || (deadline && room.isForceContinue()); String token = UUID.randomUUID().toString(); cycle.setStatus(CycleStatus.ADVANCING); cycle.setAdvanceStartedAt(Instant.now()); cycle.setAdvanceToken(token); cycle.setAdvanceLeaseUntil(Instant.now().plusSeconds(LEASE_SECONDS)); cycle.setAdvanceMode(rapid ? "RAPID" : "NORMAL"); cycle.setAdvanceForceContinue(forceContinue); rooms.cycles().saveAndFlush(cycle); return new AdvanceClaim(cycle.getId(), token, forceContinue);
    }

    private boolean allFastForward(List<GameRoomMember> members) { return !members.isEmpty() && members.stream().allMatch(GameRoomMember::isFastForwardEnabled); }
    private boolean targetReached(List<GameRoomMember> members) { long now = calendars.findTopByOrderBySeasonDesc().map(c -> absolute(c)).orElse(0L); return members.stream().anyMatch(m -> m.getFastForwardUntilAbsoluteDay() == null || now >= m.getFastForwardUntilAbsoluteDay()); }
    public int requiredVotes(GameRoom room, int total) { return Math.max(1, (int) Math.ceil(total * room.getContinueThresholdPercent() / 100.0)); }
    static long absolute(GameCalendar c) { return ((long) c.getSeason() - 1L) * 366L + c.getCurrentDay(); }
    /** Claims one rapid day under room -> cycle -> member locks. */
    @Transactional
    public AdvanceClaim claimRapidForRoom(Long roomId) {
        GameRoom room = rooms.rooms().findByIdForUpdate(roomId).orElse(null);
        if (room == null || room.getStatus() != RoomStatus.ACTIVE) return null;
        RoomContinueCycle cycle = rooms.cycles().findOpenForUpdate(roomId).orElse(null);
        if (cycle == null) return null;
        List<GameRoomMember> active = rooms.membersRepository().findActiveForUpdate(roomId);
        expireReachedTargets(active, cycle);
        if (!allFastForward(active) || targetReached(active)) return null;
        return claimIfReadyLocked(room, cycle, true);
    }

    @Transactional
    public boolean rapidEligible(Long roomId) {
        GameRoom room = rooms.rooms().findByIdForUpdate(roomId).orElse(null);
        if (room == null || room.getStatus() != RoomStatus.ACTIVE) return false;
        RoomContinueCycle cycle = rooms.cycles().findOpenForUpdate(roomId).orElse(null);
        if (cycle == null) return false;
        List<GameRoomMember> active = rooms.membersRepository().findActiveForUpdate(roomId);
        expireReachedTargets(active, cycle);
        return allFastForward(active) && !targetReached(active);
    }

    private void expireReachedTargets(List<GameRoomMember> members, RoomContinueCycle cycle) {
        long now = calendars.findTopByOrderBySeasonDesc().map(RoomContinueCoordinator::absolute).orElse(0L);
        for (GameRoomMember member : members) {
            if (member.isFastForwardEnabled() && member.getFastForwardUntilAbsoluteDay() != null && now >= member.getFastForwardUntilAbsoluteDay()) {
                member.setFastForwardEnabled(false); member.setFastForwardUntilAbsoluteDay(null); rooms.membersRepository().save(member);
                if (cycle.getMajorityDeadline() == null) rooms.votes().findForUpdate(cycle.getId(), member.getUserId()).filter(v -> v.getSource() == VoteSource.FAST_FORWARD).ifPresent(rooms.votes()::delete);
            }
        }
    }
}
