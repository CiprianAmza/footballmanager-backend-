package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.CareerRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class MultiplayerRoomService {
    public static final int DEFAULT_THRESHOLD = 50, DEFAULT_DAY_TIMEOUT = 300, DEFAULT_MAJORITY_TIMEOUT = 60, DEFAULT_MAX_PLAYERS = 2;
    private final GameRoomRepository roomRepository;
    private final GameRoomMemberRepository memberRepository;
    private final RoomContinueCycleRepository cycleRepository;
    private final RoomContinueVoteRepository voteRepository;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final TeamRepository teamRepository;
    private final GameCalendarRepository calendarRepository;

    public MultiplayerRoomService(GameRoomRepository roomRepository, GameRoomMemberRepository memberRepository,
                                  RoomContinueCycleRepository cycleRepository, RoomContinueVoteRepository voteRepository,
                                  CurrentUserService currentUserService, PasswordEncoder passwordEncoder,
                                  TeamRepository teamRepository, GameCalendarRepository calendarRepository) {
        this.roomRepository = roomRepository; this.memberRepository = memberRepository; this.cycleRepository = cycleRepository;
        this.voteRepository = voteRepository; this.currentUserService = currentUserService; this.passwordEncoder = passwordEncoder;
        this.teamRepository = teamRepository; this.calendarRepository = calendarRepository;
    }

    public User user() { return currentUserService.requireUser(); }

    @Transactional
    public synchronized GameRoom create(CreateRoom command) {
        User user = user();
        requireManager(user);
        requireTeam(user);
        if (memberRepository.findFirstByUserIdAndMembershipStatus(user.getId(), MembershipStatus.ACTIVE).isPresent()) conflict("User is already in a room");
        if (roomRepository.findOpenForUpdate(List.of(RoomStatus.LOBBY, RoomStatus.ACTIVE)).isPresent()) conflict("A room is already open");
        validateSettings(command.threshold(), command.dayTimeoutSeconds(), command.majorityTimeoutSeconds(), command.maxPlayers());
        if (command.password() == null || command.password().isBlank()) bad("Password is required");
        GameRoom room = new GameRoom(); room.setHostUserId(user.getId()); room.setPasswordHash(passwordEncoder.encode(command.password()));
        room.setContinueThresholdPercent(command.threshold()); room.setDayTimeoutSeconds(command.dayTimeoutSeconds());
        room.setMajorityTimeoutSeconds(command.majorityTimeoutSeconds()); room.setMaxPlayers(command.maxPlayers());
        room.setForceContinue(command.forceContinue());
        room = roomRepository.save(room);
        memberRepository.save(member(room, user));
        return room;
    }

    @Transactional
    public GameRoom join(String password) {
        User user = user(); requireManager(user); requireTeam(user);
        if (memberRepository.findFirstByUserIdAndMembershipStatus(user.getId(), MembershipStatus.ACTIVE).isPresent()) conflict("User is already in a room");
        GameRoom room = roomRepository.findOpenForUpdate(List.of(RoomStatus.LOBBY, RoomStatus.ACTIVE)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_ACTIVE_ROOM"));
        if (!passwordEncoder.matches(password == null ? "" : password, room.getPasswordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_ROOM_PASSWORD");
        List<GameRoomMember> members = memberRepository.findActiveForUpdate(room.getId());
        if (room.getStatus() != RoomStatus.LOBBY) conflict("ROOM_ALREADY_STARTED");
        if (members.size() >= room.getMaxPlayers()) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_FULL");
        if (members.stream().anyMatch(m -> m.getTeamId() == user.getTeamId())) conflict("TEAM_ALREADY_IN_ROOM");
        GameRoomMember previous = memberRepository.findByRoomIdAndUserId(room.getId(), user.getId()).orElse(null);
        if (previous == null) previous = member(room, user);
        previous.setTeamId(user.getTeamId()); previous.setMembershipStatus(MembershipStatus.ACTIVE); previous.setReady(false);
        previous.setFastForwardEnabled(false); previous.setFastForwardTargetSeason(null); previous.setFastForwardTargetDay(null);
        previous.setLastSeenAt(Instant.now()); memberRepository.save(previous); return room;
    }

    @Transactional
    public GameRoom updateSettings(Settings command) {
        User user = user(); requireManager(user); GameRoom room = lockMemberRoom(user.getId());
        if (room.getHostUserId() != user.getId()) forbidden("HOST_ONLY");
        if (room.getStatus() != RoomStatus.LOBBY) conflict("ROOM_ALREADY_STARTED");
        validateSettings(command.threshold(), command.dayTimeoutSeconds(), command.majorityTimeoutSeconds(), command.maxPlayers());
        if (memberRepository.findAllByRoomIdAndMembershipStatus(room.getId(), MembershipStatus.ACTIVE).size() > command.maxPlayers()) conflict("MAX_PLAYERS_TOO_LOW");
        room.setContinueThresholdPercent(command.threshold()); room.setDayTimeoutSeconds(command.dayTimeoutSeconds()); room.setMajorityTimeoutSeconds(command.majorityTimeoutSeconds()); room.setMaxPlayers(command.maxPlayers());
        room.setForceContinue(command.forceContinue());
        return roomRepository.save(room);
    }

    @Transactional public void ready(boolean ready) { User user = user(); requireManager(user); GameRoom room = lockMemberRoom(user.getId()); if (room.getStatus() != RoomStatus.LOBBY) conflict("ROOM_ALREADY_STARTED"); GameRoomMember m = memberRepository.findActiveForUpdate(room.getId(), user.getId()).orElseThrow(); m.setReady(ready); memberRepository.save(m); }

    @Transactional
    public void leave() {
        User user = user(); requireManager(user);
        GameRoom room = lockMemberRoom(user.getId());
        GameRoomMember member = memberRepository.findActiveForUpdate(room.getId(), user.getId()).orElseThrow();
        if (room.getStatus() != RoomStatus.LOBBY) conflict("ROOM_LEAVE_ONLY_IN_LOBBY");
        member.setMembershipStatus(MembershipStatus.LEFT); member.setReady(false); member.setFastForwardEnabled(false);
        member.setFastForwardTargetSeason(null); member.setFastForwardTargetDay(null); memberRepository.saveAndFlush(member);
        if (room.getHostUserId() == user.getId()) {
            for (RoomContinueCycle cycle : cycleRepository.findAllByRoomId(room.getId())) {
                voteRepository.deleteByCycleId(cycle.getId());
            }
            voteRepository.flush();
            cycleRepository.deleteAll(cycleRepository.findAllByRoomId(room.getId())); cycleRepository.flush();
            memberRepository.deleteAll(memberRepository.findAllByRoomIdAndMembershipStatus(room.getId(), MembershipStatus.ACTIVE));
            memberRepository.delete(member); memberRepository.flush();
            roomRepository.delete(room); roomRepository.flush();
        }
    }

    @Transactional
    public void start() {
        User user = user(); requireManager(user); GameRoom room = lockMemberRoom(user.getId());
        if (room.getHostUserId() != user.getId()) forbidden("HOST_ONLY");
        if (room.getStatus() != RoomStatus.LOBBY) conflict("ROOM_ALREADY_STARTED");
        List<GameRoomMember> members = memberRepository.findActiveForUpdate(room.getId());
        if (members.size() < 2) conflict("MINIMUM_TWO_PLAYERS");
        if (members.stream().anyMatch(m -> !m.isReady())) conflict("ALL_MEMBERS_MUST_BE_READY");
        if (members.stream().map(GameRoomMember::getTeamId).distinct().count() != members.size()) conflict("TEAM_ALREADY_IN_ROOM");
        room.setStatus(RoomStatus.ACTIVE); room.setStartedAt(Instant.now()); roomRepository.save(room);
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "GAME_NOT_INITIALIZED"));
        createCycle(room, calendar.getSeason(), calendar.getCurrentDay());
    }

    public GameRoom openRoom() { return roomRepository.findFirstByStatusIn(List.of(RoomStatus.LOBBY, RoomStatus.ACTIVE)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_ACTIVE_ROOM")); }
    public GameRoom openRoomForUpdate() { return roomRepository.findOpenForUpdate(List.of(RoomStatus.LOBBY, RoomStatus.ACTIVE)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_ACTIVE_ROOM")); }
    public GameRoom requireMemberRoom(int userId) { GameRoomMember m = memberRepository.findFirstByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER")); return roomRepository.findById(m.getRoomId()).orElseThrow(); }
    public GameRoom requireMemberRoom() { return requireMemberRoom(user().getId()); }
    public boolean currentUserInActiveRoom() {
        User current = currentUserService.getUserOrNull();
        if (current == null) return false;
        return memberRepository.findFirstByUserIdAndMembershipStatus(current.getId(), MembershipStatus.ACTIVE)
                .map(m -> roomRepository.findById(m.getRoomId()).map(r -> r.getStatus() == RoomStatus.ACTIVE).orElse(false))
                .orElse(false);
    }
    public boolean hasActiveRoom() { return roomRepository.findFirstByStatusIn(List.of(RoomStatus.ACTIVE)).isPresent(); }
    public boolean activeRoomHasTeams(long team1, long team2) {
        return roomRepository.findFirstByStatusIn(List.of(RoomStatus.ACTIVE))
                .map(room -> {
                    Set<Long> teams = memberRepository.findAllByRoomIdAndMembershipStatus(room.getId(), MembershipStatus.ACTIVE)
                            .stream().map(GameRoomMember::getTeamId).collect(java.util.stream.Collectors.toSet());
                    return teams.contains(team1) && teams.contains(team2);
                }).orElse(false);
    }
    public GameRoomMember member(int userId) { GameRoom room = requireMemberRoom(userId); return memberRepository.findByRoomIdAndUserIdAndMembershipStatus(room.getId(), userId, MembershipStatus.ACTIVE).orElseThrow(); }
    public GameRoomMember memberForUpdate(GameRoom room, int userId) { return memberRepository.findActiveForUpdate(room.getId(), userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER")); }
    public GameRoom lockMemberRoom(int userId) { GameRoomMember m = memberRepository.findFirstByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER")); return roomRepository.findByIdForUpdate(m.getRoomId()).orElseThrow(); }
    public GameRoom requireMember(int userId) { return requireMemberRoom(userId); }
    public List<GameRoomMember> members(GameRoom room) { return memberRepository.findAllByRoomIdAndMembershipStatus(room.getId(), MembershipStatus.ACTIVE); }
    public RoomContinueCycle currentCycle(GameRoom room) { return cycleRepository.findCurrent(room.getId()).orElse(null); }
    public RoomContinueCycle currentCycleForUpdate(GameRoom room) { return cycleRepository.findCurrentForUpdate(room.getId()).orElse(null); }
    public RoomContinueCycle createCycle(GameRoom room, int season, int day) { RoomContinueCycle c = new RoomContinueCycle(); Instant now = Instant.now(); c.setRoomId(room.getId()); c.setSeason(season); c.setGameDay(day); c.setOpenedAt(now); c.setDayDeadline(now.plusSeconds(room.getDayTimeoutSeconds())); c = cycleRepository.save(c); for (GameRoomMember m : members(room)) if (m.isFastForwardEnabled()) { RoomContinueVote v = new RoomContinueVote(); v.setCycleId(c.getId()); v.setUserId(m.getUserId()); v.setSource(VoteSource.FAST_FORWARD); v.setVotedAt(now); voteRepository.save(v); } return c; }
    public RoomContinueCycleRepository cycles() { return cycleRepository; }
    public RoomContinueVoteRepository votes() { return voteRepository; }
    public GameRoomRepository rooms() { return roomRepository; }
    public GameRoomMemberRepository membersRepository() { return memberRepository; }

    @Transactional
    public void setFastForward(boolean enabled, int seasons) {
        User user = user(); requireManager(user); GameRoom room = lockMemberRoom(user.getId());
        if (room.getStatus() != RoomStatus.ACTIVE) conflict("ROOM_NOT_ACTIVE");
        GameRoomMember member = memberRepository.findActiveForUpdate(room.getId(), user.getId()).orElseThrow();
        RoomContinueCycle cycle = cycleRepository.findCurrentForUpdate(room.getId()).orElse(null);
        member.setFastForwardEnabled(enabled);
        if (enabled) {
            if (seasons < 1 || seasons > 100) bad("INVALID_FAST_FORWARD_TARGET");
            GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElseThrow();
            member.setFastForwardTargetSeason(calendar.getSeason() + seasons);
            member.setFastForwardTargetDay(calendar.getCurrentDay());
            if (cycle != null) upsertFastForwardVote(cycle, user.getId());
        } else {
            member.setFastForwardTargetSeason(null);
            member.setFastForwardTargetDay(null);
            if (cycle != null && (cycle.getMajorityDeadline() == null || "RAPID".equals(cycle.getAdvanceMode()))) voteRepository.findForUpdate(cycle.getId(), user.getId()).filter(v -> v.getSource() == VoteSource.FAST_FORWARD).ifPresent(voteRepository::delete);
        }
        memberRepository.save(member);
    }

    private void upsertFastForwardVote(RoomContinueCycle cycle, int userId) {
        if (voteRepository.findForUpdate(cycle.getId(), userId).isPresent()) return;
        RoomContinueVote vote = new RoomContinueVote(); vote.setCycleId(cycle.getId()); vote.setUserId(userId); vote.setSource(VoteSource.FAST_FORWARD); vote.setVotedAt(Instant.now()); voteRepository.saveAndFlush(vote);
    }

    private GameRoomMember member(GameRoom room, User user) { GameRoomMember m = new GameRoomMember(); m.setRoomId(room.getId()); m.setUserId(user.getId()); m.setTeamId(user.getTeamId()); return m; }
    private void requireManager(User u) { if (u.getCareerRole() != CareerRole.MANAGER) forbidden("MANAGER_ONLY"); }
    private void requireTeam(User u) { if (u.getTeamId() == null || u.getTeamId() <= 0) bad("MANAGER_TEAM_REQUIRED"); }
    private void validateSettings(int threshold, int day, int majority, int max) { if (threshold < 50 || threshold > 100 || day < 30 || day > 3600 || majority < 5 || majority > 600 || max < 2 || max > 8) bad("INVALID_ROOM_SETTINGS"); }
    private void bad(String s) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, s); }
    private void conflict(String s) { throw new ResponseStatusException(HttpStatus.CONFLICT, s); }
    private void forbidden(String s) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, s); }
    public record CreateRoom(String password, int threshold, int dayTimeoutSeconds, int majorityTimeoutSeconds, int maxPlayers, boolean forceContinue) {
        public CreateRoom(String password, int threshold, int dayTimeoutSeconds, int majorityTimeoutSeconds, int maxPlayers) { this(password, threshold, dayTimeoutSeconds, majorityTimeoutSeconds, maxPlayers, false); }
    }
    public record Settings(int threshold, int dayTimeoutSeconds, int majorityTimeoutSeconds, int maxPlayers, boolean forceContinue) {
        public Settings(int threshold, int dayTimeoutSeconds, int majorityTimeoutSeconds, int maxPlayers) { this(threshold, dayTimeoutSeconds, majorityTimeoutSeconds, maxPlayers, false); }
    }
}
