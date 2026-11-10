package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only, explainable manager/player value rankings. No writes happen on a GET. */
@Service
public class CompetitionValueRankingService {
    private final CompetitionSeasonExpectationRepository expectations;
    private final CompetitionHistoryRepository history;
    private final CompetitionRepository competitions;
    private final CompetitionTeamInfoRepository entries;
    private final CompetitionTeamInfoMatchRepository fixtures;
    private final TeamCompetitionDetailRepository standings;
    private final CompetitionTeamInfoDetailRepository results;
    private final ScorerRepository scorers;
    private final ManagerHistoryRepository managerHistory;
    private final HumanRepository people;
    private final TeamRepository teams;
    private final GameCalendarRepository calendars;
    private final CompetitionProgressService progress;
    private final CompetitionFormatConfig formats;

    public CompetitionValueRankingService(CompetitionSeasonExpectationRepository expectations,
            CompetitionHistoryRepository history, CompetitionRepository competitions,
            CompetitionTeamInfoRepository entries, CompetitionTeamInfoMatchRepository fixtures,
            TeamCompetitionDetailRepository standings, CompetitionTeamInfoDetailRepository results,
            ScorerRepository scorers, ManagerHistoryRepository managerHistory,
            HumanRepository people, TeamRepository teams, GameCalendarRepository calendars,
            CompetitionProgressService progress, CompetitionFormatConfig formats) {
        this.expectations = expectations;
        this.history = history;
        this.competitions = competitions;
        this.entries = entries;
        this.fixtures = fixtures;
        this.standings = standings;
        this.results = results;
        this.scorers = scorers;
        this.managerHistory = managerHistory;
        this.people = people;
        this.teams = teams;
        this.calendars = calendars;
        this.progress = progress;
        this.formats = formats;
    }

    public record RankingRow(int rank, long personId, String personName, Long teamId, String teamName,
            double score, long goals, long assists, long appearances, int competitions, int seasons,
            Integer predictedPosition, Integer actualPosition, String resultLabel,
            Integer positionDelta, double outcomePoints,
            double expectedPoints, double competitionPrestige, boolean exactOpeningPrediction,
            Long currentTeamId, String currentTeamName, List<TrophyWon> trophies,
            List<ManagerPerformance> performances) {}
    public record RankingResponse(String subject, String scope, Integer season, Long competitionId,
            int currentSeason, String scoringVersion, List<RankingRow> rows) {}
    public record TrophyWon(int season, long competitionId, String competitionName) {}
    public record ManagerPerformance(int season, long competitionId, String competitionName,
            long teamId, String teamName, long matchesManaged, double value,
            Integer predictedPosition, Integer actualPosition, String resultLabel,
            Integer positionDelta, double outcomePoints, double expectedPoints,
            double competitionPrestige, double scoreWeight, double titleBonus,
            boolean exactOpeningPrediction, boolean trophyWon) {}

    private record TeamKey(int season, long competition, long team) {}
    private record ManagerKey(int season, long team) {}
    private record PersonKey(long id, Long teamId) {}
    private record Outcome(TeamKey key, Integer prediction, Integer position, String resultLabel, int fieldSize,
            double finish, double expected, double prestige, double scoreWeight, double titleBonus,
            double score, int games, boolean exact) {}
    private record ManagerRef(long id, String name) {}

    private static final class Accumulator {
        long id;
        String name;
        Long teamId;
        String teamName;
        double score;
        long goals;
        long assists;
        long appearances;
        Set<String> competitions = new HashSet<>();
        Set<Integer> seasons = new HashSet<>();
        Integer predicted;
        Integer actual;
        String resultLabel;
        double finish;
        double expected;
        double prestige;
        boolean exact = true;
        List<ManagerPerformance> performances = new ArrayList<>();
        Set<TrophyWon> playerTrophies = new HashSet<>();

        void add(Outcome outcome, double points, long goals, long assists, long appearances) {
            score += points;
            this.goals += goals;
            this.assists += assists;
            this.appearances += appearances;
            competitions.add(outcome.key.season + ":" + outcome.key.competition);
            seasons.add(outcome.key.season);
            if (competitions.size() == 1) {
                predicted = outcome.prediction;
                actual = outcome.position;
                resultLabel = outcome.resultLabel;
                finish = outcome.finish;
                expected = outcome.expected;
                prestige = outcome.prestige;
            } else {
                predicted = null;
                actual = null;
                resultLabel = null;
                finish += outcome.finish;
                expected += outcome.expected;
                prestige = 0;
            }
            exact &= outcome.exact;
        }
    }

    @Transactional(readOnly = true)
    public RankingResponse rank(String subject, String scope, Integer season, Long competitionId) {
        if (!subject.equals("players") && !subject.equals("managers"))
            throw new IllegalArgumentException("subject must be players or managers");
        if (!scope.equals("season") && !scope.equals("all-time"))
            throw new IllegalArgumentException("scope must be season or all-time");
        int current = calendars.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
        Integer selectedSeason = scope.equals("all-time") ? null : (season == null ? current : season);
        if (selectedSeason != null && (selectedSeason < 1 || selectedSeason > current))
            throw new IllegalArgumentException("season is outside this career");

        List<CompetitionSeasonExpectation> captured = selectedSeason == null
                ? expectations.findAll() : expectations.findAllBySeasonNumber(selectedSeason);
        List<CompetitionHistory> archived = selectedSeason == null
                ? history.findAll() : history.findAllBySeasonNumber(selectedSeason);
        Map<Long, Competition> competitionById = competitions.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Function.identity()));
        Map<TeamKey, CompetitionSeasonExpectation> opening = new HashMap<>();
        for (CompetitionSeasonExpectation row : captured) {
            if (competitionId == null || competitionId == row.getCompetitionId())
                opening.put(new TeamKey(row.getSeasonNumber(), row.getCompetitionId(), row.getTeamId()), row);
        }
        Map<TeamKey, CompetitionHistory> finalRows = new HashMap<>();
        Map<String, Integer> historyFieldSizes = new HashMap<>();
        for (CompetitionHistory row : archived) {
            if (competitionId != null && competitionId != row.getCompetitionId()) continue;
            finalRows.put(new TeamKey((int) row.getSeasonNumber(), row.getCompetitionId(), row.getTeamId()), row);
            historyFieldSizes.merge(row.getSeasonNumber() + ":" + row.getCompetitionId(), 1, Integer::sum);
        }
        Map<TeamKey, Long> historicalRound = new HashMap<>();
        Map<String, Long> historicalFinalRound = new HashMap<>();
        List<CompetitionTeamInfoDetailRepository.TeamRoundAggregate> roundRows = new ArrayList<>(
                results.aggregateHomeRounds(selectedSeason == null ? null : selectedSeason.longValue()));
        roundRows.addAll(results.aggregateAwayRounds(selectedSeason == null ? null : selectedSeason.longValue()));
        for (CompetitionTeamInfoDetailRepository.TeamRoundAggregate round : roundRows) {
            if (competitionId != null && competitionId != round.getCompetitionId()) continue;
            TeamKey key = new TeamKey(round.getSeasonNumber().intValue(), round.getCompetitionId(), round.getTeamId());
            historicalRound.merge(key, round.getLatestRound(), Math::max);
            historicalFinalRound.merge(key.season + ":" + key.competition, round.getLatestRound(), Math::max);
        }

        Map<TeamKey, Outcome> outcomes = new HashMap<>();
        Set<TeamKey> keys = new HashSet<>(opening.keySet());
        keys.addAll(finalRows.keySet());
        Map<TeamKey, Long> scheduledRound = new HashMap<>();
        Set<TeamKey> unfinishedFixtures = new HashSet<>();
        Map<Long, Set<Long>> liveParticipants = new HashMap<>();
        if (selectedSeason == null || selectedSeason == current) {
            for (CompetitionTeamInfo entry : entries.findAllBySeasonNumber(current)) {
                if (competitionId != null && competitionId != entry.getCompetitionId()) continue;
                liveParticipants.computeIfAbsent(entry.getCompetitionId(), ignored -> new HashSet<>())
                        .add(entry.getTeamId());
                keys.add(new TeamKey(current, entry.getCompetitionId(), entry.getTeamId()));
            }
            for (CompetitionTeamInfoMatch fixture : fixtures.findAllBySeasonNumber(String.valueOf(current))) {
                if (competitionId != null && competitionId != fixture.getCompetitionId()) continue;
                for (long teamId : new long[]{fixture.getTeam1Id(), fixture.getTeam2Id()}) {
                    if (teamId <= 0) continue;
                    liveParticipants.computeIfAbsent(fixture.getCompetitionId(), ignored -> new HashSet<>()).add(teamId);
                    TeamKey key = new TeamKey(current, fixture.getCompetitionId(), teamId);
                    keys.add(key);
                    scheduledRound.merge(key, fixture.getRound(), Math::max);
                    if (fixture.getTeam1Score() < 0 || fixture.getTeam2Score() < 0)
                        unfinishedFixtures.add(key);
                }
            }
        }
        // The current league table is live. Historic tables come only from the frozen finish.
        Map<Long, List<TeamCompetitionDetail>> liveLeagues = new HashMap<>();
        if (selectedSeason == null || selectedSeason == current) {
            for (TeamCompetitionDetail row : standings.findAll()) {
                Competition comp = competitionById.get((long) row.getCompetitionId());
                if (comp != null && isLeague(comp)
                        && (competitionId == null || competitionId == comp.getId())) {
                    liveLeagues.computeIfAbsent(comp.getId(), ignored -> new ArrayList<>()).add(row);
                    keys.add(new TeamKey(current, comp.getId(), row.getTeamId()));
                }
            }
        }
        Map<TeamKey, Integer> liveLeaguePosition = new HashMap<>();
        Map<TeamKey, Integer> liveGames = new HashMap<>();
        for (Map.Entry<Long, List<TeamCompetitionDetail>> item : liveLeagues.entrySet()) {
            item.getValue().sort(Comparator.comparingInt(TeamCompetitionDetail::getPoints).reversed()
                    .thenComparing(Comparator.comparingInt(TeamCompetitionDetail::getGoalDifference).reversed())
                    .thenComparing(Comparator.comparingInt(TeamCompetitionDetail::getGoalsFor).reversed())
                    .thenComparingLong(TeamCompetitionDetail::getTeamId));
            for (int i = 0; i < item.getValue().size(); i++) {
                TeamCompetitionDetail row = item.getValue().get(i);
                TeamKey key = new TeamKey(current, item.getKey(), row.getTeamId());
                liveLeaguePosition.put(key, i + 1);
                liveGames.put(key, row.getGames());
            }
        }

        List<CompetitionTeamInfoDetail> liveResults = (selectedSeason == null || selectedSeason == current)
                ? results.findAllBySeasonNumber(current) : List.of();
        Map<TeamKey, Long> latestRound = new HashMap<>();
        Map<TeamKey, Integer> cupGames = new HashMap<>();
        Map<TeamKey, CompetitionTeamInfoDetail> latestFinal = new HashMap<>();
        Map<Long, Long> finalRound = new HashMap<>();
        for (CompetitionTeamInfoDetail result : liveResults) {
            Competition comp = competitionById.get(result.getCompetitionId());
            if (comp == null || isLeague(comp) || competitionId != null && competitionId != comp.getId()) continue;
            for (long teamId : new long[]{result.getTeam1Id(), result.getTeam2Id()}) {
                TeamKey key = new TeamKey(current, comp.getId(), teamId);
                keys.add(key);
                latestRound.merge(key, result.getRoundId(), Math::max);
                cupGames.merge(key, 1, Integer::sum);
            }
        }
        for (long compId : liveResults.stream().map(CompetitionTeamInfoDetail::getCompetitionId)
                .distinct().toList()) {
            Competition comp = competitionById.get(compId);
            if (comp == null || isLeague(comp)) continue;
            long round = progress.stages(compId, current).stream()
                    .filter(stage -> "FINAL".equals(stage.get("phase")))
                    .mapToLong(stage -> ((Number) stage.get("round")).longValue()).max().orElse(Long.MAX_VALUE);
            finalRound.put(compId, round);
        }
        for (CompetitionTeamInfoDetail result : liveResults) {
            if (result.getRoundId() != finalRound.getOrDefault(result.getCompetitionId(), Long.MAX_VALUE)
                    || result.getWinnerTeamId() == null) continue;
            for (long teamId : new long[]{result.getTeam1Id(), result.getTeam2Id()}) {
                TeamKey key = new TeamKey(current, result.getCompetitionId(), teamId);
                latestFinal.merge(key, result, (a, b) -> a.getId() > b.getId() ? a : b);
            }
        }

        Map<String, Set<Long>> cupParticipants = new HashMap<>();
        Map<String, Map<Long, Long>> cupRounds = new HashMap<>();
        for (TeamKey key : keys) {
            Competition comp = competitionById.get(key.competition);
            if (comp == null || isLeague(comp)) continue;
            String group = key.season + ":" + key.competition;
            cupParticipants.computeIfAbsent(group, ignored -> new HashSet<>()).add(key.team);
            long furthest = Math.max(historicalRound.getOrDefault(key, 0L),
                    scheduledRound.getOrDefault(key, 0L));
            cupRounds.computeIfAbsent(group, ignored -> new HashMap<>()).put(key.team, furthest);
        }
        Map<String, Long> cupWinners = new HashMap<>();
        Map<String, Long> cupRunnersUp = new HashMap<>();
        for (Map.Entry<TeamKey, CompetitionHistory> item : finalRows.entrySet()) {
            String group = item.getKey().season + ":" + item.getKey().competition;
            if (item.getValue().getLastPosition() == 1) cupWinners.put(group, item.getKey().team);
            if (item.getValue().getLastPosition() == 2) cupRunnersUp.put(group, item.getKey().team);
        }
        for (Map.Entry<TeamKey, CompetitionTeamInfoDetail> item : latestFinal.entrySet()) {
            CompetitionTeamInfoDetail decision = item.getValue();
            String group = item.getKey().season + ":" + item.getKey().competition;
            cupWinners.put(group, decision.getWinnerTeamId());
            cupRunnersUp.put(group, decision.getWinnerTeamId() == decision.getTeam1Id()
                    ? decision.getTeam2Id() : decision.getTeam1Id());
        }
        Map<String, Map<Long, Integer>> cupPlaces = new HashMap<>();
        for (Map.Entry<String, Set<Long>> item : cupParticipants.entrySet()) {
            String group = item.getKey();
            cupPlaces.put(group, CupFinishingPositions.calculate(item.getValue(), cupRounds.get(group),
                    cupWinners.get(group), cupRunnersUp.get(group)));
        }

        for (TeamKey key : keys) {
            Competition comp = competitionById.get(key.competition);
            if (comp == null) continue;
            CompetitionSeasonExpectation start = opening.get(key);
            CompetitionHistory end = finalRows.get(key);
            int fieldSize = start != null ? start.getFieldSize()
                    : historyFieldSizes.getOrDefault(key.season + ":" + key.competition,
                            Math.max(liveLeagues.getOrDefault(key.competition, List.of()).size(),
                                    liveParticipants.getOrDefault(key.competition, Set.of()).size()));
            if (fieldSize < 2) continue;
            Integer position = end != null ? Integer.valueOf((int) end.getLastPosition())
                    : liveLeaguePosition.get(key);
            int games = end != null ? end.getGames() : liveGames.getOrDefault(key, cupGames.getOrDefault(key, 0));
            if (end == null && games == 0) continue;
            double finish;
            String resultLabel = null;
            if (isLeague(comp)) {
                if (position == null) continue;
                finish = leagueFinish(position, fieldSize);
            } else if (end != null) {
                if (end.getLastPosition() == 1) { finish = 100; resultLabel = "Winner"; }
                else if (end.getLastPosition() == 2) { finish = 70; resultLabel = "Runner-up"; }
                else {
                    long reached = historicalRound.getOrDefault(key, 0L);
                    long last = historicalFinalRound.getOrDefault(key.season + ":" + key.competition, 0L);
                    finish = reached == 0 || last <= 0 ? cupFinish((int) end.getLastPosition(), fieldSize)
                            : Math.min(55, 15 + 40.0 * reached / last);
                    resultLabel = reached == 0 ? "Eliminated" : "Eliminated in "
                            + CupStageLabels.forRound(comp, formats.get((int) comp.getTypeId()), reached, last);
                    position = cupPlaces.getOrDefault(key.season + ":" + key.competition, Map.of()).get(key.team);
                }
            } else {
                CompetitionTeamInfoDetail finalMatch = latestFinal.get(key);
                if (finalMatch != null) {
                    finish = finalMatch.getWinnerTeamId() == key.team ? 100 : 70;
                    position = finalMatch.getWinnerTeamId() == key.team ? 1 : 2;
                    resultLabel = finalMatch.getWinnerTeamId() == key.team ? "Winner" : "Runner-up";
                } else {
                    long reached = latestRound.getOrDefault(key, 0L);
                    long last = finalRound.getOrDefault(key.competition, 0L);
                    finish = reached == 0 || last <= 0 ? 0 : Math.min(55, 15 + 40.0 * reached / last);
                    position = unfinishedFixtures.contains(key) ? null
                            : cupPlaces.getOrDefault(key.season + ":" + key.competition, Map.of()).get(key.team);
                    resultLabel = reached == 0 ? "Not started" : (position == null ? "Reached " : "Eliminated in ")
                            + CupStageLabels.forRound(comp, formats.get((int) comp.getTypeId()), reached, last);
                }
            }
            Integer prediction = start == null ? null : start.getPredictedPosition();
            double expected = prediction == null ? 0 : isLeague(comp)
                    ? leagueFinish(prediction, fieldSize) : cupFinish(prediction, fieldSize);
            // Reputation belongs to the competition. Opening snapshots remain the source
            // of the prediction, but never substitute participant strength for reputation.
            double prestige = CompetitionReputationService.reputationOf(comp) / 10_000.0;
            double scoreWeight = scoreWeight(comp);
            // A known finish below the opening prediction is a debit, not a smaller
            // positive reward. Compare cup places on the same scale as predictions;
            // live cup teams without a settled place are not penalized prematurely.
            double comparableFinish = position == null || isLeague(comp)
                    ? finish : cupFinish(position, fieldSize);
            double raw = prediction != null && position != null && position > prediction
                    ? -0.4 * Math.max(0, expected - comparableFinish)
                    : Math.max(0, Math.min(140,
                            finish + (prediction == null ? 0 : 0.4 * (finish - expected))));
            boolean completedWinner = position != null && position == 1
                    && (end != null || !isLeague(comp) && latestFinal.containsKey(key));
            double titleBonus = completedWinner ? 80 * prestige * scoreWeight : 0;
            outcomes.put(key, new Outcome(key, prediction, position, resultLabel, fieldSize, finish, expected,
                    prestige, scoreWeight, titleBonus, prestige * scoreWeight * raw + titleBonus,
                    games, start != null));
        }

        Map<Long, Team> teamById = teams.findAll().stream().collect(Collectors.toMap(Team::getId, Function.identity()));
        Map<PersonKey, Accumulator> totals = new HashMap<>();
        Map<Long, Human> currentManagers = new HashMap<>();
        Map<Long, Human> currentPlayers = new HashMap<>();
        if (subject.equals("players")) {
            List<ScorerRepository.ValueAggregate> aggregates = scorers.aggregateForValueRankings(selectedSeason);
            Set<Long> ids = aggregates.stream().map(ScorerRepository.ValueAggregate::getPlayerId).collect(Collectors.toSet());
            Map<Long, Human> peopleById = people.findAllById(ids).stream()
                    .collect(Collectors.toMap(Human::getId, Function.identity()));
            currentPlayers.putAll(peopleById);
            for (ScorerRepository.ValueAggregate stats : aggregates) {
                if (competitionId != null && competitionId != stats.getCompetitionId()) continue;
                TeamKey key = new TeamKey(stats.getSeasonNumber(), stats.getCompetitionId(), stats.getTeamId());
                Outcome outcome = outcomes.get(key);
                if (outcome == null) continue;
                Long displayTeam = competitionId == null || scope.equals("all-time") ? null : stats.getTeamId();
                PersonKey person = new PersonKey(stats.getPlayerId(), displayTeam);
                Accumulator row = totals.computeIfAbsent(person, ignored -> new Accumulator());
                row.id = stats.getPlayerId();
                row.name = peopleById.containsKey(row.id) ? peopleById.get(row.id).getName() : "Player #" + row.id;
                row.teamId = displayTeam;
                row.teamName = displayTeam == null ? null : teamName(teamById, displayTeam);
                double raw = (outcome.score - outcome.titleBonus) / (outcome.prestige * outcome.scoreWeight);
                double contribution = outcome.prestige * outcome.scoreWeight
                        * (3.0 * stats.getGoals() + 1.5 * stats.getAssists())
                        * (0.8 + 0.4 * raw / 140.0);
                double share = Math.min(1, stats.getAppearances() / (double) Math.max(1, outcome.games));
                row.add(outcome, contribution + 0.15 * outcome.score * share,
                        stats.getGoals(), stats.getAssists(), stats.getAppearances());
                // Credit a completed title only to players who appeared for its winning club.
                if (outcome.titleBonus > 0) {
                    Competition competition = competitionById.get(key.competition);
                    row.playerTrophies.add(new TrophyWon(key.season, key.competition,
                            competition == null ? "Competition #" + key.competition : competition.getName()));
                }
            }
        } else {
            Map<ManagerKey, ManagerRef> managerByTeam = new HashMap<>();
            Map<ManagerKey, ManagerHistory> managerHistoryByTeam = new HashMap<>();
            for (ManagerHistory row : managerHistory.findAll()) {
                managerByTeam.put(new ManagerKey(row.getSeasonNumber(), row.getTeamId()),
                        new ManagerRef(row.getManagerId(), row.getManagerName()));
                managerHistoryByTeam.put(new ManagerKey(row.getSeasonNumber(), row.getTeamId()), row);
            }
            for (Human person : people.findAllByTypeId(TypeNames.MANAGER_TYPE)) {
                currentManagers.put(person.getId(), person);
                if (!person.isRetired() && person.getTeamId() != null && person.getTeamId() > 0) {
                    managerByTeam.putIfAbsent(new ManagerKey(current, person.getTeamId()),
                            new ManagerRef(person.getId(), person.getName()));
                }
            }
            Map<TeamKey, Map<Long, Long>> creditedMatches = new HashMap<>();
            List<CompetitionTeamInfoDetailRepository.ManagerMatchAggregate> managerMatches = new ArrayList<>(
                    results.aggregateHomeManagers(selectedSeason == null ? null : selectedSeason.longValue()));
            managerMatches.addAll(results.aggregateAwayManagers(
                    selectedSeason == null ? null : selectedSeason.longValue()));
            for (CompetitionTeamInfoDetailRepository.ManagerMatchAggregate match : managerMatches) {
                if (competitionId != null && competitionId != match.getCompetitionId()) continue;
                TeamKey key = new TeamKey(match.getSeasonNumber().intValue(), match.getCompetitionId(), match.getTeamId());
                creditedMatches.computeIfAbsent(key, ignored -> new HashMap<>())
                        .merge(match.getManagerId(), match.getMatches(), Long::sum);
            }
            Set<Long> managerIds = creditedMatches.values().stream().flatMap(map -> map.keySet().stream())
                    .collect(Collectors.toSet());
            Map<Long, Human> managerPeople = people.findAllById(managerIds).stream()
                    .collect(Collectors.toMap(Human::getId, Function.identity()));
            for (Outcome outcome : outcomes.values()) {
                Map<Long, Long> attribution = creditedMatches.get(outcome.key);
                if (attribution == null || attribution.isEmpty()) {
                    ManagerRef fallback = managerByTeam.get(new ManagerKey(outcome.key.season, outcome.key.team));
                    if (fallback == null) continue;
                    attribution = Map.of(fallback.id, (long) Math.max(1, outcome.games));
                }
                long recordedGames = attribution.values().stream().mapToLong(Long::longValue).sum();
                long denominator = Math.max(Math.max(1, outcome.games), recordedGames);
                for (Map.Entry<Long, Long> credit : attribution.entrySet()) {
                    long managerId = credit.getKey();
                    ManagerRef known = managerByTeam.get(new ManagerKey(outcome.key.season, outcome.key.team));
                    String name = managerPeople.containsKey(managerId) ? managerPeople.get(managerId).getName()
                            : known != null && known.id == managerId ? known.name : "Manager #" + managerId;
                    Long displayTeam = competitionId == null || scope.equals("all-time") ? null : outcome.key.team;
                    PersonKey person = new PersonKey(managerId, displayTeam);
                    Accumulator row = totals.computeIfAbsent(person, ignored -> new Accumulator());
                    row.id = managerId;
                    row.name = name;
                    row.teamId = displayTeam;
                    row.teamName = displayTeam == null ? null : teamName(teamById, displayTeam);
                    double value = outcome.score * credit.getValue() / denominator;
                    row.add(outcome, value, 0, 0, credit.getValue());
                    Competition competition = competitionById.get(outcome.key.competition);
                    String competitionName = competition == null ? "Competition #" + outcome.key.competition
                            : competition.getName();
                    ManagerHistory archivedManager = managerHistoryByTeam.get(
                            new ManagerKey(outcome.key.season, outcome.key.team));
                    CompetitionTeamInfoDetail finalMatch = latestFinal.get(outcome.key);
                    Long finalManager = finalMatch == null ? null
                            : finalMatch.getWinnerTeamId() == finalMatch.getTeam1Id()
                                    ? finalMatch.getManager1Id() : finalMatch.getManager2Id();
                    boolean trophyWon = outcome.position != null && outcome.position == 1
                            && (archivedManager != null && archivedManager.getManagerId() == managerId
                                    && Arrays.stream(Optional.ofNullable(archivedManager.getTrophiesWon())
                                            .orElse("").split(","))
                                            .anyMatch(namePart -> namePart.trim().equals(competitionName))
                                || finalManager != null && finalManager == managerId);
                    row.performances.add(new ManagerPerformance(outcome.key.season, outcome.key.competition,
                            competitionName, outcome.key.team, teamName(teamById, outcome.key.team),
                            credit.getValue(), Math.round(value * 100.0) / 100.0,
                            outcome.prediction, outcome.position, outcome.resultLabel,
                            outcome.prediction == null || outcome.position == null ? null
                                    : outcome.prediction - outcome.position,
                            Math.round(outcome.finish * 100.0) / 100.0,
                            Math.round(outcome.expected * 100.0) / 100.0, outcome.prestige,
                            outcome.scoreWeight,
                            Math.round(outcome.titleBonus * credit.getValue() / denominator * 100.0) / 100.0,
                            outcome.exact, trophyWon));
                }
            }
        }
        List<Accumulator> sorted = totals.values().stream()
                .sorted(Comparator.comparingDouble((Accumulator row) -> row.score).reversed()
                        .thenComparingLong(row -> row.id)).toList();
        List<RankingRow> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Accumulator row = sorted.get(i);
            Human person = subject.equals("players") ? currentPlayers.get(row.id) : currentManagers.get(row.id);
            Long currentTeamId = person != null && !person.isRetired() && person.getTeamId() != null
                    && person.getTeamId() > 0 ? person.getTeamId() : null;
            List<ManagerPerformance> performances = row.performances.stream()
                    .sorted(Comparator.comparingInt(ManagerPerformance::season).reversed()
                            .thenComparing(ManagerPerformance::competitionName)
                            .thenComparingLong(ManagerPerformance::teamId)).toList();
            List<TrophyWon> trophies = subject.equals("players") ? row.playerTrophies.stream()
                    .sorted(Comparator.comparingInt(TrophyWon::season).reversed()
                            .thenComparing(TrophyWon::competitionName)).toList()
                    : performances.stream().filter(ManagerPerformance::trophyWon)
                            .map(performance -> new TrophyWon(performance.season, performance.competitionId,
                                    performance.competitionName)).distinct().toList();
            rows.add(new RankingRow(i + 1, row.id, row.name, row.teamId, row.teamName,
                    Math.round(row.score * 100.0) / 100.0, row.goals, row.assists, row.appearances,
                    row.competitions.size(), row.seasons.size(), row.predicted, row.actual, row.resultLabel,
                    row.predicted == null || row.actual == null ? null : row.predicted - row.actual,
                    Math.round(row.finish * 100.0) / 100.0, Math.round(row.expected * 100.0) / 100.0,
                    competitionId == null ? row.prestige
                            : CompetitionReputationService.reputationOf(competitionById.get(competitionId)) / 10_000.0,
                    row.exact, currentTeamId,
                    currentTeamId == null ? null : teamName(teamById, currentTeamId), trophies, performances));
        }
        return new RankingResponse(subject, scope, selectedSeason, competitionId, current, "v7", rows);
    }

    private static double scoreWeight(Competition competition) {
        if (competition.getTypeId() == Competition.CUP) return 0.70;
        if (competition.getTypeId() == Competition.SUPER_CUP) return 0.30;
        return 1.0;
    }

    private static double leagueFinish(int position, int fieldSize) {
        return 100 * Math.pow(Math.max(0, (fieldSize - position) / (double) (fieldSize - 1)), 1.5);
    }

    private static boolean isLeague(Competition competition) {
        return competition.isLeague() || competition.getTypeId() == 3;
    }

    private static double cupFinish(int rank, int fieldSize) {
        if (rank == 1) return 100;
        if (rank == 2) return 70;
        double share = Math.max(0, (fieldSize - rank) / (double) (fieldSize - 1));
        return 10 + 45 * share;
    }

    private static String teamName(Map<Long, Team> teams, long id) {
        Team team = teams.get(id);
        return team == null ? "Club #" + id : team.getName();
    }
}
