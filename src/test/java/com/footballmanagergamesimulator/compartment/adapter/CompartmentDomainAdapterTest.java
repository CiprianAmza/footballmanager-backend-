package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRatingInput;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompartmentDomainAdapterTest {

    private final CompartmentEngineConfig config = AdapterTestFixture.loadConfig();
    private final CompartmentDomainAdapter adapter = new CompartmentDomainAdapter(config);

    private DomainPlayerSnapshot snapshot(String usedPosition, String role, String duty,
                                          Double familiarity, Double suitability) {
        return DomainSnapshotFactory.fromDomain(
                AdapterTestFixture.human(1L, usedPosition, 100.0, 70.0),
                AdapterTestFixture.skillsAll(15),
                usedPosition, role, duty, familiarity, suitability);
    }

    // ---- Position coverage --------------------------------------------------

    @Test
    void everyCanonicalPositionRatesWithoutErrorAndUsesItsConfiguredMultiplier() {
        for (String position : config.getPositions().keySet()) {
            ContextualPlayerRating rating = adapter.rate(snapshot(position, "Central Midfielder", "Support", 1.0, 50.0));
            assertThat(rating.compartments()).containsOnlyKeys(Compartment.values());
            for (Compartment compartment : Compartment.values()) {
                var breakdown = rating.compartments().get(compartment);
                assertThat(breakdown.finalScore()).as("%s %s finite", position, compartment).isFinite();
                assertThat(breakdown.positionMultiplier())
                        .as("position multiplier %s %s", position, compartment)
                        .isEqualTo(config.getPositions().get(position).forCompartment(compartment));
            }
        }
    }

    @Test
    void unknownOrLegacyPositionFallsBackToDefaultMultiplier() {
        var breakdown = adapter.rate(snapshot("SWEEPER", "Central Midfielder", "Support", 1.0, 50.0))
                .compartments().get(Compartment.MIDFIELD);
        assertThat(breakdown.positionMultiplier())
                .isEqualTo(config.getRating().getDefaultPositionMultiplier());
    }

    @Test
    void blankUsedPositionFallsBackToNaturalThenToUnknownSentinel() {
        // used blank -> natural used; both blank -> UNKNOWN sentinel, default multiplier.
        DomainPlayerSnapshot bothBlank = new DomainPlayerSnapshot(
                1L, "  ", "", null, "Support",
                PlayerAttributeMapping.rawAttributeMap(AdapterTestFixture.skillsAll(15)),
                100.0, 70.0, 1.0, 50.0);
        PlayerRatingInput input = adapter.toRatingInput(bothBlank);
        assertThat(input.position()).isEqualTo(CompartmentDomainAdapter.UNKNOWN_POSITION);

        DomainPlayerSnapshot naturalOnly = new DomainPlayerSnapshot(
                1L, null, "DC", null, "Defend",
                PlayerAttributeMapping.rawAttributeMap(AdapterTestFixture.skillsAll(15)),
                100.0, 70.0, 1.0, 50.0);
        assertThat(adapter.toRatingInput(naturalOnly).position()).isEqualTo("DC");
    }

    // ---- Role coverage ------------------------------------------------------

    @Test
    void everyCanonicalRoleMapsToItsConfiguredMultiplier() {
        for (PlayerRole role : config.getRoles().keySet()) {
            var breakdown = adapter.rate(snapshot("MC", role.displayName(), "Support", 1.0, 50.0))
                    .compartments().get(Compartment.ATTACK);
            assertThat(breakdown.roleMultiplier())
                    .as("role %s", role)
                    .isEqualTo(config.getRoles().get(role).forCompartment(Compartment.ATTACK));
        }
    }

    @Test
    void unknownOrBlankRoleFallsBackToDefaultRoleMultiplier() {
        double def = config.getRating().getDefaultRoleMultiplier();
        assertThat(adapter.rate(snapshot("MC", "Totally Made Up Role", "Support", 1.0, 50.0))
                .compartments().get(Compartment.ATTACK).roleMultiplier()).isEqualTo(def);
        assertThat(adapter.rate(snapshot("MC", "   ", "Support", 1.0, 50.0))
                .compartments().get(Compartment.ATTACK).roleMultiplier()).isEqualTo(def);
        assertThat(adapter.rate(snapshot("MC", null, "Support", 1.0, 50.0))
                .compartments().get(Compartment.ATTACK).roleMultiplier()).isEqualTo(def);
    }

    // ---- Duty coverage ------------------------------------------------------

    @Test
    void everyCanonicalDutyLabelMaps() {
        assertThat(CompartmentDomainAdapter.mapDuty("Attack")).isEqualTo(Duty.ATTACK);
        assertThat(CompartmentDomainAdapter.mapDuty("Support")).isEqualTo(Duty.SUPPORT);
        assertThat(CompartmentDomainAdapter.mapDuty("Defend")).isEqualTo(Duty.DEFEND);
        // Case-insensitive and whitespace tolerant.
        assertThat(CompartmentDomainAdapter.mapDuty("  aTTack ")).isEqualTo(Duty.ATTACK);
    }

    @Test
    void unknownOrNullDutyDefaultsToSupport() {
        assertThat(CompartmentDomainAdapter.mapDuty(null)).isEqualTo(CompartmentDomainAdapter.DEFAULT_DUTY);
        assertThat(CompartmentDomainAdapter.mapDuty("")).isEqualTo(Duty.SUPPORT);
        assertThat(CompartmentDomainAdapter.mapDuty("Libero")).isEqualTo(Duty.SUPPORT);
        assertThat(adapter.toRatingInput(snapshot("MC", "Central Midfielder", "Libero", 1.0, 50.0)).duty())
                .isEqualTo(Duty.SUPPORT);
    }

    // ---- Attribute mapping / clamping / missing -----------------------------

    @Test
    void attributesAreClampedIntoTheConfiguredDomain() {
        Map<PlayerAttribute, Integer> attrs = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute a : PlayerAttribute.values()) {
            attrs.put(a, 10);
        }
        attrs.put(PlayerAttribute.FINISHING, 0);   // legacy below-domain value
        attrs.put(PlayerAttribute.PASSING, 99);    // legacy above-domain value
        DomainPlayerSnapshot s = new DomainPlayerSnapshot(1L, "ST", "ST", "Poacher", "Attack",
                attrs, 100.0, 70.0, 1.0, 50.0);

        PlayerRatingInput input = adapter.toRatingInput(s);
        assertThat(input.attributes().get(PlayerAttribute.FINISHING)).isEqualTo(config.getRating().getAttributeMin());
        assertThat(input.attributes().get(PlayerAttribute.PASSING)).isEqualTo(config.getRating().getAttributeMax());
    }

    @Test
    void missingAttributeDefaultsToConfiguredMinimum() {
        Map<PlayerAttribute, Integer> attrs = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute a : PlayerAttribute.values()) {
            attrs.put(a, 12);
        }
        attrs.remove(PlayerAttribute.FINISHING);
        DomainPlayerSnapshot s = new DomainPlayerSnapshot(1L, "ST", "ST", "Poacher", "Attack",
                attrs, 100.0, 70.0, 1.0, 50.0);

        assertThat(adapter.toRatingInput(s).attributes().get(PlayerAttribute.FINISHING))
                .isEqualTo(config.getRating().getAttributeMin());
        // Full contract still present so the pure calculator never throws.
        assertThat(adapter.toRatingInput(s).attributes()).hasSize(PlayerAttribute.values().length);
    }

    // ---- Context coefficients: K stays 0 (roadmap item 3 excluded) ----------

    @Test
    void contextCoefficientsAreAlwaysEmptySoNoTacticBoostLeaksIn() {
        PlayerRatingInput input = adapter.toRatingInput(snapshot("MC", "Advanced Playmaker", "Support", 1.0, 50.0));
        assertThat(input.contextCoefficients()).isEmpty();

        var breakdown = adapter.rate(snapshot("MC", "Advanced Playmaker", "Support", 1.0, 50.0))
                .compartments().get(Compartment.MIDFIELD);
        assertThat(breakdown.attributes()).allSatisfy(a -> {
            assertThat(a.requestedContextCoefficient()).isEqualTo(0.0);
            assertThat(a.appliedContextCoefficient()).isEqualTo(0.0);
            assertThat(a.contextFactor()).isEqualTo(1.0);
        });
        assertThat(breakdown.contextRatio()).isEqualTo(1.0);
    }

    // ---- Missing familiarity / suitability defaults -------------------------

    @Test
    void missingFamiliarityAndSuitabilityResolveToDocumentedNeutralDefaults() {
        var breakdown = adapter.rate(snapshot("DC", "Central Defender", "Defend", null, null))
                .compartments().get(Compartment.DEFENSE);
        assertThat(breakdown.familiarityFactor()).isEqualTo(CompartmentDomainAdapter.DEFAULT_POSITION_FAMILIARITY);
        // suitability 50 -> roleFit = 0.85 + 0.30 * 0.5 = 1.0 (neutral).
        assertThat(breakdown.roleFit()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void suppliedFamiliarityAndSuitabilityFlowThroughUnchanged() {
        PlayerRatingInput input = adapter.toRatingInput(snapshot("DC", "Central Defender", "Defend", 0.5, 100.0));
        assertThat(input.positionFamiliarity()).isEqualTo(0.5);
        assertThat(input.roleSuitability()).isEqualTo(100.0);
        assertThat(input.fitness()).isEqualTo(100.0);
        assertThat(input.morale()).isEqualTo(70.0);
    }

    // ---- Determinism and contract consistency -------------------------------

    @Test
    void sameSnapshotProducesIdenticalBreakdown() {
        DomainPlayerSnapshot s = snapshot("AMC", "Advanced Playmaker", "Attack", 0.9, 80.0);
        assertThat(adapter.rate(s)).isEqualTo(adapter.rate(s));
    }

    @Test
    void adapterDelegatesToTheExistingPureCalculatorContract() {
        DomainPlayerSnapshot s = snapshot("ST", "Poacher", "Attack", 1.0, 100.0);
        var calculator = new com.footballmanagergamesimulator.compartment.ContextualPlayerRatingCalculator(config);
        assertThat(adapter.rate(s)).isEqualTo(calculator.rate(adapter.toRatingInput(s)));
    }

    // ---- Flag stays OFF -----------------------------------------------------

    @Test
    void compartmentEngineFlagIsOffByDefault() {
        assertThat(config.isEnabled()).isFalse();
    }
}
