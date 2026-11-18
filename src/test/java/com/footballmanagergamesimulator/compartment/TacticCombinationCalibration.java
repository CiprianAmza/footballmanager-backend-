package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calibration measurement for the tactical combinations and conflicts (not a unit test; run its
 * {@code main}).
 *
 * <p>No Spring context and no database: the real YAML catalogue is bound with Spring's
 * {@code Binder} and the classic scoring code (team evaluation, match evaluation, score sampler) is
 * called directly. Team A (4-4-2) plays seeded matches against a default 4-4-2 team B, alternating
 * home and away. Every row is measured with {@code tactic-combinations.enabled=false} (the settings
 * alone) and {@code true} (the whole tactic: settings + synergies + conflicts) on the same seeds.
 *
 * <p>Matchups: equal teams (attributes 12 v 12), "Zero-like" (A stronger, about 1.97 points per match
 * on defaults) and "underdog" (A weaker, about 0.90). Fractional levels spread the attribute values
 * over the eleven players, so the average is exact. Nothing in the combinations reads attributes.
 *
 * <p>Arguments: {@code matches=N} (default 40000), {@code zero=L}, {@code under=L},
 * {@code mode=levels} (print default-v-default points for a range of levels) and overrides
 * {@code KEY.field=value} for a synergy or a conflict, e.g. {@code POSSESSION.midfield=0.12}.
 */
public final class TacticCombinationCalibration {

    private static final double AVERAGE = 12.0;

    static final Map<String, Map<String, String>> SINGLES = new LinkedHashMap<>();
    static final Map<String, Map<String, String>> TOPS = new LinkedHashMap<>();
    static final Map<String, Map<String, String>> CONFLICTS = new LinkedHashMap<>();
    static final Map<String, Map<String, String>> BAD = new LinkedHashMap<>();

    static {
        SINGLES.put("PASSING_STYLE", Map.of("passingType", "Short", "pressing", "Aggressive", "recovery", "Very Fast"));
        SINGLES.put("POSSESSION", Map.of("inPossession", "Keep Ball", "passingType", "Short", "tempo", "Lower"));
        SINGLES.put("GEGENPRESS", Map.of("pressing", "Aggressive", "defensiveLine", "High", "recovery", "Very Fast"));
        SINGLES.put("COUNTER", Map.of("defensiveLine", "Deep", "transition", "Fast Counter", "passingType", "Long"));
        SINGLES.put("WINGS", Map.of("width", "Wide", "widePlay", "Cross"));
        SINGLES.put("BUS", Map.of("mentality", "Very Defensive", "defensiveLine", "Deep", "timeWasting", "Always"));
        SINGLES.put("ALL_OUT_ATTACK", Map.of("mentality", "Very Attacking", "tempo", "Much Higher",
                "defensiveLine", "High", "dribbling", "More"));

        TOPS.put("TOP GEGENPRESS+ALL_OUT_ATTACK+WINGS", merge("GEGENPRESS", "ALL_OUT_ATTACK", "WINGS"));
        TOPS.put("TOP POSSESSION+PASSING_STYLE+WINGS", merge("POSSESSION", "PASSING_STYLE", "WINGS"));
        TOPS.put("TOP COUNTER+WINGS", merge("COUNTER", "WINGS"));
        TOPS.put("COUNTER+BUS+WINGS", merge("COUNTER", "BUS", "WINGS"));

        CONFLICTS.put("HIGH_LINE_NO_PRESS", Map.of("defensiveLine", "High", "pressing", "Easy"));
        CONFLICTS.put("DEEP_LINE_HARD_PRESS", Map.of("defensiveLine", "Deep", "pressing", "Aggressive"));
        CONFLICTS.put("KEEP_BALL_LONG", Map.of("inPossession", "Keep Ball", "passingType", "Long"));
        CONFLICTS.put("DEFEND_HIGH", Map.of("mentality", "Defensive", "defensiveLine", "High"));
        CONFLICTS.put("SLOW_COUNTER", Map.of("transition", "Fast Counter", "tempo", "Lower"));
        CONFLICTS.put("NARROW_CROSS", Map.of("width", "Narrow", "widePlay", "Cross"));
        CONFLICTS.put("ATTACK_TIME_WASTE", Map.of("mentality", "Attacking", "timeWasting", "Frequently"));

        BAD.put("BAD 3 conflicts", Map.of("defensiveLine", "High", "pressing", "Very Easy",
                "mentality", "Defensive", "inPossession", "Keep Ball", "passingType", "Long"));
        BAD.put("BAD 4 conflicts", Map.of("defensiveLine", "High", "pressing", "Very Easy",
                "mentality", "Defensive", "inPossession", "Keep Ball", "passingType", "Long",
                "width", "Narrow", "widePlay", "Cross"));
        BAD.put("BAD 4 conflicts with slow counter", Map.of("transition", "Fast Counter", "tempo", "Much Lower",
                "inPossession", "Keep Ball", "passingType", "Long", "width", "Narrow", "widePlay", "Cross",
                "mentality", "Attacking", "timeWasting", "Always"));
    }

    private static Map<String, String> merge(String... keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) result.putAll(SINGLES.get(key));
        return Map.copyOf(result);
    }

    public static void main(String[] args) {
        int matches = 40_000;
        double zero = 13.55;
        double under = 10.5;
        String mode = "all";
        boolean zeroOnly = false;
        CompartmentEngineConfig config = CompartmentConfigFixture.load();
        for (String arg : args) {
            if (arg.startsWith("matches=")) matches = Integer.parseInt(arg.substring(8));
            else if (arg.startsWith("zero=")) zero = Double.parseDouble(arg.substring(5));
            else if (arg.startsWith("under=")) under = Double.parseDouble(arg.substring(6));
            else if (arg.startsWith("mode=")) mode = arg.substring(5);
            else if (arg.equals("only=zero")) zeroOnly = true;
            else override(config, arg);
        }
        if (mode.equals("xg")) {
            expectationTable(config, zero, matches);
            return;
        }
        if (mode.equals("levels")) {
            for (double level = 9.0; level <= 16.0; level += 0.25) {
                Result r = simulate(config, Map.of(), Map.of(), level, AVERAGE, matches);
                System.out.printf(Locale.ROOT, "A level %.2f v 12: %.3f pts  %.2f-%.2f%n", level, r.pts(), r.gf(), r.ga());
            }
            return;
        }
        System.out.println("synergies " + config.getTacticCombinations().getCombinations().keySet()
                + " conflicts " + config.getTacticCombinations().getConflicts().keySet()
                + " enabled=" + config.getTacticCombinations().isEnabled()
                + " maxActive=" + config.getTacticCombinations().getMaxActive());
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        rows.put("BASE (all defaults)", Map.of());
        rows.putAll(SINGLES);
        rows.putAll(TOPS);
        CONFLICTS.forEach((key, value) -> rows.put("CONFLICT " + key, value));
        rows.putAll(BAD);

        double[][] matchups = {{zero, AVERAGE}, {AVERAGE, AVERAGE}};
        String[] names = {"Zero-like (A " + zero + " v 12)", "equal teams (12 v 12)"};
        for (int m = 0; m < (zeroOnly ? 1 : matchups.length); m++) {
            System.out.printf(Locale.ROOT, "%n%s, %d seeded matches per row, A alternates home/away, B on defaults%n",
                    names[m], matches);
            header();
            for (Map.Entry<String, Map<String, String>> row : rows.entrySet()) {
                run(config, row.getKey(), row.getValue(), Map.of(), matchups[m][0], matchups[m][1], matches);
            }
        }
        if (zeroOnly) return;
        System.out.printf(Locale.ROOT, "%nunderdog (A %.2f v 12)%n", under);
        header();
        run(config, "BASE (all defaults)", Map.of(), Map.of(), under, AVERAGE, matches);
        run(config, "BUS", SINGLES.get("BUS"), Map.of(), under, AVERAGE, matches);
        run(config, "COUNTER+BUS+WINGS", TOPS.get("COUNTER+BUS+WINGS"), Map.of(), under, AVERAGE, matches);

        System.out.printf(Locale.ROOT, "%nZero-like v an Attacking + High-line opponent%n");
        header();
        Map<String, String> exposed = Map.of("mentality", "Attacking", "defensiveLine", "High");
        run(config, "BASE (all defaults)", Map.of(), exposed, zero, AVERAGE, matches);
        run(config, "COUNTER", SINGLES.get("COUNTER"), exposed, zero, AVERAGE, matches);
    }

    /**
     * Pre-sampling expectation against the sampled reality: for both sides, mean goals vs mean xG
     * and mean points vs mean xPts over the same seeded matches (A alternates home and away).
     */
    private static void expectationTable(CompartmentEngineConfig config, double zero, int matches) {
        record Row(String name, Map<String, String> settings, double levelA, double levelB, boolean shooter) {}
        List<Row> rows = List.of(
                new Row("default v default (12 v 12)", Map.of(), AVERAGE, AVERAGE, false),
                new Row("A with a SHOOTER (12 v 12)", Map.of(), AVERAGE, AVERAGE, true),
                new Row("TOP Possession+Passing style+Wings", TOPS.get("TOP POSSESSION+PASSING_STYLE+WINGS"),
                        AVERAGE, AVERAGE, false),
                new Row("BAD 4 conflicts", BAD.get("BAD 4 conflicts"), AVERAGE, AVERAGE, false),
                new Row("Zero-like (" + zero + " v 12)", Map.of(), zero, AVERAGE, false),
                new Row("TOP Gegenpress+AOA+Wings, Zero-like", TOPS.get("TOP GEGENPRESS+ALL_OUT_ATTACK+WINGS"),
                        zero, AVERAGE, false));
        System.out.printf(Locale.ROOT, "%d seeded matches per row%n", matches);
        System.out.printf(Locale.ROOT, "%-38s | %-27s | %-27s | %-27s | %-27s%n", "scenario",
                "A goals / xG (diff)", "B goals / xG (diff)", "A pts / xPts (diff)", "B pts / xPts (diff)");
        for (Row row : rows) {
            CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
            CanonicalScoreSampler sampler = new CanonicalScoreSampler(config);
            CanonicalTeamEvaluation a = adapter.evaluateTeam(team(1, row.settings(), row.levelA(), row.shooter()));
            CanonicalTeamEvaluation b = adapter.evaluateTeam(team(101, Map.of(), row.levelB(), false));
            CanonicalMatchEvaluation aHome = adapter.evaluate(a, b, MatchVenue.HOME);
            CanonicalMatchEvaluation bHome = adapter.evaluate(b, a, MatchVenue.HOME);
            CanonicalScoreSampler.MatchExpectation eA = sampler.expect(aHome);
            CanonicalScoreSampler.MatchExpectation eB = sampler.expect(bHome);
            double goalsA = 0, goalsB = 0, ptsA = 0, ptsB = 0, xgA = 0, xgB = 0, xptsA = 0, xptsB = 0;
            for (int i = 0; i < matches; i++) {
                long seed = 0x9E3779B97F4A7C15L * (i + 1) + 1_234_567L;
                int forA;
                int forB;
                if (i % 2 == 0) {
                    CanonicalScoreSampler.GoalSample s = sampler.sample(aHome, seed);
                    forA = s.homeGoals();
                    forB = s.awayGoals();
                    xgA += eA.homeXg(); xgB += eA.awayXg(); xptsA += eA.homeXpts(); xptsB += eA.awayXpts();
                } else {
                    CanonicalScoreSampler.GoalSample s = sampler.sample(bHome, seed);
                    forA = s.awayGoals();
                    forB = s.homeGoals();
                    xgA += eB.awayXg(); xgB += eB.homeXg(); xptsA += eB.awayXpts(); xptsB += eB.homeXpts();
                }
                goalsA += forA;
                goalsB += forB;
                ptsA += forA > forB ? 3 : forA == forB ? 1 : 0;
                ptsB += forB > forA ? 3 : forA == forB ? 1 : 0;
            }
            double n = matches;
            System.out.printf(Locale.ROOT, "%-38s | %5.3f / %5.3f (%+6.3f)    | %5.3f / %5.3f (%+6.3f)    | %5.3f / %5.3f (%+6.3f)    | %5.3f / %5.3f (%+6.3f)%n",
                    row.name(), goalsA / n, xgA / n, (goalsA - xgA) / n, goalsB / n, xgB / n, (goalsB - xgB) / n,
                    ptsA / n, xptsA / n, (ptsA - xptsA) / n, ptsB / n, xptsB / n, (ptsB - xptsB) / n);
        }
    }

    private static void header() {
        System.out.printf(Locale.ROOT, "%-38s | %-20s | %-20s | %-7s | %s%n", "scenario",
                "settings only pts GF GA", "whole tactic pts GF GA", "delta", "synergies / conflicts (A)");
    }

    private static void run(CompartmentEngineConfig config, String name, Map<String, String> settingsA,
                            Map<String, String> settingsB, double levelA, double levelB, int matches) {
        boolean original = config.getTacticCombinations().isEnabled();
        try {
            config.getTacticCombinations().setEnabled(false);
            Result off = simulate(config, settingsA, settingsB, levelA, levelB, matches);
            config.getTacticCombinations().setEnabled(true);
            Result on = simulate(config, settingsA, settingsB, levelA, levelB, matches);
            System.out.printf(Locale.ROOT, "%-38s | %5.3f %4.2f %4.2f     | %5.3f %4.2f %4.2f     | %+6.3f | %s %s%n",
                    name, off.pts(), off.gf(), off.ga(), on.pts(), on.gf(), on.ga(), on.pts() - off.pts(),
                    on.active, on.conflicts);
        } finally {
            config.getTacticCombinations().setEnabled(original);
        }
    }

    private record Result(double points, double goalsFor, double goalsAgainst, int matches,
                          List<String> active, List<String> conflicts) {
        double pts() { return points / matches; }
        double gf() { return goalsFor / matches; }
        double ga() { return goalsAgainst / matches; }
    }

    static Result simulate(CompartmentEngineConfig config, Map<String, String> settingsA,
                           Map<String, String> settingsB, double levelA, double levelB, int matches) {
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(config);
        CanonicalTeamEvaluation a = adapter.evaluateTeam(team(1, settingsA, levelA));
        CanonicalTeamEvaluation b = adapter.evaluateTeam(team(101, settingsB, levelB));
        CanonicalMatchEvaluation aHome = adapter.evaluate(a, b, MatchVenue.HOME);
        CanonicalMatchEvaluation bHome = adapter.evaluate(b, a, MatchVenue.HOME);
        double points = 0;
        double gf = 0;
        double ga = 0;
        for (int i = 0; i < matches; i++) {
            long seed = 0x9E3779B97F4A7C15L * (i + 1) + 1_234_567L;
            int forA;
            int againstA;
            if (i % 2 == 0) {
                CanonicalScoreSampler.GoalSample s = sampler.sample(aHome, seed);
                forA = s.homeGoals();
                againstA = s.awayGoals();
            } else {
                CanonicalScoreSampler.GoalSample s = sampler.sample(bHome, seed);
                forA = s.awayGoals();
                againstA = s.homeGoals();
            }
            points += forA > againstA ? 3 : forA == againstA ? 1 : 0;
            gf += forA;
            ga += againstA;
        }
        TacticCombinations.Profile profile = a.team().combinations();
        return new Result(points, gf, ga, matches,
                profile == null ? List.of() : profile.active(), profile == null ? List.of() : profile.conflicts());
    }

    /** A 4-4-2 whose attributes average {@code level} (12 = average); fractions are spread over the XI. */
    static CanonicalRuntimeTeamInput team(long firstId, Map<String, String> settings, double level) {
        return team(firstId, settings, level, false);
    }

    /** As above; with {@code shooter} the ML is a SHOOTER with Long Shots and Positioning 20. */
    static CanonicalRuntimeTeamInput team(long firstId, Map<String, String> settings, double level, boolean shooter) {
        List<PlayerPosition> positions = List.of(PlayerPosition.GK, PlayerPosition.DL, PlayerPosition.DC,
                PlayerPosition.DC, PlayerPosition.DR, PlayerPosition.ML, PlayerPosition.MC, PlayerPosition.MC,
                PlayerPosition.MR, PlayerPosition.ST, PlayerPosition.ST);
        TacticalContextInput context = context(settings);
        List<CanonicalLineupPlayer> lineup = new ArrayList<>();
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        int base = (int) Math.floor(level);
        int raised = (int) Math.round((level - base) * positions.size());
        for (int i = 0; i < positions.size(); i++) {
            PlayerPosition position = positions.get(i);
            int occurrence = (int) positions.subList(0, i).stream().filter(p -> p == position).count() + 1;
            // Spread the raised players evenly over the XI (every position group gets its share).
            int value = (i * raised) / positions.size() != ((i + 1) * raised) / positions.size() ? base + 1 : base;
            EnumMap<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) attributes.put(attribute, value);
            Set<PlayerTrait> traits = shooter && position == PlayerPosition.ML ? Set.of(PlayerTrait.SHOOTER) : Set.of();
            if (!traits.isEmpty()) {
                attributes.put(PlayerAttribute.LONG_SHOTS, 20);
                attributes.put(PlayerAttribute.POSITIONING, 20);
            }
            long id = firstId + i;
            PlayerCapabilitySnapshot capability = new PlayerCapabilitySnapshot(id, position,
                    Map.of(position, 20), Map.of(), 10, 10, false, false, false);
            lineup.add(new CanonicalLineupPlayer(id, position, occurrence, null, Duty.SUPPORT, attributes,
                    100, 70, capability, 50.0, traits, ForwardInstruction.DEFAULT, 100.0));
            contexts.put(id, context);
        }
        return new CanonicalRuntimeTeamInput(mentality(context.mentality()), lineup, contexts);
    }

    static CanonicalRuntimeTeamInput team(long firstId, Map<String, String> settings, int level) {
        return team(firstId, settings, (double) level);
    }

    static TacticalContextInput context(Map<String, String> s) {
        return new TacticalContextInput(
                s.getOrDefault("mentality", "Balanced"), s.getOrDefault("tempo", "Standard"),
                s.getOrDefault("passingType", "Normal"), s.getOrDefault("defensiveLine", "Standard"),
                s.getOrDefault("pressing", "Normal"), s.getOrDefault("width", "Balanced"),
                s.getOrDefault("recovery", "Standard"),
                s.getOrDefault("inPossession", TacticalContextInput.DEFAULT_IN_POSSESSION),
                s.getOrDefault("timeWasting", TacticalContextInput.DEFAULT_TIME_WASTING),
                s.getOrDefault("dribbling", TacticalContextInput.DEFAULT_DRIBBLING),
                s.getOrDefault("widePlay", TacticalContextInput.DEFAULT_WIDE_PLAY),
                s.getOrDefault("transition", TacticalContextInput.DEFAULT_TRANSITION), List.of());
    }

    static Mentality mentality(String text) {
        return Mentality.valueOf(text.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    private static void override(CompartmentEngineConfig config, String arg) {
        String[] keyValue = arg.split("=", 2);
        String[] path = keyValue[0].split("\\.", 2);
        if (path[0].equals("share")) {
            config.getTacticCombinations().setSynergyStackShare(Double.parseDouble(keyValue[1]));
            return;
        }
        if (path[0].equals("escalation")) {
            config.getTacticCombinations().setConflictEscalation(Double.parseDouble(keyValue[1]));
            return;
        }
        if (path[0].equals("maxActive")) {
            config.getTacticCombinations().setMaxActive(Integer.parseInt(keyValue[1]));
            return;
        }
        CompartmentEngineConfig.Combination c = config.getTacticCombinations().getCombinations().get(path[0]);
        if (c == null) c = config.getTacticCombinations().getConflicts().get(path[0]);
        if (c == null) throw new IllegalArgumentException("unknown combination or conflict " + path[0]);
        double value = Double.parseDouble(keyValue[1]);
        CompartmentEngineConfig.PassingMechanic p = c.getPassingStyle();
        switch (path[1]) {
            case "suppression" -> p.setSuppression(value);
            case "aggressiveSuppression" -> p.setAggressiveSuppression(value);
            case "veryAggressiveSuppression" -> p.setVeryAggressiveSuppression(value);
            case "longPassingSuppression" -> p.setLongPassingSuppression(value);
            case "strikerGoalChance" -> p.setStrikerGoalChance(value);
            case "midfield" -> c.setMidfield(value);
            case "ownXg" -> c.setOwnXg(value);
            case "opponentXg" -> c.setOpponentXg(value);
            case "redCard" -> c.setRedCard(value);
            case "exposed" -> c.setExposedOpponentOwnXg(value);
            default -> throw new IllegalArgumentException("unknown field " + path[1]);
        }
    }
}
