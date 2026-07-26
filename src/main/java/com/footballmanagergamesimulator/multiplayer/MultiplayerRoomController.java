package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.user.User;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/multiplayer")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class MultiplayerRoomController {
    private final MultiplayerRoomService service;
    private final RoomContinueCoordinator coordinator;
    private final GameCalendarRepository calendars;
    private final RoomRapidFastForwardService rapid;
    private final com.footballmanagergamesimulator.service.LiveMatchSimulationService liveMatches;

    public MultiplayerRoomController(MultiplayerRoomService service, RoomContinueCoordinator coordinator,
                                     GameCalendarRepository calendars, RoomRapidFastForwardService rapid,
                                     com.footballmanagergamesimulator.service.LiveMatchSimulationService liveMatches) {
        this.service = service; this.coordinator = coordinator; this.calendars = calendars;
        this.rapid = rapid; this.liveMatches = liveMatches;
    }

    @GetMapping("/room") public Map<String, Object> room() { return state(false); }
    @PostMapping("/room") public Map<String, Object> create(@RequestBody RoomRequest r) { service.create(r.create()); return state(false); }
    @PostMapping("/room/join") public Map<String, Object> join(@RequestBody PasswordRequest r) { service.join(r.password()); return state(false); }
    @PatchMapping("/room/settings") public Map<String, Object> settings(@RequestBody SettingsRequest r) { service.updateSettings(r.settings()); return state(false); }
    @PostMapping("/room/ready") public Map<String, Object> ready() { service.ready(true); return state(false); }
    @DeleteMapping("/room/ready") public Map<String, Object> unready() { service.ready(false); return state(false); }
    @PostMapping("/room/start") public Map<String, Object> start() { service.start(); return state(false); }
    @PostMapping("/room/leave") public Map<String, Object> leave() { service.leave(); return Map.of("status", "LEFT"); }

    @PostMapping("/room/continue")
    public Map<String, Object> continueDay() {
        AdvanceClaim claim = coordinator.cast(service.user().getId(), VoteSource.MANUAL);
        if (claim != null) coordinator.advanceClaimed(claim);
        return state(false);
    }

    @DeleteMapping("/room/continue")
    public Map<String, Object> withdraw() { coordinator.withdraw(service.user().getId()); return state(false); }

    @PostMapping("/room/fast-forward")
    public Map<String, Object> fastForward(@RequestBody FastForwardRequest r) {
        service.setFastForward(r.enabled(), r.seasons());
        GameRoom room = service.requireMemberRoom();
        if (r.enabled()) rapid.start(room.getId());
        AdvanceClaim claim = coordinator.tryClaim(service.user().getId());
        if (claim != null) coordinator.advanceClaimed(claim);
        return state(false);
    }

    @DeleteMapping("/room/fast-forward")
    public Map<String, Object> stopFastForward() { service.setFastForward(false, 0); return state(false); }

    @GetMapping("/room/state")
    public Map<String, Object> stateEndpoint() {
        try {
            return state(true);
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 403 && "NOT_ROOM_MEMBER".equals(exception.getReason())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "ROOM_MEMBERSHIP_NOT_FOUND");
            }
            throw exception;
        }
    }

    private Map<String, Object> state(boolean includeCycle) {
        GameRoom room = service.requireMemberRoom();
        User user = service.user();
        List<GameRoomMember> members = service.members(room);
        RoomContinueCycle cycle = service.currentCycle(room);
        GameCalendar cal = calendars.findTopByOrderBySeasonDesc().orElse(null);
        long votes = cycle == null ? 0 : service.votes().countByCycleId(cycle.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentUserId", user.getId());
        out.put("currentMember", members.stream().filter(m -> m.getUserId() == user.getId()).findFirst()
                .map(this::memberState).orElse(null));
        out.put("status", room.getStatus()); out.put("roomId", room.getId()); out.put("hostUserId", room.getHostUserId());
        out.put("continueThresholdPercent", room.getContinueThresholdPercent()); out.put("dayTimeoutSeconds", room.getDayTimeoutSeconds());
        out.put("majorityTimeoutSeconds", room.getMajorityTimeoutSeconds()); out.put("maxPlayers", room.getMaxPlayers());
        out.put("forceContinue", room.isForceContinue()); out.put("members", members.stream().map(this::memberState).toList());
        out.put("votes", votes); out.put("totalPlayers", members.size()); out.put("requiredVotes", coordinator.requiredVotes(room, members.size()));
        out.put("currentUserVoted", cycle != null && service.votes().findByCycleIdAndUserId(cycle.getId(), user.getId()).isPresent());
        out.put("fastForwardCount", members.stream().filter(GameRoomMember::isFastForwardEnabled).count());
        out.put("allFastForward", !members.isEmpty() && members.stream().allMatch(GameRoomMember::isFastForwardEnabled));
        out.put("season", cal == null ? 0 : cal.getSeason()); out.put("day", cal == null ? 0 : cal.getCurrentDay());

        RoomDate currentDate = cal == null ? null : RoomDate.of(cal);
        RoomDate targetDate = members.stream()
                .filter(m -> m.getFastForwardTargetSeason() != null && m.getFastForwardTargetDay() != null)
                .map(m -> new RoomDate(m.getFastForwardTargetSeason(), m.getFastForwardTargetDay()))
                .min(RoomDate::compareTo).orElse(null);
        RoomRapidFastForwardService.Progress progress = rapid.state(room.getId(), currentDate, targetDate);
        out.put("rapidStatus", progress.status()); out.put("rapidCurrentSeason", progress.currentSeason());
        out.put("rapidCurrentDay", progress.currentDay()); out.put("rapidTargetSeason", progress.targetSeason());
        out.put("rapidTargetDay", progress.targetDay()); out.put("rapidCancelPending", progress.cancelPending());

        GameRoomMember currentMember = members.stream().filter(m -> m.getUserId() == user.getId()).findFirst().orElse(null);
        var ownSession = currentMember == null ? null : liveMatches.findAnyUncommittedSessionForTeam(currentMember.getTeamId());
        String liveKey = ownSession == null ? null : com.footballmanagergamesimulator.service.LiveMatchSimulationService.buildKey(
                ownSession.getCompetitionId(), ownSession.getSeason(), ownSession.getRound(), ownSession.getTeamId1(), ownSession.getTeamId2());
        boolean liveInteractive = ownSession != null && !ownSession.isFinished();
        boolean anyLiveMatch = members.stream().anyMatch(m -> liveMatches.findAnyUncommittedSessionForTeam(m.getTeamId()) != null);
        if (liveKey != null) { out.put("liveMatchKey", liveKey); out.put("liveMatchInteractive", liveInteractive); }
        if (cycle != null) {
            out.put("dayDeadline", cycle.getDayDeadline()); out.put("majorityDeadline", cycle.getMajorityDeadline());
            out.put("effectiveDeadline", effective(cycle));
        }
        String blockerCode = anyLiveMatch ? "LIVE_MATCH_PENDING" : room.getBlockerCode();
        if (blockerCode == null && cycle != null && cycle.getStatus() == CycleStatus.BLOCKED) blockerCode = "ADVANCE_BLOCKED";
        if (blockerCode == null && cycle != null && cycle.getStatus() == CycleStatus.FAILED) blockerCode = "ADVANCE_FAILED";
        out.put("blocker", Map.of("code", blockerCode == null ? "NONE" : blockerCode,
                "message", room.getBlockerMessage() == null ? "" : room.getBlockerMessage()));
        return out;
    }

    private Map<String, Object> memberState(GameRoomMember member) {
        return Map.of("userId", member.getUserId(), "teamId", member.getTeamId(), "ready", member.isReady(),
                "fastForwardEnabled", member.isFastForwardEnabled());
    }

    private Instant effective(RoomContinueCycle c) {
        if (c.getMajorityDeadline() == null) return c.getDayDeadline();
        return c.getDayDeadline().isBefore(c.getMajorityDeadline()) ? c.getDayDeadline() : c.getMajorityDeadline();
    }

    public record RoomRequest(String password, Integer continueThresholdPercent, Integer dayTimeoutSeconds,
                              Integer majorityTimeoutSeconds, Integer maxPlayers, Boolean forceContinue) {
        MultiplayerRoomService.CreateRoom create() { return new MultiplayerRoomService.CreateRoom(password,
                continueThresholdPercent == null ? 50 : continueThresholdPercent,
                dayTimeoutSeconds == null ? 300 : dayTimeoutSeconds,
                majorityTimeoutSeconds == null ? 60 : majorityTimeoutSeconds,
                maxPlayers == null ? 2 : maxPlayers, Boolean.TRUE.equals(forceContinue)); }
    }
    public record SettingsRequest(Integer continueThresholdPercent, Integer dayTimeoutSeconds,
                                  Integer majorityTimeoutSeconds, Integer maxPlayers, Boolean forceContinue) {
        MultiplayerRoomService.Settings settings() { return new MultiplayerRoomService.Settings(
                continueThresholdPercent == null ? 50 : continueThresholdPercent,
                dayTimeoutSeconds == null ? 300 : dayTimeoutSeconds,
                majorityTimeoutSeconds == null ? 60 : majorityTimeoutSeconds,
                maxPlayers == null ? 2 : maxPlayers, Boolean.TRUE.equals(forceContinue)); }
    }
    public record PasswordRequest(String password) {}
    public record FastForwardRequest(boolean enabled, int seasons) {}
}
