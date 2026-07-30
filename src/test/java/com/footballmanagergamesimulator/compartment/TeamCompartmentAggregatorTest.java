package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.LineupSlot;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerBreakdown;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PlayerCompartmentInput;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.TeamAggregationResult;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.WideChannel;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.MentalityRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TeamCompartmentAggregatorTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();
    private final TeamCompartmentAggregator aggregator = new TeamCompartmentAggregator(config);

    @Test
    void allMentalitiesApplyExactContractAndConserveMass() {
        List<PlayerCompartmentInput> lineup = List.of(
                player(1, PlayerPosition.GK, 1, 10, 5, 70, 0.0),
                player(2, PlayerPosition.DC, 1, 15, 10, 60, 0.6),
                player(3, PlayerPosition.DM, 1, 20, 30, 90, 0.7),
                player(4, PlayerPosition.MC, 1, 25, 40, 35, 0.5),
                player(5, PlayerPosition.AMC, 1, 40, 45, 20, 0.4),
                player(6, PlayerPosition.ST, 1, 55, 15, 10, 0.8));

        double rawAttack = 165.0;
        double rawMidfield = 145.0;
        double rawDefense = 285.0;

        assertMentality(lineup, Mentality.VERY_ATTACKING, rawAttack, rawMidfield, rawDefense,
                0.90, 0.10, Compartment.DEFENSE, Compartment.ATTACK, 0.20, 3.10);
        assertMentality(lineup, Mentality.ATTACKING, rawAttack, rawMidfield, rawDefense,
                0.70, 0.30, Compartment.DEFENSE, Compartment.ATTACK, 0.08, 2.89);
        assertMentality(lineup, Mentality.BALANCED, rawAttack, rawMidfield, rawDefense,
                0.50, 0.50, null, null, 0.00, 2.70);
        assertMentality(lineup, Mentality.DEFENSIVE, rawAttack, rawMidfield, rawDefense,
                0.25, 0.75, Compartment.ATTACK, Compartment.DEFENSE, 0.08, 2.43);
        assertMentality(lineup, Mentality.VERY_DEFENSIVE, rawAttack, rawMidfield, rawDefense,
                0.10, 0.90, Compartment.ATTACK, Compartment.DEFENSE, 0.20, 2.11);
    }

    @Test
    void wideRedistributionUsesTypedZonesAndPreservesTotals() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.AML, 1, 40, 20, 10, 0.6),
                player(3, PlayerPosition.DR, 1, 20, 30, 25, 0.7),
                player(4, PlayerPosition.ST, 1, 10, 10, 5, 0.8)));

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
        assertThat(new ArrayList<>(result.channelBreakdown().keySet())).containsExactly(
                WideChannel.CENTRAL,
                WideChannel.LEFT_HALF_SPACE,
                WideChannel.RIGHT_HALF_SPACE,
                WideChannel.LEFT_WIDE,
                WideChannel.RIGHT_WIDE);
    }

    @Test
    void aggregationIsOrderIndependentAndResultSnapshotIsDeterministic() {
        List<PlayerCompartmentInput> lineup = List.of(
                player(6, PlayerPosition.ST, 1, 30, 10, 5, 0.9),
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(4, PlayerPosition.MC, 1, 20, 35, 20, 0.6),
                player(3, PlayerPosition.DC, 1, 10, 10, 45, 0.7),
                player(5, PlayerPosition.AMR, 1, 25, 20, 10, 0.8),
                player(2, PlayerPosition.DM, 1, 15, 25, 50, 0.5));

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
        assertThat(snapshot(second)).isEqualTo(snapshot(first));
    }

    @Test
    void coverageAndProtectionBoundsClampNormalizedInputs() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.DC, 1, 10, 10, 90, 1.0),
                player(3, PlayerPosition.DM, 1, 15, 20, 180, 0.6),
                player(4, PlayerPosition.DM, 2, 15, 20, 140, 0.6),
                player(5, PlayerPosition.ST, 1, 25, 10, 5, 0.7)));

        assertThat(result.coverage().bestDm().raw()).isEqualTo(1.0);
        assertThat(result.coverage().secondDm().raw()).isEqualTo(1.0);
        assertThat(result.coverage().cappedCbRecoveryPace()).isEqualTo(0.50);
        assertThat(result.coverage().totalCoverage()).isCloseTo(2.05, within(1e-12));
        assertThat(result.attackProtection()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void coverageExposureAndNonlinearProtectionFollowPureContract() {
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.DC, 1, 10, 10, 40, 0.9),
                player(3, PlayerPosition.DM, 1, 15, 20, 80, 0.6),
                player(4, PlayerPosition.DM, 2, 15, 20, 60, 0.4),
                player(5, PlayerPosition.ST, 1, 30, 10, 5, 0.8,
                        List.of(PlayerTrait.REFUSES_DEFENSIVE_WORK), ForwardInstruction.TRACK_BACK),
                player(6, PlayerPosition.AML, 1, 25, 15, 10, 0.7,
                        List.of(), ForwardInstruction.STAY_FORWARD),
                player(7, PlayerPosition.MR, 1, 20, 15, 15, 0.5,
                        List.of(), ForwardInstruction.TRACK_BACK)));

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
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.ST, 1, 30, 10, 5, 0.8,
                        List.of(PlayerTrait.REFUSES_DEFENSIVE_WORK), ForwardInstruction.STAY_FORWARD),
                player(3, PlayerPosition.DC, 1, 10, 10, 45, 0.5)));

        PlayerBreakdown striker = breakdown(result, 2);
        assertThat(striker.engagement()).isEqualTo(0.08);
        assertThat(striker.attackMultiplier()).isEqualTo(10.0);
        assertThat(striker.adjustedAttack()).isCloseTo(striker.baseAttack() * 10.0, within(1e-12));
        assertThat(striker.traits()).containsExactly(PlayerTrait.REFUSES_DEFENSIVE_WORK);
    }

    @Test
    void shooterContributesTwentyPercentAttackZeroMidfieldAndTenPercentDefense() {
        PlayerCompartmentInput shooter = new PlayerCompartmentInput(
                2L, new LineupSlot(PlayerPosition.ML, 1),
                rating("ML", 80, 75, 60, 0.7),
                List.of(PlayerTrait.SHOOTER), ForwardInstruction.DEFAULT,
                100.0, 20, 20);
        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED, "Normal", List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0), shooter));

        PlayerBreakdown breakdown = breakdown(result, 2L);
        assertThat(breakdown.adjustedAttack()).isEqualTo(20.0);
        assertThat(breakdown.midfield()).isZero();
        assertThat(breakdown.defense()).isEqualTo(10.0);
        assertThat(result.shooter()).isEqualTo(new TeamCompartmentAggregator.ShooterProfile(2L, 20, 20));
        assertThat(result.pressing()).isEqualTo("Normal");
    }

    @Test
    void passingStyleActivatesOnlyForExactTacticAndMidfieldAverageAtLeast19() {
        List<PlayerCompartmentInput> lineup = List.of(
                passingPlayer(1, PlayerPosition.GK, 1, 10, 10, 10),
                passingPlayer(2, PlayerPosition.DM, 1, 19, 19, 20),
                passingPlayer(3, PlayerPosition.MC, 1, 19, 19, 19),
                passingPlayer(4, PlayerPosition.AMC, 1, 19, 19, 19),
                passingPlayer(5, PlayerPosition.ML, 1, 20, 19, 20),
                passingPlayer(6, PlayerPosition.AMR, 1, 20, 20, 20),
                passingPlayer(7, PlayerPosition.ST, 1, 10, 10, 20));

        TeamAggregationResult active = aggregator.aggregate(Mentality.BALANCED,
                "Short", "Aggressive", "Instantly", lineup);
        TeamAggregationResult wrongRecovery = aggregator.aggregate(Mentality.BALANCED,
                "Short", "Aggressive", "Standard", lineup);

        assertThat(active.passingStyle().active()).isTrue();
        assertThat(active.passingStyle().midfieldAverage()).isEqualTo(19.3);
        assertThat(active.passingStyle().midfielders()).hasSize(5);
        assertThat(active.passingStyle().strikers()).extracting(TeamCompartmentAggregator.PassingStriker::playerId)
                .containsExactly(7L);
        assertThat(wrongRecovery.passingStyle().active()).isFalse();
    }

    @Test
    void passingStyleDoesNotActivateForExactTacticWhenMidfieldAverageIsBelow19() {
        List<PlayerCompartmentInput> lineup = List.of(
                passingPlayer(1, PlayerPosition.GK, 1, 10, 10, 10),
                passingPlayer(2, PlayerPosition.DM, 1, 19, 19, 20),
                passingPlayer(3, PlayerPosition.MC, 1, 19, 19, 20),
                passingPlayer(4, PlayerPosition.AMC, 1, 19, 19, 20),
                passingPlayer(5, PlayerPosition.ML, 1, 19, 19, 20),
                passingPlayer(6, PlayerPosition.AMR, 1, 18, 19, 20),
                passingPlayer(7, PlayerPosition.ST, 1, 10, 10, 20));

        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED,
                "Short", "Aggressive", "Instantly", lineup);

        assertThat(result.passingStyle().midfieldAverage()).isEqualTo(18.9);
        assertThat(result.passingStyle().midfielders()).hasSize(5);
        assertThat(result.passingStyle().strikers()).extracting(TeamCompartmentAggregator.PassingStriker::playerId)
                .containsExactly(7L);
        assertThat(result.passingStyle().active()).isFalse();
    }

    @Test
    void passingStyleActivatesAtAttributeAverage19EvenWhenEveryMidfielderHasOnlyPace19() {
        List<PlayerCompartmentInput> lineup = List.of(
                passingPlayer(1, PlayerPosition.GK, 1, 10, 10, 10),
                passingPlayer(2, PlayerPosition.DM, 1, 19, 19, 19),
                passingPlayer(3, PlayerPosition.MC, 1, 19, 19, 19),
                passingPlayer(4, PlayerPosition.MC, 2, 19, 19, 19),
                passingPlayer(5, PlayerPosition.AMC, 1, 19, 19, 19),
                passingPlayer(6, PlayerPosition.ML, 1, 19, 19, 19),
                passingPlayer(7, PlayerPosition.MR, 1, 19, 19, 19),
                passingPlayer(8, PlayerPosition.ST, 1, 10, 10, 19));

        TeamAggregationResult result = aggregator.aggregate(Mentality.BALANCED,
                "Short", "Aggressive", "Instantly", lineup);

        assertThat(result.passingStyle().midfieldAverage()).isEqualTo(19.0);
        assertThat(result.passingStyle().midfielders()).hasSize(6)
                .allSatisfy(player -> assertThat(player.pace()).isEqualTo(19));
        assertThat(result.passingStyle().active()).isTrue();
    }

    private PlayerCompartmentInput passingPlayer(long id, PlayerPosition position, int occurrence,
                                                  int ballRecovery, int tackling, int pace) {
        return new PlayerCompartmentInput(id, new LineupSlot(position, occurrence),
                rating(position.code(), 30, 30, 30, 0.5), List.of(), ForwardInstruction.DEFAULT,
                100.0, 10, 10, 20, pace, ballRecovery, tackling);
    }

    @Test
    void invalidMentalityRulesFailFast() {
        assertInvalidMentality(rule -> rule.setMidfieldToAttack(Double.NaN), "midfieldToAttack");
        assertInvalidMentality(rule -> rule.setMidfieldToAttack(1.20), "midfieldToAttack");
        assertInvalidMentality(rule -> rule.setMidfieldToDefense(-0.10), "midfieldToDefense");
        assertInvalidMentality(rule -> rule.setMidfieldToDefense(0.40), "midfield shares must sum to 1.0");
        assertInvalidMentality(rule -> rule.setTransferShare(1.10), "transferShare");
        assertInvalidMentality(rule -> rule.setOpenness(0.0), "openness");
        assertInvalidMentality(rule -> rule.setTransferFrom(null), "transfer compartments");
        assertInvalidMentality(rule -> {
            rule.setTransferFrom(Compartment.MIDFIELD);
            rule.setTransferTo(Compartment.ATTACK);
            rule.setTransferShare(0.20);
        }, "only Attack<->Defense transfer is supported");
        assertInvalidMentality(rule -> {
            rule.setTransferFrom(null);
            rule.setTransferTo(null);
            rule.setTransferShare(0.20);
        }, "positive transferShare requires transfer compartments");
    }

    @Test
    void invalidLineupsAreRejected() {
        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineup must not be empty");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.DC, 1, 10, 10, 40, 0.5),
                player(2, PlayerPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one goalkeeper");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(1, PlayerPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate player id");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.DC, 2, 10, 10, 40, 0.5),
                player(3, PlayerPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing lineup slot");

        assertThatThrownBy(() -> aggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.DC, 1, 10, 10, 40, 0.5),
                player(3, PlayerPosition.DC, 1, 12, 8, 35, 0.5),
                player(4, PlayerPosition.ST, 1, 20, 10, 5, 0.5))))
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

    private void assertInvalidMentality(java.util.function.Consumer<MentalityRule> mutator, String expectedMessage) {
        CompartmentEngineConfig invalidConfig = CompartmentConfigFixture.load();
        MentalityRule invalidRule = copyRule(invalidConfig.getMentalities().get(Mentality.BALANCED));
        mutator.accept(invalidRule);
        invalidConfig.getMentalities().put(Mentality.BALANCED, invalidRule);
        TeamCompartmentAggregator invalidAggregator = new TeamCompartmentAggregator(invalidConfig);

        assertThatThrownBy(() -> invalidAggregator.aggregate(Mentality.BALANCED, List.of(
                player(1, PlayerPosition.GK, 1, 5, 5, 40, 0.0),
                player(2, PlayerPosition.ST, 1, 20, 10, 5, 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    private PlayerCompartmentInput player(long id, PlayerPosition position, int occurrence,
                                          double attack, double midfield, double defense, double pace) {
        return player(id, position, occurrence, attack, midfield, defense, pace, List.of(), ForwardInstruction.DEFAULT);
    }

    private PlayerCompartmentInput player(long id, PlayerPosition position, int occurrence,
                                          double attack, double midfield, double defense, double pace,
                                          List<PlayerTrait> traits, ForwardInstruction instruction) {
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

    private MentalityRule copyRule(MentalityRule source) {
        MentalityRule copy = new MentalityRule();
        copy.setMidfieldToAttack(source.getMidfieldToAttack());
        copy.setMidfieldToDefense(source.getMidfieldToDefense());
        copy.setTransferFrom(source.getTransferFrom());
        copy.setTransferTo(source.getTransferTo());
        copy.setTransferShare(source.getTransferShare());
        copy.setOpenness(source.getOpenness());
        return copy;
    }

    private String snapshot(TeamAggregationResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append(result.mentality()).append('|')
                .append(result.openness()).append('|')
                .append(result.attack()).append('|')
                .append(result.attackProtection()).append('|');
        result.channelBreakdown().forEach((channel, breakdown) ->
                builder.append(channel).append(':').append(breakdown.attack()).append(':')
                        .append(breakdown.protection()).append('|'));
        for (PlayerBreakdown player : result.players()) {
            builder.append(player.playerId()).append('@').append(player.slot().position()).append('#')
                    .append(player.slot().occurrence()).append(':')
                    .append(player.traits()).append(':')
                    .append(player.finalAttackContribution()).append(':')
                    .append(player.finalProtectionContribution()).append('|');
        }
        return builder.toString();
    }
}
