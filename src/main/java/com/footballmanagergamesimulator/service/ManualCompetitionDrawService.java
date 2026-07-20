package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.PredeterminedScore;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin draw control for domestic cups and European competitions.
 *
 * <p>Domestic cups keep their pre-generated bracket slots, so an admin draw
 * changes only the teams occupying the next unplayed round. European rounds are
 * genuinely created by the draw; the scheduled draw event is completed after a
 * manual selection so the automatic draw cannot create a second set of games.
 */
@Service
public class ManualCompetitionDrawService {

    private final CompetitionRepository competitions;
    private final CompetitionTeamInfoRepository entries;
    private final CompetitionTeamInfoMatchRepository fixtures;
    private final CompetitionTeamInfoDetailRepository results;
    private final TeamRepository teams;
    private final CalendarEventRepository calendarEvents;
    private final PredeterminedScoreRepository predeterminedScores;
    private final RoundRepository rounds;
    private final CompetitionFormatConfig formats;
    private final CompetitionProgressService progress;
    private final EuropeanDrawService europeanDraws;
    private final EuropeanCompetitionService europeanCompetitions;
    private final EuropeanCoefficientService coefficients;
    private final FixtureSchedulingService fixtureScheduling;

    public ManualCompetitionDrawService(
            CompetitionRepository competitions,
            CompetitionTeamInfoRepository entries,
            CompetitionTeamInfoMatchRepository fixtures,
            CompetitionTeamInfoDetailRepository results,
            TeamRepository teams,
            CalendarEventRepository calendarEvents,
            PredeterminedScoreRepository predeterminedScores,
            RoundRepository rounds,
            CompetitionFormatConfig formats,
            CompetitionProgressService progress,
            EuropeanDrawService europeanDraws,
            EuropeanCompetitionService europeanCompetitions,
            EuropeanCoefficientService coefficients,
            FixtureSchedulingService fixtureScheduling) {
        this.competitions = competitions;
        this.entries = entries;
        this.fixtures = fixtures;
        this.results = results;
        this.teams = teams;
        this.calendarEvents = calendarEvents;
        this.predeterminedScores = predeterminedScores;
        this.rounds = rounds;
        this.formats = formats;
        this.progress = progress;
        this.europeanDraws = europeanDraws;
        this.europeanCompetitions = europeanCompetitions;
        this.coefficients = coefficients;
        this.fixtureScheduling = fixtureScheduling;
    }

    public record Pairing(long team1Id, long team2Id) {}
    public record GroupSelection(int groupNumber, List<Long> teamIds) {}
    public record DrawCommand(long competitionId, int season, long round,
                              List<Pairing> pairings, Long byeTeamId,
                              List<GroupSelection> groups) {}

    /** Draws that can currently be controlled by the admin. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> drawStates(int season) {
        List<Map<String, Object>> states = new ArrayList<>();
        List<Competition> drawCompetitions = competitions.findAll().stream()
                .filter(c -> c.getTypeId() == 2 || c.getTypeId() == 4 || c.getTypeId() == 5)
                .sorted(Comparator.comparingLong(Competition::getTypeId)
                        .thenComparing(Competition::getName))
                .toList();

        drawCompetitions.stream().filter(c -> c.getTypeId() == 2)
                .map(c -> domesticState(c, season))
                .filter(state -> !state.isEmpty())
                .forEach(states::add);

        for (Competition competition : drawCompetitions) {
            if (competition.getTypeId() != 4 && competition.getTypeId() != 5) continue;
            CompetitionFormat format = formats.get((int) competition.getTypeId());
            for (Map<String, Object> published : europeanDraws.drawStates(competition.getId(), season)) {
                if ("DRAW_COMPLETED".equals(published.get("status"))) continue;
                long round = number(published.get("round")).longValue();
                List<Map<String, Object>> participants = participantsFromPots(published.get("pots"));
                boolean groupDraw = format.isGroupDrawRound(round);
                int expectedTeams = number(published.get("expectedTeams")).intValue();
                boolean ready = "POTS_PUBLISHED".equals(published.get("status"))
                        && participants.size() == expectedTeams;

                Map<String, Object> state = new LinkedHashMap<>(published);
                state.put("drawMode", groupDraw ? "GROUPS" : "PAIRINGS");
                state.put("participants", participants);
                state.put("expectedPairings", groupDraw ? 0 : participants.size() / 2);
                state.put("byeSlots", groupDraw ? 0 : participants.size() % 2);
                state.put("groupCount", groupDraw ? format.groupCount() : 0);
                state.put("groupSize", groupDraw ? format.groupSize() : 0);
                state.put("canEdit", ready && (groupDraw || participants.size() >= 2));
                state.put("source", "EUROPEAN_DRAW");
                states.add(state);
            }
        }

        states.sort(Comparator
                .comparing((Map<String, Object> state) -> !Boolean.TRUE.equals(state.get("canEdit")))
                .thenComparingInt(state -> number(state.getOrDefault("drawDay", Integer.MAX_VALUE)).intValue())
                .thenComparing(state -> String.valueOf(state.get("competitionName"))));
        return states;
    }

    /** Applies and locks one admin-selected draw. */
    @Transactional
    public Map<String, Object> complete(DrawCommand command) {
        if (command == null) throw new IllegalArgumentException("Draw request is required");
        int currentSeason = (int) rounds.findById(1L).orElse(new Round()).getSeason();
        if (command.season() != currentSeason) {
            throw new IllegalStateException("Only the current season can be drawn manually");
        }

        Competition competition = competitions.findById(command.competitionId())
                .orElseThrow(() -> new IllegalArgumentException("Competition not found"));
        int type = (int) competition.getTypeId();
        if (type != 2 && type != 4 && type != 5) {
            throw new IllegalArgumentException("Manual draws are available only for cups and European competitions");
        }
        if (!results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(
                competition.getId(), command.round(), command.season()).isEmpty()) {
            throw new IllegalStateException("This round has already started and can no longer be redrawn");
        }

        if (type == 2) {
            redrawDomesticCup(competition, command);
        } else {
            completeEuropeanDraw(competition, command);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("competitionId", competition.getId());
        response.put("competitionName", competition.getName());
        response.put("season", command.season());
        response.put("round", command.round());
        response.put("stageLabel", progress.roundLabel(
                competition.getId(), command.round(), command.season()));
        response.put("message", "Manual draw saved");
        return response;
    }

    private Map<String, Object> domesticState(Competition competition, int season) {
        Map<Long, List<CompetitionTeamInfoMatch>> byRound = fixtures
                .findAllBySeasonNumber(String.valueOf(season)).stream()
                .filter(f -> f.getCompetitionId() == competition.getId())
                .collect(Collectors.groupingBy(CompetitionTeamInfoMatch::getRound));
        if (byRound.isEmpty()) return Map.of();

        for (long round : byRound.keySet().stream().sorted().toList()) {
            if (!results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(
                    competition.getId(), round, season).isEmpty()) continue;

            List<CompetitionTeamInfoMatch> roundFixtures = byRound.get(round).stream()
                    .sorted(Comparator.comparingInt(CompetitionTeamInfoMatch::getMatchIndex)
                            .thenComparingLong(CompetitionTeamInfoMatch::getId))
                    .toList();
            Set<Long> participantIds = roundFixtures.stream()
                    .flatMap(f -> java.util.stream.Stream.of(f.getTeam1Id(), f.getTeam2Id()))
                    .filter(id -> id > 0)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean completeField = participantIds.size() == roundFixtures.size() * 2;
            boolean untouched = roundFixtures.stream().allMatch(f -> f.getTeam1Score() < 0 && f.getTeam2Score() < 0);
            Map<Long, Team> teamById = teams.findAllById(participantIds).stream()
                    .collect(Collectors.toMap(Team::getId, Function.identity()));

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("competitionId", competition.getId());
            state.put("competitionName", competition.getName());
            state.put("competitionTypeId", competition.getTypeId());
            state.put("season", season);
            state.put("round", round);
            state.put("stageLabel", progress.roundLabel(competition.getId(), round, season));
            state.put("drawMode", "PAIRINGS");
            state.put("participants", participantIds.stream()
                    .map(id -> teamView(id, teamById.get(id), null)).toList());
            state.put("expectedTeams", roundFixtures.size() * 2);
            state.put("expectedPairings", roundFixtures.size());
            state.put("byeSlots", 0);
            state.put("existingPairings", completeField ? roundFixtures.stream().map(f -> Map.of(
                    "team1Id", f.getTeam1Id(), "team2Id", f.getTeam2Id())).toList() : List.of());
            state.put("canEdit", completeField && untouched);
            state.put("status", completeField ? "PAIRINGS_EDITABLE" : "WAITING_FOR_TEAMS");
            state.put("statusLabel", completeField
                    ? "Pairings editable until the first match starts"
                    : "Waiting for winners from the previous round");
            state.put("source", "DOMESTIC_BRACKET");
            return state;
        }
        return Map.of();
    }

    private void redrawDomesticCup(Competition competition, DrawCommand command) {
        List<CompetitionTeamInfoMatch> roundFixtures = fixtures
                .findAllByCompetitionIdAndRoundAndSeasonNumber(
                        competition.getId(), command.round(), String.valueOf(command.season())).stream()
                .sorted(Comparator.comparingInt(CompetitionTeamInfoMatch::getMatchIndex)
                        .thenComparingLong(CompetitionTeamInfoMatch::getId))
                .toList();
        if (roundFixtures.isEmpty()) throw new IllegalStateException("This cup round has no bracket slots");
        if (roundFixtures.stream().anyMatch(f -> f.getTeam1Id() <= 0 || f.getTeam2Id() <= 0)) {
            throw new IllegalStateException("The previous round must finish before these pairings can be selected");
        }
        if (roundFixtures.stream().anyMatch(f -> f.getTeam1Score() >= 0 || f.getTeam2Score() >= 0)) {
            throw new IllegalStateException("This round has already started and can no longer be redrawn");
        }

        Set<Long> candidates = roundFixtures.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getTeam1Id(), f.getTeam2Id()))
                .collect(Collectors.toSet());
        validatePairings(command, candidates, roundFixtures.size(), false);
        clearPredeterminedScores(competition.getId(), command.season(), command.round());

        List<Pairing> selected = safePairings(command);
        for (int i = 0; i < roundFixtures.size(); i++) {
            CompetitionTeamInfoMatch fixture = roundFixtures.get(i);
            Pairing pairing = selected.get(i);
            fixture.setTeam1Id(pairing.team1Id());
            fixture.setTeam2Id(pairing.team2Id());
            fixture.setTeam1Score(-1);
            fixture.setTeam2Score(-1);
        }
        fixtures.saveAll(roundFixtures);
    }

    private void completeEuropeanDraw(Competition competition, DrawCommand command) {
        CompetitionFormat format = formats.get((int) competition.getTypeId());
        List<CompetitionTeamInfoMatch> existing = fixtures
                .findAllByCompetitionIdAndRoundAndSeasonNumber(
                        competition.getId(), command.round(), String.valueOf(command.season()));
        if (!existing.isEmpty()) throw new IllegalStateException("The draw has already been completed");

        List<CompetitionTeamInfo> roundEntries = entries
                .findAllByRoundAndCompetitionIdAndSeasonNumber(
                        command.round(), competition.getId(), command.season());
        Map<Long, CompetitionTeamInfo> candidateEntries = roundEntries.stream()
                .collect(Collectors.toMap(CompetitionTeamInfo::getTeamId, Function.identity(), (first, ignored) -> first));
        Set<Long> candidates = candidateEntries.keySet();
        if (candidates.size() < 2) throw new IllegalStateException("Not all qualifiers are known yet");

        CalendarEvent drawEvent = drawEvent(competition.getId(), command.season(), command.round(), format);
        if (drawEvent == null) throw new IllegalStateException("No scheduled draw was found for this round");

        if (format.isGroupDrawRound(command.round())) {
            applyManualGroups(competition, command, format, candidateEntries);
            for (int matchday = drawEvent.getMatchday();
                 matchday < drawEvent.getMatchday() + format.groupMatchdayCount(); matchday++) {
                fixtureScheduling.assignMatchDayForNewRound(competition.getId(), matchday, command.season());
            }
        } else {
            validatePairings(command, candidates, candidates.size() / 2, candidates.size() % 2 == 1);
            List<Pairing> selected = safePairings(command);
            for (int i = 0; i < selected.size(); i++) {
                Pairing pairing = selected.get(i);
                europeanCompetitions.saveKnockoutPairing(
                        competition.getId(), command.round(), pairing.team1Id(), pairing.team2Id(), i);
            }
            if (command.byeTeamId() != null) {
                CompetitionTeamInfo bye = new CompetitionTeamInfo();
                bye.setCompetitionId(competition.getId());
                bye.setSeasonNumber(command.season());
                bye.setRound(command.round() + 1);
                bye.setTeamId(command.byeTeamId());
                entries.save(bye);
            }
            fixtureScheduling.assignMatchDayForNewRound(
                    competition.getId(), drawEvent.getMatchday(), command.season());
        }
        completeDrawEvents(competition.getId(), command.season(), drawEvent.getMatchday());
    }

    private void applyManualGroups(Competition competition, DrawCommand command,
                                   CompetitionFormat format,
                                   Map<Long, CompetitionTeamInfo> candidateEntries) {
        List<GroupSelection> selectedGroups = command.groups() == null ? List.of() : command.groups();
        if (selectedGroups.size() != format.groupCount()) {
            throw new IllegalArgumentException("Exactly " + format.groupCount() + " groups are required");
        }
        Set<Integer> groupNumbers = selectedGroups.stream().map(GroupSelection::groupNumber)
                .collect(Collectors.toSet());
        if (groupNumbers.size() != format.groupCount()
                || groupNumbers.stream().anyMatch(n -> n < 1 || n > format.groupCount())) {
            throw new IllegalArgumentException("Group numbers must be unique and between 1 and " + format.groupCount());
        }
        if (selectedGroups.stream().anyMatch(g -> g.teamIds() == null || g.teamIds().size() != format.groupSize())) {
            throw new IllegalArgumentException("Every group must contain exactly " + format.groupSize() + " teams");
        }
        Set<Long> selectedIds = selectedGroups.stream().flatMap(g -> g.teamIds().stream())
                .collect(Collectors.toSet());
        int selectedCount = selectedGroups.stream().mapToInt(g -> g.teamIds().size()).sum();
        if (selectedIds.size() != selectedCount || !selectedIds.equals(candidateEntries.keySet())) {
            throw new IllegalArgumentException("Every eligible team must be selected exactly once");
        }

        List<Long> ranked = new ArrayList<>(candidateEntries.keySet());
        ranked.sort(Comparator.<Long>comparingDouble(
                id -> coefficients.getClubCoefficientRolling(id, command.season() - 1)).reversed()
                .thenComparingLong(Long::longValue));
        Map<Long, Integer> potByTeam = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            potByTeam.put(ranked.get(i), i / format.groupCount() + 1);
        }

        for (GroupSelection group : selectedGroups) {
            for (long teamId : group.teamIds()) {
                CompetitionTeamInfo entry = candidateEntries.get(teamId);
                entry.setGroupNumber(group.groupNumber());
                entry.setPotNumber(potByTeam.getOrDefault(teamId, 0));
            }
        }
        entries.saveAll(candidateEntries.values());
        europeanCompetitions.resetEuropeanStats(competition.getId());
        europeanCompetitions.generateGroupStageFixtures(competition.getId());
    }

    private void validatePairings(DrawCommand command, Set<Long> candidates,
                                  int expectedPairings, boolean expectsBye) {
        List<Pairing> selected = safePairings(command);
        if (selected.size() != expectedPairings) {
            throw new IllegalArgumentException("Exactly " + expectedPairings + " pairings are required");
        }
        Set<Long> selectedIds = new HashSet<>();
        for (Pairing pairing : selected) {
            if (pairing.team1Id() <= 0 || pairing.team2Id() <= 0) {
                throw new IllegalArgumentException("Both teams are required for every pairing");
            }
            if (pairing.team1Id() == pairing.team2Id()) {
                throw new IllegalArgumentException("A team cannot play itself");
            }
            if (!selectedIds.add(pairing.team1Id()) || !selectedIds.add(pairing.team2Id())) {
                throw new IllegalArgumentException("A team can be selected only once");
            }
        }
        if (expectsBye) {
            if (command.byeTeamId() == null || !selectedIds.add(command.byeTeamId())) {
                throw new IllegalArgumentException("Exactly one unique bye team is required");
            }
        } else if (command.byeTeamId() != null) {
            throw new IllegalArgumentException("This round has no bye slot");
        }
        if (!selectedIds.equals(candidates)) {
            throw new IllegalArgumentException("Every eligible team must be selected exactly once");
        }
    }

    private List<Pairing> safePairings(DrawCommand command) {
        return command.pairings() == null ? List.of() : command.pairings();
    }

    private CalendarEvent drawEvent(long competitionId, int season, long round, CompetitionFormat format) {
        return calendarEvents.findAllBySeason(season).stream()
                .filter(e -> "EUROPEAN_DRAW".equals(e.getEventType()))
                .filter(e -> e.getCompetitionId() != null && e.getCompetitionId() == competitionId)
                .filter(e -> format.roundForMatchday(e.getMatchday()) == round)
                .min(Comparator.comparingInt(CalendarEvent::getDay))
                .orElse(null);
    }

    private void completeDrawEvents(long competitionId, int season, int matchday) {
        List<CalendarEvent> events = calendarEvents
                .findBySeasonAndCompetitionIdAndMatchday(season, competitionId, matchday).stream()
                .filter(e -> "EUROPEAN_DRAW".equals(e.getEventType()))
                .toList();
        events.forEach(e -> e.setStatus("COMPLETED"));
        calendarEvents.saveAll(events);
    }

    private void clearPredeterminedScores(long competitionId, int season, long round) {
        List<PredeterminedScore> stale = predeterminedScores.findAllByConsumedFalse().stream()
                .filter(score -> score.getCompetitionId() == competitionId)
                .filter(score -> score.getSeasonNumber() == season)
                .filter(score -> score.getRoundNumber() == round)
                .toList();
        if (!stale.isEmpty()) predeterminedScores.deleteAll(stale);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> participantsFromPots(Object potsValue) {
        if (!(potsValue instanceof List<?> pots)) return List.of();
        Map<Long, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Object potValue : pots) {
            if (!(potValue instanceof Map<?, ?> pot)) continue;
            int potNumber = number(pot.get("potNumber")).intValue();
            Object teamValues = pot.get("teams");
            if (!(teamValues instanceof List<?> potTeams)) continue;
            for (Object teamValue : potTeams) {
                if (!(teamValue instanceof Map<?, ?> team)) continue;
                long teamId = number(team.get("teamId")).longValue();
                Map<String, Object> view = new LinkedHashMap<>((Map<String, Object>) team);
                view.put("potNumber", potNumber);
                unique.put(teamId, view);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private Map<String, Object> teamView(long id, Team team, Double coefficient) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("teamId", id);
        view.put("teamName", team == null ? "Unknown team" : team.getName());
        if (coefficient != null) view.put("coefficient", coefficient);
        return view;
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }
}
