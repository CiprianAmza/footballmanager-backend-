package com.footballmanagergamesimulator.integration.career;

import com.footballmanagergamesimulator.controller.MatchController;
import com.footballmanagergamesimulator.controller.TeamController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameAdvanceService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-running release gate that exercises a real career rather than isolated
 * match rounds. It advances the production calendar through every phase and
 * event, lets every domestic/European competition play, executes end-of-season
 * processing, and repeats for two or three seasons.
 *
 * <p>Run with {@code mvn verify -Pcareer}. Use
 * {@code -Dcareer.seasons=2} for the shorter variant.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Full career simulation — production calendar over 2-3 seasons")
class FullCareerSimulationIT {

    private static final int DEFAULT_SEASONS = 3;
    private static final int MAX_ADVANCES_PER_SEASON = 2_000;
    private static final Pattern SCORE = Pattern.compile("^\\s*(\\d+)\\s*-\\s*(\\d+)");
    private static final Set<Long> LEAGUE_TYPES = Set.of(1L, 3L);

    @Autowired private GameAdvanceService gameAdvanceService;
    @Autowired private MatchController matchController;
    @Autowired private TeamController teamController;
    @Autowired private RoundRepository roundRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private CompetitionTeamInfoDetailRepository resultRepository;
    @Autowired private CompetitionHistoryRepository historyRepository;
    @Autowired private MatchStatsRepository matchStatsRepository;
    @Autowired private SeasonObjectiveRepository objectiveRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private SuspensionRepository suspensionRepository;

    @Test
    @DisplayName("Three full seasons preserve calendar, match, table, squad and UX API invariants")
    void simulateFullCareer() throws IOException {
        int seasonsToRun = Integer.getInteger("career.seasons", DEFAULT_SEASONS);
        assertThat(seasonsToRun)
                .as("career.seasons is intentionally bounded to the supported release gate")
                .isBetween(2, 3);

        int firstSeason = currentSeason();
        int lastSeason = firstSeason + seasonsToRun - 1;
        long campaignStarted = System.currentTimeMillis();
        List<SeasonAudit> audits = new ArrayList<>();

        System.out.printf("%n=== FULL CAREER START | seasons=%d..%d ===%n", firstSeason, lastSeason);

        while (currentSeason() <= lastSeason) {
            int season = currentSeason();
            SeasonStartSnapshot start = snapshotSeasonStart(season);
            long seasonStarted = System.currentTimeMillis();
            int advances = advanceUntilNextSeason(season);
            long elapsedMs = System.currentTimeMillis() - seasonStarted;

            SeasonAudit audit = auditCompletedSeason(start, advances, elapsedMs);
            audits.add(audit);
            System.out.printf(
                    "=== season %d complete | advances=%d events=%d fixtures=%d results=%d stats=%d history=%d regens=%d time=%.1fs ===%n",
                    audit.season(), audit.advances(), audit.calendarEvents(), audit.fixtures(),
                    audit.results(), audit.matchStats(), audit.historyRows(), audit.regens(),
                    audit.elapsedMs() / 1000.0);
        }

        assertThat(currentSeason()).isEqualTo(lastSeason + 1);
        assertNextSeasonReady(lastSeason + 1, audits.get(0).domesticTeams());
        assertLongTermSquadHealth();
        assertUxEndpointsAcrossHistory();

        writeReport(audits, System.currentTimeMillis() - campaignStarted);
        assertThat(audits).hasSize(seasonsToRun);
    }

    private SeasonStartSnapshot snapshotSeasonStart(int season) {
        List<CompetitionTeamInfoMatch> fixtures =
                fixtureRepository.findAllBySeasonNumber(String.valueOf(season));
        assertThat(fixtures)
                .as("season %d must start with generated fixtures", season)
                .isNotEmpty();

        Map<Long, Competition> competitions = competitionsById();
        Map<Long, List<FixtureKey>> leagueFixtures = new LinkedHashMap<>();
        Set<Long> domesticTeams = new LinkedHashSet<>();

        for (CompetitionTeamInfoMatch fixture : fixtures) {
            Competition competition = competitions.get(fixture.getCompetitionId());
            if (competition == null || !LEAGUE_TYPES.contains(competition.getTypeId())) continue;
            assertThat(fixture.getTeam1Id()).as("league fixture home team").isPositive();
            assertThat(fixture.getTeam2Id()).as("league fixture away team").isPositive();
            leagueFixtures.computeIfAbsent(fixture.getCompetitionId(), ignored -> new ArrayList<>())
                    .add(FixtureKey.from(fixture));
            domesticTeams.add(fixture.getTeam1Id());
            domesticTeams.add(fixture.getTeam2Id());
        }

        assertThat(leagueFixtures)
                .as("season %d must contain domestic league schedules", season)
                .isNotEmpty();
        assertThat(objectivesForSeason(season))
                .as("season %d must start with objectives", season)
                .isNotEmpty();

        return new SeasonStartSnapshot(
                season, fixtures.size(), leagueFixtures, domesticTeams,
                objectivesForSeason(season).size());
    }

    private int advanceUntilNextSeason(int season) {
        int advances = 0;
        int unchangedStateCount = 0;
        String previousState = calendarState(season);

        while (currentSeason() == season) {
            assertThat(advances)
                    .as("season %d exceeded the advance safety cap; calendar is probably stuck at %s",
                            season, previousState)
                    .isLessThan(MAX_ADVANCES_PER_SEASON);

            Map<String, Object> response = gameAdvanceService.advance(season);
            advances++;

            assertThat(response.get("reason"))
                    .as("headless career must not stop on a user-only hard pause")
                    .isNotEqualTo("LIVE_MATCH_PENDING")
                    .isNotEqualTo("JOB_OFFER_PENDING")
                    .isNotEqualTo("MANAGER_FIRED");

            if (currentSeason() != season) break;

            String currentState = calendarState(season);
            if (currentState.equals(previousState)) {
                unchangedStateCount++;
            } else {
                unchangedStateCount = 0;
                previousState = currentState;
            }
            assertThat(unchangedStateCount)
                    .as("calendar did not progress for season %d; stuck at %s", season, currentState)
                    .isLessThan(6);
        }

        return advances;
    }

    private SeasonAudit auditCompletedSeason(
            SeasonStartSnapshot start, int advances, long elapsedMs) {
        int season = start.season();
        List<CalendarEvent> events = calendarEventRepository.findAllBySeason(season);
        List<CompetitionTeamInfoDetail> results = resultRepository.findAllBySeasonNumber(season);
        List<MatchStats> stats = matchStatsRepository.findAllBySeasonNumber(season);
        List<CompetitionHistory> history = historyRepository.findAllBySeasonNumber(season);
        Map<Long, Competition> competitions = competitionsById();
        Set<Long> knownTeamIds = teamRepository.findAll().stream()
                .map(team -> team.getId())
                .collect(Collectors.toSet());

        assertThat(events).as("season %d calendar events", season).isNotEmpty();
        assertThat(events)
                .as("season %d must not leave retryable events behind", season)
                .noneMatch(event -> "PENDING".equals(event.getStatus())
                        || "PROCESSING".equals(event.getStatus()));

        assertThat(results)
                .as("season %d must persist match results", season)
                .isNotEmpty();
        assertThat(stats)
                .as("season %d must persist match statistics", season)
                .hasSameSizeAs(results);

        Set<ResultKey> uniqueResults = new HashSet<>();
        Map<StatKey, MatchStats> statsByMatch = stats.stream()
                .collect(Collectors.toMap(StatKey::from, Function.identity()));

        for (CompetitionTeamInfoDetail result : results) {
            assertThat(uniqueResults.add(ResultKey.from(result)))
                    .as("duplicate persisted result in season %d: %s", season, ResultKey.from(result))
                    .isTrue();
            assertThat(knownTeamIds).contains(result.getTeam1Id(), result.getTeam2Id());

            int[] score = parseScore(result.getScore());
            assertThat(score[0]).isNotNegative();
            assertThat(score[1]).isNotNegative();

            if (result.getWinnerTeamId() != null) {
                assertThat(result.getWinnerTeamId())
                        .isIn(result.getTeam1Id(), result.getTeam2Id());
            }
            if (result.getDecidedBy() != null
                    && Set.of("PENALTIES", "EXTRA_TIME", "AGGREGATE")
                    .contains(result.getDecidedBy())) {
                assertThat(result.getWinnerTeamId())
                        .as("%s result must expose its winner", result.getDecidedBy())
                        .isNotNull();
            }
            if ("FIRST_LEG".equals(result.getDecidedBy())) {
                assertThat(result.getWinnerTeamId())
                        .as("a first leg cannot advance a winner")
                        .isNull();
            }

            MatchStats matchStats = statsByMatch.get(StatKey.from(result));
            assertThat(matchStats)
                    .as("every result must have a statistics row: %s", StatKey.from(result))
                    .isNotNull();
            assertMatchStats(matchStats);
        }

        auditDomesticLeagues(start, results, history, competitions);
        auditObjectives(season);
        int regens = auditNextSeasonRegens(season + 1, knownTeamIds);

        return new SeasonAudit(
                season,
                advances,
                events.size(),
                start.totalFixtures(),
                results.size(),
                stats.size(),
                history.size(),
                regens,
                start.objectives(),
                start.domesticTeams().size(),
                elapsedMs);
    }

    private int auditNextSeasonRegens(int nextSeason, Set<Long> knownTeamIds) {
        List<Human> regens = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> player.getSeasonCreated() == nextSeason)
                .toList();
        assertThat(regens)
                .as("season %d must generate and label its youth intake", nextSeason)
                .isNotEmpty()
                .allSatisfy(player -> {
                    assertThat(player.getTeamId()).isNotNull().isPositive();
                    assertThat(knownTeamIds).contains(player.getTeamId());
                    assertThat(player.getAge()).isBetween(15, 18);
                    assertThat(player.getRating()).isPositive();
                });
        return regens.size();
    }

    private void auditDomesticLeagues(
            SeasonStartSnapshot start,
            List<CompetitionTeamInfoDetail> results,
            List<CompetitionHistory> history,
            Map<Long, Competition> competitions) {

        Map<Long, List<CompetitionTeamInfoDetail>> resultsByCompetition = results.stream()
                .collect(Collectors.groupingBy(CompetitionTeamInfoDetail::getCompetitionId));
        Map<Long, List<CompetitionHistory>> historyByCompetition = history.stream()
                .collect(Collectors.groupingBy(CompetitionHistory::getCompetitionId));

        for (Map.Entry<Long, List<FixtureKey>> entry : start.leagueFixtures().entrySet()) {
            long competitionId = entry.getKey();
            List<FixtureKey> fixtures = entry.getValue();
            List<CompetitionTeamInfoDetail> leagueResults =
                    resultsByCompetition.getOrDefault(competitionId, List.of());
            Competition competition = competitions.get(competitionId);

            assertThat(leagueResults)
                    .as("all %s fixtures in season %d must be played",
                            competition.getName(), start.season())
                    .hasSize(fixtures.size());

            Map<Long, Long> expectedGamesByTeam = fixtures.stream()
                    .flatMap(fixture -> java.util.stream.Stream.of(fixture.home(), fixture.away()))
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            List<CompetitionHistory> table =
                    historyByCompetition.getOrDefault(competitionId, List.of());
            assertThat(table)
                    .as("%s must have a complete historical table", competition.getName())
                    .hasSize(expectedGamesByTeam.size());

            int wins = 0;
            int losses = 0;
            int draws = 0;
            int goalsFor = 0;
            int goalsAgainst = 0;
            Set<Long> tableTeams = new HashSet<>();

            for (CompetitionHistory row : table) {
                assertThat(tableTeams.add(row.getTeamId()))
                        .as("one history row per team in %s", competition.getName())
                        .isTrue();
                assertThat(row.getGames()).isEqualTo(row.getWins() + row.getDraws() + row.getLoses());
                assertThat(row.getPoints()).isEqualTo(row.getWins() * 3 + row.getDraws());
                assertThat(row.getGoalDifference()).isEqualTo(row.getGoalsFor() - row.getGoalsAgainst());
                assertThat(row.getGames())
                        .as("played count for team %d in %s", row.getTeamId(), competition.getName())
                        .isEqualTo(expectedGamesByTeam.get(row.getTeamId()).intValue());

                wins += row.getWins();
                losses += row.getLoses();
                draws += row.getDraws();
                goalsFor += row.getGoalsFor();
                goalsAgainst += row.getGoalsAgainst();
            }

            assertThat(wins).as("wins and losses must balance").isEqualTo(losses);
            assertThat(draws).as("each draw is counted for two teams").isEven();
            assertThat(goalsFor).as("goals for and against must balance").isEqualTo(goalsAgainst);
        }
    }

    private void auditObjectives(int season) {
        List<SeasonObjective> objectives = objectivesForSeason(season);
        assertThat(objectives).isNotEmpty();
        assertThat(objectives)
                .as("season %d objectives must all be evaluated", season)
                .allMatch(objective -> Set.of("achieved", "failed").contains(objective.getStatus()));
    }

    private void assertMatchStats(MatchStats stats) {
        assertThat(stats.getHomePossession() + stats.getAwayPossession()).isEqualTo(100);
        assertThat(stats.getHomeShots()).isGreaterThanOrEqualTo(stats.getHomeShotsOnTarget());
        assertThat(stats.getAwayShots()).isGreaterThanOrEqualTo(stats.getAwayShotsOnTarget());
        assertThat(stats.getHomeShotsOnTarget()).isGreaterThanOrEqualTo(stats.getHomeGoals());
        assertThat(stats.getAwayShotsOnTarget()).isGreaterThanOrEqualTo(stats.getAwayGoals());
        assertThat(stats.getHomePassAccuracy()).isBetween(0, 100);
        assertThat(stats.getAwayPassAccuracy()).isBetween(0, 100);
        assertThat(stats.getHomeXg()).isNotNegative();
        assertThat(stats.getAwayXg()).isNotNegative();
    }

    private void assertNextSeasonReady(int season, int initialDomesticTeamCount) {
        List<CompetitionTeamInfoMatch> nextFixtures =
                fixtureRepository.findAllBySeasonNumber(String.valueOf(season));
        assertThat(nextFixtures)
                .as("season %d fixtures must be ready immediately after transition", season)
                .isNotEmpty();

        Map<Long, Competition> competitions = competitionsById();
        Map<Long, Long> domesticLeagueByTeam = new HashMap<>();
        for (CompetitionTeamInfoMatch fixture : nextFixtures) {
            Competition competition = competitions.get(fixture.getCompetitionId());
            if (competition == null || !LEAGUE_TYPES.contains(competition.getTypeId())) continue;
            registerDomesticMembership(domesticLeagueByTeam, fixture.getTeam1Id(), competition.getId());
            registerDomesticMembership(domesticLeagueByTeam, fixture.getTeam2Id(), competition.getId());
        }

        assertThat(domesticLeagueByTeam)
                .as("no domestic club may disappear during promotion/relegation")
                .hasSize(initialDomesticTeamCount);
        assertThat(objectivesForSeason(season))
                .as("new season objectives must be generated")
                .isNotEmpty()
                .allMatch(objective -> "active".equals(objective.getStatus()));
    }

    private void registerDomesticMembership(
            Map<Long, Long> leagueByTeam, long teamId, long competitionId) {
        assertThat(teamId).isPositive();
        Long previous = leagueByTeam.putIfAbsent(teamId, competitionId);
        assertThat(previous == null || previous == competitionId)
                .as("team %d appears in domestic leagues %s and %s", teamId, previous, competitionId)
                .isTrue();
    }

    private void assertLongTermSquadHealth() {
        Map<Long, List<Human>> activePlayersByTeam = humanRepository
                .findAllByTypeId(TypeNames.PLAYER_TYPE)
                .stream()
                .filter(player -> !player.isRetired() && player.getTeamId() != null)
                .collect(Collectors.groupingBy(Human::getTeamId));

        Set<Long> activeClubIds = fixtureRepository
                .findAllBySeasonNumber(String.valueOf(currentSeason()))
                .stream()
                .flatMap(fixture -> java.util.stream.Stream.of(
                        fixture.getTeam1Id(), fixture.getTeam2Id()))
                .filter(teamId -> teamId > 0)
                .collect(Collectors.toSet());

        for (Long teamId : activeClubIds) {
            List<Human> squad = activePlayersByTeam.getOrDefault(teamId, List.of());
            assertThat(squad)
                    .as("team %d must retain a selectable XI after all transitions", teamId)
                    .hasSizeGreaterThanOrEqualTo(11);
            assertThat(squad).allSatisfy(player -> {
                assertThat(Double.isFinite(player.getRating())).isTrue();
                assertThat(player.getRating()).isPositive();
                assertThat(player.getFitness()).isBetween(0.0, 100.0);
                assertThat(player.getMorale()).isBetween(0.0, 100.0);
                assertThat(player.getAge()).isBetween(15, 50);
            });
        }

        for (Injury injury : injuryRepository.findAll()) {
            assertThat(injury.getDaysRemaining()).isNotNegative();
            assertThat(humanRepository.existsById(injury.getPlayerId())).isTrue();
        }
        for (Suspension suspension : suspensionRepository.findAll()) {
            assertThat(suspension.getMatchesBanned()).isPositive();
            assertThat(suspension.getMatchesServed()).isBetween(0, suspension.getMatchesBanned());
            if (suspension.isActive()) {
                assertThat(suspension.getMatchesServed()).isLessThan(suspension.getMatchesBanned());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void assertUxEndpointsAcrossHistory() {
        List<CompetitionTeamInfoDetail> allResults =
                resultRepository.findAll().stream()
                        .sorted(Comparator.comparingLong(CompetitionTeamInfoDetail::getSeasonNumber))
                        .toList();
        assertThat(allResults).isNotEmpty();

        Map<TeamPair, List<CompetitionTeamInfoDetail>> byPair = allResults.stream()
                .collect(Collectors.groupingBy(result ->
                        TeamPair.of(result.getTeam1Id(), result.getTeam2Id())));
        Map.Entry<TeamPair, List<CompetitionTeamInfoDetail>> richestPair = byPair.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .orElseThrow();

        TeamPair pair = richestPair.getKey();
        Map<String, Object> h2h = matchController.getHeadToHead(pair.first(), pair.second());
        List<Map<String, Object>> meetings = (List<Map<String, Object>>) h2h.get("meetings");
        int classified = ((Number) h2h.get("teamAWins")).intValue()
                + ((Number) h2h.get("teamBWins")).intValue()
                + ((Number) h2h.get("draws")).intValue();
        assertThat(meetings).hasSize(richestPair.getValue().size());
        assertThat(classified).isEqualTo(meetings.size());

        long teamId = pair.first();
        Set<Long> squadIds = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .map(Human::getId)
                .collect(Collectors.toSet());
        List<Map<String, Object>> availability = teamController.getSquadAvailability(teamId);
        assertThat(availability).allSatisfy(reason -> {
            assertThat(squadIds).contains(((Number) reason.get("playerId")).longValue());
            assertThat(reason.get("type")).isIn("INJURY", "SUSPENSION");
            assertThat(((Number) reason.get("remaining")).intValue()).isPositive();
            assertThat(String.valueOf(reason.get("explanation"))).isNotBlank();
        });
    }

    private Map<Long, Competition> competitionsById() {
        return competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Function.identity()));
    }

    private List<SeasonObjective> objectivesForSeason(int season) {
        return objectiveRepository.findAll().stream()
                .filter(objective -> objective.getSeasonNumber() == season)
                .toList();
    }

    private int currentSeason() {
        Round round = roundRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Round id=1 is missing"));
        return Math.toIntExact(round.getSeason());
    }

    private String calendarState(int season) {
        GameCalendar calendar = gameCalendarRepository.findBySeason(season).stream()
                .findFirst()
                .orElse(null);
        if (calendar == null) return "NOT_CREATED";
        return calendar.getCurrentDay() + "/" + calendar.getCurrentPhase()
                + "/paused=" + calendar.isPaused();
    }

    private int[] parseScore(String rawScore) {
        Matcher matcher = SCORE.matcher(rawScore == null ? "" : rawScore);
        assertThat(matcher.find())
                .as("score must start with '<home> - <away>', got '%s'", rawScore)
                .isTrue();
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        };
    }

    private void writeReport(List<SeasonAudit> audits, long totalMs) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# Full career simulation\n\n");
        report.append("- Seasons: ").append(audits.size()).append('\n');
        report.append("- Total runtime: ").append(String.format("%.1fs", totalMs / 1000.0)).append('\n');
        report.append("- Result: PASS\n\n");
        report.append("| Season | Advances | Events | Initial fixtures | Results | Match stats | History rows | Regens | Objectives | Domestic clubs | Runtime |\n");
        report.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (SeasonAudit audit : audits) {
            report.append("| ").append(audit.season())
                    .append(" | ").append(audit.advances())
                    .append(" | ").append(audit.calendarEvents())
                    .append(" | ").append(audit.fixtures())
                    .append(" | ").append(audit.results())
                    .append(" | ").append(audit.matchStats())
                    .append(" | ").append(audit.historyRows())
                    .append(" | ").append(audit.regens())
                    .append(" | ").append(audit.objectives())
                    .append(" | ").append(audit.domesticTeams())
                    .append(" | ").append(String.format("%.1fs", audit.elapsedMs() / 1000.0))
                    .append(" |\n");
        }
        report.append("\n## Invariants covered\n\n");
        report.append("- Calendar progress and event completion without stuck PROCESSING/PENDING rows.\n");
        report.append("- One result and one statistics row per played fixture.\n");
        report.append("- Valid scores, knockout winners, possession, shots, passing and xG.\n");
        report.append("- Complete domestic tables with balanced W/D/L, points and goals.\n");
        report.append("- Evaluated objectives and ready fixtures/objectives for the next season.\n");
        report.append("- Every transition creates correctly labelled youth regens for the next season.\n");
        report.append("- Promotion/relegation does not duplicate or lose domestic clubs.\n");
        report.append("- Every active club retains at least eleven valid players.\n");
        report.append("- Injury, suspension, H2H and availability API contracts remain valid.\n");

        Files.writeString(Path.of("target", "full-career-simulation.md"), report.toString());
    }

    private record FixtureKey(
            long competition, long round, long home, long away, int leg) {
        static FixtureKey from(CompetitionTeamInfoMatch fixture) {
            return new FixtureKey(
                    fixture.getCompetitionId(), fixture.getRound(),
                    fixture.getTeam1Id(), fixture.getTeam2Id(), fixture.getLegNumber());
        }
    }

    private record ResultKey(
            long competition, long round, long home, long away, int leg) {
        static ResultKey from(CompetitionTeamInfoDetail result) {
            return new ResultKey(
                    result.getCompetitionId(), result.getRoundId(),
                    result.getTeam1Id(), result.getTeam2Id(), result.getLegNumber());
        }
    }

    private record StatKey(long competition, int round, long home, long away) {
        static StatKey from(MatchStats stats) {
            return new StatKey(
                    stats.getCompetitionId(), stats.getRoundNumber(),
                    stats.getTeam1Id(), stats.getTeam2Id());
        }

        static StatKey from(CompetitionTeamInfoDetail result) {
            return new StatKey(
                    result.getCompetitionId(), Math.toIntExact(result.getRoundId()),
                    result.getTeam1Id(), result.getTeam2Id());
        }
    }

    private record TeamPair(long first, long second) {
        static TeamPair of(long a, long b) {
            return a < b ? new TeamPair(a, b) : new TeamPair(b, a);
        }
    }

    private record SeasonStartSnapshot(
            int season,
            int totalFixtures,
            Map<Long, List<FixtureKey>> leagueFixtures,
            Set<Long> domesticTeams,
            int objectives) {
    }

    private record SeasonAudit(
            int season,
            int advances,
            int calendarEvents,
            int fixtures,
            int results,
            int matchStats,
            int historyRows,
            int regens,
            int objectives,
            int domesticTeams,
            long elapsedMs) {
    }
}
