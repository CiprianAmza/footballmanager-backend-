package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.EuropeanFormatPlan;
import com.footballmanagergamesimulator.config.EuropeanStage;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Backend-owned competition vocabulary and team lifecycle state. */
@Service
public class CompetitionProgressService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final CompetitionTeamInfoDetailRepository resultRepository;
    private final TeamCompetitionDetailRepository standingsRepository;
    private final TeamRepository teamRepository;
    private final CompetitionFormatConfig formats;
    private final CalendarEventRepository calendarEventRepository;
    private final GameCalendarRepository gameCalendarRepository;
    private final CalendarService calendarService;

    public CompetitionProgressService(CompetitionRepository competitionRepository,
                                      CompetitionTeamInfoRepository entryRepository,
                                      CompetitionTeamInfoMatchRepository fixtureRepository,
                                      CompetitionTeamInfoDetailRepository resultRepository,
                                      TeamCompetitionDetailRepository standingsRepository,
                                      TeamRepository teamRepository,
                                      CompetitionFormatConfig formats,
                                      CalendarEventRepository calendarEventRepository,
                                      GameCalendarRepository gameCalendarRepository,
                                      CalendarService calendarService) {
        this.competitionRepository = competitionRepository;
        this.entryRepository = entryRepository;
        this.fixtureRepository = fixtureRepository;
        this.resultRepository = resultRepository;
        this.standingsRepository = standingsRepository;
        this.teamRepository = teamRepository;
        this.formats = formats;
        this.calendarEventRepository = calendarEventRepository;
        this.gameCalendarRepository = gameCalendarRepository;
        this.calendarService = calendarService;
    }

    public List<Map<String, Object>> stages(long competitionId, long season) {
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return List.of();
        int type = (int) competition.getTypeId();
        List<Long> rounds = allRounds(competitionId, season);
        if (type == 4) {
            EuropeanFormatPlan plan = formats.get(type).europeanPlan();
            rounds = plan == null ? rounds : plan.stages().stream().map(s -> (long) s.round()).toList();
        } else if (type == 5) {
            rounds = java.util.stream.LongStream.rangeClosed(1, formats.get(5).finalRound()).boxed().toList();
        } else if (type == 6) {
            rounds = List.of(1L);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (long round : rounds) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("round", round);
            stage.put("label", roundLabel(competition, round, season));
            stage.put("phase", phase(competition, round, season));
            stage.put("twoLeg", formats.isTwoLeg(type, round));
            result.add(stage);
        }
        return result;
    }

    public String roundLabel(long competitionId, long round, long season) {
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        return competition == null ? "Round " + round : roundLabel(competition, round, season);
    }

    public Map<String, Object> teamProgress(long teamId, long competitionId, long season) {
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return Map.of();

        List<CompetitionTeamInfo> entries = entryRepository.findAllByTeamIdAndSeasonNumber(teamId, season).stream()
                .filter(e -> e.getCompetitionId() == competitionId).toList();
        List<CompetitionTeamInfoMatch> fixtures = fixtureRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(season), teamId).stream()
                .filter(f -> f.getCompetitionId() == competitionId).toList();
        List<CompetitionTeamInfoDetail> allResults = resultRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season);
        List<CompetitionTeamInfoDetail> results = allResults.stream()
                .filter(r -> r.getTeam1Id() == teamId || r.getTeam2Id() == teamId).toList();

        long entryRound = entries.stream().mapToLong(CompetitionTeamInfo::getRound).min()
                .orElseGet(() -> fixtures.stream().mapToLong(CompetitionTeamInfoMatch::getRound).min().orElse(1));
        Set<String> playedKeys = results.stream().map(this::resultKey).collect(Collectors.toSet());
        List<CompetitionTeamInfoMatch> upcoming = fixtures.stream()
                .filter(f -> !playedKeys.contains(fixtureKey(f)))
                .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound)
                        .thenComparingInt(CompetitionTeamInfoMatch::getDay)).toList();

        long latestRound = results.stream().mapToLong(CompetitionTeamInfoDetail::getRoundId).max()
                .orElse(entryRound);
        long highestEntryRound = entries.stream().mapToLong(CompetitionTeamInfo::getRound).max().orElse(entryRound);
        long currentRound = upcoming.isEmpty() ? Math.max(latestRound, highestEntryRound) : upcoming.get(0).getRound();
        int groupNumber = entries.stream().mapToInt(CompetitionTeamInfo::getGroupNumber)
                .filter(n -> n > 0).findFirst().orElse(0);
        GroupStageTiming groupTiming = groupStageTiming(competition, season);
        List<Map<String, Object>> groupTable = groupNumber == 0 ? List.of()
                : groupStandings(competition, season, groupNumber,
                        groupTiming.started(), groupTiming.completed());
        Map<String, Object> groupRow = groupTable.stream()
                .filter(row -> ((Number) row.get("teamId")).longValue() == teamId)
                .findFirst().orElse(null);

        CompetitionTeamInfoDetail elimination = results.stream()
                .filter(r -> r.getWinnerTeamId() != null && r.getWinnerTeamId() != teamId
                        && !"FIRST_LEG".equals(r.getDecidedBy())
                        && ("QUALIFYING".equals(phase(competition, r.getRoundId(), season))
                            || "KNOCKOUT".equals(phase(competition, r.getRoundId(), season))
                            || "FINAL".equals(phase(competition, r.getRoundId(), season))))
                .max(Comparator.comparingLong(CompetitionTeamInfoDetail::getRoundId)
                        .thenComparingLong(CompetitionTeamInfoDetail::getId)).orElse(null);
        long finalRound = finalRound(competition, season);
        CompetitionTeamInfoDetail finalResult = results.stream()
                .filter(r -> r.getRoundId() == finalRound && r.getWinnerTeamId() != null)
                .max(Comparator.comparingLong(CompetitionTeamInfoDetail::getId)).orElse(null);

        String status = "ACTIVE";
        String stageReached = normalizedStageLabel(competition, currentRound, season);
        Long eliminatedById = null;
        String eliminatedByName = null;
        CompetitionTeamInfoDetail eliminationResult = null;
        boolean enteredGroupStage = entries.stream()
                .anyMatch(entry -> "GROUP".equals(phase(competition, entry.getRound(), season)));
        if (finalResult != null) {
            currentRound = finalRound;
            if (finalResult.getWinnerTeamId() == teamId) {
                status = "WINNER";
                stageReached = "Final";
            } else {
                status = "RUNNER_UP";
                stageReached = "Final";
                eliminatedById = finalResult.getWinnerTeamId();
                eliminationResult = finalResult;
            }
        } else if (elimination != null && upcoming.stream().noneMatch(f -> f.getRound() > elimination.getRoundId())) {
            status = "ELIMINATED";
            currentRound = elimination.getRoundId();
            stageReached = normalizedStageLabel(competition, currentRound, season);
            eliminatedById = elimination.getWinnerTeamId();
            eliminationResult = elimination;
        } else if (isGroupCompetition(competition) && upcoming.isEmpty()
                && groupRow != null && Boolean.TRUE.equals(groupRow.get("qualificationFinal"))) {
            String route = String.valueOf(groupRow.get("qualificationRoute"));
            if ("DIRECT".equals(route) || "PLAYOFF".equals(route)) {
                status = "QUALIFIED";
                stageReached = "Group Stage";
            } else if ("DROPPED_TO_STARS_CUP".equals(route)) {
                status = "DROPPED_TO_STARS_CUP";
                stageReached = "Group Stage";
            } else {
                status = "ELIMINATED";
                stageReached = "Group Stage";
                eliminationResult = results.stream()
                        .filter(result -> "GROUP".equals(phase(competition, result.getRoundId(), season)))
                        .max(Comparator.comparingLong(CompetitionTeamInfoDetail::getRoundId)
                                .thenComparingLong(CompetitionTeamInfoDetail::getId))
                        .orElse(null);
            }
        } else if (isGroupCompetition(competition) && enteredGroupStage
                && !groupTiming.started()) {
            // Direct group-stage entrants exist before qualifying is played and
            // often before their group fixtures have been drawn. They are not
            // eliminated merely because there is no current fixture/table yet.
            status = "QUALIFIED_FOR_STAGE";
            currentRound = formats.get((int) competition.getTypeId()).groupStartRound();
            stageReached = "Group Stage";
        } else if (results.isEmpty()) {
            status = "NOT_STARTED";
            currentRound = upcoming.isEmpty() ? entryRound : upcoming.get(0).getRound();
            stageReached = normalizedStageLabel(competition, currentRound, season);
        }
        if (eliminatedById != null) {
            eliminatedByName = teamRepository.findById(eliminatedById).map(Team::getName).orElse("Unknown");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("competitionId", competitionId);
        view.put("competitionName", competition.getName());
        view.put("competitionTypeId", competition.getTypeId());
        view.put("season", season);
        view.put("entryRound", entryRound);
        view.put("entryStage", normalizedStageLabel(competition, entryRound, season));
        view.put("currentRound", currentRound);
        view.put("currentStage", roundLabel(competition, currentRound, season));
        view.put("stageReached", stageReached);
        view.put("status", status);
        view.put("statusLabel", statusLabel(status, stageReached, eliminatedByName));
        view.put("eliminatedByTeamId", eliminatedById);
        view.put("eliminatedByTeamName", eliminatedByName);
        view.put("groupNumber", groupNumber == 0 ? null : groupNumber);
        view.put("qualified", groupRow == null ? null : groupRow.get("qualified"));
        view.put("qualificationRoute", groupRow == null ? null : groupRow.get("qualificationRoute"));
        long displayedRound = currentRound;
        List<Map<String, Object>> currentFixtures = fixtureViews(competition, upcoming.stream()
                .filter(f -> f.getRound() == displayedRound).toList(), allResults);
        view.put("currentFixtures", currentFixtures);
        view.put("start", startView(entryRound,
                normalizedStageLabel(competition, entryRound, season),
                "NOT_STARTED".equals(status) || "QUALIFIED_FOR_STAGE".equals(status)));
        view.put("nextMatch", currentFixtures.isEmpty() ? null
                : nextMatchView(currentFixtures.get(0), teamId));
        view.put("elimination", eliminationResult == null ? null
                : eliminationView(competition, eliminationResult, teamId, eliminatedById, eliminatedByName, season));
        view.put("groupStandings", groupTable);
        return view;
    }

    private Map<String, Object> startView(long round, String stage, boolean notStarted) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("round", round);
        view.put("stage", stage);
        view.put("started", !notStarted);
        return view;
    }

    private Map<String, Object> nextMatchView(Map<String, Object> fixture, long teamId) {
        long team1Id = ((Number) fixture.get("team1Id")).longValue();
        boolean home = team1Id == teamId;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("round", fixture.get("round"));
        view.put("stage", fixture.get("stage"));
        view.put("day", fixture.get("day"));
        view.put("date", fixture.get("date"));
        view.put("legNumber", fixture.get("legNumber"));
        view.put("opponentTeamId", home ? fixture.get("team2Id") : fixture.get("team1Id"));
        view.put("opponentTeamName", home ? fixture.get("team2Name") : fixture.get("team1Name"));
        view.put("venue", home ? "HOME" : "AWAY");
        return view;
    }

    private Map<String, Object> eliminationView(Competition competition,
                                                CompetitionTeamInfoDetail result,
                                                long teamId,
                                                Long eliminatedById,
                                                String eliminatedByName,
                                                long season) {
        int day = result.getDay();
        if (day <= 0) {
            day = fixtureRepository.findAllBySeasonNumberAndTeamId(String.valueOf(season), teamId).stream()
                    .filter(fixture -> fixture.getCompetitionId() == competition.getId()
                            && fixture.getRound() == result.getRoundId()
                            && fixture.getTeam1Id() == result.getTeam1Id()
                            && fixture.getTeam2Id() == result.getTeam2Id()
                            && fixture.getLegNumber() == result.getLegNumber())
                    .mapToInt(CompetitionTeamInfoMatch::getDay).filter(value -> value > 0)
                    .findFirst().orElse(0);
        }
        if (day <= 0) {
            CompetitionFormat format = formats.get((int) competition.getTypeId());
            day = calendarEventRepository.findAllBySeason((int) season).stream()
                    .filter(event -> event.getCompetitionId() != null
                            && event.getCompetitionId() == competition.getId())
                    .filter(event -> event.getEventType() != null
                            && event.getEventType().startsWith("MATCH_"))
                    .filter(event -> format.roundForMatchday(event.getMatchday()) == result.getRoundId())
                    .filter(event -> result.getLegNumber() == 0
                            || event.getLegNumber() == result.getLegNumber())
                    .mapToInt(CalendarEvent::getDay).filter(value -> value > 0)
                    .max().orElse(0);
        }
        boolean groupTableExit = "GROUP".equals(phase(competition, result.getRoundId(), season))
                && eliminatedById == null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("round", result.getRoundId());
        view.put("stage", normalizedStageLabel(competition, result.getRoundId(), season));
        view.put("day", day > 0 ? day : null);
        view.put("date", day > 0 ? calendarService.getDateDisplay(day) : null);
        view.put("byTeamId", eliminatedById);
        view.put("byTeamName", eliminatedByName);
        view.put("reason", groupTableExit ? "GROUP_TABLE" : "KNOCKOUT_LOSS");
        view.put("score", result.getScore());
        view.put("decidedBy", result.getDecidedBy());
        return view;
    }

    private List<Map<String, Object>> fixtureViews(Competition competition,
                                                   List<CompetitionTeamInfoMatch> fixtures,
                                                   List<CompetitionTeamInfoDetail> results) {
        Set<Long> ids = fixtures.stream().flatMap(f -> java.util.stream.Stream.of(f.getTeam1Id(), f.getTeam2Id()))
                .filter(id -> id > 0).collect(Collectors.toSet());
        Map<Long, String> names = teamRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        Map<String, CompetitionTeamInfoDetail> resultByKey = new HashMap<>();
        for (CompetitionTeamInfoDetail result : results) resultByKey.put(resultKey(result), result);
        List<Map<String, Object>> views = new ArrayList<>();
        for (CompetitionTeamInfoMatch fixture : fixtures) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("team1Id", fixture.getTeam1Id());
            view.put("team2Id", fixture.getTeam2Id());
            view.put("team1Name", names.getOrDefault(fixture.getTeam1Id(), "TBD"));
            view.put("team2Name", names.getOrDefault(fixture.getTeam2Id(), "TBD"));
            view.put("round", fixture.getRound());
            view.put("stage", normalizedStageLabel(competition, fixture.getRound(),
                    Long.parseLong(fixture.getSeasonNumber())));
            view.put("day", fixture.getDay());
            view.put("date", fixture.getDay() > 0 ? calendarService.getDateDisplay(fixture.getDay()) : null);
            view.put("legNumber", fixture.getLegNumber());
            CompetitionTeamInfoDetail played = resultByKey.get(fixtureKey(fixture));
            view.put("score", played == null ? null : played.getScore());
            views.add(view);
        }
        return views;
    }

    private List<Map<String, Object>> groupStandings(Competition competition, long season, int groupNumber,
                                                     boolean groupPhaseStarted,
                                                     boolean groupPhaseCompleted) {
        long competitionId = competition.getId();
        Set<Long> groupTeams = entryRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .filter(e -> e.getGroupNumber() == groupNumber).map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, Team> teams = teamRepository.findAllById(groupTeams).stream()
                .collect(Collectors.toMap(Team::getId, t -> t));
        Map<Long, GroupStanding> standings = new HashMap<>();
        groupTeams.forEach(teamId -> standings.put(teamId, new GroupStanding(teamId)));
        resultRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .filter(result -> groupPhaseStarted)
                .filter(result -> "GROUP".equals(phase(competition, result.getRoundId(), season)))
                .filter(result -> groupTeams.contains(result.getTeam1Id())
                        && groupTeams.contains(result.getTeam2Id()))
                .forEach(result -> {
                    int[] score = parseScore(result.getScore());
                    if (score == null) return;
                    GroupStanding home = standings.get(result.getTeam1Id());
                    GroupStanding away = standings.get(result.getTeam2Id());
                    home.record(score[0], score[1]);
                    away.record(score[1], score[0]);
                });
        List<GroupStanding> details = standings.values().stream()
                .sorted(Comparator.comparingInt(GroupStanding::points).reversed()
                        .thenComparing(Comparator.comparingInt(GroupStanding::goalDifference).reversed())
                        .thenComparing(Comparator.comparingInt(GroupStanding::goalsFor).reversed())
                        .thenComparingLong(GroupStanding::teamId))
                .toList();
        CompetitionFormat format = formats.get((int) competition.getTypeId());
        int directPlaces = format.qualifyPerGroupToKnockout();
        int playoffPlaces = format.playoffQualifyPerGroup();
        int expectedGames = Math.max(1, format.groupMatchdayCount());
        boolean qualificationFinal = groupPhaseCompleted && !details.isEmpty()
                && details.stream().allMatch(row -> row.played() >= expectedGames);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < details.size(); i++) {
            GroupStanding d = details.get(i);
            int position = i + 1;
            String route = null;
            if (qualificationFinal) {
                if (position <= directPlaces) route = "DIRECT";
                else if (position <= directPlaces + playoffPlaces) route = "PLAYOFF";
                else if (format.thirdPlaceDropTypeId() > 0
                        && position == directPlaces + playoffPlaces + 1) route = "DROPPED_TO_STARS_CUP";
                else route = "ELIMINATED";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", position);
            row.put("teamId", d.teamId());
            row.put("teamName", teams.containsKey(d.teamId()) ? teams.get(d.teamId()).getName() : "Unknown");
            row.put("played", d.played());
            row.put("wins", d.wins());
            row.put("draws", d.draws());
            row.put("losses", d.losses());
            row.put("goalsFor", d.goalsFor());
            row.put("goalsAgainst", d.goalsAgainst());
            row.put("goalDifference", d.goalDifference());
            row.put("points", d.points());
            row.put("qualified", qualificationFinal
                    ? "DIRECT".equals(route) || "PLAYOFF".equals(route)
                    : null);
            row.put("qualificationRoute", route);
            row.put("qualificationFinal", qualificationFinal);
            rows.add(row);
        }
        return rows;
    }

    /**
     * A completed-looking table alone is not enough to close the current
     * season's group stage. Imported/legacy saves can contain rows tagged with
     * the new season before that season's scheduled group matches have started.
     * The calendar is the authority for current-season lifecycle state.
     */
    private GroupStageTiming groupStageTiming(Competition competition, long season) {
        int currentSeason = gameCalendarRepository.findTopByOrderBySeasonDesc()
                .map(calendar -> calendar.getSeason()).orElse((int) season);
        if (season < currentSeason) return new GroupStageTiming(true, true);
        if (season > currentSeason || !isGroupCompetition(competition)) {
            return new GroupStageTiming(false, false);
        }

        List<CalendarEvent> groupEvents = calendarEventRepository.findAllBySeason((int) season).stream()
                .filter(event -> event.getCompetitionId() != null
                        && event.getCompetitionId() == competition.getId())
                .filter(event -> event.getEventType() != null
                        && event.getEventType().startsWith("MATCH_"))
                .filter(event -> phase(competition,
                        formats.get((int) competition.getTypeId()).roundForMatchday(event.getMatchday()),
                        season).equals("GROUP"))
                .toList();
        if (groupEvents.isEmpty()) return new GroupStageTiming(false, false);

        boolean started = groupEvents.stream().anyMatch(event ->
                "PROCESSING".equals(event.getStatus()) || "COMPLETED".equals(event.getStatus()));
        long scheduledRounds = groupEvents.stream().map(CalendarEvent::getMatchday).distinct().count();
        int expectedRounds = formats.get((int) competition.getTypeId()).groupMatchdayCount();
        boolean completed = scheduledRounds >= expectedRounds && groupEvents.stream().allMatch(event ->
                "COMPLETED".equals(event.getStatus()) || "SKIPPED".equals(event.getStatus()));
        return new GroupStageTiming(started, completed);
    }

    private record GroupStageTiming(boolean started, boolean completed) {}

    private int[] parseScore(String score) {
        if (score == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+)\\s*-\\s*(\\d+)").matcher(score);
        if (!matcher.find()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static final class GroupStanding {
        private final long teamId;
        private int played;
        private int wins;
        private int draws;
        private int losses;
        private int goalsFor;
        private int goalsAgainst;
        private int points;

        private GroupStanding(long teamId) { this.teamId = teamId; }
        private void record(int scored, int conceded) {
            played++;
            goalsFor += scored;
            goalsAgainst += conceded;
            if (scored > conceded) { wins++; points += 3; }
            else if (scored == conceded) { draws++; points++; }
            else losses++;
        }
        private long teamId() { return teamId; }
        private int played() { return played; }
        private int wins() { return wins; }
        private int draws() { return draws; }
        private int losses() { return losses; }
        private int goalsFor() { return goalsFor; }
        private int goalsAgainst() { return goalsAgainst; }
        private int goalDifference() { return goalsFor - goalsAgainst; }
        private int points() { return points; }
    }

    private String statusLabel(String status, String stage, String eliminator) {
        return switch (status) {
            case "WINNER" -> "Winner";
            case "RUNNER_UP" -> "Runner-up";
            case "QUALIFIED" -> "Qualified from " + stage;
            case "QUALIFIED_FOR_STAGE" -> "Qualified for " + stage;
            case "DROPPED_TO_STARS_CUP" -> "Continues in Stars Cup";
            case "ELIMINATED" -> "Eliminated in " + stage
                    + (eliminator == null ? "" : " by " + eliminator);
            case "NOT_STARTED" -> "Starts in " + stage;
            default -> "Playing in " + stage;
        };
    }

    private boolean isGroupCompetition(Competition competition) {
        return competition.getTypeId() == 4 || competition.getTypeId() == 5;
    }

    private String normalizedStageLabel(Competition competition, long round, long season) {
        String phase = phase(competition, round, season);
        return "GROUP".equals(phase) ? "Group Stage" : roundLabel(competition, round, season);
    }

    private String phase(Competition competition, long round, long season) {
        int type = (int) competition.getTypeId();
        if (type == 1 || type == 3) return "LEAGUE";
        if (type == 6) return "FINAL";
        CompetitionFormat format = formats.get(type);
        if (type == 4 || type == 5) {
            if (format.isGroupRound(round)) return "GROUP";
            if (round == format.finalRound()) return "FINAL";
            if (format.isPreliminaryRound(round)) return "QUALIFYING";
            return "KNOCKOUT";
        }
        return round == finalRound(competition, season) ? "FINAL" : "KNOCKOUT";
    }

    private String roundLabel(Competition competition, long round, long season) {
        int type = (int) competition.getTypeId();
        if (type == 1 || type == 3) return "Matchday " + round;
        if (type == 6) return "Final";
        CompetitionFormat format = formats.get(type);
        if (type == 4) {
            EuropeanFormatPlan plan = format.europeanPlan();
            if (plan != null && round >= 0 && round < plan.totalRounds()) {
                EuropeanStage stage = plan.stageForRound((int) round);
                return switch (stage.phase()) {
                    case PRELIMINARY -> "Qualifying Round " + (round + 1);
                    case GROUP -> "Group Stage · Matchday " + (round - plan.groupStartRound() + 1);
                    case KNOCKOUT -> knockoutLabel(stage.roundsFromFinal());
                };
            }
        }
        if (type == 5) {
            if (format.isGroupRound(round)) return "Group Stage · Matchday " + round;
            if (round == format.playoffRound()) return "Knockout Playoff";
            return knockoutLabel(format.finalRound() - (int) round + 1);
        }
        long finalRound = finalRound(competition, season);
        return knockoutLabel((int) (finalRound - round + 1));
    }

    private String knockoutLabel(int roundsFromFinal) {
        return switch (roundsFromFinal) {
            case 1 -> "Final";
            case 2 -> "Semi-Final";
            case 3 -> "Quarter-Final";
            case 4 -> "Round of 16";
            case 5 -> "Round of 32";
            default -> "Knockout Round";
        };
    }

    private long finalRound(Competition competition, long season) {
        int type = (int) competition.getTypeId();
        if (type == 4 || type == 5) return formats.get(type).finalRound();
        if (type == 6) return 1;
        return allRounds(competition.getId(), season).stream().mapToLong(Long::longValue).max().orElse(1);
    }

    private List<Long> allRounds(long competitionId, long season) {
        Set<Long> rounds = new HashSet<>(fixtureRepository
                .findDistinctRoundsByCompetitionIdAndSeasonNumber(competitionId, String.valueOf(season)));
        resultRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season)
                .forEach(r -> rounds.add(r.getRoundId()));
        return rounds.stream().sorted().toList();
    }

    private String resultKey(CompetitionTeamInfoDetail result) {
        return result.getRoundId() + ":" + result.getTeam1Id() + ":" + result.getTeam2Id() + ":" + result.getLegNumber();
    }

    private String fixtureKey(CompetitionTeamInfoMatch fixture) {
        return fixture.getRound() + ":" + fixture.getTeam1Id() + ":" + fixture.getTeam2Id() + ":" + fixture.getLegNumber();
    }
}
