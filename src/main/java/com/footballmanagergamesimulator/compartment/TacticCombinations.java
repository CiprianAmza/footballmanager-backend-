package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure rules for the tactical combinations (style bonuses) of the classic engine.
 *
 * <p>A combination is active for a team only when every one of its configured settings matches the
 * team tactic exactly (any listed value counts). Activation and size never depend on player ratings
 * or attributes; only the opponent's tactic may change a boost (COUNTER). At most {@code maxActive}
 * combinations count: the ones with the largest boost.
 *
 * <p>Effects are multipliers {@code 1 + delta}: midfield mass (applied by the aggregator),
 * own/opponent expected goals and an added red-card chance (applied by the match evaluation and the
 * sampler). PASSING_STYLE additionally carries its goal-erasing / striker-chance mechanic. A team
 * without an active combination gets {@link Profile#none}, and every consumer skips its arithmetic
 * entirely, so such matches stay bit-identical.
 */
public final class TacticCombinations {

    private final CompartmentEngineConfig.TacticCombinations rules;

    public TacticCombinations(CompartmentEngineConfig config) {
        CompartmentEngineConfig.TacticCombinations configured =
                Objects.requireNonNull(config, "config").getTacticCombinations();
        this.rules = configured == null ? new CompartmentEngineConfig.TacticCombinations() : configured;
    }

    /** Team-level tactic settings as the engine sees them. */
    public record TeamSettings(String mentality, String tempo, String passingType, String defensiveLine,
                               String pressing, String width, String recovery, String inPossession,
                               String timeWasting, String dribbling, String widePlay, String transition) {

        public static TeamSettings of(TacticalContextInput context) {
            return new TeamSettings(context.mentality(), context.tempo(), context.passingType(),
                    context.defensiveLine(), context.pressing(), context.width(), context.recovery(),
                    context.inPossession(), context.timeWasting(), context.dribbling(),
                    context.widePlay(), context.transition());
        }

        /**
         * Settings shared by every starter. An axis on which the starters disagree (only possible
         * in hand-built fixtures) becomes {@code null}, which never matches a combination.
         */
        public static TeamSettings shared(java.util.Collection<TacticalContextInput> contexts) {
            TeamSettings result = null;
            for (TacticalContextInput context : contexts) {
                TeamSettings next = of(context);
                result = result == null ? next : result.intersect(next);
            }
            return result;
        }

        private TeamSettings intersect(TeamSettings other) {
            return new TeamSettings(same(mentality, other.mentality), same(tempo, other.tempo),
                    same(passingType, other.passingType), same(defensiveLine, other.defensiveLine),
                    same(pressing, other.pressing), same(width, other.width), same(recovery, other.recovery),
                    same(inPossession, other.inPossession), same(timeWasting, other.timeWasting),
                    same(dribbling, other.dribbling), same(widePlay, other.widePlay),
                    same(transition, other.transition));
        }

        private static String same(String left, String right) {
            return left != null && left.equalsIgnoreCase(right) ? left : null;
        }

        /** Value of an axis by its configured name (case, '-', '_' and spaces are ignored). */
        public String value(String axis) {
            return switch (normalizeAxis(axis)) {
                case "mentality" -> mentality;
                case "tempo" -> tempo;
                case "passingtype", "passing" -> passingType;
                case "defensiveline", "line" -> defensiveLine;
                case "pressing" -> pressing;
                case "width" -> width;
                case "recovery" -> recovery;
                case "inpossession" -> inPossession;
                case "timewasting" -> timeWasting;
                case "dribbling" -> dribbling;
                case "wideplay" -> widePlay;
                case "transition" -> transition;
                default -> throw new IllegalArgumentException("unknown tactic combination axis: " + axis);
            };
        }
    }

    /** Result for one team in one match: synergies kept (at most maxActive) plus every conflict. */
    public record Profile(List<String> qualified,
                          List<String> active,
                          List<String> conflicts,
                          double midfieldMultiplier,
                          double ownXgMultiplier,
                          double opponentXgMultiplier,
                          double redCardChanceAdd,
                          double exposedOpponentOwnXgMultiplier,
                          CompartmentEngineConfig.PassingMechanic passingStyle,
                          String defensiveLine) {
        public Profile {
            qualified = List.copyOf(qualified);
            active = List.copyOf(active);
            conflicts = List.copyOf(conflicts);
        }

        /** Synergies only (no conflict). */
        public Profile(List<String> qualified, List<String> active, double midfieldMultiplier,
                       double ownXgMultiplier, double opponentXgMultiplier, double redCardChanceAdd,
                       double exposedOpponentOwnXgMultiplier, CompartmentEngineConfig.PassingMechanic passingStyle,
                       String defensiveLine) {
            this(qualified, active, List.of(), midfieldMultiplier, ownXgMultiplier, opponentXgMultiplier,
                    redCardChanceAdd, exposedOpponentOwnXgMultiplier, passingStyle, defensiveLine);
        }

        public static Profile none(String defensiveLine) {
            return new Profile(List.of(), List.of(), List.of(), 1.0, 1.0, 1.0, 0.0, 1.0, null, defensiveLine);
        }

        /** True when a synergy or a conflict applies; otherwise every consumer skips its arithmetic. */
        public boolean isActive() {
            return !active.isEmpty() || !conflicts.isEmpty();
        }
    }

    /** Per-side expected-goal factors for one match. */
    public record XgFactors(double home, double away) {}

    public boolean enabled() {
        return rules.isEnabled() && (!isEmpty(rules.getCombinations()) || !isEmpty(rules.getConflicts()));
    }

    private static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /** Resolve which synergies (at most maxActive, largest boost) and conflicts (all) apply to a team. */
    public Profile resolve(TeamSettings settings) {
        String line = settings == null ? null : settings.defensiveLine();
        if (settings == null || !enabled()) return Profile.none(line);
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> qualified = matching(rules.getCombinations(), settings);
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> conflicts = matching(rules.getConflicts(), settings);
        if (qualified.isEmpty() && conflicts.isEmpty()) return Profile.none(line);

        int limit = Math.max(0, rules.getMaxActive());
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> ranked = new ArrayList<>(qualified);
        // Stable sort: equal boosts keep the configured order.
        ranked.sort(Comparator.comparingDouble(
                (Map.Entry<String, CompartmentEngineConfig.Combination> e) -> boostStrength(e.getValue())).reversed());
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> kept =
                new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> applied = new ArrayList<>();
        List<String> active = new ArrayList<>();
        for (Map.Entry<String, CompartmentEngineConfig.Combination> entry : qualified) {
            if (!kept.contains(entry)) continue;
            active.add(entry.getKey());
            applied.add(entry);
        }
        List<String> qualifiedNames = qualified.stream().map(Map.Entry::getKey).toList();
        List<String> conflictNames = conflicts.stream().map(Map.Entry::getKey).toList();
        if (applied.isEmpty() && conflicts.isEmpty()) {
            return new Profile(qualifiedNames, List.of(), List.of(), 1.0, 1.0, 1.0, 0.0, 1.0, null, line);
        }
        // Synergies stack per lever by adding their deltas (not multiplying): every cost counts in
        // full, the largest helpful delta in full, every further helpful delta on the same lever
        // at synergy-stack-share. One synergy alone is exactly 1 + delta.
        List<CompartmentEngineConfig.Combination> synergies = applied.stream().map(Map.Entry::getValue).toList();
        double share = rules.getSynergyStackShare();
        double midfield = factor(stacked(synergies, CompartmentEngineConfig.Combination::getMidfield, 1, share));
        double own = factor(stacked(synergies, CompartmentEngineConfig.Combination::getOwnXg, 1, share));
        double opponent = factor(stacked(synergies, CompartmentEngineConfig.Combination::getOpponentXg, -1, share));
        double exposed = factor(stacked(synergies,
                CompartmentEngineConfig.Combination::getExposedOpponentOwnXg, 1, share));
        double red = 0.0;
        CompartmentEngineConfig.PassingMechanic passing = null;
        for (CompartmentEngineConfig.Combination c : synergies) {
            red += c.getRedCard();
            if (c.getPassingStyle() != null) passing = c.getPassingStyle();
        }
        // Conflicts compound: each delta grows with the number of active conflicts.
        double scale = 1.0 + Math.max(0.0, rules.getConflictEscalation()) * Math.max(0, conflicts.size() - 1);
        for (Map.Entry<String, CompartmentEngineConfig.Combination> entry : conflicts) {
            CompartmentEngineConfig.Combination c = entry.getValue();
            midfield *= factor(c.getMidfield() * scale);
            own *= factor(c.getOwnXg() * scale);
            opponent *= factor(c.getOpponentXg() * scale);
            red += c.getRedCard() * scale;
            exposed *= factor(c.getExposedOpponentOwnXg() * scale);
        }
        return new Profile(qualifiedNames, active, conflictNames, midfield, own, opponent, red, exposed,
                passing, line);
    }

    /**
     * Summed delta of one lever over the active synergies. {@code helpfulSign} is +1 when a positive
     * delta helps the team (midfield, own xG) and -1 when a negative one does (opponent xG).
     */
    static double stacked(List<CompartmentEngineConfig.Combination> synergies,
                          java.util.function.ToDoubleFunction<CompartmentEngineConfig.Combination> lever,
                          int helpfulSign, double share) {
        double total = 0.0;
        List<Double> helpful = new ArrayList<>();
        for (CompartmentEngineConfig.Combination c : synergies) {
            double delta = lever.applyAsDouble(c);
            if (delta * helpfulSign > 0) helpful.add(delta);
            else total += delta;
        }
        helpful.sort(Comparator.comparingDouble(delta -> -Math.abs(delta)));
        for (int i = 0; i < helpful.size(); i++) {
            total += i == 0 ? helpful.get(i) : helpful.get(i) * share;
        }
        return total;
    }

    /** {@code 1 + delta}, never below {@link #MIN_FACTOR} (a compounded penalty cannot erase a lever). */
    private static double factor(double delta) {
        return Math.max(MIN_FACTOR, 1.0 + delta);
    }

    private static final double MIN_FACTOR = 0.05;

    private static List<Map.Entry<String, CompartmentEngineConfig.Combination>> matching(
            Map<String, CompartmentEngineConfig.Combination> rules, TeamSettings settings) {
        List<Map.Entry<String, CompartmentEngineConfig.Combination>> result = new ArrayList<>();
        if (rules == null) return result;
        for (Map.Entry<String, CompartmentEngineConfig.Combination> entry : rules.entrySet()) {
            if (qualifies(entry.getValue(), settings)) result.add(entry);
        }
        return result;
    }

    /**
     * The boost used to rank combinations for {@code maxActive}: the sum of the deltas that help the
     * team (more midfield, more own xG, fewer opponent xG, the conditional COUNTER extra) and, for
     * PASSING_STYLE, its base share of erased opponent goals plus its expected extra striker goals.
     */
    public static double boostStrength(CompartmentEngineConfig.Combination c) {
        double boost = Math.max(0.0, c.getMidfield()) + Math.max(0.0, c.getOwnXg())
                + Math.max(0.0, -c.getOpponentXg()) + Math.max(0.0, c.getExposedOpponentOwnXg());
        CompartmentEngineConfig.PassingMechanic passing = c.getPassingStyle();
        if (passing != null) {
            boost += Math.max(0.0, passing.getSuppression())
                    + Math.max(0.0, passing.getStrikerGoalChance()) * passing.meanOpportunities();
        }
        return boost;
    }

    static boolean qualifies(CompartmentEngineConfig.Combination combination, TeamSettings settings) {
        if (combination.getSettings() == null || combination.getSettings().isEmpty()) return false;
        for (Map.Entry<String, List<String>> required : combination.getSettings().entrySet()) {
            String actual = settings.value(required.getKey());
            if (actual == null) return false;
            String normalized = actual.trim();
            if (required.getValue() == null
                    || required.getValue().stream().noneMatch(value -> value != null
                    && value.trim().equalsIgnoreCase(normalized))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expected-goal factors for a match, or {@code null} when neither side has an active combination
     * (callers then keep their original numbers untouched).
     */
    public static XgFactors xgFactors(TeamCompartmentAggregator.TeamAggregationResult home,
                                      TeamCompartmentAggregator.TeamAggregationResult away) {
        Profile homeProfile = home.combinations();
        Profile awayProfile = away.combinations();
        boolean homeActive = homeProfile != null && homeProfile.isActive();
        boolean awayActive = awayProfile != null && awayProfile.isActive();
        if (!homeActive && !awayActive) return null;
        double homeFactor = 1.0;
        double awayFactor = 1.0;
        if (homeActive) {
            homeFactor *= homeProfile.ownXgMultiplier();
            if (exposed(away)) homeFactor *= homeProfile.exposedOpponentOwnXgMultiplier();
            awayFactor *= homeProfile.opponentXgMultiplier();
        }
        if (awayActive) {
            awayFactor *= awayProfile.ownXgMultiplier();
            if (exposed(home)) awayFactor *= awayProfile.exposedOpponentOwnXgMultiplier();
            homeFactor *= awayProfile.opponentXgMultiplier();
        }
        return new XgFactors(homeFactor, awayFactor);
    }

    /** An opponent that commits forward: Attacking / Very Attacking mentality or a High line. */
    static boolean exposed(TeamCompartmentAggregator.TeamAggregationResult team) {
        if (team.mentality() == Mentality.ATTACKING || team.mentality() == Mentality.VERY_ATTACKING) return true;
        Profile profile = team.combinations();
        return profile != null && profile.defensiveLine() != null
                && "High".equalsIgnoreCase(profile.defensiveLine().trim());
    }

    private static String normalizeAxis(String axis) {
        return axis == null ? "" : axis.replaceAll("[\\s_-]", "").toLowerCase(Locale.ROOT);
    }
}
