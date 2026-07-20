package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Calendar-owned European draw lifecycle and its read-only UI projection. */
@Service
public class EuropeanDrawService {

    private final CalendarEventRepository calendarEventRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final GameCalendarRepository gameCalendarRepository;
    private final TeamRepository teamRepository;
    private final CompetitionFormatConfig formats;
    private final CompetitionProgressService progressService;
    private final EuropeanCoefficientService coefficientService;
    private final EuropeanFixturePreparationService preparationService;
    private final CalendarService calendarService;

    public EuropeanDrawService(CalendarEventRepository calendarEventRepository,
                               CompetitionRepository competitionRepository,
                               CompetitionTeamInfoRepository entryRepository,
                               CompetitionTeamInfoMatchRepository fixtureRepository,
                               GameCalendarRepository gameCalendarRepository,
                               TeamRepository teamRepository,
                               CompetitionFormatConfig formats,
                               CompetitionProgressService progressService,
                               EuropeanCoefficientService coefficientService,
                               EuropeanFixturePreparationService preparationService,
                               CalendarService calendarService) {
        this.calendarEventRepository = calendarEventRepository;
        this.competitionRepository = competitionRepository;
        this.entryRepository = entryRepository;
        this.fixtureRepository = fixtureRepository;
        this.gameCalendarRepository = gameCalendarRepository;
        this.teamRepository = teamRepository;
        this.formats = formats;
        this.progressService = progressService;
        this.coefficientService = coefficientService;
        this.preparationService = preparationService;
        this.calendarService = calendarService;
    }

    /**
     * Backfills dedicated draw events for careers created by the old calendar.
     * Existing fixtures are represented as completed draws; an undrawn stage that
     * is already inside its seven-day window is scheduled on the next reachable morning.
     */
    @Transactional
    public void ensureDrawEventsForSeason(int season) {
        List<CalendarEvent> seasonEvents = calendarEventRepository.findAllBySeason(season);
        List<CalendarEvent> matchEvents = seasonEvents.stream()
                .filter(e -> "MATCH_EUROPEAN".equals(e.getEventType()) && e.getCompetitionId() != null)
                .filter(e -> e.getLegNumber() == 0 || e.getLegNumber() == 1)
                .sorted(Comparator.comparingInt(CalendarEvent::getDay))
                .toList();
        Map<String, List<CalendarEvent>> drawsByStage = seasonEvents.stream()
                .filter(e -> "EUROPEAN_DRAW".equals(e.getEventType()) && e.getCompetitionId() != null)
                .collect(Collectors.groupingBy(this::stageKey));
        GameCalendar current = gameCalendarRepository.findBySeason(season).stream().findFirst().orElse(null);

        List<CalendarEvent> missing = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>();
        for (CalendarEvent match : matchEvents) {
            String key = stageKey(match);
            if (!handled.add(key)) continue;
            Competition competition = competitionRepository.findById(match.getCompetitionId()).orElse(null);
            if (competition == null) continue;
            CompetitionFormat format = formats.get((int) competition.getTypeId());
            int round = format.roundForMatchday(match.getMatchday());
            if (format.isGroupRound(round) && !format.isGroupDrawRound(round)) continue;

            boolean fixturesExist = hasFixtures(competition.getId(), round, season);
            boolean hasUsableEvent = drawsByStage.getOrDefault(key, List.of()).stream()
                    .anyMatch(e -> !"COMPLETED".equals(e.getStatus()) || fixturesExist);
            if (hasUsableEvent) continue;

            int intendedDay = Math.max(1, match.getDay() - FixtureSchedulingService.EUROPEAN_DRAW_LEAD_DAYS);
            int scheduledDay = intendedDay;
            if (!fixturesExist && current != null && current.getCurrentDay() > intendedDay) {
                scheduledDay = current.getCurrentDay() + ("MORNING".equals(current.getCurrentPhase()) ? 0 : 1);
                scheduledDay = Math.min(scheduledDay, match.getDay());
            }
            CalendarEvent draw = newDrawEvent(competition, season, match.getMatchday(), round, scheduledDay);
            draw.setStatus(fixturesExist ? "COMPLETED" : "PENDING");
            missing.add(draw);
        }
        if (!missing.isEmpty()) calendarEventRepository.saveAll(missing);
    }

    /** Executes the scheduled draw and postpones it safely when a qualifier is still unknown. */
    @Transactional
    public Map<String, Object> executeDraw(CalendarEvent event, GameCalendar calendar) {
        boolean drawn = preparationService.prepareMatchday(
                event.getCompetitionId(), event.getMatchday(), event.getSeason());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("drawCompleted", drawn);
        if (drawn) {
            result.put("title", stageLabel(event) + " Draw");
            result.put("details", "Draw completed: " + stageLabel(event));
            result.put("draw", drawState(event.getCompetitionId(), event.getSeason(), event.getMatchday()));
            return result;
        }

        int matchDay = firstMatchDay(event.getCompetitionId(), event.getSeason(), event.getMatchday());
        int retryDay = calendar.getCurrentDay() + 1;
        if (matchDay > 0 && retryDay < matchDay && !hasPendingDraw(
                event.getCompetitionId(), event.getSeason(), event.getMatchday())) {
            Competition competition = competitionRepository.findById(event.getCompetitionId()).orElse(null);
            if (competition != null) {
                int round = formats.get((int) competition.getTypeId()).roundForMatchday(event.getMatchday());
                CalendarEvent retry = newDrawEvent(competition, event.getSeason(), event.getMatchday(), round, retryDay);
                retry.setTitle(retry.getTitle() + " (postponed)");
                calendarEventRepository.save(retry);
            }
        }
        result.put("title", stageLabel(event) + " Draw");
        result.put("details", "Draw postponed: the complete list of qualifiers is not available yet.");
        result.put("draw", drawState(event.getCompetitionId(), event.getSeason(), event.getMatchday()));
        return result;
    }

    /** All real draw stages, including their pots before the pairing is revealed. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> drawStates(long competitionId, int season) {
        List<CalendarEvent> events = calendarEventRepository.findAllBySeason(season).stream()
                .filter(e -> "EUROPEAN_DRAW".equals(e.getEventType()))
                .filter(e -> e.getCompetitionId() != null && e.getCompetitionId() == competitionId)
                .sorted(Comparator.comparingInt(CalendarEvent::getDay))
                .toList();
        Map<Integer, CalendarEvent> byMatchday = new LinkedHashMap<>();
        for (CalendarEvent event : events) byMatchday.put(event.getMatchday(), event);
        return byMatchday.values().stream()
                .map(e -> drawState(competitionId, season, e.getMatchday()))
                .toList();
    }

    public Map<String, Object> drawState(long competitionId, int season, int matchday) {
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return Map.of();
        CompetitionFormat format = formats.get((int) competition.getTypeId());
        int round = format.roundForMatchday(matchday);
        List<CompetitionTeamInfo> entries = entryRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(round, competitionId, season);
        List<Long> participants = entries.stream().map(CompetitionTeamInfo::getTeamId)
                .distinct().collect(Collectors.toCollection(ArrayList::new));
        List<CompetitionTeamInfoMatch> fixtures = fixtureRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(competitionId, round, String.valueOf(season));
        List<CalendarEvent> stageEvents = calendarEventRepository
                .findBySeasonAndCompetitionIdAndMatchday(season, competitionId, matchday);
        int drawDay = stageEvents.stream().filter(e -> "EUROPEAN_DRAW".equals(e.getEventType()))
                .mapToInt(CalendarEvent::getDay).min().orElse(0);
        int matchDay = stageEvents.stream().filter(e -> "MATCH_EUROPEAN".equals(e.getEventType()))
                .mapToInt(CalendarEvent::getDay).min().orElse(0);
        int currentDay = gameCalendarRepository.findBySeason(season).stream().findFirst()
                .map(GameCalendar::getCurrentDay).orElse(season < currentSeason() ? 365 : 1);
        int expectedTeams = expectedTeams((int) competition.getTypeId(), format, round, participants.size());
        boolean completeField = participants.size() >= expectedTeams;
        boolean completed = !fixtures.isEmpty();
        String status = completed ? "DRAW_COMPLETED" : completeField ? "POTS_PUBLISHED" : "WAITING_FOR_TEAMS";

        Map<Long, Team> teams = teamRepository.findAllById(participants).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        List<Long> ranked = new ArrayList<>(participants);
        ranked.sort(coefficientComparator(teams, season));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("competitionId", competitionId);
        state.put("competitionName", competition.getName());
        state.put("season", season);
        state.put("matchday", matchday);
        state.put("round", round);
        state.put("stageLabel", progressService.roundLabel(competitionId, round, season));
        state.put("drawDay", drawDay);
        state.put("drawDate", drawDay > 0 ? calendarService.getDateDisplay(drawDay) : null);
        state.put("matchDay", matchDay);
        state.put("matchDate", matchDay > 0 ? calendarService.getDateDisplay(matchDay) : null);
        state.put("daysUntilDraw", drawDay > 0 ? Math.max(0, drawDay - currentDay) : null);
        state.put("status", status);
        state.put("statusLabel", statusLabel(status, drawDay, currentDay));
        state.put("knownTeams", participants.size());
        state.put("expectedTeams", expectedTeams);
        state.put("pots", pots(competitionId, season, (int) competition.getTypeId(), format,
                round, ranked, teams));
        state.put("pairings", completed && !format.isGroupDrawRound(round)
                ? pairingViews(fixtures, teams, season) : List.of());
        state.put("groups", completed && format.isGroupDrawRound(round)
                ? groupViews(entries, teams, season) : List.of());
        return state;
    }

    private List<Map<String, Object>> pots(long competitionId, int season, int typeId,
                                           CompetitionFormat format, int round,
                                           List<Long> ranked, Map<Long, Team> teams) {
        List<List<Long>> teamPots = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (format.isGroupDrawRound(round)) {
            int potCount = Math.max(1, format.groupSize());
            int teamsPerPot = Math.max(1, format.groupCount());
            for (int p = 0; p < potCount; p++) {
                int from = Math.min(p * teamsPerPot, ranked.size());
                int to = Math.min(from + teamsPerPot, ranked.size());
                teamPots.add(new ArrayList<>(ranked.subList(from, to)));
                labels.add("Pot " + (p + 1));
            }
        } else if (typeId == 5 && round == format.playoffRound()) {
            long locId = competitionRepository.findAll().stream().filter(c -> c.getTypeId() == 4)
                    .map(Competition::getId).findFirst().orElse(-1L);
            Set<Long> locGroupTeams = entryRepository.findAllBySeasonNumber(season).stream()
                    .filter(e -> e.getCompetitionId() == locId && e.getGroupNumber() > 0)
                    .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
            teamPots.add(ranked.stream().filter(locGroupTeams::contains).toList());
            teamPots.add(ranked.stream().filter(id -> !locGroupTeams.contains(id)).toList());
            labels.add("Pot 1 · League of Champions third-place teams");
            labels.add("Pot 2 · Stars Cup runners-up");
        } else if (format.isSeededKnockoutDrawRound(round) || format.isPreliminaryRound(round)) {
            int firstPotSize = (ranked.size() + 1) / 2;
            teamPots.add(new ArrayList<>(ranked.subList(0, firstPotSize)));
            teamPots.add(new ArrayList<>(ranked.subList(firstPotSize, ranked.size())));
            labels.add("Pot 1 · Seeded");
            labels.add("Pot 2 · Unseeded");
        } else {
            teamPots.add(ranked);
            labels.add("Open draw");
        }

        List<Map<String, Object>> views = new ArrayList<>();
        for (int i = 0; i < teamPots.size(); i++) {
            Map<String, Object> pot = new LinkedHashMap<>();
            pot.put("potNumber", i + 1);
            pot.put("label", labels.get(i));
            pot.put("teams", teamPots.get(i).stream()
                    .map(id -> teamView(id, teams.get(id), season)).toList());
            views.add(pot);
        }
        return views;
    }

    private List<Map<String, Object>> pairingViews(List<CompetitionTeamInfoMatch> fixtures,
                                                    Map<Long, Team> existingTeams, int season) {
        Set<Long> ids = fixtures.stream().flatMap(f -> java.util.stream.Stream.of(f.getTeam1Id(), f.getTeam2Id()))
                .collect(Collectors.toSet());
        Map<Long, Team> teams = new LinkedHashMap<>(existingTeams);
        teamRepository.findAllById(ids).forEach(t -> teams.put(t.getId(), t));
        return fixtures.stream()
                .filter(f -> f.getLegNumber() != 2)
                .sorted(Comparator.comparingInt(CompetitionTeamInfoMatch::getMatchIndex)
                        .thenComparingLong(CompetitionTeamInfoMatch::getId))
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("team1", teamView(f.getTeam1Id(), teams.get(f.getTeam1Id()), season));
                    row.put("team2", teamView(f.getTeam2Id(), teams.get(f.getTeam2Id()), season));
                    row.put("twoLeg", f.getLegNumber() == 1);
                    return row;
                }).toList();
    }

    private List<Map<String, Object>> groupViews(List<CompetitionTeamInfo> entries,
                                                 Map<Long, Team> teams, int season) {
        return entries.stream().filter(e -> e.getGroupNumber() > 0)
                .collect(Collectors.groupingBy(CompetitionTeamInfo::getGroupNumber))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).map(group -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("groupNumber", group.getKey());
                    row.put("teams", group.getValue().stream()
                            .sorted(Comparator.comparingInt(CompetitionTeamInfo::getPotNumber))
                            .map(e -> teamView(e.getTeamId(), teams.get(e.getTeamId()), season)).toList());
                    return row;
                }).toList();
    }

    private Map<String, Object> teamView(long teamId, Team team, int season) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("teamId", teamId);
        view.put("teamName", team == null ? "Unknown" : team.getName());
        view.put("coefficient", round2(coefficientService.getClubCoefficientRolling(teamId, season - 1)));
        view.put("reputation", team == null ? 0 : team.getReputation());
        return view;
    }

    private Comparator<Long> coefficientComparator(Map<Long, Team> teams, int season) {
        return Comparator.<Long>comparingDouble(id -> coefficientService
                        .getClubCoefficientRolling(id, season - 1)).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Long id) -> teams.get(id) == null ? 0 : teams.get(id).getReputation()).reversed())
                .thenComparingLong(Long::longValue);
    }

    private int expectedTeams(int typeId, CompetitionFormat format, int round, int known) {
        if (format.isPreliminaryRound(round) && format.europeanPlan() != null) {
            return format.europeanPlan().stageForRound(round).bracketSize();
        }
        if (format.isGroupDrawRound(round)) return format.groupCount() * format.groupSize();
        if (typeId == 5 && round == format.playoffRound()) {
            return format.groupCount() * format.playoffQualifyPerGroup() + formats.get(4).groupCount();
        }
        return Math.max(2, known);
    }

    private String statusLabel(String status, int drawDay, int currentDay) {
        return switch (status) {
            case "DRAW_COMPLETED" -> "Draw completed";
            case "WAITING_FOR_TEAMS" -> "Waiting for all qualifiers";
            default -> drawDay > currentDay ? "Pots published · draw in " + (drawDay - currentDay) + " days"
                    : "Pots published · draw today";
        };
    }

    private CalendarEvent newDrawEvent(Competition competition, int season, int matchday,
                                       int round, int day) {
        CalendarEvent event = new CalendarEvent();
        event.setSeason(season);
        event.setDay(day);
        event.setPhase("MORNING");
        event.setEventType("EUROPEAN_DRAW");
        event.setCompetitionId(competition.getId());
        event.setMatchday(matchday);
        event.setStatus("PENDING");
        event.setPriority(0);
        event.setTitle(competition.getName() + " - "
                + progressService.roundLabel(competition.getId(), round, season) + " Draw");
        event.setDescription("The draw takes place seven days before the first match of the stage.");
        return event;
    }

    private boolean hasFixtures(long competitionId, int round, int season) {
        return !fixtureRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                competitionId, round, String.valueOf(season)).isEmpty();
    }

    private boolean hasPendingDraw(long competitionId, int season, int matchday) {
        return calendarEventRepository.findBySeasonAndCompetitionIdAndMatchday(season, competitionId, matchday)
                .stream().anyMatch(e -> "EUROPEAN_DRAW".equals(e.getEventType())
                        && "PENDING".equals(e.getStatus()));
    }

    private int firstMatchDay(long competitionId, int season, int matchday) {
        return calendarEventRepository.findBySeasonAndCompetitionIdAndMatchday(season, competitionId, matchday)
                .stream().filter(e -> "MATCH_EUROPEAN".equals(e.getEventType()))
                .mapToInt(CalendarEvent::getDay).min().orElse(0);
    }

    private String stageLabel(CalendarEvent event) {
        Competition competition = competitionRepository.findById(event.getCompetitionId()).orElse(null);
        if (competition == null) return "European round";
        int round = formats.get((int) competition.getTypeId()).roundForMatchday(event.getMatchday());
        return competition.getName() + " · "
                + progressService.roundLabel(competition.getId(), round, event.getSeason());
    }

    private String stageKey(CalendarEvent event) {
        return event.getCompetitionId() + ":" + event.getMatchday();
    }

    private int currentSeason() {
        return gameCalendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
