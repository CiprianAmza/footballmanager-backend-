package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducible statistical snapshot of the current {@link TacticalScoreService} scoring kernel.
 *
 * <p>This is a characterization test, not a calibration claim. It runs fixed synthetic profiles,
 * tactics and seeds against the untouched current engine. Updating the committed artifact requires
 * an explicit review of the current-engine change; the compartment formulas never write it.
 */
class CurrentTacticalEngineBaselineTest {

    private static final String ARTIFACT = "/compartment-engine/current-tactical-engine-baseline.json";
    private static final String BASE_COMMIT = "49664fb2d56f6e6b99e25abfe882f88d4c1df392";
    private static final long BASE_SEED = 2026072301L;
    private static final int MATCHES_PER_SCENARIO = 20_000;

    private final MatchEngineConfig currentConfig = new MatchEngineConfig();
    private final TacticalScoreService currentEngine = currentEngine();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void committedSnapshotMatchesCurrentEngineExactly() throws Exception {
        BaselineSnapshot actual = generateSnapshot();
        if (Boolean.getBoolean("compartment.baseline.print")) {
            System.out.println("COMPARTMENT_BASELINE_JSON_START");
            System.out.println(mapper.writeValueAsString(actual));
            System.out.println("COMPARTMENT_BASELINE_JSON_END");
            return;
        }
        try (InputStream input = getClass().getResourceAsStream(ARTIFACT)) {
            assertThat(input).as("committed baseline artifact " + ARTIFACT).isNotNull();
            BaselineSnapshot expected = mapper.readValue(input, BaselineSnapshot.class);
            assertThat(actual).isEqualTo(expected);
        }
    }

    private TacticalScoreService currentEngine() {
        TacticalScoreService service = new TacticalScoreService();
        service.engineConfig = currentConfig;
        return service;
    }

    private BaselineSnapshot generateSnapshot() {
        MatchEngineConfig.TacticalModel cfg = currentConfig.getTacticalModel();
        List<Scenario> definitions = List.of(
                scenario("equal-balanced", 1000, 1000, 1000, 1000, neutral(), neutral()),
                scenario("home-thirty-percent-stronger", 1300, 1300, 1000, 1000, neutral(), neutral()),
                scenario("home-thirty-percent-underdog", 1000, 1000, 1300, 1300, neutral(), neutral()),
                scenario("equal-home-very-defensive", 1000, 1000, 1000, 1000, veryDefensive(), neutral()),
                scenario("equal-home-very-attacking", 1000, 1000, 1000, 1000, veryAttacking(), neutral()),
                scenario("equal-home-high-line-vs-direct", 1000, 1000, 1000, 1000, highLinePress(), directCounter())
        );
        List<ScenarioSnapshot> results = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            results.add(run(definitions.get(i), BASE_SEED + (long) i * 1_000_003L));
        }
        return new BaselineSnapshot(1, BASE_COMMIT, TacticalScoreService.class.getName(), BASE_SEED,
                MATCHES_PER_SCENARIO,
                new EngineConfigSnapshot(cfg.isEnabled(), cfg.getRatioExponent(), cfg.getBaseOpenness(),
                        cfg.getHomeAttackBonus(), cfg.getMaxGoalsPerTeam()),
                results);
    }

    private ScenarioSnapshot run(Scenario scenario, long seed) {
        Random random = new Random(seed);
        int homeWins = 0, draws = 0, awayWins = 0;
        long homeGoals = 0, awayGoals = 0;
        int scorelessDraws = 0, sixPlusGoals = 0, goalCapHits = 0;
        for (int i = 0; i < MATCHES_PER_SCENARIO; i++) {
            List<Integer> score = currentEngine.score(scenario.homeProfile(), scenario.homeTactic(),
                    scenario.awayProfile(), scenario.awayTactic(), random);
            int home = score.get(0), away = score.get(1);
            homeGoals += home;
            awayGoals += away;
            if (home > away) homeWins++;
            else if (home == away) draws++;
            else awayWins++;
            if (home == 0 && away == 0) scorelessDraws++;
            if (home + away >= 6) sixPlusGoals++;
            if (home == currentConfig.getTacticalModel().getMaxGoalsPerTeam()
                    || away == currentConfig.getTacticalModel().getMaxGoalsPerTeam()) goalCapHits++;
        }
        double matches = MATCHES_PER_SCENARIO;
        return new ScenarioSnapshot(scenario.name(), seed, MATCHES_PER_SCENARIO,
                homeWins, draws, awayWins, homeGoals, awayGoals, scorelessDraws, sixPlusGoals, goalCapHits,
                (homeGoals + awayGoals) / matches, homeWins / matches, draws / matches, awayWins / matches,
                scorelessDraws / matches, sixPlusGoals / matches, goalCapHits / matches);
    }

    private Scenario scenario(String name, double homeAttack, double homeDefense,
                              double awayAttack, double awayDefense,
                              PersonalizedTactic homeTactic, PersonalizedTactic awayTactic) {
        return new Scenario(name,
                new TacticalScoreService.TeamProfile(homeAttack, homeDefense),
                new TacticalScoreService.TeamProfile(awayAttack, awayDefense),
                currentEngine.vector(homeTactic), currentEngine.vector(awayTactic));
    }

    private static PersonalizedTactic neutral() {
        return new PersonalizedTactic();
    }

    private static PersonalizedTactic veryDefensive() {
        return tactic("Very Defensive", "Lower", "Short", "Keep Ball", "Always",
                "Deep", "Low", "Narrow");
    }

    private static PersonalizedTactic veryAttacking() {
        return tactic("Very Attacking", "Higher", "Long", "Free Ball Early", "Never",
                "High", "High", "Wide");
    }

    private static PersonalizedTactic highLinePress() {
        return tactic("Balanced", "Higher", "Short", "Standard", "Sometimes",
                "High", "High", "Wide");
    }

    private static PersonalizedTactic directCounter() {
        return tactic("Balanced", "Higher", "Long", "Free Ball Early", "Never",
                "Standard", "Low", "Narrow");
    }

    private static PersonalizedTactic tactic(String mentality, String tempo, String passing,
                                             String possession, String timeWasting,
                                             String line, String pressing, String width) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality(mentality);
        tactic.setTempo(tempo);
        tactic.setPassingType(passing);
        tactic.setInPossession(possession);
        tactic.setTimeWasting(timeWasting);
        tactic.setDefensiveLine(line);
        tactic.setPressing(pressing);
        tactic.setWidth(width);
        return tactic;
    }

    private record Scenario(String name, TacticalScoreService.TeamProfile homeProfile,
                            TacticalScoreService.TeamProfile awayProfile,
                            TacticalScoreService.TacticVector homeTactic,
                            TacticalScoreService.TacticVector awayTactic) {}

    public record BaselineSnapshot(int schemaVersion, String baseCommit, String engine,
                                   long baseSeed, int matchesPerScenario,
                                   EngineConfigSnapshot engineConfig,
                                   List<ScenarioSnapshot> scenarios) {
        public BaselineSnapshot {
            scenarios = List.copyOf(scenarios);
        }
    }

    public record EngineConfigSnapshot(boolean enabled, double ratioExponent, double baseOpenness,
                                       double homeAttackBonus, int maxGoalsPerTeam) {}

    public record ScenarioSnapshot(String name, long seed, int matches,
                                   int homeWins, int draws, int awayWins,
                                   long homeGoals, long awayGoals,
                                   int scorelessDraws, int sixPlusGoals, int goalCapHits,
                                   double goalsPerMatch, double homeWinRate, double drawRate,
                                   double awayWinRate, double scorelessDrawRate,
                                   double sixPlusGoalRate, double goalCapHitRate) {}
}
