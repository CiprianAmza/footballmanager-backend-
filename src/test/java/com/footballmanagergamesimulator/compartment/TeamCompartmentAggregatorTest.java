package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.LineupPosition;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.LineupSlot;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerBreakdown;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerCompartmentInput;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.TeamAggregationResult;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.WideChannel;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TeamCompartmentAggregatorTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();
    private final TeamCompartmentAggregator aggregator = new TeamCompartmentAggregator(config);

    @Test
    void allMentalitiesApplyExactContractAndConserveMass() {
        List<PlayerCompartmentInput> lineup = List.of(
                player(1, LineupPosition.GK, 1, 10, 5, 70, 0.0),
                player(2, LineupPosition.DC, 1, 15, 10, 60, 0.6),
                player(3, LineupPosition.DM, 1, 20, 30, 90, 0.7),
                player(4, LineupPosition.MC, 1, 25, 40, 35, 0.5),
                player(5, LineupPosition.AMC, 1, 40, 45, 20, 0.4),
                player(6, LineupPosition.ST, 1, 55, 15, 10, 0.8));

        double rawAttack = 165.0;
        double rawMidfield = 145.0;
        double rawDefense = 285.0;

        assertMentality(lineup, Mentality.VERY_ATTACKING, rawAttack, rawMidfield, rawDefense,
                0.90, 0.10, Compartment.DEFENSE, Compartment.ATTACK, 0.20, 1.15);
        assertMentality(lineup, Mentality.ATTACKING, rawAttack, rawMidfield, rawDefense,
                0.70, 0.30, Compartment.DEFENSE, Compartment.ATTACK, 0.08, 1.07);
        assertMentality(lineup, Mentality.BALANCED, rawAttack, rawMidfield, rawDefense,
                0.50, 0.50, null, null, 0.00, 1.00);
        assertMentality(lineup, Mentality.DEFENSIVE, rawAttack, rawMidfield, rawDefense,
                0.25, 0.75, Compartment.ATTACK, Compartment.DEFENSE, 0.08, 0.90);
        assertMentality(lineup, Mentality.VERY_DEFENSIVE, rawAttack, rawMidfield, rawDefense,
                0.10, 0.90, Compartment.ATTACK, Compartment.DEFENSE, 0.20, 0.78);
    }

    @Test
    void wideRedistributionUsesTypedZonesAndPreservesTotals() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.AML, 1, 40, 20, 10, 0.6),
                player(3, LineupPosition.DR, 1, 20, 30, 25, 0.7),
                player(4, LineupPosition.ST, 1, 10, 10, 5, 0.8)));

        PlayerBreakdown aml = breakdown(result, 2);
        PlayerBreakdown dr = breakdown(result, 3);

        assertThat(aml.channel()).isEqualTo(WideChannel.LEFT_HALF_SPACE);
        assertThat(aml.channelShare()).isEqualTo(0.20);
        assertThat(aml.channelAttackContribution()).isCloseTo(aml.finalAttackContribution() * 0.20, within(1e-12));
        assertThat(aml.channelProtectionContribution()).isCloseTo(aml.finalProtectionContribution() * 0.20, within(1e-12));

        assertThat(dr.channel()).isEqualTo(WideChannel.RIGHT_WIDE);
        assertThat(dr.channelShare()).isEqualTo(0.20);
        assertThat(dr.channelAttackContribution()).isCloseTo(dr.finalAttackContribution() * 0.20, within(1e-12));
        assertThat(dr.channelProtectionContribution()).isCloseTo(dr.finalProtectionContribution() * 0.20, within(1e-12));

        double channelAttack = result.channelBreakdown().values().stream().mapToDouble(value -> value.attack()).sum();
        double channelProtection = result.channelBreakdown().values().stream().mapToDouble(value -> value.protection()).sum();
        assertThat(channelAttack).isCloseTo(result.attack(), within(1e-12));
        assertThat(channelProtection).isCloseTo(result.exposure().protectionBeforeExposure(), within(1e-12));
    }

    @Test
    void aggregationIsOrderIndependentAndBreakdownOrderIsDeterministic() {
        List<PlayerCompartmentInput> lineup = List.of(
                player(6, LineupPosition.ST, 1, 30, 10, 5, 0.9),
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(4, LineupPosition.MC, 1, 20, 35, 20, 0.6),
                player(3, LineupPosition.DC, 1, 10, 10, 45, 0.7),
                player(5, LineupPosition.AMR, 1, 25, 20, 10, 0.8),
                player(2, LineupPosition.DM, 1, 15, 25, 50, 0.5));

        TeamAggregationResult first = aggregator.aggregate(Mentality.ATTACKING, lineup);
        TeamAggregationResult second = aggregator.aggregate(Mentality.ATTACKING, List.of(
                lineup.get(2), lineup.get(5), lineup.get(0), lineup.get(4), lineup.get(1), lineup.get(3)));

        assertThat(second.attack()).isCloseTo(first.attack(), within(1e-12));
        assertThat(second.attackProtection()).isCloseTo(first.attackProtection(), within(1e-12));
        assertThat(second.exposure().exposure()).isCloseTo(first.exposure().exposure(), within(1e-12));
        assertThat(second.coverage().totalCoverage()).isCloseTo(first.coverage().totalCoverage(), within(1e-12));
        assertThat(first.players().stream().map(PlayerBreakdown::playerId).toList())
                .containsExactly(1L, 3L, 2L, 4L, 5L, 6L);
        assertThat(second.players().stream().map(PlayerBreakdown::playerId).toList())
                .containsExactlyElementsOf(first.players().stream().map(PlayerBreakdown::playerId).toList());
    }

    @Test
    void coverageAndProtectionBoundsClampNormalizedInputs() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.DC, 1, 10, 10, 90, 1.0),
                player(3, LineupPosition.DM, 1, 15, 20, 180, 0.6),
                player(4, LineupPosition.DM, 2, 15, 20, 140, 0.6),
                player(5, LineupPosition.ST, 1, 25, 10, 5, 0.7)));

        assertThat(result.coverage().bestDm().raw()).isEqualTo(1.0);
        assertThat(result.coverage().secondDm().raw()).isEqualTo(1.0);
        assertThat(result.coverage().cappedCbRecoveryPace()).isEqualTo(0.50);
        assertThat(result.coverage().totalCoverage()).isCloseTo(2.05, within(1e-12));
        assertThat(result.attackProtection()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void coverageExposureAndNonlinearProtectionFollowPureContract() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.DC, 1, 10, 10, 40, 0.9),
                player(3, LineupPosition.DM, 1, 15, 20, 80, 0.6),
                player(4, LineupPosition.DM, 2, 15, 20, 60, 0.4),
                player(5, LineupPosition.ST, 1, 30, 10, 5, 0.8,
                        Set.of(PlayerTrait.REFUSES_DEFENSIVE_WORK), ForwardInstruction.TRACK_BACK),
                player(6, LineupPosition.AML, 1, 25, 15, 10, 0.7,
                        Set.of(), ForwardInstruction.STAY_FORWARD),
                player(7, LineupPosition.MR, 1, 20, 15, 15, 0.5,
                        Set.of(), ForwardInstruction.TRACK_BACK)));

        assertThat(result.coverage().bestDm().raw()).isEqualTo(0.80);
        assertThat(result.coverage().secondDm().raw()).isEqualTo(0.60);
        assertThat(result.coverage().totalCoverage()).isCloseTo(1.63, within(1e-12));
        assertThat(result.exposure().exposure()).isCloseTo(1.39, within(1e-12));

        double expectedResidual = Math.max(0.0, 1.39 - 0.65 * 1.63);
        double expectedMultiplier = Math.exp(-0.55 * Math.pow(expectedResidual, 1.70));
        assertThat(result.exposure().residualRisk()).isCloseTo(expectedResidual, within(1e-12));
        assertThat(result.exposure().protectionMultiplier()).isCloseTo(expectedMultiplier, within(1e-12));
        assertThat(result.attackProtection()).isCloseTo(
                result.exposure().protectionBeforeExposure() * expectedMultiplier, within(1e-12));
    }

    @Test
    void refusesDefensiveWorkTakesPrecedenceOverStayForwardAndTrackBack() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.ST, 1, 30, 10, 5, 0.8,
                        Set.of(PlayerTrait.REFUSES_DEFENSIVE_WORK), ForwardInstruction.STAY_FORWARD),
                player(3, LineupPosition.DC, 1, 10, 10, 45, 0.5)));

        PlayerBreakdown striker = breakdown(result, 2);
        assertThat(striker.engagement()).isEqualTo(0.08);
        assertThat(striker.attackMultiplier()).isEqualTo(1.15);
        assertThat(striker.adjustedAttack()).isCloseTo(striker.baseAttack() * 1.15, within(1e-12));
    }

    @Test
    void invalidLineupsAreRejected() {
        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineup must not be empty");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.DC, 1, 10, 10, 40, 0.5),
                player(2, LineupPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one goalkeeper");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(1, LineupPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate player id");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.DC, 2, 10, 10, 40, 0.5),
                player(3, LineupPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing lineup slot");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, LineupPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, LineupPosition.DC, 1, 10, 10, 40, 0.5),
                player(3, LineupPosition.DC, 1, 12, 8, 35, 0.5),
                player(4, LineupPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate lineup slot");
    }

    private void assertMentality(List<PlayerCompartmentInput> lineup,
                                 Mentality mentality,
                                 double rawAttack,
                                 double rawMidfield,
                                 double rawDefense,
                                 double midfieldToAttack,
                                 double midfieldToDefense,
                                 Compartment transferFrom,
                                 Compartment transferTo,
                                 double transferShare,
                                 double openness) {
        TeamAggregationResult result = aggregator.aggregate(mentality, lineup);

        double expectedAttackAfterSplit = rawAttack + rawMidfield * midfieldToAttack;
        double expectedDefenseAfterSplit = rawDefense + rawMidfield * midfieldToDefense;
        double movedMass = transferFrom == null ? 0.0 : switch (transferFrom) {
            case DEFENSE -> expectedDefenseAfterSplit * transferShare;
            case ATTACK -> expectedAttackAfterSplit * transferShare;
            default -> throw new IllegalStateException("unsupported transfer compartment");
        };
        double expectedAttackAfterTransfer = transferFrom == Compartment.DEFENSE
                ? expectedAttackAfterSplit + movedMass
                : expectedAttackAfterSplit - movedMass;
        double expectedDefenseAfterTransfer = transferFrom == Compartment.ATTACK
                ? expectedDefenseAfterSplit + movedMass
                : expectedDefenseAfterSplit - movedMass;

        assertThat(result.openness()).isEqualTo(openness);
        assertThat(result.rawTotals().attack()).isEqualTo(rawAttack);
        assertThat(result.rawTotals().midfield()).isEqualTo(rawMidfield);
        assertThat(result.rawTotals().defense()).isEqualTo(rawDefense);
        assertThat(result.mentalityRedistribution().midfieldToAttackShare()).isEqualTo(midfieldToAttack);
        assertThat(result.mentalityRedistribution().midfieldToDefenseShare()).isEqualTo(midfieldToDefense);
        assertThat(result.mentalityRedistribution().transferFrom()).isEqualTo(transferFrom);
        assertThat(result.mentalityRedistribution().transferTo()).isEqualTo(transferTo);
        assertThat(result.mentalityRedistribution().transferShare()).isEqualTo(transferShare);
        assertThat(result.mentalityRedistribution().attackAfterMidfieldSplit())
                .isCloseTo(expectedAttackAfterSplit, within(1e-12));
        assertThat(result.mentalityRedistribution().defenseAfterMidfieldSplit())
                .isCloseTo(expectedDefenseAfterSplit, within(1e-12));
        assertThat(result.mentalityRedistribution().movedMass()).isCloseTo(movedMass, within(1e-12));
        assertThat(result.attack()).isCloseTo(expectedAttackAfterTransfer, within(1e-12));
        assertThat(result.exposure().protectionBeforeExposure())
                .isCloseTo(expectedDefenseAfterTransfer, within(1e-12));
        assertThat(result.attackProtection()).isCloseTo(expectedDefenseAfterTransfer, within(1e-12));
        assertThat(result.mentalityRedistribution().totalBeforeTransfer())
                .isCloseTo(result.mentalityRedistribution().totalAfterTransfer(), within(1e-12));
    }

    private PlayerBreakdown breakdown(TeamAggregationResult result, long playerId) {
        return result.players().stream()
                .filter(player -> player.playerId() == playerId)
                .findFirst()
                .orElseThrow();
    }

    private PlayerCompartmentInput player(long id, LineupPosition position, int occurrence,
                                          double attack, double midfield, double defense, double pace) {
        return player(id, position, occurrence, attack, midfield, defense, pace, Set.of(), ForwardInstruction.DEFAULT);
    }

    private PlayerCompartmentInput player(long id, LineupPosition position, int occurrence,
                                          double attack, double midfield, double defense, double pace,
                                          Set<PlayerTrait> traits, ForwardInstruction instruction) {
        return new PlayerCompartmentInput(
                id,
                new LineupSlot(position, occurrence),
                rating(position.code(), attack, midfield, defense, pace),
                traits,
                instruction);
    }

    private ContextualPlayerRating rating(String position, double attack, double midfield, double defense, double pace) {
        Map<Compartment, ContextualPlayerRating.CompartmentBreakdown> compartments = new EnumMap<>(Compartment.class);
        compartments.put(Compartment.ATTACK, breakdown(Compartment.ATTACK, attack, List.of()));
        compartments.put(Compartment.MIDFIELD, breakdown(Compartment.MIDFIELD, midfield, List.of()));
        compartments.put(Compartment.DEFENSE, breakdown(Compartment.DEFENSE, defense, List.of(
                new ContextualPlayerRating.AttributeContribution(
                        PlayerAttribute.PACE,
                        (int) Math.round(1 + pace * 19),
                        pace,
                        0.04,
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0))));
        return new ContextualPlayerRating(position, "Synthetic Role", Duty.SUPPORT, compartments);
    }

    private ContextualPlayerRating.CompartmentBreakdown breakdown(Compartment compartment,
                                                                  double finalScore,
                                                                  List<ContextualPlayerRating.AttributeContribution> attributes) {
        return new ContextualPlayerRating.CompartmentBreakdown(
                compartment,
                finalScore,
                finalScore,
                1.0,
                finalScore,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                finalScore,
                attributes);
    }
}
