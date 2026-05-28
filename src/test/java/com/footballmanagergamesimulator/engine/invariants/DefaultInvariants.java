package com.footballmanagergamesimulator.engine.invariants;

import java.util.List;

/**
 * Canonical invariant catalog used by {@code EngineInvariantsCatalogIT} and
 * Faza 4's auto-tuner. Each invariant captures a real-world football
 * expectation expressed as a win-rate bound.
 *
 * <p><b>Editing this catalog is the primary way to specify what "good engine
 * behavior" means.</b> Add an invariant here → re-run the suite → adjust
 * config until it passes. The auto-tuner takes the catalog as its goal
 * function.
 *
 * <p>Power values are abstract "squad rating" units. A typical strong team
 * sits around 8000-10000, a relegation candidate around 3000-5000.
 *
 * <p>Iteration counts trade off statistical confidence against runtime:
 * <ul>
 *   <li>2000 iters is enough for ±2% precision on a 90% win rate (good for
 *       dominance assertions)</li>
 *   <li>500 iters gives ±5% (acceptable for tight range assertions)</li>
 * </ul>
 */
public final class DefaultInvariants {

    private DefaultInvariants() {}

    /** The standard catalog — load with {@code DefaultInvariants.catalog()}. */
    public static List<EngineInvariant> catalog() {
        return List.of(
                powerOverwhelmingDominance(),
                powerStrongDominance(),
                powerTwoxRange(),
                powerEqualBalanced(),
                powerUpsetCapped(),
                moraleHighVsLowAtEqualPower(),
                moralePowerDominatesAtLargeGap(),
                homeAdvantageEqualTeams(),
                homeAdvantageHelpsUnderdog(),
                luckFloorEqualMatch()
        );
    }

    // ---------------- Power-only invariants ----------------

    /** "A team with 6× the power should win at least 80% of matches" — user spec. */
    public static EngineInvariant powerOverwhelmingDominance() {
        return EngineInvariant.atLeast(
                "power-6x-dominance",
                "6× power gap (12000 vs 2000) → strong team wins ≥80%",
                MatchSetup.of("Powerhouse FC", 12000),
                MatchSetup.of("Tiny Town", 2000),
                2000, 0.80);
    }

    /** "3× power gap is decisive enough that the favourite should clear 65%." */
    public static EngineInvariant powerStrongDominance() {
        return EngineInvariant.atLeast(
                "power-3x-dominance",
                "3× power gap (9000 vs 3000) → strong team wins ≥65%",
                MatchSetup.of("Big Side", 9000),
                MatchSetup.of("Cup Minnow", 3000),
                2000, 0.65);
    }

    /** "2× gap: favourite still wins more than half but the underdog gets games." */
    public static EngineInvariant powerTwoxRange() {
        return EngineInvariant.inRange(
                "power-2x-range",
                "2× power gap (10000 vs 5000) → strong wins 55-80%",
                MatchSetup.of("Top Half", 10000),
                MatchSetup.of("Mid Table", 5000),
                2000, 0.55, 0.80);
    }

    /** "Two equal teams should each win roughly a third of the time." */
    public static EngineInvariant powerEqualBalanced() {
        return EngineInvariant.inRange(
                "power-equal-balanced",
                "Equal power (7000 vs 7000) → A wins 25-45% (rest is draws + B wins)",
                MatchSetup.of("Equal A", 7000),
                MatchSetup.of("Equal B", 7000),
                2000, 0.25, 0.45);
    }

    /** "Upset cap": a clearly inferior team must not steal it more than rarely. */
    public static EngineInvariant powerUpsetCapped() {
        return EngineInvariant.atMost(
                "power-upset-cap",
                "3× weaker team (3000 vs 9000) → A wins ≤15% (rare upsets only)",
                MatchSetup.of("Underdog", 3000),
                MatchSetup.of("Favourite", 9000),
                2000, 0.15);
    }

    // ---------------- Morale invariants ----------------

    /** "Morale alone (no power difference) gives the high-morale side a clear edge." */
    public static EngineInvariant moraleHighVsLowAtEqualPower() {
        return EngineInvariant.inRange(
                "morale-high-vs-low",
                "Equal power 7000, A morale=100 vs B morale=20 → A wins 45-65%",
                MatchSetup.of("Confident A", 7000, 100),
                MatchSetup.of("Demoralized B", 7000, 20),
                2000, 0.45, 0.65);
    }

    /** "Power gap dominates morale": low-morale powerhouse still beats high-morale minnow." */
    public static EngineInvariant moralePowerDominatesAtLargeGap() {
        return EngineInvariant.atLeast(
                "morale-power-dominates",
                "Power 12000 morale=20 vs power 3000 morale=100 → strong side wins ≥70%",
                MatchSetup.of("Slumping Giant", 12000, 20),
                MatchSetup.of("Spirited Minnow", 3000, 100),
                2000, 0.70);
    }

    // ---------------- Home advantage invariants ----------------

    /** "Home advantage gives a measurable but bounded edge between equal teams." */
    public static EngineInvariant homeAdvantageEqualTeams() {
        return EngineInvariant.inRange(
                "home-advantage-equal",
                "Equal power 7000, A at home vs B away → A wins 38-55% (home edge ~5-15pp)",
                MatchSetup.of("Equal A", 7000).atHome(),
                MatchSetup.of("Equal B", 7000),
                2000, 0.38, 0.55);
    }

    /** "Home advantage can close a small power gap (1.17×)." */
    public static EngineInvariant homeAdvantageHelpsUnderdog() {
        return EngineInvariant.inRange(
                "home-advantage-closes-gap",
                "Power 6000 at home vs power 7000 away → A wins 25-50%",
                MatchSetup.of("Smaller Home", 6000).atHome(),
                MatchSetup.of("Bigger Away", 7000),
                2000, 0.25, 0.50);
    }

    // ---------------- Luck invariants ----------------

    /** "Pure 50/50 match: outcome must be balanced over 2000 iterations." */
    public static EngineInvariant luckFloorEqualMatch() {
        return EngineInvariant.inRange(
                "luck-floor-equal",
                "Identical setups: A wins 28-42% (no systemic bias)",
                MatchSetup.of("Twin A", 7000),
                MatchSetup.of("Twin B", 7000),
                2000, 0.28, 0.42);
    }
}
