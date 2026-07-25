package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/multiplayer")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class MultiplayerRoomController {
    private final MultiplayerRoomService service; private final RoomContinueCoordinator coordinator; private final GameCalendarRepository calendars;
    public MultiplayerRoomController(MultiplayerRoomService service, RoomContinueCoordinator coordinator, GameCalendarRepository calendars) { this.service = service; this.coordinator = coordinator; this.calendars = calendars; }

    @GetMapping("/room") public Map<String,Object> room() { return state(false); }
    @PostMapping("/room") public Map<String,Object> create(@RequestBody RoomRequest r) { service.create(r.create()); return state(false); }
    @PostMapping("/room/join") public Map<String,Object> join(@RequestBody PasswordRequest r) { service.join(r.password()); return state(false); }
    @PatchMapping("/room/settings") public Map<String,Object> settings(@RequestBody SettingsRequest r) { service.updateSettings(r.settings()); return state(false); }
    @PostMapping("/room/ready") public Map<String,Object> ready() { service.ready(true); return state(false); }
    @DeleteMapping("/room/ready") public Map<String,Object> unready() { service.ready(false); return state(false); }
    @PostMapping("/room/start") public Map<String,Object> start() { service.start(); return state(false); }
    @PostMapping("/room/continue") public Map<String,Object> continueDay() { AdvanceClaim claim = coordinator.cast(service.user().getId(), VoteSource.MANUAL); if (claim != null) coordinator.advanceClaimed(claim); return state(false); }
    @DeleteMapping("/room/continue") public Map<String,Object> withdraw() { coordinator.withdraw(service.user().getId()); return state(false); }
    @PostMapping("/room/fast-forward") public Map<String,Object> fastForward(@RequestBody FastForwardRequest r) { service.setFastForward(r.enabled(), r.seasons()); AdvanceClaim claim = coordinator.tryClaim(service.user().getId()); if (claim != null) coordinator.advanceClaimed(claim); return state(false); }
    @DeleteMapping("/room/fast-forward") public Map<String,Object> stopFastForward() { service.setFastForward(false, 0); return state(false); }
    @GetMapping("/room/state") public Map<String,Object> stateEndpoint() { return state(true); }

    private Map<String,Object> state(boolean includeCycle) {
        GameRoom room = service.requireMemberRoom(); User user = service.user(); List<GameRoomMember> members = service.members(room); RoomContinueCycle cycle = service.currentCycle(room); GameCalendar cal = calendars.findTopByOrderBySeasonDesc().orElse(null); long votes = cycle == null ? 0 : service.votes().countByCycleId(cycle.getId());
        Map<String,Object> out = new LinkedHashMap<>(); out.put("currentUserId", user.getId()); out.put("currentMember", members.stream().filter(m -> m.getUserId() == user.getId()).findFirst().map(m -> Map.of("userId", m.getUserId(), "teamId", m.getTeamId(), "ready", m.isReady(), "fastForwardEnabled", m.isFastForwardEnabled())).orElse(null)); out.put("status", room.getStatus()); out.put("roomId", room.getId()); out.put("hostUserId", room.getHostUserId()); out.put("continueThresholdPercent", room.getContinueThresholdPercent()); out.put("dayTimeoutSeconds", room.getDayTimeoutSeconds()); out.put("majorityTimeoutSeconds", room.getMajorityTimeoutSeconds()); out.put("maxPlayers", room.getMaxPlayers()); out.put("members", members.stream().map(m -> Map.of("userId", m.getUserId(), "teamId", m.getTeamId(), "ready", m.isReady(), "fastForwardEnabled", m.isFastForwardEnabled())).toList()); out.put("votes", votes); out.put("totalPlayers", members.size()); out.put("requiredVotes", coordinator.requiredVotes(room, members.size())); out.put("currentUserVoted", cycle != null && service.votes().findByCycleIdAndUserId(cycle.getId(), user.getId()).isPresent()); out.put("fastForwardCount", members.stream().filter(GameRoomMember::isFastForwardEnabled).count()); out.put("allFastForward", !members.isEmpty() && members.stream().allMatch(GameRoomMember::isFastForwardEnabled)); out.put("season", cal == null ? 0 : cal.getSeason()); out.put("day", cal == null ? 0 : cal.getCurrentDay()); if (cycle != null) { out.put("dayDeadline", cycle.getDayDeadline()); out.put("majorityDeadline", cycle.getMajorityDeadline()); out.put("effectiveDeadline", effective(cycle)); } out.put("blocker", Map.of("code", cycle != null && cycle.getStatus() == CycleStatus.FAILED ? "ADVANCE_FAILED" : "NONE")); return out;
    }
    private Instant effective(RoomContinueCycle c) { if (c.getMajorityDeadline() == null) return c.getDayDeadline(); return c.getDayDeadline().isBefore(c.getMajorityDeadline()) ? c.getDayDeadline() : c.getMajorityDeadline(); }
    public record RoomRequest(String password, Integer continueThresholdPercent, Integer dayTimeoutSeconds, Integer majorityTimeoutSeconds, Integer maxPlayers) { MultiplayerRoomService.CreateRoom create() { return new MultiplayerRoomService.CreateRoom(password, continueThresholdPercent == null ? 50 : continueThresholdPercent, dayTimeoutSeconds == null ? 300 : dayTimeoutSeconds, majorityTimeoutSeconds == null ? 60 : majorityTimeoutSeconds, maxPlayers == null ? 2 : maxPlayers); } }
    public record SettingsRequest(Integer continueThresholdPercent, Integer dayTimeoutSeconds, Integer majorityTimeoutSeconds, Integer maxPlayers) { MultiplayerRoomService.Settings settings() { return new MultiplayerRoomService.Settings(continueThresholdPercent == null ? 50 : continueThresholdPercent, dayTimeoutSeconds == null ? 300 : dayTimeoutSeconds, majorityTimeoutSeconds == null ? 60 : majorityTimeoutSeconds, maxPlayers == null ? 2 : maxPlayers); } }
    public record PasswordRequest(String password) {}
    public record FastForwardRequest(boolean enabled, int seasons) {}
}
