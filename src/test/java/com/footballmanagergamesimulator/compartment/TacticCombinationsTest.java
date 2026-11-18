package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure tests of the tactical combinations: real YAML catalogue, no Spring context, no database. */
class TacticCombinationsTest {

    private static final Map<String, Map<String, String>> SETTINGS = Map.of(
            "PASSING_STYLE", Map.of("passingType", "Short", "pressing", "Aggressive", "recovery", "Very Fast"),
            "POSSESSION", Map.of("inPossession", "Keep Ball", "passingType", "Short", "tempo", "Lower"),
            "GEGENPRESS", Map.of("pressing", "Aggressive", "defensiveLine", "High", "recovery", "Very Fast"),
            "COUNTER", Map.of("defensiveLine", "Deep", "transition", "Fast Counter", "passingType", "Long"),
            "WINGS", Map.of("width", "Wide", "widePlay", "Cross"),
            "BUS", Map.of("mentality", "Very Defensive", "defensiveLine", "Deep", "timeWasting", "Always"),
            "ALL_OUT_ATTACK", Map.of("mentality", "Very Attacking", "tempo", "Much Higher",
                    "defensiveLine", "High", "dribbling", "More"));

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();

    private List<String> active(CompartmentEngineConfig cfg, Map<String, String> settings, int level) {
        CanonicalTeamEvaluation team = new CanonicalMatchEvaluationAdapter(cfg, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, settings, level));
        return team.team().combinations().active();
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSING_STYLE", "POSSESSION", "GEGENPRESS", "COUNTER", "WINGS", "BUS", "ALL_OUT_ATTACK"})
    void eachCombinationNeedsAllItsSettings(String key) {
        Map<String, String> settings = SETTINGS.get(key);
        assertThat(active(config, settings, 12)).containsExactly(key);
        for (String axis : settings.keySet()) {
            Map<String, String> missing = new HashMap<>(settings);
            missing.remove(axis);
            assertThat(active(config, missing, 12)).as(key + " without " + axis).doesNotContain(key);
        }
    }

    @Test
    void eitherListedValueCounts() {
        assertThat(active(config, with(SETTINGS.get("POSSESSION"), "tempo", "Much Lower"), 12)).containsExactly("POSSESSION");
        assertThat(active(config, with(SETTINGS.get("GEGENPRESS"), "pressing", "Very Aggressive"), 12)).containsExactly("GEGENPRESS");
        assertThat(active(config, with(SETTINGS.get("GEGENPRESS"), "recovery", "Instantly"), 12)).containsExactly("GEGENPRESS");
        assertThat(active(config, with(SETTINGS.get("PASSING_STYLE"), "recovery", "Instantly"), 12)).containsExactly("PASSING_STYLE");
        // PASSING_STYLE accepts only Aggressive pressing, as before.
        assertThat(active(config, with(SETTINGS.get("PASSING_STYLE"), "pressing", "Very Aggressive"), 12)).isEmpty();
        assertThat(active(config, with(SETTINGS.get("POSSESSION"), "tempo", "Standard"), 12)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSING_STYLE", "POSSESSION", "GEGENPRESS", "COUNTER", "WINGS", "BUS", "ALL_OUT_ATTACK"})
    void sameTacticIsActiveForWeakAndStrongSquads(String key) {
        assertThat(active(config, SETTINGS.get(key), 3)).containsExactly(key);
        assertThat(active(config, SETTINGS.get(key), 19)).containsExactly(key);
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
        TacticCombinations.Profile weak = adapter.evaluateTeam(TacticCombinationCalibration.team(1, SETTINGS.get(key), 3))
                .team().combinations();
        TacticCombinations.Profile strong = adapter.evaluateTeam(TacticCombinationCalibration.team(1, SETTINGS.get(key), 19))
                .team().combinations();
        assertThat(strong.midfieldMultiplier()).isEqualTo(weak.midfieldMultiplier());
        assertThat(strong.ownXgMultiplier()).isEqualTo(weak.ownXgMultiplier());
        assertThat(strong.opponentXgMultiplier()).isEqualTo(weak.opponentXgMultiplier());
        assertThat(strong.redCardChanceAdd()).isEqualTo(weak.redCardChanceAdd());
    }

    @Test
    void wingsHasNoStrikerCondition() {
        Map<String, String> wings = SETTINGS.get("WINGS");
        assertThat(active(config, wings, 1)).containsExactly("WINGS");
        assertThat(active(config, wings, 19)).containsExactly("WINGS");
        assertThat(config.getTacticCombinations().getCombinations().get("WINGS").getOwnXg()).isPositive();
    }

    @Test
    void moreThanMaxActiveKeepsTheLargestBoosts() {
        // Four synergies at once: PASSING_STYLE, GEGENPRESS, WINGS, ALL_OUT_ATTACK.
        Map<String, String> four = new HashMap<>(SETTINGS.get("GEGENPRESS"));
        four.putAll(SETTINGS.get("ALL_OUT_ATTACK"));
        four.putAll(SETTINGS.get("WINGS"));
        four.put("passingType", "Short");
        CanonicalTeamEvaluation team = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, four, 12));
        assertThat(team.team().combinations().qualified())
                .containsExactly("PASSING_STYLE", "GEGENPRESS", "WINGS", "ALL_OUT_ATTACK");
        // maxActive 3: GEGENPRESS has the smallest boost (0.36) and is dropped.
        assertThat(team.team().combinations().active()).containsExactly("PASSING_STYLE", "WINGS", "ALL_OUT_ATTACK");
        assertThat(team.team().combinations().conflicts()).isEmpty();

        CompartmentEngineConfig two = CompartmentConfigFixture.load();
        two.getTacticCombinations().setMaxActive(2);
        assertThat(active(two, four, 12)).containsExactly("PASSING_STYLE", "ALL_OUT_ATTACK");

        // Three that fit together all count; their deltas add up per lever (costs included).
        Map<String, String> top = new HashMap<>(SETTINGS.get("COUNTER"));
        top.putAll(SETTINGS.get("BUS"));
        top.putAll(SETTINGS.get("WINGS"));
        TacticCombinations.Profile profile = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, top, 12)).team().combinations();
        assertThat(profile.active()).containsExactly("COUNTER", "WINGS", "BUS");
        assertThat(profile.ownXgMultiplier()).isCloseTo(1 + 0.44 + 0.40 + 0.30, within(1e-9));
        assertThat(profile.opponentXgMultiplier()).isCloseTo(1 - 0.55 + 0.04, within(1e-9));
        assertThat(profile.midfieldMultiplier()).isCloseTo(0.94, within(1e-9));

        // PASSING_STYLE counts like the others: Short + Aggressive + Very Fast + High line is two combinations.
        Map<String, String> pair = with(SETTINGS.get("PASSING_STYLE"), "defensiveLine", "High");
        assertThat(active(config, pair, 12)).containsExactly("PASSING_STYLE", "GEGENPRESS");
    }

    @Test
    void noActiveCombinationGivesTheSameMatchAsDisabled() {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        // Every new axis set, but no complete synergy and no conflict.
        Map<String, String> partial = Map.of("inPossession", "Keep Ball", "timeWasting", "Always",
                "dribbling", "More", "widePlay", "Cross", "transition", "Fast Counter", "recovery", "Instantly",
                "tempo", "Higher", "pressing", "Aggressive");
        Map<String, String> plain = Map.of("tempo", "Higher", "pressing", "Aggressive");
        assertThat(evaluation(config, partial, Map.of()).home().team().combinations().isActive()).isFalse();
        List<CanonicalScoreSampler.GoalSample> on = samples(config, partial, Map.of(), 300);
        assertThat(on).isEqualTo(samples(disabled, partial, Map.of(), 300));
        assertThat(on).isEqualTo(samples(config, plain, Map.of(), 300));
        assertThat(samples(config, Map.of(), Map.of(), 300)).isEqualTo(samples(disabled, Map.of(), Map.of(), 300));
    }

    private static List<CanonicalScoreSampler.GoalSample> samples(CompartmentEngineConfig cfg, Map<String, String> home,
                                                                  Map<String, String> away, int count) {
        CanonicalMatchEvaluation evaluation = evaluation(cfg, home, away);
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(cfg);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(seed -> sampler.sample(evaluation, 31L * seed + 7)).toList();
    }

    private static CanonicalMatchEvaluation evaluation(CompartmentEngineConfig cfg, Map<String, String> home,
                                                       Map<String, String> away) {
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(cfg, new MatchEngineConfig());
        return adapter.evaluate(TacticCombinationCalibration.team(1, home, 12),
                TacticCombinationCalibration.team(101, away, 12), MatchVenue.HOME);
    }

    /** Expected goals (home, away) with the bonus enabled, divided by the same with it disabled. */
    private double[] xgRatio(Map<String, String> home, Map<String, String> away) {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        CanonicalMatchEvaluation on = evaluation(config, home, away);
        CanonicalMatchEvaluation off = evaluation(disabled, home, away);
        return new double[] {on.probability().homeXg() / off.probability().homeXg(),
                on.probability().awayXg() / off.probability().awayXg()};
    }

    @Test
    void eachBoostMovesItsTarget() {
        // POSSESSION: more midfield (both compartments up), fewer own chances.
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        var possessionOn = evaluation(config, SETTINGS.get("POSSESSION"), Map.of()).home().team();
        var possessionOff = evaluation(disabled, SETTINGS.get("POSSESSION"), Map.of()).home().team();
        assertThat(possessionOn.rawTotals().midfield() / possessionOff.rawTotals().midfield()).isCloseTo(1.78, within(1e-9));
        assertThat(possessionOn.attackProtection()).isGreaterThan(possessionOff.attackProtection());
        assertThat(TacticCombinations.xgFactors(possessionOn, evaluation(config, Map.of(), Map.of()).away().team()).home())
                .isLessThan(1.0);

        double[] gegen = xgRatio(SETTINGS.get("GEGENPRESS"), Map.of());
        assertThat(gegen[0]).isCloseTo(1.36, within(1e-9));
        assertThat(gegen[1]).isCloseTo(1.04, within(1e-9));

        double[] counter = xgRatio(SETTINGS.get("COUNTER"), Map.of());
        double[] counterExposed = xgRatio(SETTINGS.get("COUNTER"), Map.of("mentality", "Attacking"));
        double[] counterHighLine = xgRatio(SETTINGS.get("COUNTER"), Map.of("defensiveLine", "High"));
        assertThat(counter[0]).isGreaterThan(1.0);
        assertThat(counterExposed[0]).isGreaterThan(counter[0]);
        assertThat(counterHighLine[0]).isGreaterThan(counter[0]);
        // The xG factor itself (the midfield cost also moves the matchup share, so ratios are not exact).
        var counterMatch = evaluation(config, SETTINGS.get("COUNTER"), Map.of());
        var counterVsHigh = evaluation(config, SETTINGS.get("COUNTER"), Map.of("defensiveLine", "High"));
        assertThat(TacticCombinations.xgFactors(counterMatch.home().team(), counterMatch.away().team()).home())
                .isCloseTo(1.44, within(1e-9));
        assertThat(TacticCombinations.xgFactors(counterVsHigh.home().team(), counterVsHigh.away().team()).home())
                .isCloseTo(1.44 * 1.08, within(1e-9));
        var counterOn = evaluation(config, SETTINGS.get("COUNTER"), Map.of()).home().team();
        var counterOff = evaluation(disabled, SETTINGS.get("COUNTER"), Map.of()).home().team();
        assertThat(counterOn.rawTotals().midfield()).isLessThan(counterOff.rawTotals().midfield());

        assertThat(xgRatio(SETTINGS.get("WINGS"), Map.of())[0]).isGreaterThan(1.0);
        double[] bus = xgRatio(SETTINGS.get("BUS"), Map.of());
        assertThat(bus[1]).isCloseTo(0.45, within(1e-9));
        assertThat(bus[0]).isCloseTo(1.30, within(1e-9));
        // Its cost is the Very Defensive attack: even with the bonus it scores less than a default side.
        double busXg = evaluation(config, SETTINGS.get("BUS"), Map.of()).probability().homeXg();
        double defaultXg = evaluation(config, Map.of(), Map.of()).probability().homeXg();
        assertThat(busXg).isLessThan(defaultXg);
        // Every synergy has a cost somewhere (BUS and ALL_OUT_ATTACK pay theirs through their extreme
        // mentality, checked above).
        config.getTacticCombinations().getCombinations().forEach((key, c) -> assertThat(
                c.getMidfield() < 0 || c.getOwnXg() < 0 || c.getOpponentXg() > 0 || c.getRedCard() > 0
                        || key.equals("BUS") || key.equals("ALL_OUT_ATTACK")).as(key + " has a cost").isTrue());
        // ALL_OUT_ATTACK makes the risky settings pay off: more own xG, fewer chances than naive Very
        // Attacking, but the tactic as a whole still concedes more than a default side.
        double[] attack = xgRatio(SETTINGS.get("ALL_OUT_ATTACK"), Map.of());
        assertThat(attack[0]).isCloseTo(1.15, within(1e-9));
        assertThat(attack[1]).isCloseTo(0.56, within(1e-9));
        assertThat(evaluation(config, SETTINGS.get("ALL_OUT_ATTACK"), Map.of()).probability().awayXg())
                .isGreaterThan(evaluation(config, Map.of(), Map.of()).probability().awayXg());

        // The bonus applies as well when the combination plays away.
        double[] gegenAway = xgRatio(Map.of(), SETTINGS.get("GEGENPRESS"));
        assertThat(gegenAway[1]).isCloseTo(1.36, within(1e-9));
        assertThat(gegenAway[0]).isCloseTo(1.04, within(1e-9));
    }

    @Test
    void gegenpressGetsMoreRedCards() {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        long on = samples(config, SETTINGS.get("GEGENPRESS"), Map.of(), 2000).stream()
                .filter(s -> s.homeRedCardPlayerId() != null).count();
        long off = samples(disabled, SETTINGS.get("GEGENPRESS"), Map.of(), 2000).stream()
                .filter(s -> s.homeRedCardPlayerId() != null).count();
        assertThat(on).isGreaterThan(off);
    }

    @Test
    void passingStyleErasesOpponentGoalsAndCreatesStrikerChances() {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        List<CanonicalScoreSampler.GoalSample> on = samples(config, SETTINGS.get("PASSING_STYLE"), Map.of(), 2000);
        List<CanonicalScoreSampler.GoalSample> off = samples(disabled, SETTINGS.get("PASSING_STYLE"), Map.of(), 2000);
        int conceded = on.stream().mapToInt(CanonicalScoreSampler.GoalSample::awayGoals).sum();
        int concededOff = off.stream().mapToInt(CanonicalScoreSampler.GoalSample::awayGoals).sum();
        assertThat(conceded).isLessThan(concededOff);
        assertThat(on.stream().mapToInt(CanonicalScoreSampler.GoalSample::homePassingOpportunities).sum()).isPositive();
        assertThat(off.stream().mapToInt(CanonicalScoreSampler.GoalSample::homePassingOpportunities).sum()).isZero();
        assertThat(on.get(0).homePassingControl()).isCloseTo(0.30, within(1e-12));
    }
    private static final Map<String, Map<String, String>> CONFLICT_SETTINGS = Map.of(
            "HIGH_LINE_NO_PRESS", Map.of("defensiveLine", "High", "pressing", "Easy"),
            "DEEP_LINE_HARD_PRESS", Map.of("defensiveLine", "Deep", "pressing", "Aggressive"),
            "KEEP_BALL_LONG", Map.of("inPossession", "Keep Ball", "passingType", "Long"),
            "DEFEND_HIGH", Map.of("mentality", "Defensive", "defensiveLine", "High"),
            "SLOW_COUNTER", Map.of("transition", "Fast Counter", "tempo", "Lower"),
            "NARROW_CROSS", Map.of("width", "Narrow", "widePlay", "Cross"),
            "ATTACK_TIME_WASTE", Map.of("mentality", "Attacking", "timeWasting", "Frequently"));

    private List<String> conflicts(CompartmentEngineConfig cfg, Map<String, String> settings) {
        return new CanonicalMatchEvaluationAdapter(cfg, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, settings, 12)).team().combinations().conflicts();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIGH_LINE_NO_PRESS", "DEEP_LINE_HARD_PRESS", "KEEP_BALL_LONG", "DEFEND_HIGH",
            "SLOW_COUNTER", "NARROW_CROSS", "ATTACK_TIME_WASTE"})
    void eachConflictNeedsAllItsSettings(String key) {
        Map<String, String> settings = CONFLICT_SETTINGS.get(key);
        assertThat(conflicts(config, settings)).containsExactly(key);
        assertThat(active(config, settings, 12)).isEmpty();
        for (String axis : settings.keySet()) {
            Map<String, String> missing = new HashMap<>(settings);
            missing.remove(axis);
            assertThat(conflicts(config, missing)).as(key + " without " + axis).doesNotContain(key);
        }
    }

    @Test
    void eitherListedConflictValueCounts() {
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("HIGH_LINE_NO_PRESS"), "pressing", "Very Easy")))
                .containsExactly("HIGH_LINE_NO_PRESS");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("DEEP_LINE_HARD_PRESS"), "pressing", "Very Aggressive")))
                .containsExactly("DEEP_LINE_HARD_PRESS");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("DEFEND_HIGH"), "mentality", "Very Defensive")))
                .containsExactly("DEFEND_HIGH");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("SLOW_COUNTER"), "tempo", "Much Lower")))
                .containsExactly("SLOW_COUNTER");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("ATTACK_TIME_WASTE"), "mentality", "Very Attacking")))
                .containsExactly("ATTACK_TIME_WASTE");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("ATTACK_TIME_WASTE"), "timeWasting", "Always")))
                .containsExactly("ATTACK_TIME_WASTE");
        assertThat(conflicts(config, with(CONFLICT_SETTINGS.get("HIGH_LINE_NO_PRESS"), "pressing", "Normal"))).isEmpty();
    }

    @Test
    void eachConflictPenaltyMovesItsTarget() {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        assertThat(xgRatio(CONFLICT_SETTINGS.get("HIGH_LINE_NO_PRESS"), Map.of())[1]).isCloseTo(1.45, within(1e-9));
        assertThat(xgRatio(CONFLICT_SETTINGS.get("DEFEND_HIGH"), Map.of())[1]).isCloseTo(1.30, within(1e-9));
        assertThat(xgRatio(CONFLICT_SETTINGS.get("SLOW_COUNTER"), Map.of())[0]).isCloseTo(0.74, within(1e-9));
        assertThat(xgRatio(CONFLICT_SETTINGS.get("NARROW_CROSS"), Map.of())[0]).isCloseTo(0.74, within(1e-9));
        assertThat(xgRatio(CONFLICT_SETTINGS.get("ATTACK_TIME_WASTE"), Map.of())[0]).isCloseTo(0.88, within(1e-9));
        // Midfield conflicts: less midfield mass, plus their xG penalty.
        var deepOn = evaluation(config, CONFLICT_SETTINGS.get("DEEP_LINE_HARD_PRESS"), Map.of()).home().team();
        var deepOff = evaluation(disabled, CONFLICT_SETTINGS.get("DEEP_LINE_HARD_PRESS"), Map.of()).home().team();
        assertThat(deepOn.rawTotals().midfield() / deepOff.rawTotals().midfield()).isCloseTo(0.88, within(1e-9));
        assertThat(deepOn.combinations().opponentXgMultiplier()).isCloseTo(1.15, within(1e-9));
        var keepOn = evaluation(config, CONFLICT_SETTINGS.get("KEEP_BALL_LONG"), Map.of()).home().team();
        var keepOff = evaluation(disabled, CONFLICT_SETTINGS.get("KEEP_BALL_LONG"), Map.of()).home().team();
        assertThat(keepOn.rawTotals().midfield() / keepOff.rawTotals().midfield()).isCloseTo(0.88, within(1e-9));
        assertThat(keepOn.combinations().ownXgMultiplier()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void allConflictsCountAndCompound() {
        Map<String, String> four = Map.of("defensiveLine", "High", "pressing", "Very Easy",
                "mentality", "Defensive", "inPossession", "Keep Ball", "passingType", "Long",
                "width", "Narrow", "widePlay", "Cross");
        TacticCombinations.Profile profile = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, four, 12)).team().combinations();
        assertThat(profile.conflicts())
                .containsExactly("HIGH_LINE_NO_PRESS", "KEEP_BALL_LONG", "DEFEND_HIGH", "NARROW_CROSS");
        assertThat(profile.active()).isEmpty();
        // Four conflicts: every delta x (1 + 0.2 * 3) = 1.6.
        assertThat(profile.opponentXgMultiplier()).isCloseTo((1 + 0.45 * 1.6) * (1 + 0.30 * 1.6), within(1e-9));
        assertThat(profile.ownXgMultiplier()).isCloseTo((1 - 0.15 * 1.6) * (1 - 0.26 * 1.6), within(1e-9));
        assertThat(profile.midfieldMultiplier()).isCloseTo(1 - 0.12 * 1.6, within(1e-9));

        CompartmentEngineConfig flat = CompartmentConfigFixture.load();
        flat.getTacticCombinations().setConflictEscalation(0.0);
        TacticCombinations.Profile plain = new CanonicalMatchEvaluationAdapter(flat, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, four, 12)).team().combinations();
        assertThat(plain.opponentXgMultiplier()).isCloseTo(1.45 * 1.30, within(1e-9));
        assertThat(profile.opponentXgMultiplier()).isGreaterThan(plain.opponentXgMultiplier());

        // More conflicts score fewer points.
        double one = points(config, CONFLICT_SETTINGS.get("NARROW_CROSS"));
        double all = points(config, four);
        assertThat(all).isLessThan(one);
        assertThat(one).isLessThan(points(config, Map.of()));
    }

    @Test
    void synergyAndConflictApplyTogether() {
        // WINGS (Wide + Cross) together with DEEP_LINE_HARD_PRESS (Deep + Aggressive).
        Map<String, String> mixed = Map.of("width", "Wide", "widePlay", "Cross",
                "defensiveLine", "Deep", "pressing", "Aggressive");
        TacticCombinations.Profile profile = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, mixed, 12)).team().combinations();
        assertThat(profile.active()).containsExactly("WINGS");
        assertThat(profile.conflicts()).containsExactly("DEEP_LINE_HARD_PRESS");
        assertThat(profile.ownXgMultiplier()).isCloseTo(1.40, within(1e-9));
        assertThat(profile.opponentXgMultiplier()).isCloseTo(1.04 * 1.15, within(1e-9));
        assertThat(profile.midfieldMultiplier()).isCloseTo(0.88, within(1e-9));
    }

    @Test
    void conflictsAreRatingFree() {
        Map<String, String> settings = CONFLICT_SETTINGS.get("KEEP_BALL_LONG");
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
        TacticCombinations.Profile weak = adapter.evaluateTeam(TacticCombinationCalibration.team(1, settings, 3))
                .team().combinations();
        TacticCombinations.Profile strong = adapter.evaluateTeam(TacticCombinationCalibration.team(1, settings, 19))
                .team().combinations();
        assertThat(weak.conflicts()).containsExactly("KEEP_BALL_LONG");
        assertThat(strong.conflicts()).containsExactly("KEEP_BALL_LONG");
        assertThat(strong.ownXgMultiplier()).isEqualTo(weak.ownXgMultiplier());
        assertThat(strong.midfieldMultiplier()).isEqualTo(weak.midfieldMultiplier());
    }

    @Test
    void conflictsAreOffWhenDisabled() {
        CompartmentEngineConfig disabled = CompartmentConfigFixture.load();
        disabled.getTacticCombinations().setEnabled(false);
        Map<String, String> four = Map.of("defensiveLine", "High", "pressing", "Very Easy",
                "mentality", "Defensive", "inPossession", "Keep Ball", "passingType", "Long");
        assertThat(conflicts(disabled, four)).isEmpty();
        assertThat(evaluation(disabled, four, Map.of()).home().team().combinations().isActive()).isFalse();
    }

    private static double points(CompartmentEngineConfig cfg, Map<String, String> settings) {
        List<CanonicalScoreSampler.GoalSample> list = samples(cfg, settings, Map.of(), 3000);
        return list.stream().mapToInt(s -> s.homeGoals() > s.awayGoals() ? 3 : s.homeGoals() == s.awayGoals() ? 1 : 0)
                .average().orElse(0);
    }
    @Test
    void synergiesAddTheirDeltasWithTheStackShare() {
        Map<String, String> pair = new HashMap<>(SETTINGS.get("GEGENPRESS"));
        pair.putAll(SETTINGS.get("WINGS"));
        TacticCombinations.Profile sum = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, pair, 12)).team().combinations();
        assertThat(sum.active()).containsExactly("GEGENPRESS", "WINGS");
        assertThat(sum.ownXgMultiplier()).isCloseTo(1 + 0.36 + 0.40, within(1e-9));
        assertThat(sum.opponentXgMultiplier()).isCloseTo(1 + 0.04 + 0.04, within(1e-9));

        CompartmentEngineConfig half = CompartmentConfigFixture.load();
        half.getTacticCombinations().setSynergyStackShare(0.5);
        TacticCombinations.Profile damped = new CanonicalMatchEvaluationAdapter(half, new MatchEngineConfig())
                .evaluateTeam(TacticCombinationCalibration.team(1, pair, 12)).team().combinations();
        // Largest helpful delta in full, the next one at half; costs always in full.
        assertThat(damped.ownXgMultiplier()).isCloseTo(1 + 0.40 + 0.5 * 0.36, within(1e-9));
        assertThat(damped.opponentXgMultiplier()).isCloseTo(1 + 0.04 + 0.04, within(1e-9));
    }
}
