package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent configuration pair used by a sensitivity run. */
public final class CanonicalScoringWeightSet {
    private final CompartmentEngineConfig compartment;
    private final MatchEngineConfig match;

    private CanonicalScoringWeightSet(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        this.compartment = compartment;
        this.match = match;
    }

    public static CanonicalScoringWeightSet baseline(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        return new CanonicalScoringWeightSet(copyCompartment(compartment), copyMatch(match));
    }

    public CanonicalScoringWeightSet override(CanonicalScoringWeightCatalog catalog,
                                              CanonicalScoringWeightOverride override) {
        if (catalog == null || override == null) throw new NullPointerException("catalog/override");
        CanonicalScoringWeightKey key = catalog.require(override.key());
        if (key.type() == CanonicalScoringWeightKey.Type.INTEGER && override.value() != Math.rint(override.value())) {
            throw new IllegalArgumentException("integer weight requires an integral value: " + override.key());
        }
        if (override.key().endsWith("attribute-min") || override.key().endsWith("attribute-max")) {
            if (override.value() < 1 || override.value() > 20) throw new IllegalArgumentException("attribute range is [1,20]");
        }
        if (override.key().endsWith("goal-cap") && (override.value() < 0 || override.value() > 50)) {
            throw new IllegalArgumentException("goal cap is out of range");
        }
        if (override.key().contains("quantile") && (override.value() <= 0 || override.value() >= 1)) {
            throw new IllegalArgumentException("quantile must be in (0,1)");
        }
        if ((override.key().endsWith("suitability-scale") || override.key().endsWith("scale-multiplier"))
                && override.value() <= 0) throw new IllegalArgumentException("scale must be positive");
        if (key.type() == CanonicalScoringWeightKey.Type.CONTINUOUS && override.value() == 0.0
                && key.baselineValue() instanceof Number n && n.doubleValue() > 0.0) {
            throw new IllegalArgumentException("positive continuous weight cannot be zero: " + override.key());
        }
        CanonicalScoringWeightSet copy = new CanonicalScoringWeightSet(copyCompartment(compartment), copyMatch(match));
        return copy.applyOverride(override);
    }

    private CanonicalScoringWeightSet applyOverride(CanonicalScoringWeightOverride override) {
        if (override == null) throw new NullPointerException("override");
        String key = override.key();
        if (key.startsWith("compartment.rating.")) {
            switch (key.substring("compartment.rating.".length())) {
                case "attribute-min" -> compartment.getRating().setAttributeMin((int) override.value());
                case "attribute-max" -> compartment.getRating().setAttributeMax((int) override.value());
                case "score-scale" -> compartment.getRating().setScoreScale(override.value());
                case "context-factor-min" -> {
                    compartment.getRating().setContextFactorMin(override.value());
                    compartment.getContextRules().entrySet().stream()
                            .filter(entry -> entry.getKey().equals("instruction:close down less"))
                            .findFirst().ifPresent(entry -> entry.getValue()
                                    .put(com.footballmanagergamesimulator.compartment.PlayerAttribute.CONCENTRATION, -1.0));
                }
                case "context-factor-max" -> compartment.getRating().setContextFactorMax(override.value());
                case "total-context-min" -> {
                    compartment.getRating().setTotalContextMin(override.value());
                    compartment.getContextRules().entrySet().stream()
                            .filter(entry -> entry.getKey().equals("instruction:close down less"))
                            .findFirst().ifPresent(entry -> entry.getValue()
                                    .put(com.footballmanagergamesimulator.compartment.PlayerAttribute.CONCENTRATION, -1.0));
                }
                case "total-context-max" -> compartment.getRating().setTotalContextMax(override.value());
                case "context-coefficient-min" -> compartment.getRating().setContextCoefficientMin(override.value());
                case "context-coefficient-max" -> compartment.getRating().setContextCoefficientMax(override.value());
                case "role-fit-base" -> compartment.getRating().setRoleFitBase(override.value());
                case "role-fit-range" -> compartment.getRating().setRoleFitRange(override.value());
                case "fitness-floor" -> compartment.getRating().setFitnessFloor(override.value());
                case "morale-neutral" -> compartment.getRating().setMoraleNeutral(override.value());
                case "morale-slope" -> compartment.getRating().setMoraleSlope(override.value());
                case "default-position-multiplier" -> compartment.getRating().setDefaultPositionMultiplier(override.value());
                case "default-role-multiplier" -> compartment.getRating().setDefaultRoleMultiplier(override.value());
                default -> throw new IllegalArgumentException("unsupported override: " + key);
            }
        } else if (key.equals("match.role-weights.suitability-scale")) {
            match.getRoleWeights().setSuitabilityScale(override.value());
        } else if (key.equals("match.role-weights.overall-blend")) {
            match.getRoleWeights().setOverallBlend(override.value());
        } else if (key.equals("match.role-weights.role-blend")) {
            match.getRoleWeights().setRoleBlend(override.value());
        } else if (key.equals("match.instruction-weights.bonus-scale")) {
            match.getInstructionWeights().setBonusScale(override.value());
        } else if (key.equals("match.instruction-weights.conflict-penalty")) {
            match.getInstructionWeights().setConflictPenalty(override.value());
        } else if (key.equals("match.player-value.morale-neutral")) {
            match.getPlayerValue().setMoraleNeutral(override.value());
        } else if (key.equals("match.player-value.morale-slope")) {
            match.getPlayerValue().setMoraleSlope(override.value());
        } else if (key.equals("match.player-value.scale-multiplier")) {
            match.getPlayerValue().setScaleMultiplier(override.value());
        } else if (key.equals("match.player-value.rating-floor")) {
            match.getPlayerValue().setRatingFloor(override.value());
        } else if (key.equals("match.player-value.rating-ceil")) {
            match.getPlayerValue().setRatingCeil(override.value());
        } else if (key.equals("match.player-value.fitness-floor")) {
            match.getPlayerValue().setFitnessFloor(override.value());
        } else if (key.equals("match.player-value.default-familiarity-penalty")) {
            match.getPlayerValue().setDefaultFamiliarityPenalty(override.value());
        } else if (key.startsWith("match.player-value.weights.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 5) throw new IllegalArgumentException("unsupported player-value weight: " + key);
            match.getPlayerValue().getWeights().computeIfAbsent(parts[3], ignored -> new LinkedHashMap<>()).put(parts[4], override.value());
        } else if (key.startsWith("match.player-value.familiarity-penalty.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 5) throw new IllegalArgumentException("unsupported familiarity weight: " + key);
            match.getPlayerValue().getFamiliarityPenalty().computeIfAbsent(parts[3], ignored -> new LinkedHashMap<>()).put(parts[4], override.value());
        } else if (key.startsWith("match.role-weights.attributes.")) {
            String prefix = "match.role-weights.attributes.";
            String path = key.substring(prefix.length());
            int separator = path.lastIndexOf('.');
            if (separator <= 0 || separator == path.length() - 1) {
                throw new IllegalArgumentException("unsupported role attribute weight: " + key);
            }
            String roleDisplay = path.substring(0, separator);
            String attributeDisplay = path.substring(separator + 1);
            com.footballmanagergamesimulator.compartment.PlayerRole role =
                    com.footballmanagergamesimulator.compartment.PlayerRole.fromDisplayName(roleDisplay)
                            .orElseThrow(() -> new IllegalArgumentException("unknown role attribute role: " + roleDisplay));
            String attribute = com.footballmanagergamesimulator.service.PlayerSkillsService.GETTER_MAP.keySet().stream()
                    .filter(candidate -> normalize(candidate).equals(normalize(attributeDisplay)))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown role attribute: " + attributeDisplay));
            String roleKey = match.getRoleWeights().getAttributes().keySet().stream()
                    .filter(candidate -> normalize(candidate).equals(normalize(role.displayName())))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("missing role weights for " + role.displayName()));
            match.getRoleWeights().getAttributes().get(roleKey).put(attribute, override.value());
        } else if (key.startsWith("compartment.context-rules.")) {
            String[] parts = key.split("\\.", 4);
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            String source = parts[2];
            String normalizedSource = source.replace(" ", "").replace(":", "");
            com.footballmanagergamesimulator.compartment.PlayerAttribute attribute =
                    com.footballmanagergamesimulator.compartment.PlayerAttribute.valueOf(parts[3]);
            Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double> row = compartment.getContextRules().get(source);
            var matchingRows = compartment.getContextRules().entrySet().stream()
                    .filter(entry -> entry.getKey().replace(" ", "").replace(":", "").equals(normalizedSource))
                    .sorted(java.util.Comparator.comparing((Map.Entry<String, Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double>> entry)
                            -> entry.getKey().contains(":") ? 0 : 1).thenComparing(Map.Entry::getKey))
                    .toList();
            if (!matchingRows.isEmpty()) row = matchingRows.get(0).getValue();
            if (row == null) throw new IllegalArgumentException("unknown context rule: " + source);
            row.put(attribute, override.value());
        } else if (key.startsWith("compartment.compartments.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 5 || !parts[3].equals("attributes")) throw new IllegalArgumentException("unsupported override: " + key);
            Compartment c = Compartment.valueOf(parts[2]);
            com.footballmanagergamesimulator.compartment.PlayerAttribute attribute =
                    com.footballmanagergamesimulator.compartment.PlayerAttribute.valueOf(parts[4]);
            compartment.getCompartments().get(c).getAttributes().put(attribute, override.value());
        } else if (key.startsWith("compartment.positions.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            var multipliers = compartment.getPositions().get(parts[2]);
            if (multipliers == null) throw new IllegalArgumentException("unknown position: " + parts[2]);
            setMultiplier(multipliers, parts[3], override.value());
        } else if (key.startsWith("compartment.position-compartment-overrides.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 6 || !parts[4].equals("attributes")) throw new IllegalArgumentException("unsupported override: " + key);
            var row = compartment.getPositionCompartmentOverrides().get(parts[2]);
            var weights = row == null ? null : row.get(Compartment.valueOf(parts[3]));
            if (weights == null) throw new IllegalArgumentException("unknown position override: " + key);
            weights.getAttributes().put(com.footballmanagergamesimulator.compartment.PlayerAttribute.valueOf(parts[5]), override.value());
        } else if (key.startsWith("compartment.roles.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            var multipliers = compartment.getRoles().get(com.footballmanagergamesimulator.compartment.PlayerRole.valueOf(parts[2]));
            if (multipliers == null) throw new IllegalArgumentException("unknown role: " + parts[2]);
            setMultiplier(multipliers, parts[3], override.value());
        } else if (key.startsWith("compartment.duties.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            setMultiplier(compartment.getDuties().get(com.footballmanagergamesimulator.compartment.Duty.valueOf(parts[2])), parts[3], override.value());
        } else if (key.startsWith("compartment.mentalities.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            var rule = compartment.getMentalities().get(com.footballmanagergamesimulator.compartment.Mentality.valueOf(parts[2]));
            switch (parts[3]) {
                case "midfield-to-attack" -> { rule.setMidfieldToAttack(override.value()); rule.setMidfieldToDefense(1.0 - override.value()); }
                case "midfield-to-defense" -> { rule.setMidfieldToDefense(override.value()); rule.setMidfieldToAttack(1.0 - override.value()); }
                case "transfer-share" -> rule.setTransferShare(override.value());
                case "openness" -> rule.setOpenness(override.value());
                default -> throw new IllegalArgumentException("unknown mentality leaf: " + key);
            }
        } else if (key.startsWith("compartment.work-rate.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 5) throw new IllegalArgumentException("unsupported override: " + key);
            CompartmentEngineConfig.WorkRule rule;
            if (parts[2].equals("traits")) rule = compartment.getWorkRate().getTraits()
                    .get(com.footballmanagergamesimulator.compartment.PlayerTrait.valueOf(parts[3]));
            else if (parts[2].equals("instructions")) rule = compartment.getWorkRate().getInstructions()
                    .get(com.footballmanagergamesimulator.compartment.ForwardInstruction.valueOf(parts[3]));
            else throw new IllegalArgumentException("unknown work-rate group: " + key);
            switch (parts[4]) {
                case "engagement" -> rule.setEngagement(override.value());
                case "attack-multiplier" -> rule.setAttackMultiplier(override.value());
                case "forced-defensive-morale-delta" -> rule.setForcedDefensiveMoraleDelta(override.value());
                default -> throw new IllegalArgumentException("discrete work-rate leaf is not numerically overridden: " + key);
            }
        } else if (key.startsWith("compartment.exposure.")) {
            String exposureKey = key.substring("compartment.exposure.".length());
            if (exposureKey.startsWith("zone-weights.")) {
                compartment.getExposure().getZoneWeights().put(exposureKey.substring("zone-weights.".length()), override.value());
                return this;
            }
            switch (exposureKey) {
                case "coverage-reduction" -> compartment.getExposure().setCoverageReduction(override.value());
                case "second-dm-weight" -> compartment.getExposure().setSecondDmWeight(override.value());
                case "cb-recovery-pace-cap" -> compartment.getExposure().setCbRecoveryPaceCap(override.value());
                case "penalty-strength" -> compartment.getExposure().setPenaltyStrength(override.value());
                case "penalty-exponent" -> compartment.getExposure().setPenaltyExponent(override.value());
                default -> throw new IllegalArgumentException("unknown exposure leaf: " + key);
            }
        } else if (key.startsWith("compartment.probability.")) {
            switch (key.substring("compartment.probability.".length())) {
                case "matchup-exponent" -> compartment.getProbability().setMatchupExponent(override.value());
                case "home-advantage" -> compartment.getProbability().setHomeAdvantage(override.value());
                case "gamma-shape" -> compartment.getProbability().setGammaShape(override.value());
                case "goal-cap" -> compartment.getProbability().setGoalCap((int) override.value());
                case "extra-time-scale" -> compartment.getProbability().setExtraTimeScale(override.value());
                case "interval-lower-quantile" -> compartment.getProbability().setIntervalLowerQuantile(override.value());
                case "interval-upper-quantile" -> compartment.getProbability().setIntervalUpperQuantile(override.value());
                default -> throw new IllegalArgumentException("unknown probability leaf: " + key);
            }
        } else if (key.equals("compartment.aggregation.wide-redistribution-share")) {
            compartment.getAggregation().setWideRedistributionShare(override.value());
        } else if (key.equals("match.instruction-weights.clamp-min")) {
            match.getInstructionWeights().setClampMin(override.value());
        } else if (key.equals("match.instruction-weights.clamp-max")) {
            match.getInstructionWeights().setClampMax(override.value());
        } else if (key.startsWith("match.instruction-weights.bonuses.")) {
            String[] parts = key.split("\\.");
            if (parts.length < 5) throw new IllegalArgumentException("unsupported instruction bonus: " + key);
            MatchEngineConfig.InstructionWeights.InstructionBonus bonus = match.getInstructionWeights().getBonuses()
                    .computeIfAbsent(parts[3], ignored -> new MatchEngineConfig.InstructionWeights.InstructionBonus());
            if (parts.length == 5 && parts[4].equals("base")) bonus.setBase(override.value());
            else if (parts.length == 6 && parts[4].equals("by-position")) bonus.getByPosition().put(parts[5], override.value());
            else throw new IllegalArgumentException("unsupported instruction bonus: " + key);
        } else {
            throw new IllegalArgumentException("unsupported override: " + key);
        }
        return this;
    }

    public CompartmentEngineConfig compartment() { return compartment; }
    public MatchEngineConfig match() { return match; }

    private static void setMultiplier(CompartmentEngineConfig.CompartmentMultipliers multipliers, String axis, double value) {
        switch (axis) {
            case "attack" -> multipliers.setAttack(value);
            case "midfield" -> multipliers.setMidfield(value);
            case "defense" -> multipliers.setDefense(value);
            default -> throw new IllegalArgumentException("unknown multiplier axis: " + axis);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(" ", "").replace("-", "").replace("_", "").toUpperCase();
    }

    private static CompartmentEngineConfig copyCompartment(CompartmentEngineConfig source) {
        if (source == null) throw new NullPointerException("compartment");
        CompartmentEngineConfig target = new CompartmentEngineConfig();
        target.setEnabled(source.isEnabled());
        target.setShadowEnabled(source.isShadowEnabled());
        target.getRating().setAttributeMin(source.getRating().getAttributeMin());
        target.getRating().setAttributeMax(source.getRating().getAttributeMax());
        target.getRating().setScoreScale(source.getRating().getScoreScale());
        target.getRating().setContextFactorMin(source.getRating().getContextFactorMin());
        target.getRating().setContextFactorMax(source.getRating().getContextFactorMax());
        target.getRating().setTotalContextMin(source.getRating().getTotalContextMin());
        target.getRating().setTotalContextMax(source.getRating().getTotalContextMax());
        target.getRating().setContextCoefficientMin(source.getRating().getContextCoefficientMin());
        target.getRating().setContextCoefficientMax(source.getRating().getContextCoefficientMax());
        target.getRating().setRoleFitBase(source.getRating().getRoleFitBase());
        target.getRating().setRoleFitRange(source.getRating().getRoleFitRange());
        target.getRating().setFitnessFloor(source.getRating().getFitnessFloor());
        target.getRating().setMoraleNeutral(source.getRating().getMoraleNeutral());
        target.getRating().setMoraleSlope(source.getRating().getMoraleSlope());
        target.getRating().setDefaultPositionMultiplier(source.getRating().getDefaultPositionMultiplier());
        target.getRating().setDefaultRoleMultiplier(source.getRating().getDefaultRoleMultiplier());
        target.setContextRules(copyNested(source.getContextRules()));
        Map<Compartment, CompartmentEngineConfig.CompartmentWeights> compartments = new LinkedHashMap<>();
        source.getCompartments().forEach((key, value) -> {
            CompartmentEngineConfig.CompartmentWeights copy = new CompartmentEngineConfig.CompartmentWeights();
            copy.setAttributes(new LinkedHashMap<>(value.getAttributes()));
            compartments.put(key, copy);
        });
        target.setCompartments(compartments);
        target.setPositions(copyMultipliers(source.getPositions()));
        target.setRoles(copyMultipliers(source.getRoles()));
        target.setDuties(copyMultipliers(source.getDuties()));
        Map<com.footballmanagergamesimulator.compartment.Mentality, CompartmentEngineConfig.MentalityRule> mentalities = new LinkedHashMap<>();
        source.getMentalities().forEach((key, value) -> {
            CompartmentEngineConfig.MentalityRule copy = new CompartmentEngineConfig.MentalityRule();
            copy.setMidfieldToAttack(value.getMidfieldToAttack()); copy.setMidfieldToDefense(value.getMidfieldToDefense());
            copy.setTransferFrom(value.getTransferFrom()); copy.setTransferTo(value.getTransferTo());
            copy.setTransferShare(value.getTransferShare()); copy.setOpenness(value.getOpenness());
            mentalities.put(key, copy);
        });
        target.setMentalities(mentalities);
        Map<String, Map<Compartment, CompartmentEngineConfig.CompartmentWeights>> overrides = new LinkedHashMap<>();
        source.getPositionCompartmentOverrides().forEach((position, byCompartment) -> {
            Map<Compartment, CompartmentEngineConfig.CompartmentWeights> nested = new LinkedHashMap<>();
            byCompartment.forEach((key, value) -> {
                CompartmentEngineConfig.CompartmentWeights copy = new CompartmentEngineConfig.CompartmentWeights();
                copy.setAttributes(new LinkedHashMap<>(value.getAttributes())); nested.put(key, copy);
            });
            overrides.put(position, nested);
        });
        target.setPositionCompartmentOverrides(overrides);
        Map<com.footballmanagergamesimulator.compartment.PlayerTrait, CompartmentEngineConfig.WorkRule> traits = new LinkedHashMap<>();
        source.getWorkRate().getTraits().forEach((key, value) -> traits.put(key, copyWorkRule(value)));
        Map<com.footballmanagergamesimulator.compartment.ForwardInstruction, CompartmentEngineConfig.WorkRule> instructions = new LinkedHashMap<>();
        source.getWorkRate().getInstructions().forEach((key, value) -> instructions.put(key, copyWorkRule(value)));
        target.getWorkRate().setTraits(traits); target.getWorkRate().setInstructions(instructions);
        target.getExposure().setZoneWeights(new LinkedHashMap<>(source.getExposure().getZoneWeights()));
        target.getExposure().setCoverageReduction(source.getExposure().getCoverageReduction());
        target.getExposure().setSecondDmWeight(source.getExposure().getSecondDmWeight());
        target.getExposure().setCbRecoveryPaceCap(source.getExposure().getCbRecoveryPaceCap());
        target.getExposure().setPenaltyStrength(source.getExposure().getPenaltyStrength());
        target.getExposure().setPenaltyExponent(source.getExposure().getPenaltyExponent());
        target.getProbability().setMatchupExponent(source.getProbability().getMatchupExponent());
        target.getProbability().setHomeAdvantage(source.getProbability().getHomeAdvantage());
        target.getProbability().setGammaShape(source.getProbability().getGammaShape());
        target.getProbability().setGoalCap(source.getProbability().getGoalCap());
        target.getProbability().setExtraTimeScale(source.getProbability().getExtraTimeScale());
        target.getProbability().setIntervalLowerQuantile(source.getProbability().getIntervalLowerQuantile());
        target.getProbability().setIntervalUpperQuantile(source.getProbability().getIntervalUpperQuantile());
        target.getAggregation().setWideRedistributionShare(source.getAggregation().getWideRedistributionShare());
        return target;
    }

    private static CompartmentEngineConfig.WorkRule copyWorkRule(CompartmentEngineConfig.WorkRule source) {
        CompartmentEngineConfig.WorkRule copy = new CompartmentEngineConfig.WorkRule();
        copy.setEngagement(source.getEngagement()); copy.setAttackMultiplier(source.getAttackMultiplier());
        copy.setIgnoresDefensiveInstructions(source.isIgnoresDefensiveInstructions());
        copy.setForcedDefensiveMoraleDelta(source.getForcedDefensiveMoraleDelta());
        return copy;
    }

    private static <K> Map<K, CompartmentEngineConfig.CompartmentMultipliers> copyMultipliers(Map<K, CompartmentEngineConfig.CompartmentMultipliers> source) {
        Map<K, CompartmentEngineConfig.CompartmentMultipliers> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            CompartmentEngineConfig.CompartmentMultipliers copy = new CompartmentEngineConfig.CompartmentMultipliers();
            copy.setAttack(value.getAttack()); copy.setMidfield(value.getMidfield()); copy.setDefense(value.getDefense());
            result.put(key, copy);
        });
        return result;
    }

    private static MatchEngineConfig copyMatch(MatchEngineConfig source) {
        if (source == null) throw new NullPointerException("match");
        MatchEngineConfig target = new MatchEngineConfig();
        target.getPlayerValue().setScaleMultiplier(source.getPlayerValue().getScaleMultiplier());
        target.getPlayerValue().setRatingFloor(source.getPlayerValue().getRatingFloor());
        target.getPlayerValue().setRatingCeil(source.getPlayerValue().getRatingCeil());
        target.getPlayerValue().setMoraleNeutral(source.getPlayerValue().getMoraleNeutral());
        target.getPlayerValue().setMoraleSlope(source.getPlayerValue().getMoraleSlope());
        target.getPlayerValue().setFitnessFloor(source.getPlayerValue().getFitnessFloor());
        target.getPlayerValue().setDefaultFamiliarityPenalty(source.getPlayerValue().getDefaultFamiliarityPenalty());
        target.getPlayerValue().setWeights(copyNested(source.getPlayerValue().getWeights()));
        target.getPlayerValue().setFamiliarityPenalty(copyNested(source.getPlayerValue().getFamiliarityPenalty()));
        target.getRoleWeights().setOverallBlend(source.getRoleWeights().getOverallBlend());
        target.getRoleWeights().setRoleBlend(source.getRoleWeights().getRoleBlend());
        target.getRoleWeights().setSuitabilityScale(source.getRoleWeights().getSuitabilityScale());
        target.getRoleWeights().setAttributes(copyNested(source.getRoleWeights().getAttributes()));
        target.getInstructionWeights().setBonusScale(source.getInstructionWeights().getBonusScale());
        target.getInstructionWeights().setConflictPenalty(source.getInstructionWeights().getConflictPenalty());
        target.getInstructionWeights().setClampMin(source.getInstructionWeights().getClampMin());
        target.getInstructionWeights().setClampMax(source.getInstructionWeights().getClampMax());
        Map<String, MatchEngineConfig.InstructionWeights.InstructionBonus> bonuses = new LinkedHashMap<>();
        source.getInstructionWeights().getBonuses().forEach((key, value) -> {
            MatchEngineConfig.InstructionWeights.InstructionBonus copy = new MatchEngineConfig.InstructionWeights.InstructionBonus();
            copy.setBase(value.getBase()); copy.setByPosition(new LinkedHashMap<>(value.getByPosition())); bonuses.put(key, copy);
        });
        target.getInstructionWeights().setBonuses(bonuses);
        java.util.List<MatchEngineConfig.InstructionWeights.ConflictPair> conflicts = new java.util.ArrayList<>();
        source.getInstructionWeights().getConflicts().forEach(pair -> conflicts.add(
                new MatchEngineConfig.InstructionWeights.ConflictPair(pair.getA(), pair.getB())));
        target.getInstructionWeights().setConflicts(conflicts);
        return target;
    }

    private static <K, V> Map<K, V> copyNested(Map<K, V> source) {
        Map<K, V> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                result.put(key, (V) copyNested((Map<Object, Object>) nested));
            } else result.put(key, value);
        });
        return result;
    }
}
