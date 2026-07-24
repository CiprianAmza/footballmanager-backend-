package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.MentalityRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure team-level compartment aggregation for final Attack and Attack Protection.
 *
 * <p>This boundary consumes immutable, explainable per-player contextual ratings and produces one
 * deterministic team breakdown. It does not know about runtime services, JPA entities, RNG or
 * match execution.
 */
public final class TeamCompartmentAggregator {

    private static final double WIDE_REDISTRIBUTION_SHARE = 0.20;
    private static final double SHARE_TOLERANCE = 1e-9;
    private static final Comparator<PlayerCompartmentInput> INPUT_ORDER =
            Comparator.comparing((PlayerCompartmentInput input) -> input.slot().position())
                    .thenComparingInt(input -> input.slot().occurrence())
                    .thenComparingLong(PlayerCompartmentInput::playerId);
    private static final Comparator<PlayerTrait> TRAIT_ORDER = Comparator.comparingInt(Enum::ordinal);

    private final CompartmentEngineConfig config;
    private final DefensiveExposureFormula exposureFormula;

    public TeamCompartmentAggregator(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.exposureFormula = new DefensiveExposureFormula(config);
    }

    public TeamAggregationResult aggregate(Mentality mentality, Collection<PlayerCompartmentInput> lineup) {
        Objects.requireNonNull(mentality, "mentality");
        List<PlayerCompartmentInput> ordered = normalizeAndValidate(lineup);
        MentalityRule rule = Objects.requireNonNull(config.getMentalities().get(mentality),
                "Missing mentality rule for " + mentality);
        validateMentalityRule(rule);

        List<PlayerAggregation> players = ordered.stream()
                .map(this::toPlayerAggregation)
                .toList();

        double rawAttack = players.stream().mapToDouble(PlayerAggregation::adjustedAttack).sum();
        double rawMidfield = players.stream().mapToDouble(PlayerAggregation::midfield).sum();
        double rawDefense = players.stream().mapToDouble(PlayerAggregation::defense).sum();

        double splitAttack = rawAttack + rawMidfield * rule.getMidfieldToAttack();
        double splitDefense = rawDefense + rawMidfield * rule.getMidfieldToDefense();
        double totalBeforeTransfer = rawAttack + rawMidfield + rawDefense;

        TransferDirection direction = TransferDirection.from(rule);
        double movedMass = direction.sourceTotal(splitAttack, splitDefense) * rule.getTransferShare();

        List<PlayerBreakdown> playerBreakdowns = players.stream()
                .map(player -> toBreakdown(player, rule, direction, splitAttack, splitDefense, movedMass))
                .toList();

        double attackBeforeExposure = playerBreakdowns.stream().mapToDouble(PlayerBreakdown::finalAttackContribution).sum();
        double protectionBeforeExposure = playerBreakdowns.stream().mapToDouble(PlayerBreakdown::finalProtectionContribution).sum();
        double totalAfterTransfer = attackBeforeExposure + protectionBeforeExposure;

        Map<WideChannel, ChannelBreakdown> channelBreakdown = buildChannelBreakdown(playerBreakdowns);
        CoverageBreakdown coverage = buildCoverageBreakdown(playerBreakdowns);
        DefensiveExposureFormula.ExposureResult exposure = exposureFormula.apply(
                protectionBeforeExposure,
                playerBreakdowns.stream().map(PlayerBreakdown::zoneEngagement).mapToDouble(ZoneEngagementBreakdown::weightedExposure).sum(),
                coverage.totalCoverage());

        return new TeamAggregationResult(
                mentality,
                rule.getOpenness(),
                new RawTotals(rawAttack, rawMidfield, rawDefense),
                new MentalityRedistribution(
                        rule.getMidfieldToAttack(),
                        rule.getMidfieldToDefense(),
                        direction.from(),
                        direction.to(),
                        rule.getTransferShare(),
                        rawAttack,
                        rawMidfield,
                        rawDefense,
                        splitAttack,
                        splitDefense,
                        movedMass,
                        attackBeforeExposure,
                        protectionBeforeExposure,
                        totalBeforeTransfer,
                        totalAfterTransfer),
                channelBreakdown,
                coverage,
                new ExposureBreakdown(
                        coverage.totalCoverage(),
                        exposure.exposure(),
                        exposure.residualRisk(),
                        exposure.protectionMultiplier(),
                        protectionBeforeExposure,
                        exposure.finalAttackProtection()),
                playerBreakdowns,
                attackBeforeExposure,
                exposure.finalAttackProtection());
    }

    private List<PlayerCompartmentInput> normalizeAndValidate(Collection<PlayerCompartmentInput> lineup) {
        if (lineup == null || lineup.isEmpty()) {
            throw new IllegalArgumentException("lineup must not be empty");
        }
        List<PlayerCompartmentInput> ordered = lineup.stream()
                .map(input -> Objects.requireNonNull(input, "lineup contains null player"))
                .sorted(INPUT_ORDER)
                .toList();

        Set<Long> seenPlayers = new HashSet<>();
        Set<LineupSlot> seenSlots = new HashSet<>();
        Map<LineupPosition, List<Integer>> occurrences = new EnumMap<>(LineupPosition.class);
        int goalkeepers = 0;

        for (PlayerCompartmentInput input : ordered) {
            if (!seenPlayers.add(input.playerId())) {
                throw new IllegalArgumentException("duplicate player id: " + input.playerId());
            }
            if (!seenSlots.add(input.slot())) {
                throw new IllegalArgumentException("duplicate lineup slot: " + input.slot());
            }
            if (!input.slot().position().code().equals(input.rating().position())) {
                throw new IllegalArgumentException("rating position does not match slot for player " + input.playerId());
            }
            if (input.slot().position() == LineupPosition.GK) {
                goalkeepers++;
            }
            occurrences.computeIfAbsent(input.slot().position(), ignored -> new ArrayList<>())
                    .add(input.slot().occurrence());
        }

        if (goalkeepers != 1) {
            throw new IllegalArgumentException("lineup must contain exactly one goalkeeper");
        }

        for (Map.Entry<LineupPosition, List<Integer>> entry : occurrences.entrySet()) {
            List<Integer> values = entry.getValue().stream().sorted().toList();
            for (int i = 0; i < values.size(); i++) {
                int expected = i + 1;
                if (values.get(i) != expected) {
                    throw new IllegalArgumentException("missing lineup slot for " + entry.getKey().code()
                            + ": expected occurrence " + expected + " but found " + values.get(i));
                }
            }
        }

        return ordered;
    }

    private PlayerAggregation toPlayerAggregation(PlayerCompartmentInput input) {
        var attack = compartment(input.rating(), Compartment.ATTACK);
        var midfield = compartment(input.rating(), Compartment.MIDFIELD);
        var defense = compartment(input.rating(), Compartment.DEFENSE);
        var behavior = exposureFormula.resolveWorkBehavior(new LinkedHashSet<>(input.traits()), input.instruction(), false);
        double adjustedAttack = attack.finalScore() * behavior.attackMultiplier();
        double normalizedDefense = clamp01(defense.finalScore() / config.getRating().getScoreScale());
        double cbRecoveryPace = input.slot().position().isCenterBack() ? paceNormalization(input.rating()) : 0.0;
        ZoneEngagementBreakdown zoneEngagement = new ZoneEngagementBreakdown(
                input.slot().position().exposureZone(),
                behavior.engagement(),
                zoneWeight(input.slot().position().exposureZone()),
                zoneWeight(input.slot().position().exposureZone()) * (1.0 - behavior.engagement()));

        return new PlayerAggregation(
                input,
                attack.finalScore(),
                adjustedAttack,
                midfield.finalScore(),
                defense.finalScore(),
                normalizedDefense,
                cbRecoveryPace,
                behavior,
                zoneEngagement);
    }

    private PlayerBreakdown toBreakdown(PlayerAggregation player, MentalityRule rule, TransferDirection direction,
                                        double splitAttackTotal, double splitDefenseTotal, double movedMass) {
        double midfieldToAttack = player.midfield() * rule.getMidfieldToAttack();
        double midfieldToDefense = player.midfield() * rule.getMidfieldToDefense();
        double attackBeforeTransfer = player.adjustedAttack() + midfieldToAttack;
        double protectionBeforeTransfer = player.defense() + midfieldToDefense;
        double sourceShare = switch (direction) {
            case DEFENSE_TO_ATTACK -> safeShare(protectionBeforeTransfer, splitDefenseTotal);
            case ATTACK_TO_DEFENSE -> safeShare(attackBeforeTransfer, splitAttackTotal);
            case NONE -> 0.0;
        };
        double transferMass = movedMass * sourceShare;
        double finalAttack = attackBeforeTransfer + direction.attackDelta(transferMass);
        double finalProtection = protectionBeforeTransfer + direction.defenseDelta(transferMass);

        double channelShare = player.input().slot().position().wideRedistributionShare();
        double channelAttack = finalAttack * channelShare;
        double channelProtection = finalProtection * channelShare;

        return new PlayerBreakdown(
                player.input().playerId(),
                player.input().slot(),
                player.input().traits(),
                player.input().instruction(),
                player.attackBase(),
                player.adjustedAttack(),
                player.midfield(),
                player.defense(),
                player.behavior().engagement(),
                player.behavior().attackMultiplier(),
                player.normalizedDefense(),
                player.cbRecoveryPace(),
                midfieldToAttack,
                midfieldToDefense,
                transferMass,
                attackBeforeTransfer,
                protectionBeforeTransfer,
                finalAttack,
                finalProtection,
                channelShare,
                player.input().slot().position().channel(),
                channelAttack,
                channelProtection,
                player.zoneEngagement());
    }

    private Map<WideChannel, ChannelBreakdown> buildChannelBreakdown(List<PlayerBreakdown> players) {
        Map<WideChannel, ChannelBreakdown> result = new LinkedHashMap<>();
        for (WideChannel channel : WideChannel.values()) {
            double attack = players.stream().mapToDouble(player -> attackForChannel(player, channel)).sum();
            double protection = players.stream().mapToDouble(player -> protectionForChannel(player, channel)).sum();
            result.put(channel, new ChannelBreakdown(channel, attack, protection));
        }
        return immutableOrderedMap(result);
    }

    private static double attackForChannel(PlayerBreakdown player, WideChannel channel) {
        if (channel == WideChannel.CENTRAL) {
            return player.finalAttackContribution() - player.channelAttackContribution();
        }
        return player.channel() == channel ? player.channelAttackContribution() : 0.0;
    }

    private static double protectionForChannel(PlayerBreakdown player, WideChannel channel) {
        if (channel == WideChannel.CENTRAL) {
            return player.finalProtectionContribution() - player.channelProtectionContribution();
        }
        return player.channel() == channel ? player.channelProtectionContribution() : 0.0;
    }

    private CoverageBreakdown buildCoverageBreakdown(List<PlayerBreakdown> players) {
        List<PlayerBreakdown> defensiveMidfielders = players.stream()
                .filter(player -> player.slot().position().isDefensiveMidfielder())
                .sorted(Comparator.comparingDouble(PlayerBreakdown::normalizedDefenseContribution).reversed()
                        .thenComparing(player -> player.slot().position())
                        .thenComparingInt(player -> player.slot().occurrence())
                        .thenComparingLong(PlayerBreakdown::playerId))
                .toList();
        PlayerBreakdown bestDm = defensiveMidfielders.isEmpty() ? null : defensiveMidfielders.get(0);
        PlayerBreakdown secondDm = defensiveMidfielders.size() > 1 ? defensiveMidfielders.get(1) : null;
        double bestDmValue = bestDm == null ? 0.0 : bestDm.normalizedDefenseContribution();
        double secondDmValue = secondDm == null ? 0.0 : secondDm.normalizedDefenseContribution();
        double bestCbRecovery = players.stream()
                .filter(player -> player.slot().position().isCenterBack())
                .mapToDouble(PlayerBreakdown::cbRecoveryPaceNormalized)
                .max()
                .orElse(0.0);
        double cbCapped = Math.min(bestCbRecovery, config.getExposure().getCbRecoveryPaceCap());
        double total = exposureFormula.coverage(bestDmValue, secondDmValue, bestCbRecovery);
        return new CoverageBreakdown(
                bestDm == null ? null : new CoverageContributor(bestDm.playerId(), bestDm.slot(), bestDmValue, bestDmValue),
                secondDm == null ? null : new CoverageContributor(secondDm.playerId(), secondDm.slot(),
                        secondDmValue, config.getExposure().getSecondDmWeight() * secondDmValue),
                bestCbRecovery,
                cbCapped,
                total);
    }

    private ContextualPlayerRating.CompartmentBreakdown compartment(ContextualPlayerRating rating, Compartment compartment) {
        return Objects.requireNonNull(rating.compartments().get(compartment),
                "Missing compartment " + compartment + " in contextual rating");
    }

    private double paceNormalization(ContextualPlayerRating rating) {
        return rating.compartments().values().stream()
                .flatMap(compartment -> compartment.attributes().stream())
                .filter(attribute -> attribute.attribute() == PlayerAttribute.PACE)
                .mapToDouble(ContextualPlayerRating.AttributeContribution::normalizedValue)
                .findFirst()
                .orElse(0.0);
    }

    private double zoneWeight(ExposureZone zone) {
        Double weight = config.getExposure().getZoneWeights().get(zone.configKey());
        if (weight == null) {
            throw new IllegalStateException("Missing exposure zone weight for " + zone.configKey());
        }
        return weight;
    }

    private void validateMentalityRule(MentalityRule rule) {
        requireFiniteUnit(rule.getMidfieldToAttack(), "midfieldToAttack");
        requireFiniteUnit(rule.getMidfieldToDefense(), "midfieldToDefense");
        double split = rule.getMidfieldToAttack() + rule.getMidfieldToDefense();
        if (Math.abs(split - 1.0) > SHARE_TOLERANCE) {
            throw new IllegalArgumentException("midfield shares must sum to 1.0");
        }
        requireFiniteUnit(rule.getTransferShare(), "transferShare");
        if (!Double.isFinite(rule.getOpenness()) || rule.getOpenness() <= 0.0) {
            throw new IllegalArgumentException("openness must be finite and positive");
        }

        boolean fromMissing = rule.getTransferFrom() == null;
        boolean toMissing = rule.getTransferTo() == null;
        if (fromMissing != toMissing) {
            throw new IllegalArgumentException("transfer compartments must be both present or both absent");
        }
        if (!fromMissing && !TransferDirection.isValidPair(rule.getTransferFrom(), rule.getTransferTo())) {
            throw new IllegalArgumentException("only Attack<->Defense transfer is supported");
        }
        if (rule.getTransferShare() > 0.0 && fromMissing) {
            throw new IllegalArgumentException("positive transferShare requires transfer compartments");
        }
    }

    private static void requireFiniteUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double safeShare(double value, double total) {
        return total <= 0.0 ? 0.0 : value / total;
    }

    private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static List<PlayerTrait> immutableOrderedTraits(Collection<PlayerTrait> traits) {
        if (traits == null || traits.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<PlayerTrait> ordered = traits.stream()
                .filter(Objects::nonNull)
                .sorted(TRAIT_ORDER)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    public enum ExposureZone {
        CENTRAL("CENTRAL"),
        HALF_SPACE("HALF_SPACE"),
        WIDE("WIDE");

        private final String configKey;

        ExposureZone(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    public enum WideChannel {
        CENTRAL,
        LEFT_HALF_SPACE,
        RIGHT_HALF_SPACE,
        LEFT_WIDE,
        RIGHT_WIDE
    }

    public enum LineupPosition {
        GK("GK", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, false, false),
        DC("DC", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, false, true),
        DL("DL", ExposureZone.WIDE, WideChannel.LEFT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        DR("DR", ExposureZone.WIDE, WideChannel.RIGHT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        WBL("WBL", ExposureZone.WIDE, WideChannel.LEFT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        WBR("WBR", ExposureZone.WIDE, WideChannel.RIGHT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        DM("DM", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, true, false),
        MC("MC", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, false, false),
        ML("ML", ExposureZone.WIDE, WideChannel.LEFT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        MR("MR", ExposureZone.WIDE, WideChannel.RIGHT_WIDE, WIDE_REDISTRIBUTION_SHARE, false, false),
        AMC("AMC", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, false, false),
        AML("AML", ExposureZone.HALF_SPACE, WideChannel.LEFT_HALF_SPACE, WIDE_REDISTRIBUTION_SHARE, false, false),
        AMR("AMR", ExposureZone.HALF_SPACE, WideChannel.RIGHT_HALF_SPACE, WIDE_REDISTRIBUTION_SHARE, false, false),
        ST("ST", ExposureZone.CENTRAL, WideChannel.CENTRAL, 0.0, false, false);

        private final String code;
        private final ExposureZone exposureZone;
        private final WideChannel channel;
        private final double wideRedistributionShare;
        private final boolean defensiveMidfielder;
        private final boolean centerBack;

        LineupPosition(String code, ExposureZone exposureZone, WideChannel channel,
                       double wideRedistributionShare, boolean defensiveMidfielder, boolean centerBack) {
            this.code = code;
            this.exposureZone = exposureZone;
            this.channel = channel;
            this.wideRedistributionShare = wideRedistributionShare;
            this.defensiveMidfielder = defensiveMidfielder;
            this.centerBack = centerBack;
        }

        public String code() {
            return code;
        }

        public ExposureZone exposureZone() {
            return exposureZone;
        }

        public WideChannel channel() {
            return channel;
        }

        public double wideRedistributionShare() {
            return wideRedistributionShare;
        }

        public boolean isDefensiveMidfielder() {
            return defensiveMidfielder;
        }

        public boolean isCenterBack() {
            return centerBack;
        }
    }

    public record LineupSlot(LineupPosition position, int occurrence) {
        public LineupSlot {
            Objects.requireNonNull(position, "position");
            if (occurrence <= 0) {
                throw new IllegalArgumentException("occurrence must be >= 1");
            }
        }
    }

    public record PlayerCompartmentInput(long playerId,
                                         LineupSlot slot,
                                         ContextualPlayerRating rating,
                                         List<PlayerTrait> traits,
                                         ForwardInstruction instruction) {
        public PlayerCompartmentInput {
            if (playerId <= 0) {
                throw new IllegalArgumentException("playerId must be positive");
            }
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(rating, "rating");
            traits = immutableOrderedTraits(traits);
            instruction = instruction == null ? ForwardInstruction.DEFAULT : instruction;
        }
    }

    public record RawTotals(double attack, double midfield, double defense) {}

    public record MentalityRedistribution(
            double midfieldToAttackShare,
            double midfieldToDefenseShare,
            Compartment transferFrom,
            Compartment transferTo,
            double transferShare,
            double attackBeforeSplit,
            double midfieldBeforeSplit,
            double defenseBeforeSplit,
            double attackAfterMidfieldSplit,
            double defenseAfterMidfieldSplit,
            double movedMass,
            double attackAfterTransfer,
            double protectionBeforeExposure,
            double totalBeforeTransfer,
            double totalAfterTransfer) {}

    public record ChannelBreakdown(WideChannel channel, double attack, double protection) {}

    public record CoverageContributor(long playerId, LineupSlot slot, double raw, double weighted) {}

    public record CoverageBreakdown(
            CoverageContributor bestDm,
            CoverageContributor secondDm,
            double bestCbRecoveryPace,
            double cappedCbRecoveryPace,
            double totalCoverage) {}

    public record ExposureBreakdown(
            double coverage,
            double exposure,
            double residualRisk,
            double protectionMultiplier,
            double protectionBeforeExposure,
            double finalAttackProtection) {}

    public record ZoneEngagementBreakdown(
            ExposureZone zone,
            double engagement,
            double zoneWeight,
            double weightedExposure) {}

    public record PlayerBreakdown(
            long playerId,
            LineupSlot slot,
            List<PlayerTrait> traits,
            ForwardInstruction instruction,
            double baseAttack,
            double adjustedAttack,
            double midfield,
            double defense,
            double engagement,
            double attackMultiplier,
            double normalizedDefenseContribution,
            double cbRecoveryPaceNormalized,
            double midfieldToAttack,
            double midfieldToProtection,
            double transferredMass,
            double attackBeforeTransfer,
            double protectionBeforeTransfer,
            double finalAttackContribution,
            double finalProtectionContribution,
            double channelShare,
            WideChannel channel,
            double channelAttackContribution,
            double channelProtectionContribution,
            ZoneEngagementBreakdown zoneEngagement) {
        public PlayerBreakdown {
            traits = immutableOrderedTraits(traits);
        }
    }

    public record TeamAggregationResult(
            Mentality mentality,
            double openness,
            RawTotals rawTotals,
            MentalityRedistribution mentalityRedistribution,
            Map<WideChannel, ChannelBreakdown> channelBreakdown,
            CoverageBreakdown coverage,
            ExposureBreakdown exposure,
            List<PlayerBreakdown> players,
            double attack,
            double attackProtection) {
        public TeamAggregationResult {
            channelBreakdown = immutableOrderedMap(channelBreakdown);
            players = List.copyOf(players);
        }
    }

    private enum TransferDirection {
        DEFENSE_TO_ATTACK(Compartment.DEFENSE, Compartment.ATTACK),
        ATTACK_TO_DEFENSE(Compartment.ATTACK, Compartment.DEFENSE),
        NONE(Compartment.ATTACK, Compartment.DEFENSE);

        private final Compartment from;
        private final Compartment to;

        TransferDirection(Compartment from, Compartment to) {
            this.from = from;
            this.to = to;
        }

        static TransferDirection from(MentalityRule rule) {
            if (rule.getTransferShare() == 0.0) {
                return NONE;
            }
            if (isValidPair(rule.getTransferFrom(), rule.getTransferTo())
                    && rule.getTransferFrom() == Compartment.DEFENSE) {
                return DEFENSE_TO_ATTACK;
            }
            if (isValidPair(rule.getTransferFrom(), rule.getTransferTo())
                    && rule.getTransferFrom() == Compartment.ATTACK) {
                return ATTACK_TO_DEFENSE;
            }
            throw new IllegalArgumentException("only Attack<->Defense transfer is supported");
        }

        static boolean isValidPair(Compartment from, Compartment to) {
            return (from == Compartment.DEFENSE && to == Compartment.ATTACK)
                    || (from == Compartment.ATTACK && to == Compartment.DEFENSE);
        }

        Compartment from() {
            return this == NONE ? null : from;
        }

        Compartment to() {
            return this == NONE ? null : to;
        }

        double sourceTotal(double attackTotal, double defenseTotal) {
            return switch (this) {
                case DEFENSE_TO_ATTACK -> defenseTotal;
                case ATTACK_TO_DEFENSE -> attackTotal;
                case NONE -> 0.0;
            };
        }

        double attackDelta(double movedMass) {
            return switch (this) {
                case DEFENSE_TO_ATTACK -> movedMass;
                case ATTACK_TO_DEFENSE -> -movedMass;
                case NONE -> 0.0;
            };
        }

        double defenseDelta(double movedMass) {
            return switch (this) {
                case DEFENSE_TO_ATTACK -> -movedMass;
                case ATTACK_TO_DEFENSE -> movedMass;
                case NONE -> 0.0;
            };
        }
    }

    private record PlayerAggregation(
            PlayerCompartmentInput input,
            double attackBase,
            double adjustedAttack,
            double midfield,
            double defense,
            double normalizedDefense,
            double cbRecoveryPace,
            DefensiveExposureFormula.WorkBehavior behavior,
            ZoneEngagementBreakdown zoneEngagement) {}
}
