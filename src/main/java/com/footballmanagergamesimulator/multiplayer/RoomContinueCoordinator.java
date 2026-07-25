package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class RoomContinueCoordinator {
    private final MultiplayerRoomService rooms;
    private final RoomAdvanceService advance;
    private final GameCalendarRepository calendars;

    public RoomContinueCoordinator(MultiplayerRoomService rooms, RoomAdvanceService advance, GameCalendarRepository calendars) {
        this.rooms = rooms; this.advance = advance; this.calendars = calendars;
    }

    /** Records an idempotent vote and claims the cycle when it is ready. */
    @Transactional
    public boolean cast(int userId, VoteSource source) {
        GameRoom room = rooms.requireMemberRoom(userId);
        if (room.getStatus() != RoomStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_NOT_ACTIVE");
        RoomContinueCycle cycle = rooms.currentCycle(room);
        if (cycle == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "NO_OPEN_CONTINUE_CYCLE");
        if (cycle.getStatus() != CycleStatus.OPEN) return false;
        if (rooms.votes().findByCycleIdAndUserId(cycle.getId(), userId).isEmpty()) {
            RoomContinueVote vote = new RoomContinueVote(); vote.setCycleId(cycle.getId()); vote.setUserId(userId); vote.setSource(source); vote.setVotedAt(Instant.now()); rooms.votes().save(vote);
        }
        return claimIfReady(room, cycle);
    }

    @Transactional
    public boolean withdraw(int userId) {
        GameRoom room = rooms.requireMemberRoom(userId); RoomContinueCycle cycle = rooms.currentCycle(room);
        if (cycle == null) return false;
        if (cycle.getMajorityDeadline() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTINUE_WINDOW_LOCKED");
        return rooms.votes().findByCycleIdAndUserId(cycle.getId(), userId).map(v -> { rooms.votes().delete(v); return true; }).orElse(false);
    }

    /** Invoked by the scheduler at most once per second. */
    @Transactional
    public boolean claimExpired() {
        GameRoom room = rooms.openRoom(); if (room.getStatus() != RoomStatus.ACTIVE) return false;
        RoomContinueCycle cycle = rooms.currentCycle(room); if (cycle == null || cycle.getStatus() != CycleStatus.OPEN) return false;
        Instant now = Instant.now(); long votes = rooms.votes().countByCycleId(cycle.getId());
        boolean deadline = !now.isBefore(cycle.getDayDeadline()) || (cycle.getMajorityDeadline() != null && !now.isBefore(cycle.getMajorityDeadline()));
        if (votes == rooms.members(room).size() || deadline) return claim(room, cycle);
        if (votes >= requiredVotes(room, rooms.members(room).size()) && votes < rooms.members(room).size()) {
            if (cycle.getMajorityDeadline() == null) { cycle.setMajorityReachedAt(now); cycle.setMajorityDeadline(now.plusSeconds(room.getMajorityTimeoutSeconds())); rooms.cycles().save(cycle); }
        }
        return false;
    }

    public void advanceClaimed() {
        GameRoom room = rooms.openRoom(); RoomContinueCycle cycle = rooms.cycles().findFirstByRoomIdAndStatusOrderByIdDesc(room.getId(), CycleStatus.ADVANCING).orElse(null);
        if (cycle == null) return;
        GameCalendar calendar = calendars.findTopByOrderBySeasonDesc().orElseThrow(() -> new IllegalStateException("calendar missing"));
        if (calendar.getSeason() > cycle.getSeason() || (calendar.getSeason() == cycle.getSeason() && calendar.getCurrentDay() > cycle.getGameDay())) { completeAndOpen(room, cycle, calendar); return; }
        try {
            advance.advanceOneDay(cycle.getSeason(), rooms.members(room).stream().map(GameRoomMember::getUserId).collect(java.util.stream.Collectors.toSet()));
            GameCalendar after = calendars.findTopByOrderBySeasonDesc().orElseThrow();
            completeAndOpen(room, cycle, after);
        } catch (RuntimeException e) { fail(cycle, e.getClass().getSimpleName()); }
    }

    @Transactional
    public boolean claimIfReady(GameRoom room, RoomContinueCycle cycle) {
        List<GameRoomMember> members = rooms.members(room); long votes = rooms.votes().countByCycleId(cycle.getId());
        if (votes == members.size()) return claim(room, cycle);
        if (votes >= requiredVotes(room, members.size()) && cycle.getMajorityDeadline() == null) { Instant now = Instant.now(); cycle.setMajorityReachedAt(now); cycle.setMajorityDeadline(now.plusSeconds(room.getMajorityTimeoutSeconds())); rooms.cycles().save(cycle); }
        return false;
    }

    @Transactional
    public void completeAndOpen(GameRoom room, RoomContinueCycle cycle, GameCalendar calendar) {
        cycle.setStatus(CycleStatus.COMPLETED); cycle.setCompletedAt(Instant.now()); rooms.cycles().save(cycle);
        RoomContinueCycle next = rooms.createCycle(room, calendar.getSeason(), calendar.getCurrentDay());
        for (GameRoomMember m : rooms.members(room)) if (m.isFastForwardEnabled()) autoVote(next, m.getUserId());
    }

    private void autoVote(RoomContinueCycle c, int userId) { if (rooms.votes().findByCycleIdAndUserId(c.getId(), userId).isEmpty()) { RoomContinueVote v = new RoomContinueVote(); v.setCycleId(c.getId()); v.setUserId(userId); v.setSource(VoteSource.FAST_FORWARD); v.setVotedAt(Instant.now()); rooms.votes().save(v); } }
    private boolean claim(GameRoom room, RoomContinueCycle cycle) { if (cycle.getStatus() != CycleStatus.OPEN) return false; cycle.setStatus(CycleStatus.ADVANCING); cycle.setAdvanceStartedAt(Instant.now()); rooms.cycles().save(cycle); return true; }
    private void fail(RoomContinueCycle c, String code) { markFailed(c.getId(), code); }
    @Transactional public void markFailed(Long id, String code) { rooms.cycles().findById(id).ifPresent(c -> { c.setStatus(CycleStatus.FAILED); c.setFailureCode(code); rooms.cycles().save(c); }); }
    public int requiredVotes(GameRoom room, int total) { return Math.max(1, (int) Math.ceil(total * room.getContinueThresholdPercent() / 100.0)); }
}
