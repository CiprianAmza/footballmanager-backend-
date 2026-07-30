package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.OutcomeTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast 200-season league outcome report using the production Compartment V1 model.
 *
 * <p>Unlike {@link LeagueOutcome2IT}, this test exploits the fact that its squads and tactics are
 * immutable during the run. It builds each canonical team once, evaluates every directed fixture
 * once, then samples that exact production probability distribution 200 times with independent,
 * deterministic production seeds. Every sample still belongs to its own season table: scores are
 * never averaged before standings are calculated.</p>
 *
 * <p>This is statistically equivalent to repeating the same fixed-input season 200 times. It is
 * intentionally not a career simulator: transfers, injuries, fitness, morale and tactics do not
 * evolve between samples.</p>
 *
 * <p>A single team's formation and all six canonical team axes can be overridden without
 * mutating the database:</p>
 * <pre>
 * -Dleague.outcome3.team-id=14 -Dleague.outcome3.formation=442
 * -Dleague.outcome3.mentality=5 -Dleague.outcome3.tempo=5 -Dleague.outcome3.passing=3
 * -Dleague.outcome3.defensive-line=3 -Dleague.outcome3.pressing=3 -Dleague.outcome3.width=3
 * </pre>
 * Every numeric axis follows the order of its canonical options. The persistent
 * refuses-defensive-work attack bonus can be swept with the canonical engine property:</p>
 * <pre>
 * -Dmatch.engine.compartment.work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier=1.05
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bootstrap.seed=20260528"
})
@DisplayName("League outcome 3 — fast 200-season Compartment V1 sampling")
class LeagueOutcome3IT {

    private static final int SEASONS = 200;
    private static final long BASE_SEED = 20260528L;
    private static final String TEAM_IDS_PROPERTY = "team.ids";
    private static final String OVERRIDE_TEAM_ID_PROPERTY = "league.outcome3.team-id";
    private static final String FORMATION_PROPERTY = "league.outcome3.formation";
    private static final String MENTALITY_LEVEL_PROPERTY = "league.outcome3.mentality";
    private static final String TEMPO_LEVEL_PROPERTY = "league.outcome3.tempo";
    private static final String PASSING_LEVEL_PROPERTY = "league.outcome3.passing";
    private static final String DEFENSIVE_LINE_LEVEL_PROPERTY = "league.outcome3.defensive-line";
    private static final String PRESSING_LEVEL_PROPERTY = "league.outcome3.pressing";
    private static final String WIDTH_LEVEL_PROPERTY = "league.outcome3.width";
    private static final String REFUSES_ATTACK_MULTIPLIER_PROPERTY =
            "match.engine.compartment.work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier";
    private static final List<String> MENTALITY_LEVELS =
            List.of("Very Defensive", "Defensive", "Balanced", "Attacking", "Very Attacking");
    private static final List<String> TEMPO_LEVELS =
            List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");
    private static final List<String> PASSING_LEVELS = List.of("Short", "Normal", "Long");
    private static final List<String> DEFENSIVE_LINE_LEVELS = List.of("Deep", "Standard", "High");
    private static final List<String> PRESSING_LEVELS =
            List.of("Very Easy", "Easy", "Normal", "Aggressive", "Very Aggressive");
    private static final List<String> WIDTH_LEVELS = List.of("Narrow", "Balanced", "Wide");
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CanonicalScoreSampler scoreSampler;
    @Autowired private CanonicalScoringFingerprintService fingerprintService;
    @Autowired private GameStateService gameState;
    @Autowired private com.footballmanagergamesimulator.service.TacticService tacticService;
    @Autowired private TacticSimulationService tacticSimulationService;

    @Test
    @DisplayName("all leagues, 200 independent standings, one canonical evaluation per fixture")
    void simulateAllLeaguesWithPreparedCanonicalEvaluations() throws Exception {
        configureRunOverrides();
        List<Long> availableLeagues = availableLeagues();
        int season = gameState.currentSeason();
        Set<Long> simulatedTeamIds = new HashSet<>();

        StringBuilder combined = new StringBuilder("# League Outcome 3 — All Leagues\n\n")
                .append("Leagues: ").append(availableLeagues).append('\n')
                .append("Seasons sampled per league: ").append(SEASONS).append("\n\n");
        for (long competitionId : availableLeagues) {
            Competition league = competitionRepository.findById(competitionId)
                    .orElseThrow(() -> new IllegalStateException("league does not exist: " + competitionId));
            List<TeamSetup> teams = loadTeams(competitionId, season);
            assertThat(teams).as("league %s must have teams", competitionId).isNotEmpty();
            teams.forEach(team -> simulatedTeamIds.add(team.id));

            AggregatedSimulation aggregate = runAggregateSimulation(competitionId, teams);
            String report = buildReport("Competition: id=" + competitionId + ", name="
                            + league.getName() + ", season=" + season,
                    availableLeagues, teams, aggregate);
            Files.writeString(Path.of("target", "league-outcome-3-" + competitionId + ".md"), report);
            combined.append(report).append("\n\n---\n\n");
            assertCompleteSimulation(teams, aggregate);
        }
        TacticOverride tacticOverride = requestedTacticOverride();
        if (tacticOverride != null) {
            assertThat(simulatedTeamIds).as("configured tactic override team must belong to a simulated league")
                    .contains(tacticOverride.teamId);
        }
        writeAndPrint(Path.of("target", "league-outcome-3-all.md"), combined.toString());
    }

    @Test
    @DisplayName("custom -Dteam.ids league uses the same prepared canonical path")
    void simulateCustomTeamsWithPreparedCanonicalEvaluations() throws Exception {
        configureRunOverrides();
        String rawIds = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(rawIds != null && !rawIds.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");
        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(rawIds);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 teams; got " + teamIds.size());
        }
        List<TeamSetup> teams = loadTeamsByIds(teamIds);
        TacticOverride tacticOverride = requestedTacticOverride();
        if (tacticOverride != null) {
            assertThat(teamIds).as("configured tactic override team must belong to -Dteam.ids")
                    .contains(tacticOverride.teamId);
        }
        long scoringCompetitionId = availableLeagues().get(0);
        AggregatedSimulation aggregate = runAggregateSimulation(scoringCompetitionId, teams);
        String label = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining(", "));
        String filenameIds = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining("_"));
        String report = buildReport("Competition: CUSTOM league of " + teams.size()
                        + " teams (IDs: " + label + ")", null, teams, aggregate);
        writeAndPrint(Path.of("target", "league-outcome-3-custom-" + filenameIds + ".md"), report);
        assertCompleteSimulation(teams, aggregate);
    }

    private List<Long> availableLeagues() {
        List<Long> leagues = gameState.getLeagueCompetitionIdsCached().stream().sorted().toList();
        assertThat(leagues).as("at least one league competition must be bootstrapped").isNotEmpty();
        return leagues;
    }

    private List<TeamSetup> loadTeams(long competitionId, int season) {
        List<Long> teamIds = competitionTeamInfoRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .map(CompetitionTeamInfo::getTeamId)
                .filter(id -> id > 0).distinct().sorted().toList();
        return loadTeamsByIds(teamIds);
    }

    private List<TeamSetup> loadTeamsByIds(List<Long> teamIds) {
        List<TeamSetup> result = new ArrayList<>();
        for (long teamId : teamIds.stream().distinct().sorted().toList()) {
            assertThat(teamRepository.existsById(teamId)).as("team %s must exist", teamId).isTrue();
            PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(teamId)
                    .orElseGet(LeagueOutcome3IT::defaultTactic);
            tactic = tacticForTeam(teamId, tactic);
            // A saved formation is what the club actually plays — MatchRoundSimulator.chooseFormation
            // reads it. Letting bestCanonicalFormation re-pick here made this report show a
            // different eleven from the one the game fields, so a seeded 4231 was reported as 352.
            TacticSimulationService.CanonicalFormationEvaluation best =
                    tactic.getTactic() == null || tactic.getTactic().isBlank()
                            ? tacticSimulationService.bestCanonicalFormation(teamId, tactic)
                            : tacticSimulationService.canonicalFormation(teamId, tactic.getTactic(), tactic);
            result.add(new TeamSetup(teamId, teamName(teamId), best.topXiRating(), best.formation(),
                    tactic, best.evaluation(), lineupDetails(best)));
        }
        assertUniquePlayersAcrossTeams(result);
        return result;
    }

    private PersonalizedTactic tacticForTeam(long teamId, PersonalizedTactic source) {
        TacticOverride override = requestedTacticOverride();
        if (override == null || override.teamId != teamId) return source;
        PersonalizedTactic copy = copyTactic(source);
        if (override.formation != null) {
            if (!tacticService.getAllExistingTactics().contains(override.formation)) {
                throw new IllegalArgumentException("-D" + FORMATION_PROPERTY
                        + " is not a known formation: " + override.formation);
            }
            copy.setTactic(override.formation);
        }
        if (override.mentality != null) copy.setMentality(override.mentality);
        if (override.tempo != null) copy.setTempo(override.tempo);
        if (override.passing != null) copy.setPassingType(override.passing);
        if (override.defensiveLine != null) copy.setDefensiveLine(override.defensiveLine);
        if (override.pressing != null) copy.setPressing(override.pressing);
        if (override.width != null) copy.setWidth(override.width);
        return copy;
    }

    private static PersonalizedTactic copyTactic(PersonalizedTactic source) {
        PersonalizedTactic copy = defaultTactic();
        copy.setTeamId(source.getTeamId());
        copy.setFirst11(source.getFirst11());
        copy.setTactic(source.getTactic());
        copy.setMentality(tacticValue(source.getMentality(), "Balanced"));
        copy.setTempo(tacticValue(source.getTempo(), "Standard"));
        copy.setPassingType(tacticValue(source.getPassingType(), "Normal"));
        copy.setDefensiveLine(tacticValue(source.getDefensiveLine(), "Standard"));
        copy.setPressing(tacticValue(source.getPressing(), "Standard"));
        copy.setWidth(tacticValue(source.getWidth(), "Balanced"));
        copy.setTimeWasting(source.getTimeWasting());
        copy.setInPossession(source.getInPossession());
        copy.setDribbling(source.getDribbling());
        copy.setFoulFrequency(source.getFoulFrequency());
        copy.setFoulHardness(source.getFoulHardness());
        copy.setTempoFragmentation(source.getTempoFragmentation());
        copy.setWidePlay(source.getWidePlay());
        copy.setTransition(source.getTransition());
        return copy;
    }

    private void configureRunOverrides() {
        String raw = trimmedSystemProperty(REFUSES_ATTACK_MULTIPLIER_PROPERTY);
        if (raw == null) return;
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + REFUSES_ATTACK_MULTIPLIER_PROPERTY
                    + " must be a number; got " + raw, exception);
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("-D" + REFUSES_ATTACK_MULTIPLIER_PROPERTY
                    + " must be finite and > 0; got " + raw);
        }
        var rule = compartmentConfig.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (rule == null) throw new IllegalStateException("REFUSES_DEFENSIVE_WORK rule is missing");
        rule.setAttackMultiplier(value);
    }

    private static TacticOverride requestedTacticOverride() {
        String rawTeamId = trimmedSystemProperty(OVERRIDE_TEAM_ID_PROPERTY);
        String rawFormation = trimmedSystemProperty(FORMATION_PROPERTY);
        String rawMentality = trimmedSystemProperty(MENTALITY_LEVEL_PROPERTY);
        String rawTempo = trimmedSystemProperty(TEMPO_LEVEL_PROPERTY);
        String rawPassing = trimmedSystemProperty(PASSING_LEVEL_PROPERTY);
        String rawDefensiveLine = trimmedSystemProperty(DEFENSIVE_LINE_LEVEL_PROPERTY);
        String rawPressing = trimmedSystemProperty(PRESSING_LEVEL_PROPERTY);
        String rawWidth = trimmedSystemProperty(WIDTH_LEVEL_PROPERTY);
        boolean hasOverride = rawFormation != null || rawMentality != null || rawTempo != null
                || rawPassing != null || rawDefensiveLine != null || rawPressing != null || rawWidth != null;
        if (rawTeamId == null && !hasOverride) return null;
        if (rawTeamId == null) {
            throw new IllegalArgumentException("-D" + OVERRIDE_TEAM_ID_PROPERTY
                    + " is required when a tactic or formation is overridden");
        }
        long teamId;
        try {
            teamId = Long.parseLong(rawTeamId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + OVERRIDE_TEAM_ID_PROPERTY
                    + " must be a positive integer; got " + rawTeamId, exception);
        }
        if (teamId <= 0) {
            throw new IllegalArgumentException("-D" + OVERRIDE_TEAM_ID_PROPERTY
                    + " must be a positive integer; got " + rawTeamId);
        }
        if (!hasOverride) {
            throw new IllegalArgumentException("At least one league.outcome3 tactic/formation override is required");
        }
        return new TacticOverride(teamId, rawFormation,
                levelValue(MENTALITY_LEVEL_PROPERTY, rawMentality, MENTALITY_LEVELS),
                levelValue(TEMPO_LEVEL_PROPERTY, rawTempo, TEMPO_LEVELS),
                levelValue(PASSING_LEVEL_PROPERTY, rawPassing, PASSING_LEVELS),
                levelValue(DEFENSIVE_LINE_LEVEL_PROPERTY, rawDefensiveLine, DEFENSIVE_LINE_LEVELS),
                levelValue(PRESSING_LEVEL_PROPERTY, rawPressing, PRESSING_LEVELS),
                levelValue(WIDTH_LEVEL_PROPERTY, rawWidth, WIDTH_LEVELS));
    }

    private static String levelValue(String property, String raw, List<String> values) {
        if (raw == null) return null;
        int level;
        try {
            level = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + property + " must be an integer in [1,"
                    + values.size() + "]; got " + raw,
                    exception);
        }
        if (level < 1 || level > values.size()) {
            throw new IllegalArgumentException("-D" + property + " must be in [1,"
                    + values.size() + "]; got " + raw);
        }
        return values.get(level - 1);
    }

    private static String trimmedSystemProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<LineupPlayer> lineupDetails(TacticSimulationService.CanonicalFormationEvaluation best) {
        List<Long> playerIds = best.evaluation().players().stream().map(player -> player.playerId()).toList();
        Map<Long, Human> humans = new HashMap<>();
        humanRepository.findAllById(playerIds).forEach(human -> humans.put(human.getId(), human));
        Map<Long, com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerBreakdown>
                breakdowns = best.evaluation().team().players().stream().collect(
                java.util.stream.Collectors.toMap(
                        com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerBreakdown::playerId,
                        java.util.function.Function.identity()));
        return best.evaluation().players().stream()
                .map(player -> {
                    Human human = humans.get(player.playerId());
                    if (human == null) {
                        throw new IllegalStateException("missing selected player " + player.playerId());
                    }
                    var breakdown = breakdowns.get(player.playerId());
                    if (breakdown == null) {
                        throw new IllegalStateException("missing aggregate breakdown for player " + player.playerId());
                    }
                    return new LineupPlayer(player.playerId(), human.getName(), player.usedPosition(),
                            player.occurrence(), player.role() == null ? null : player.role().displayName(),
                            human.getRating(), breakdown.adjustedAttack(), breakdown.midfield(), breakdown.defense(),
                            human.isStayForward(), breakdown.traits().contains(PlayerTrait.SHOOTER));
                })
                .sorted(Comparator.comparingInt(LeagueOutcome3IT::lineupOrder)
                        .thenComparingInt(LineupPlayer::occurrence)
                        .thenComparingLong(LineupPlayer::playerId))
                .toList();
    }

    private String teamName(long teamId) {
        String name = teamRepository.findNameById(teamId);
        return name == null ? "Team#" + teamId : name;
    }

    private static PersonalizedTactic defaultTactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Balanced");
        tactic.setTempo("Standard");
        tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard");
        tactic.setPressing("Standard");
        tactic.setWidth("Balanced");
        return tactic;
    }

    private static void assertUniquePlayersAcrossTeams(List<TeamSetup> teams) {
        Set<Long> playerIds = new HashSet<>();
        for (TeamSetup team : teams) {
            for (var player : team.evaluation.players()) {
                if (!playerIds.add(player.playerId())) {
                    throw new IllegalArgumentException("player belongs to multiple simulated teams: "
                            + player.playerId());
                }
            }
        }
    }

    private AggregatedSimulation runAggregateSimulation(long competitionId, List<TeamSetup> teams) {
        return runPreparedSimulation(competitionId, teams);
    }

    private AggregatedSimulation runPreparedSimulation(long competitionId, List<TeamSetup> teams) {
        long startedAt = System.nanoTime();
        int n = teams.size();
        int encounters = competitionFormat.get(1).encountersFor(n);
        CanonicalMatchEvaluationAdapter matchAdapter =
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
        List<CanonicalTeamEvaluation> teamEvaluations =
                teams.stream().map(TeamSetup::evaluation).toList();
        List<CanonicalStrength> strengths = teamEvaluations.stream()
                .map(evaluation -> new CanonicalStrength(
                        evaluation.team().rawTotals().attack(),
                        evaluation.team().rawTotals().midfield(),
                        evaluation.team().rawTotals().defense(),
                        evaluation.team().attack(),
                        evaluation.team().attackProtection()))
                .toList();

        SeasonTable[] seasons = new SeasonTable[SEASONS];
        for (int seasonIndex = 0; seasonIndex < SEASONS; seasonIndex++) {
            seasons[seasonIndex] = new SeasonTable(n);
        }

        int fixtureOrdinal = 0;
        int preparedFixtures = 0;
        int matchesScored = 0;
        for (int meeting = 0; meeting < encounters; meeting++) {
            for (int first = 0; first < n; first++) {
                for (int second = first + 1; second < n; second++) {
                    int home = meeting % 2 == 0 ? first : second;
                    int away = meeting % 2 == 0 ? second : first;
                    CanonicalMatchEvaluation evaluation = matchAdapter.evaluate(
                            teamEvaluations.get(home), teamEvaluations.get(away), MatchVenue.HOME);
                    preparedFixtures++;
                    int requestRound = fixtureOrdinal + 1;
                    for (int seasonIndex = 0; seasonIndex < SEASONS; seasonIndex++) {
                        int simulatedSeason = seasonIndex + 1;
                        // Keep LeagueOutcome2IT's key contract so both tests produce identical scores
                        // for identical inputs; only the amount of repeated evaluation work differs.
                        String fixtureKey = "LEAGUE_V2:" + competitionId + ":" + simulatedSeason + ":"
                                + fixtureOrdinal + ":" + teams.get(home).id + ":" + teams.get(away).id;
                        long seed = MatchPlanService.seedFor(fixtureKey, competitionId, simulatedSeason,
                                requestRound, teams.get(home).id, teams.get(away).id);
                        CanonicalScoreSampler.GoalSample score = scoreSampler.sample(evaluation, seed);
                        seasons[seasonIndex].apply(home, away, score.homeGoals(), score.awayGoals());
                        matchesScored++;
                    }
                    fixtureOrdinal++;
                }
            }
        }

        long[][] positionCounts = new long[n][n];
        long[] totalPoints = new long[n];
        long[] totalGF = new long[n];
        long[] totalGA = new long[n];
        long[] totalWins = new long[n];
        long[] totalDraws = new long[n];
        long[] totalLosses = new long[n];
        int[] championships = new int[n];
        for (SeasonTable season : seasons) {
            Integer[] order = season.order(teams);
            for (int position = 0; position < n; position++) {
                int team = order[position];
                positionCounts[team][position]++;
                totalPoints[team] += season.points[team];
                totalGF[team] += season.goalsFor[team];
                totalGA[team] += season.goalsAgainst[team];
                totalWins[team] += season.wins[team];
                totalDraws[team] += season.draws[team];
                totalLosses[team] += season.losses[team];
            }
            championships[order[0]]++;
        }
        return new AggregatedSimulation(positionCounts, totalPoints, totalGF, totalGA,
                totalWins, totalDraws, totalLosses, championships, matchesScored, preparedFixtures,
                (System.nanoTime() - startedAt) / 1_000_000, strengths);
    }

    private String buildReport(String competitionLine, List<Long> availableLeagues,
                               List<TeamSetup> teams, AggregatedSimulation aggregate) {
        int n = teams.size();
        String configFingerprint = fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig);
        StringBuilder report = new StringBuilder();
        report.append("# League Outcome 3 — Prepared Canonical Sampling\n\n")
                .append("Run on ").append(java.time.LocalDateTime.now()).append('\n')
                .append(competitionLine).append('\n')
                .append("Independent season tables: ").append(SEASONS).append('\n')
                .append("Teams: ").append(n).append('\n')
                .append("Canonical fixture evaluations prepared once: ").append(aggregate.preparedFixtures).append('\n')
                .append("Score samples: ").append(aggregate.matchesScored).append('\n')
                .append("Elapsed: ").append(aggregate.elapsedMs).append(" ms\n")
                .append("Engine: COMPARTMENT_V1 prepared evaluation + production CanonicalScoreSampler\n")
                .append("Weights: classpath:compartment-scoring-weights-v1.yml\n")
                .append("Config fingerprint: ").append(configFingerprint).append('\n')
                .append("Bootstrap seed: ").append(BASE_SEED).append('\n')
                .append("Team tactic override: ").append(tacticOverrideLabel()).append('\n')
                .append("REFUSES_DEFENSIVE_WORK attack multiplier: ")
                .append(String.format("%.4f", refusesDefensiveWorkAttackMultiplier())).append("\n\n");
        if (availableLeagues != null) {
            report.append("## Available Leagues\n\n")
                    .append("This run includes every bootstrapped league.\n\n")
                    .append('`').append(availableLeagues).append("`\n\n");
        }
        report.append("## Average Standings After ").append(SEASONS).append(" Samples\n\n")
                .append("Each sample is a complete independent season. Each team plays ")
                .append((n - 1) * competitionFormat.get(1).encountersFor(n)).append(" matches per season.\n\n");

        double[] meanPos = new double[n];
        double[] stddevPos = new double[n];
        for (int team = 0; team < n; team++) {
            double meanSquare = 0;
            for (int position = 0; position < n; position++) {
                meanPos[team] += (position + 1) * aggregate.positionCounts[team][position];
                meanSquare += (position + 1.0) * (position + 1.0)
                        * aggregate.positionCounts[team][position];
            }
            meanPos[team] /= SEASONS;
            stddevPos[team] = Math.sqrt(Math.max(0,
                    meanSquare / SEASONS - meanPos[team] * meanPos[team]));
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> meanPos[i]));
        MarkdownTable standings = new MarkdownTable(
                List.of("Rank", "Team", "Top XI", "GK Overall", "Attack", "Midfield", "Defense",
                        "Final Attack", "Final Protection", "Tactic", "Mean Pos ± σ", "Mean Pts",
                        "Avg GF", "Avg GA", "W/D/L", "Champion"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT));
        for (int rank = 0; rank < n; rank++) {
            int team = order[rank];
            CanonicalStrength strength = aggregate.strengths.get(team);
            standings.addRow(String.valueOf(rank + 1), teams.get(team).name,
                    String.format("%.1f", teams.get(team).topXiRating),
                    String.format("%.1f", teams.get(team).goalkeeperOverall()),
                    String.format("%.2f", strength.rawAttack),
                    String.format("%.2f", strength.rawMidfield),
                    String.format("%.2f", strength.rawDefense),
                    String.format("%.2f", strength.finalAttack),
                    String.format("%.2f", strength.finalProtection),
                    tacticLabel(teams.get(team).formation, teams.get(team).tactic),
                    String.format("%.1f ± %.1f", meanPos[team], stddevPos[team]),
                    String.format("%.1f", aggregate.totalPoints[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGF[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGA[team] / (double) SEASONS),
                    String.format("%.1f / %.1f / %.1f", aggregate.totalWins[team] / (double) SEASONS,
                            aggregate.totalDraws[team] / (double) SEASONS,
                            aggregate.totalLosses[team] / (double) SEASONS),
                    String.format("%.1f%%", aggregate.championships[team] * 100.0 / SEASONS));
        }
        report.append(standings.render()).append('\n');

        report.append("## Finishing Bands (% of seasons)\n\n");
        MarkdownTable bands = new MarkdownTable(
                List.of("Team", "1st (%)", "Top 4 (%)", "Top half (%)", "Bottom 4 (%)", "Last (%)"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int team : order) {
            bands.addRow(teams.get(team).name,
                    percentage(aggregate.positionCounts[team][0]),
                    percentage(sumPositions(aggregate.positionCounts[team], 0, Math.min(4, n))),
                    percentage(sumPositions(aggregate.positionCounts[team], 0, n / 2)),
                    percentage(sumPositions(aggregate.positionCounts[team], Math.max(0, n - 4), n)),
                    percentage(aggregate.positionCounts[team][n - 1]));
        }
        report.append(bands.render()).append('\n');

        report.append("## Position Heatmap (% of seasons at each finish position)\n\n");
        List<String> headers = new ArrayList<>();
        headers.add("Team \\ Pos");
        for (int position = 1; position <= n; position++) headers.add(String.valueOf(position));
        List<MarkdownTable.Align> aligns = new ArrayList<>();
        aligns.add(MarkdownTable.Align.LEFT);
        for (int i = 0; i < n; i++) aligns.add(MarkdownTable.Align.RIGHT);
        MarkdownTable heatmap = new MarkdownTable(headers, aligns);
        for (int team : order) {
            List<String> row = new ArrayList<>();
            row.add(teams.get(team).name);
            for (int position = 0; position < n; position++) {
                row.add(percentage(aggregate.positionCounts[team][position]));
            }
            heatmap.addRow(row.toArray(String[]::new));
        }
        report.append(heatmap.render()).append('\n')
                .append("## Method\n\n")
                .append("- Squads, tactics and canonical team evaluations are frozen for this statistical run.\n")
                .append("- Every manager is treated as if **Always use best possible tactic** were enabled; no manager row is mutated.\n")
                .append("- **Top XI** is the sum of the persisted 1–300 `Human.rating` values in the selected XI. Formation selection uses only this value.\n")
                .append("- Attributes, fitness, morale, roles, tactics and engine weights are applied only after the formation and XI are fixed.\n")
                .append("- **GK Overall** is the goalkeeper's persisted 1–300 position rating; it is not a sum of compartments.\n")
                .append("- **Attack / Midfield / Defense** are the canonical raw compartment totals before mentality redistribution.\n")
                .append("- **Final Attack / Final Protection** are the two values that actually enter the goal-probability matchup.\n")
                .append("- **Tactic** lists formation | mentality / tempo / passing / defensive line / pressing / width.\n")
                .append("- Every directed fixture probability distribution is calculated once with the production Compartment V1 formulas and current weights.\n")
                .append("- That distribution is sampled 200 times with distinct deterministic production seeds.\n")
                .append("- Sample `s` contributes only to standings table `s`; no score averaging occurs before ranking.\n")
                .append("- This test measures fixed-input score behavior, not career evolution.\n\n")
                .append("## Starting XIs — Top 3\n\n")
                .append("Listed in formation order. **Overall** is the comparable 1–300 position rating; **A/M/D** are the adjusted raw contributions actually aggregated by the engine (so a SHOOTER displays 20% / 0% / 10% of Overall).\n\n");
        for (int rank = 0; rank < Math.min(3, n); rank++) {
            TeamSetup team = teams.get(order[rank]);
            report.append(rank + 1).append(". **").append(team.name).append(" — ")
                    .append(team.formation).append("**: ")
                    .append(team.lineup.stream().map(LeagueOutcome3IT::lineupLabel)
                            .collect(java.util.stream.Collectors.joining(" - ")))
                    .append("\n\n");
        }
        return report.toString();
    }

    private void assertCompleteSimulation(List<TeamSetup> teams, AggregatedSimulation aggregate) {
        int fixturesPerSeason = teams.size() * (teams.size() - 1)
                * competitionFormat.get(1).encountersFor(teams.size()) / 2;
        assertThat(aggregate.preparedFixtures).isEqualTo(fixturesPerSeason);
        assertThat(aggregate.matchesScored).isEqualTo(SEASONS * fixturesPerSeason);
    }

    private static void writeAndPrint(Path reportPath, String report) throws Exception {
        Files.writeString(reportPath, report);
        System.out.println();
        System.out.println(report);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    private static long sumPositions(long[] values, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) total += values[i];
        return total;
    }

    private static String percentage(long count) {
        return String.format("%.1f%%", count * 100.0 / SEASONS);
    }

    private static String tacticLabel(String formation, PersonalizedTactic tactic) {
        return formation + " | " + String.join(" / ",
                tacticValue(tactic.getMentality(), "Balanced"),
                tacticValue(tactic.getTempo(), "Standard"),
                tacticValue(tactic.getPassingType(), "Normal"),
                tacticValue(tactic.getDefensiveLine(), "Standard"),
                tacticValue(tactic.getPressing(), "Standard"),
                tacticValue(tactic.getWidth(), "Balanced"));
    }

    private static String tacticValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String tacticOverrideLabel() {
        TacticOverride override = requestedTacticOverride();
        if (override == null) return "none";
        return "teamId=" + override.teamId
                + ", formation=" + (override.formation == null ? "persisted" : override.formation)
                + ", mentality=" + (override.mentality == null ? "persisted" : override.mentality)
                + ", tempo=" + (override.tempo == null ? "persisted" : override.tempo)
                + ", passing=" + (override.passing == null ? "persisted" : override.passing)
                + ", defensiveLine=" + (override.defensiveLine == null ? "persisted" : override.defensiveLine)
                + ", pressing=" + (override.pressing == null ? "persisted" : override.pressing)
                + ", width=" + (override.width == null ? "persisted" : override.width);
    }

    private double refusesDefensiveWorkAttackMultiplier() {
        var rule = compartmentConfig.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (rule == null) throw new IllegalStateException("REFUSES_DEFENSIVE_WORK rule is missing");
        return rule.getAttackMultiplier();
    }

    private static int lineupOrder(LineupPlayer player) {
        return switch (player.position) {
            case GK -> 0;
            case DL, WBL -> 10;
            case DC -> 20;
            case DR, WBR -> 30;
            case DM -> 40;
            case ML -> 50;
            case MC -> 60;
            case MR -> 70;
            case AML -> 80;
            case AMC -> 90;
            case AMR -> 100;
            case ST -> 110;
        };
    }

    private static String lineupLabel(LineupPlayer player) {
        String slot = player.position.code() + (player.occurrence > 1 ? player.occurrence : "");
        StringBuilder details = new StringBuilder(String.format(
                "Overall %.1f; A %.1f / M %.1f / D %.1f",
                player.overallRating, player.attack, player.midfield, player.defense));
        if (player.role != null) details.append("; ").append(player.role);
        if (player.stayForward) details.append("; Stay Forward");
        if (player.shooter) details.append("; SHOOTER");
        return player.name + " — " + slot + " (" + details + ")";
    }

    private record TeamSetup(long id, String name, double topXiRating, String formation,
                             PersonalizedTactic tactic, CanonicalTeamEvaluation evaluation,
                             List<LineupPlayer> lineup) {
        private TeamSetup {
            lineup = List.copyOf(lineup);
        }

        private double goalkeeperOverall() {
            return lineup.stream()
                    .filter(player -> player.position() == PlayerPosition.GK)
                    .mapToDouble(LineupPlayer::overallRating)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("team has no goalkeeper slot: " + id));
        }
    }

    private record LineupPlayer(long playerId, String name, PlayerPosition position, int occurrence,
                                String role, double overallRating, double attack, double midfield,
                                double defense, boolean stayForward, boolean shooter) {}

    private record TacticOverride(long teamId, String formation, String mentality, String tempo,
                                  String passing, String defensiveLine, String pressing, String width) {}

    private record CanonicalStrength(double rawAttack, double rawMidfield, double rawDefense,
                                     double finalAttack, double finalProtection) {}

    private record AggregatedSimulation(long[][] positionCounts, long[] totalPoints, long[] totalGF,
                                        long[] totalGA, long[] totalWins, long[] totalDraws,
                                        long[] totalLosses, int[] championships, int matchesScored,
                                        int preparedFixtures, long elapsedMs,
                                        List<CanonicalStrength> strengths) {
        private AggregatedSimulation {
            strengths = List.copyOf(strengths);
        }
    }

    private static final class SeasonTable {
        private final int[] points;
        private final int[] goalsFor;
        private final int[] goalsAgainst;
        private final int[] wins;
        private final int[] draws;
        private final int[] losses;

        private SeasonTable(int teams) {
            points = new int[teams];
            goalsFor = new int[teams];
            goalsAgainst = new int[teams];
            wins = new int[teams];
            draws = new int[teams];
            losses = new int[teams];
        }

        private void apply(int home, int away, int homeGoals, int awayGoals) {
            goalsFor[home] += homeGoals;
            goalsAgainst[home] += awayGoals;
            goalsFor[away] += awayGoals;
            goalsAgainst[away] += homeGoals;
            if (homeGoals > awayGoals) {
                points[home] += 3;
                wins[home]++;
                losses[away]++;
            } else if (homeGoals < awayGoals) {
                points[away] += 3;
                wins[away]++;
                losses[home]++;
            } else {
                points[home]++;
                points[away]++;
                draws[home]++;
                draws[away]++;
            }
        }

        private Integer[] order(List<TeamSetup> teams) {
            Integer[] order = new Integer[points.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (first, second) -> {
                if (points[first] != points[second]) return Integer.compare(points[second], points[first]);
                int firstGoalDifference = goalsFor[first] - goalsAgainst[first];
                int secondGoalDifference = goalsFor[second] - goalsAgainst[second];
                if (firstGoalDifference != secondGoalDifference) {
                    return Integer.compare(secondGoalDifference, firstGoalDifference);
                }
                if (goalsFor[first] != goalsFor[second]) {
                    return Integer.compare(goalsFor[second], goalsFor[first]);
                }
                return teams.get(first).name.compareTo(teams.get(second).name);
            });
            return order;
        }
    }
}
