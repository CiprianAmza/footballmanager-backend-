package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.MatchPreviewView;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.model.BoardRequest;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.BoardRequestRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MemoryCheckpointService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small, current-day read model for the manager's landing page. */
@RestController
@RequestMapping("/manager/today")
public class ManagerDayController {
    private static final List<InboxAudience> MANAGER_AUDIENCE = List.of(InboxAudience.MANAGER, InboxAudience.BOTH);

    private final CurrentUserService users;
    private final GameCalendarRepository calendars;
    private final TeamRepository teams;
    private final MatchController matches;
    private final TacticController tactics;
    private final HumanRepository players;
    private final ManagerInboxRepository inbox;
    private final BoardRequestRepository boardRequests;
    private final MemoryCheckpointService checkpoints;

    public ManagerDayController(CurrentUserService users, GameCalendarRepository calendars, TeamRepository teams,
                                MatchController matches, TacticController tactics, HumanRepository players,
                                ManagerInboxRepository inbox, BoardRequestRepository boardRequests,
                                MemoryCheckpointService checkpoints) {
        this.users = users;
        this.calendars = calendars;
        this.teams = teams;
        this.matches = matches;
        this.tactics = tactics;
        this.players = players;
        this.inbox = inbox;
        this.boardRequests = boardRequests;
        this.checkpoints = checkpoints;
    }

    @GetMapping
    public ResponseEntity<TodayBrief> today() {
        User user = users.getUserOrNull();
        if (user == null) return ResponseEntity.status(401).build();
        GameCalendar calendar = calendars.findTopByOrderBySeasonDesc().orElse(null);
        if (calendar == null) return ResponseEntity.ok(new TodayBrief(0, "", 0, 0, null,
                new LineupBrief("", 0, 0, false, List.of("The game has not been set up yet.")),
                0, List.of(), checkpoints.status()));

        long teamId = user.getTeamId() == null ? 0 : user.getTeamId();
        String teamName = teamId > 0 ? teams.findNameById(teamId) : "";
        MatchPreviewView nextMatch = teamId > 0 ? matches.getMatchPreview(teamId) : null;
        LineupBrief lineup = teamId > 0 ? lineup(teamId) : new LineupBrief("", 0, 0, false, List.of());
        List<Attention> attention = new ArrayList<>();
        for (String warning : lineup.warnings()) {
            attention.add(new Attention("lineup", warning, "Review the saved XI before your next match."));
        }

        long unread = 0;
        if (teamId > 0) {
            unread = inbox.countByTeamIdAndAudienceInAndIsReadFalse(teamId, MANAGER_AUDIENCE);
            List<ManagerInbox> recent = inbox.findByTeamIdAndAudienceInAndIsReadFalseOrderByIdDesc(
                    teamId, MANAGER_AUDIENCE, PageRequest.of(0, 3));
            for (ManagerInbox message : recent) {
                attention.add(new Attention("inbox", message.getTitle(), "Unread message in your inbox."));
            }
            boardRequests.findAllByTeamIdAndSeasonNumber(teamId, calendar.getSeason()).stream()
                    .filter(request -> "ACTIVE".equalsIgnoreCase(request.getStatus()))
                    .sorted(Comparator.comparingInt(BoardRequest::getDeadline))
                    .limit(2)
                    .forEach(request -> attention.add(new Attention("board", request.getDescription(),
                            "Board request · day " + request.getDeadline())));
        }

        return ResponseEntity.ok(new TodayBrief(teamId, teamName, calendar.getSeason(), calendar.getCurrentDay(),
                nextMatch, lineup, unread, attention.stream().limit(5).toList(), checkpoints.status()));
    }

    private LineupBrief lineup(long teamId) {
        var result = tactics.getTeamTacticView(teamId);
        if (!(result.getBody() instanceof Map<?, ?> view)
                || !(view.get("managerTactic") instanceof PersonalizedTacticView tactic)) {
            return new LineupBrief("", 0, 0, false, List.of("The starting XI could not be loaded."));
        }
        boolean saved = "SAVED".equals(view.get("managerTacticSource"));
        List<FormationData> slots = tactic.getFormationDataList() == null ? List.of() : tactic.getFormationDataList();
        List<Long> starterIds = slots.stream().filter(slot -> slot.getPositionIndex() < 30 && slot.getPlayerId() > 0)
                .map(FormationData::getPlayerId).toList();
        int benchCount = (int) slots.stream().filter(slot -> slot.getPositionIndex() >= 30
                && slot.getPositionIndex() <= 36 && slot.getPlayerId() > 0).count();
        List<String> warnings = new ArrayList<>();
        if (starterIds.size() != 11) warnings.add("Starting XI has " + starterIds.size() + " of 11 players.");
        Set<Long> uniqueIds = new HashSet<>(starterIds);
        if (uniqueIds.size() != starterIds.size()) warnings.add("A player appears twice in the starting XI.");
        Map<Long, Human> byId = new HashMap<>();
        players.findAllById(uniqueIds).forEach(player -> byId.put(player.getId(), player));
        if (starterIds.stream().noneMatch(id -> "GK".equalsIgnoreCase(
                byId.containsKey(id) ? byId.get(id).getPosition() : ""))) {
            warnings.add("The starting XI has no goalkeeper.");
        }
        for (Long id : starterIds) {
            Human player = byId.get(id);
            if (player != null && player.getFitness() < 75) {
                warnings.add(player.getName() + " is below 75% fitness.");
                break;
            }
        }
        return new LineupBrief(tactic.getTactic(), starterIds.size(), benchCount, saved, warnings);
    }

    public record TodayBrief(long teamId, String teamName, int season, int day,
                             MatchPreviewView nextMatch, LineupBrief lineup, long unreadCount,
                             List<Attention> attention, MemoryCheckpointService.Status checkpoint) {}
    public record LineupBrief(String formation, int starters, int bench, boolean saved, List<String> warnings) {}
    public record Attention(String kind, String title, String detail) {}
}
