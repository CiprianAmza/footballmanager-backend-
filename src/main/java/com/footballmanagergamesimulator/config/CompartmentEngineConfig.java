package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed Phase 0/1 contract for the future compartment engine.
 *
 * <p>The bean binds the complete coefficient catalogue under {@code match.engine.compartment};
 * canonical runtime scoring receives it explicitly. Both rollout flags remain off until the
 * authoritative integration is separately enabled.
 */
@Configuration
@ConfigurationProperties(prefix = "match.engine.compartment")
public class CompartmentEngineConfig {

    private boolean enabled = false;
    private boolean shadowEnabled = false;
    private Rating rating = new Rating();
    private Map<Compartment, CompartmentWeights> compartments = new LinkedHashMap<>();
    private Map<String, Map<Compartment, CompartmentWeights>> positionCompartmentOverrides = new LinkedHashMap<>();
    private Map<String, CompartmentMultipliers> positions = new LinkedHashMap<>();
    private Map<PlayerRole, CompartmentMultipliers> roles = new LinkedHashMap<>();
    private Map<Duty, CompartmentMultipliers> duties = new LinkedHashMap<>();
    private Map<Mentality, MentalityRule> mentalities = new LinkedHashMap<>();
    private WorkRate workRate = new WorkRate();
    private Exposure exposure = new Exposure();
    private Probability probability = new Probability();
    private Aggregation aggregation = new Aggregation();
    private Map<String, Map<PlayerAttribute, Double>> contextRules = defaultContextRules();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isShadowEnabled() { return shadowEnabled; }
    public void setShadowEnabled(boolean shadowEnabled) { this.shadowEnabled = shadowEnabled; }
    public Rating getRating() { return rating; }
    public void setRating(Rating rating) { this.rating = rating; }
    public Map<Compartment, CompartmentWeights> getCompartments() { return compartments; }
    public void setCompartments(Map<Compartment, CompartmentWeights> compartments) { this.compartments = compartments; }
    public Map<String, Map<Compartment, CompartmentWeights>> getPositionCompartmentOverrides() {
        return positionCompartmentOverrides;
    }
    public void setPositionCompartmentOverrides(
            Map<String, Map<Compartment, CompartmentWeights>> positionCompartmentOverrides) {
        this.positionCompartmentOverrides = positionCompartmentOverrides;
    }
    public Map<String, CompartmentMultipliers> getPositions() { return positions; }
    public void setPositions(Map<String, CompartmentMultipliers> positions) { this.positions = positions; }
    public Map<PlayerRole, CompartmentMultipliers> getRoles() { return roles; }
    public void setRoles(Map<PlayerRole, CompartmentMultipliers> roles) { this.roles = roles; }
    public Map<Duty, CompartmentMultipliers> getDuties() { return duties; }
    public void setDuties(Map<Duty, CompartmentMultipliers> duties) { this.duties = duties; }
    public Map<Mentality, MentalityRule> getMentalities() { return mentalities; }
    public void setMentalities(Map<Mentality, MentalityRule> mentalities) { this.mentalities = mentalities; }
    public WorkRate getWorkRate() { return workRate; }
    public void setWorkRate(WorkRate workRate) { this.workRate = workRate; }
    public Exposure getExposure() { return exposure; }
    public void setExposure(Exposure exposure) { this.exposure = exposure; }
    public Probability getProbability() { return probability; }
    public void setProbability(Probability probability) { this.probability = probability; }
    public Aggregation getAggregation() { return aggregation; }
    public void setAggregation(Aggregation aggregation) { this.aggregation = aggregation; }
    public Map<String, Map<PlayerAttribute, Double>> getContextRules() { return contextRules; }
    public void setContextRules(Map<String, Map<PlayerAttribute, Double>> contextRules) {
        this.contextRules = contextRules;
    }

    private static Map<String, Map<PlayerAttribute, Double>> defaultContextRules() {
        Map<String, Map<PlayerAttribute, Double>> rules = new LinkedHashMap<>();
        add(rules, "mentality:attacking", PlayerAttribute.OFF_THE_BALL, .15, PlayerAttribute.COMPOSURE, .10);
        add(rules, "mentality:very attacking", PlayerAttribute.OFF_THE_BALL, .30, PlayerAttribute.COMPOSURE, .20);
        add(rules, "mentality:defensive", PlayerAttribute.POSITIONING, .15, PlayerAttribute.CONCENTRATION, .10);
        add(rules, "mentality:very defensive", PlayerAttribute.POSITIONING, .30, PlayerAttribute.CONCENTRATION, .20);
        add(rules, "tempo:higher", PlayerAttribute.DECISIONS, .15, PlayerAttribute.FIRST_TOUCH, .10);
        add(rules, "tempo:much higher", PlayerAttribute.DECISIONS, .30, PlayerAttribute.FIRST_TOUCH, .20);
        add(rules, "tempo:lower", PlayerAttribute.TECHNIQUE, .10, PlayerAttribute.VISION, .10);
        add(rules, "tempo:much lower", PlayerAttribute.TECHNIQUE, .20, PlayerAttribute.VISION, .20);
        add(rules, "passing:short", PlayerAttribute.PASSING, .15, PlayerAttribute.FIRST_TOUCH, .10);
        add(rules, "passing:long", PlayerAttribute.PASSING, .10, PlayerAttribute.VISION, .20, PlayerAttribute.KICKING, .15);
        add(rules, "line:high", PlayerAttribute.PACE, .20, PlayerAttribute.ANTICIPATION, .15, PlayerAttribute.POSITIONING, .10);
        add(rules, "line:deep", PlayerAttribute.HEADING, .15, PlayerAttribute.CONCENTRATION, .15, PlayerAttribute.POSITIONING, .10);
        add(rules, "pressing:high", PlayerAttribute.WORK_RATE, .20, PlayerAttribute.STAMINA, .20, PlayerAttribute.ANTICIPATION, .10);
        add(rules, "pressing:low", PlayerAttribute.POSITIONING, .10, PlayerAttribute.CONCENTRATION, .10);
        add(rules, "width:wide", PlayerAttribute.PACE, .10, PlayerAttribute.DRIBBLING, .10, PlayerAttribute.PASSING, .05);
        add(rules, "width:narrow", PlayerAttribute.TECHNIQUE, .10, PlayerAttribute.FIRST_TOUCH, .10, PlayerAttribute.DECISIONS, .05);
        add(rules, "instruction:mark tighter", PlayerAttribute.MARKING, .20, PlayerAttribute.CONCENTRATION, .10);
        add(rules, "instruction:close down more", PlayerAttribute.WORK_RATE, .15, PlayerAttribute.STAMINA, .10, PlayerAttribute.ANTICIPATION, .10);
        add(rules, "instruction:close down less", PlayerAttribute.POSITIONING, .15, PlayerAttribute.CONCENTRATION, .10);
        add(rules, "instruction:tackle harder", PlayerAttribute.TACKLING, .20, PlayerAttribute.BRAVERY, .10);
        add(rules, "instruction:stay on feet", PlayerAttribute.DECISIONS, .15, PlayerAttribute.POSITIONING, .10);
        add(rules, "instruction:ease off tackles", PlayerAttribute.DECISIONS, .10, PlayerAttribute.TACKLING, .05);
        add(rules, "instruction:get further forward", PlayerAttribute.OFF_THE_BALL, .20, PlayerAttribute.STAMINA, .10);
        add(rules, "instruction:hold position", PlayerAttribute.POSITIONING, .20, PlayerAttribute.CONCENTRATION, .10);
        add(rules, "instruction:shoot more often", PlayerAttribute.FINISHING, .20, PlayerAttribute.COMPOSURE, .10);
        add(rules, "instruction:shoot less often", PlayerAttribute.DECISIONS, .15, PlayerAttribute.PASSING, .10);
        add(rules, "instruction:dribble more", PlayerAttribute.DRIBBLING, .20, PlayerAttribute.ACCELERATION, .10);
        add(rules, "instruction:dribble less", PlayerAttribute.FIRST_TOUCH, .10, PlayerAttribute.PASSING, .10);
        add(rules, "instruction:roam from position", PlayerAttribute.OFF_THE_BALL, .15, PlayerAttribute.DECISIONS, .10);
        add(rules, "instruction:sit narrower", PlayerAttribute.TECHNIQUE, .10, PlayerAttribute.FIRST_TOUCH, .10);
        add(rules, "instruction:stay wider", PlayerAttribute.PACE, .10, PlayerAttribute.DRIBBLING, .10);
        add(rules, "instruction:move into channels", PlayerAttribute.OFF_THE_BALL, .20, PlayerAttribute.ANTICIPATION, .10);
        add(rules, "instruction:drop deeper", PlayerAttribute.FIRST_TOUCH, .10, PlayerAttribute.VISION, .15, PlayerAttribute.PASSING, .10);
        add(rules, "instruction:pass it shorter", PlayerAttribute.PASSING, .15, PlayerAttribute.FIRST_TOUCH, .10);
        add(rules, "instruction:try more direct passes", PlayerAttribute.PASSING, .15, PlayerAttribute.VISION, .20);
        add(rules, "instruction:cross from byline", PlayerAttribute.PACE, .10, PlayerAttribute.DRIBBLING, .10, PlayerAttribute.PASSING, .15);
        add(rules, "instruction:cross from deep", PlayerAttribute.PASSING, .20, PlayerAttribute.VISION, .10);
        add(rules, "instruction:play through balls", PlayerAttribute.VISION, .20, PlayerAttribute.PASSING, .15, PlayerAttribute.DECISIONS, .10);
        return rules;
    }

    private static void add(Map<String, Map<PlayerAttribute, Double>> rules, String key, Object... values) {
        Map<PlayerAttribute, Double> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put((PlayerAttribute) values[i], (Double) values[i + 1]);
        }
        rules.put(key, row);
    }

    public static class Rating {
        private int attributeMin = 1;
        private int attributeMax = 20;
        private double scoreScale = 100.0;
        private double contextFactorMin = 0.60;
        private double contextFactorMax = 1.40;
        private double totalContextMin = 0.70;
        private double totalContextMax = 1.30;
        private double contextCoefficientMin = -1.0;
        private double contextCoefficientMax = 1.0;
        private double roleFitBase = 0.85;
        private double roleFitRange = 0.30;
        private double fitnessFloor = 0.70;
        private double moraleNeutral = 70.0;
        private double moraleSlope = 0.0004;
        private double defaultPositionMultiplier = 1.0;
        private double defaultRoleMultiplier = 1.0;

        public int getAttributeMin() { return attributeMin; }
        public void setAttributeMin(int v) { this.attributeMin = v; }
        public int getAttributeMax() { return attributeMax; }
        public void setAttributeMax(int v) { this.attributeMax = v; }
        public double getScoreScale() { return scoreScale; }
        public void setScoreScale(double v) { this.scoreScale = v; }
        public double getContextFactorMin() { return contextFactorMin; }
        public void setContextFactorMin(double v) { this.contextFactorMin = v; }
        public double getContextFactorMax() { return contextFactorMax; }
        public void setContextFactorMax(double v) { this.contextFactorMax = v; }
        public double getTotalContextMin() { return totalContextMin; }
        public void setTotalContextMin(double v) { this.totalContextMin = v; }
        public double getTotalContextMax() { return totalContextMax; }
        public void setTotalContextMax(double v) { this.totalContextMax = v; }
        public double getContextCoefficientMin() { return contextCoefficientMin; }
        public void setContextCoefficientMin(double v) { this.contextCoefficientMin = v; }
        public double getContextCoefficientMax() { return contextCoefficientMax; }
        public void setContextCoefficientMax(double v) { this.contextCoefficientMax = v; }
        public double getRoleFitBase() { return roleFitBase; }
        public void setRoleFitBase(double v) { this.roleFitBase = v; }
        public double getRoleFitRange() { return roleFitRange; }
        public void setRoleFitRange(double v) { this.roleFitRange = v; }
        public double getFitnessFloor() { return fitnessFloor; }
        public void setFitnessFloor(double v) { this.fitnessFloor = v; }
        public double getMoraleNeutral() { return moraleNeutral; }
        public void setMoraleNeutral(double v) { this.moraleNeutral = v; }
        public double getMoraleSlope() { return moraleSlope; }
        public void setMoraleSlope(double v) { this.moraleSlope = v; }
        public double getDefaultPositionMultiplier() { return defaultPositionMultiplier; }
        public void setDefaultPositionMultiplier(double v) { this.defaultPositionMultiplier = v; }
        public double getDefaultRoleMultiplier() { return defaultRoleMultiplier; }
        public void setDefaultRoleMultiplier(double v) { this.defaultRoleMultiplier = v; }
    }

    public static class CompartmentWeights {
        private Map<PlayerAttribute, Double> attributes = new LinkedHashMap<>();

        public Map<PlayerAttribute, Double> getAttributes() { return attributes; }
        public void setAttributes(Map<PlayerAttribute, Double> attributes) { this.attributes = attributes; }
    }

    public static class CompartmentMultipliers {
        private double attack = 1.0;
        private double midfield = 1.0;
        private double defense = 1.0;

        public double getAttack() { return attack; }
        public void setAttack(double v) { this.attack = v; }
        public double getMidfield() { return midfield; }
        public void setMidfield(double v) { this.midfield = v; }
        public double getDefense() { return defense; }
        public void setDefense(double v) { this.defense = v; }

        public double forCompartment(Compartment compartment) {
            return switch (compartment) {
                case ATTACK -> attack;
                case MIDFIELD -> midfield;
                case DEFENSE -> defense;
            };
        }
    }

    public static class MentalityRule {
        private double midfieldToAttack;
        private double midfieldToDefense;
        private Compartment transferFrom;
        private Compartment transferTo;
        private double transferShare;
        private double openness = 1.0;

        public double getMidfieldToAttack() { return midfieldToAttack; }
        public void setMidfieldToAttack(double v) { this.midfieldToAttack = v; }
        public double getMidfieldToDefense() { return midfieldToDefense; }
        public void setMidfieldToDefense(double v) { this.midfieldToDefense = v; }
        public Compartment getTransferFrom() { return transferFrom; }
        public void setTransferFrom(Compartment v) { this.transferFrom = v; }
        public Compartment getTransferTo() { return transferTo; }
        public void setTransferTo(Compartment v) { this.transferTo = v; }
        public double getTransferShare() { return transferShare; }
        public void setTransferShare(double v) { this.transferShare = v; }
        public double getOpenness() { return openness; }
        public void setOpenness(double v) { this.openness = v; }
    }

    public static class WorkRate {
        private Map<PlayerTrait, WorkRule> traits = new LinkedHashMap<>();
        private Map<ForwardInstruction, WorkRule> instructions = new LinkedHashMap<>();

        public Map<PlayerTrait, WorkRule> getTraits() { return traits; }
        public void setTraits(Map<PlayerTrait, WorkRule> traits) { this.traits = traits; }
        public Map<ForwardInstruction, WorkRule> getInstructions() { return instructions; }
        public void setInstructions(Map<ForwardInstruction, WorkRule> instructions) { this.instructions = instructions; }
    }

    public static class WorkRule {
        private double engagement = 1.0;
        private double attackMultiplier = 1.0;
        private boolean ignoresDefensiveInstructions;
        private double forcedDefensiveMoraleDelta;

        public double getEngagement() { return engagement; }
        public void setEngagement(double v) { this.engagement = v; }
        public double getAttackMultiplier() { return attackMultiplier; }
        public void setAttackMultiplier(double v) { this.attackMultiplier = v; }
        public boolean isIgnoresDefensiveInstructions() { return ignoresDefensiveInstructions; }
        public void setIgnoresDefensiveInstructions(boolean v) { this.ignoresDefensiveInstructions = v; }
        public double getForcedDefensiveMoraleDelta() { return forcedDefensiveMoraleDelta; }
        public void setForcedDefensiveMoraleDelta(double v) { this.forcedDefensiveMoraleDelta = v; }
    }

    public static class Exposure {
        private Map<String, Double> zoneWeights = new LinkedHashMap<>();
        private double coverageReduction = 0.65;
        private double secondDmWeight = 0.55;
        private double cbRecoveryPaceCap = 0.50;
        private double penaltyStrength = 0.55;
        private double penaltyExponent = 1.70;

        public Map<String, Double> getZoneWeights() { return zoneWeights; }
        public void setZoneWeights(Map<String, Double> v) { this.zoneWeights = v; }
        public double getCoverageReduction() { return coverageReduction; }
        public void setCoverageReduction(double v) { this.coverageReduction = v; }
        public double getSecondDmWeight() { return secondDmWeight; }
        public void setSecondDmWeight(double v) { this.secondDmWeight = v; }
        public double getCbRecoveryPaceCap() { return cbRecoveryPaceCap; }
        public void setCbRecoveryPaceCap(double v) { this.cbRecoveryPaceCap = v; }
        public double getPenaltyStrength() { return penaltyStrength; }
        public void setPenaltyStrength(double v) { this.penaltyStrength = v; }
        public double getPenaltyExponent() { return penaltyExponent; }
        public void setPenaltyExponent(double v) { this.penaltyExponent = v; }
    }

    public static class Probability {
        private double matchupExponent = 1.5;
        private double homeAdvantage = 1.08;
        private double gammaShape = 12.0;
        private int goalCap = 7;
        private double extraTimeScale = 1.0 / 3.0;
        private double intervalLowerQuantile = 0.05;
        private double intervalUpperQuantile = 0.95;

        public double getMatchupExponent() { return matchupExponent; }
        public void setMatchupExponent(double v) { this.matchupExponent = v; }
        public double getHomeAdvantage() { return homeAdvantage; }
        public void setHomeAdvantage(double v) { this.homeAdvantage = v; }
        public double getGammaShape() { return gammaShape; }
        public void setGammaShape(double v) { this.gammaShape = v; }
        public int getGoalCap() { return goalCap; }
        public void setGoalCap(int v) { this.goalCap = v; }
        public double getExtraTimeScale() { return extraTimeScale; }
        public void setExtraTimeScale(double v) { this.extraTimeScale = v; }
        public double getIntervalLowerQuantile() { return intervalLowerQuantile; }
        public void setIntervalLowerQuantile(double v) { this.intervalLowerQuantile = v; }
        public double getIntervalUpperQuantile() { return intervalUpperQuantile; }
        public void setIntervalUpperQuantile(double v) { this.intervalUpperQuantile = v; }
    }

    public static class Aggregation {
        private double wideRedistributionShare = 0.20;

        public double getWideRedistributionShare() { return wideRedistributionShare; }
        public void setWideRedistributionShare(double value) { this.wideRedistributionShare = value; }
    }
}
