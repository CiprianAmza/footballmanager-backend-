package com.footballmanagergamesimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every numeric constant that influences a match
 * outcome. Externalized so a fuzz/auto-tuner can sweep values without
 * recompiling and so designers can tweak balance from YAML.
 *
 * <p>Defaults equal the hardcoded values that existed before extraction —
 * production behaviour is unchanged unless overridden via
 * {@code match.engine.*} properties in {@code application.yml}.
 *
 * <p>Sections map to the audit groupings:
 * <ul>
 *   <li>{@link Power} — ratio amplification, expected goals, max cap</li>
 *   <li>{@link Morale} — win/draw/loss swing ranges, bench penalties</li>
 *   <li>{@link Tactic} — tempo, man-advantage attack multipliers</li>
 *   <li>{@link Stamina} — drain, recovery, position multipliers</li>
 *   <li>{@link Injuries} — base/low-fitness chance, severity bands</li>
 *   <li>{@link Fouls} — yellow/red card rates, ranges</li>
 *   <li>{@link Live} — possession, attack chance, big-chance scaling</li>
 *   <li>{@link Events} — goal minute range, assist probability, subs</li>
 *   <li>{@link Ratings} — base, goal/assist/clean-sheet/result deltas, variance</li>
 *   <li>{@link Reputation} — strength factor, win/draw/loss base values</li>
 *   <li>{@link Stats} — possession/passes/shots/cards/corners distributions</li>
 *   <li>{@link Knockout} — AET win chance baseline and power weight</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "match.engine")
public class MatchEngineConfig {

    private Power power = new Power();
    private Morale morale = new Morale();
    private Tactic tactic = new Tactic();
    private Stamina stamina = new Stamina();
    private Injuries injuries = new Injuries();
    private Fouls fouls = new Fouls();
    private Live live = new Live();
    private Events events = new Events();
    private Ratings ratings = new Ratings();
    private Reputation reputation = new Reputation();
    private Stats stats = new Stats();
    private Knockout knockout = new Knockout();
    private Training training = new Training();
    private PlayerValue playerValue = new PlayerValue();
    private RoleWeights roleWeights = new RoleWeights();
    private InstructionWeights instructionWeights = new InstructionWeights();
    private TeamTalk teamTalk = new TeamTalk();
    private TacticalModel tacticalModel = new TacticalModel();

    public Power getPower() { return power; }
    public void setPower(Power power) { this.power = power; }
    public Morale getMorale() { return morale; }
    public void setMorale(Morale morale) { this.morale = morale; }
    public Tactic getTactic() { return tactic; }
    public void setTactic(Tactic tactic) { this.tactic = tactic; }
    public Stamina getStamina() { return stamina; }
    public void setStamina(Stamina stamina) { this.stamina = stamina; }
    public Injuries getInjuries() { return injuries; }
    public void setInjuries(Injuries injuries) { this.injuries = injuries; }
    public Fouls getFouls() { return fouls; }
    public void setFouls(Fouls fouls) { this.fouls = fouls; }
    public Live getLive() { return live; }
    public void setLive(Live live) { this.live = live; }
    public Events getEvents() { return events; }
    public void setEvents(Events events) { this.events = events; }
    public Ratings getRatings() { return ratings; }
    public void setRatings(Ratings ratings) { this.ratings = ratings; }
    public Reputation getReputation() { return reputation; }
    public void setReputation(Reputation reputation) { this.reputation = reputation; }
    public Stats getStats() { return stats; }
    public void setStats(Stats stats) { this.stats = stats; }
    public Knockout getKnockout() { return knockout; }
    public void setKnockout(Knockout knockout) { this.knockout = knockout; }
    public Training getTraining() { return training; }
    public void setTraining(Training training) { this.training = training; }
    public PlayerValue getPlayerValue() { return playerValue; }
    public void setPlayerValue(PlayerValue playerValue) { this.playerValue = playerValue; }
    public RoleWeights getRoleWeights() { return roleWeights; }
    public void setRoleWeights(RoleWeights roleWeights) { this.roleWeights = roleWeights; }
    public InstructionWeights getInstructionWeights() { return instructionWeights; }
    public void setInstructionWeights(InstructionWeights instructionWeights) { this.instructionWeights = instructionWeights; }
    public TeamTalk getTeamTalk() { return teamTalk; }
    public void setTeamTalk(TeamTalk teamTalk) { this.teamTalk = teamTalk; }
    public TacticalModel getTacticalModel() { return tacticalModel; }
    public void setTacticalModel(TacticalModel tacticalModel) { this.tacticalModel = tacticalModel; }

    // ==================== POWER / POISSON ====================
    public static class Power {
        /** Math.pow(ratio, X) amplifies power gap. Higher = stronger team dominates more. */
        private double ratioExponent = 2.0;
        /** Total expected goals per match (split by adjusted ratio). */
        private double expectedGoalsTotal = 3.0;
        /** Hard cap per team to prevent runaway Poisson tails. */
        private int maxGoalsPerTeam = 7;

        // ---- Effective-power modifiers (promoted from the test harness in S15) ----
        // The auto-tuner (S13) converged on these so the game matches the invariant
        // catalog's morale + home-advantage behaviour. effectivePower =
        //   base × (moraleFloor + moraleSpread × morale/100) × (home ? homeAdvantage : 1)

        /** Morale=0 → power × moraleFloor. (Tuner value: 0.8.) */
        private double moraleFloor = 0.8;
        /** Morale=100 → power × (moraleFloor + moraleSpread). (Tuner value: 0.4 → top morale = 1.2×.) */
        private double moraleSpread = 0.4;
        /** Home side power multiplier. (Tuner value: 1.08 ≈ +8%.) */
        private double homeAdvantage = 1.08;

        public double getRatioExponent() { return ratioExponent; }
        public void setRatioExponent(double v) { this.ratioExponent = v; }
        public double getExpectedGoalsTotal() { return expectedGoalsTotal; }
        public void setExpectedGoalsTotal(double v) { this.expectedGoalsTotal = v; }
        public int getMaxGoalsPerTeam() { return maxGoalsPerTeam; }
        public void setMaxGoalsPerTeam(int v) { this.maxGoalsPerTeam = v; }
        public double getMoraleFloor() { return moraleFloor; }
        public void setMoraleFloor(double v) { this.moraleFloor = v; }
        public double getMoraleSpread() { return moraleSpread; }
        public void setMoraleSpread(double v) { this.moraleSpread = v; }
        public double getHomeAdvantage() { return homeAdvantage; }
        public void setHomeAdvantage(double v) { this.homeAdvantage = v; }
    }

    // ==================== MORALE ====================
    public static class Morale {
        /** Win morale swing ranges by power difference vs opponent. Indexed by tier (favorite → giant-killing). */
        private SwingRange winFavoriteBig = new SwingRange(0, 1);     // diff > 500
        private SwingRange winFavorite = new SwingRange(1, 2);        // diff > 200
        private SwingRange winSlightFavorite = new SwingRange(1, 3);  // diff > 0
        private SwingRange winSlightUnderdog = new SwingRange(2, 5);  // diff > -200
        private SwingRange winUnderdog = new SwingRange(4, 7);        // diff > -500
        private SwingRange winGiantKilling = new SwingRange(5, 10);   // diff <= -500

        private SwingRange drawFavoriteBig = new SwingRange(-6, -2);
        private SwingRange drawFavorite = new SwingRange(-4, 0);
        private SwingRange drawSlightFavorite = new SwingRange(-2, 1);
        private SwingRange drawSlightUnderdog = new SwingRange(1, 3);
        private SwingRange drawUnderdog = new SwingRange(2, 5);
        private SwingRange drawGiantHold = new SwingRange(3, 7);

        private SwingRange lossFavoriteBig = new SwingRange(-15, -5);  // shocking
        private SwingRange lossFavorite = new SwingRange(-8, -3);
        private SwingRange lossSlightFavorite = new SwingRange(-5, -2);
        private SwingRange lossSlightUnderdog = new SwingRange(-3, -1);
        private SwingRange lossUnderdog = new SwingRange(-2, 0);
        private SwingRange lossExpected = new SwingRange(-1, 0);

        /** Thresholds for power difference tiers. */
        private double tierBigDiff = 500;
        private double tierMidDiff = 200;
        private double tierSmallDiff = 0;

        /** Bench morale penalties per result (cumulative bench duration counted separately). */
        private double benchLossPenalty = -3;
        private double benchDrawPenalty = -4;
        private double benchWinPenalty = -2;
        /** Extra penalty stacked when player has been benched 5+ consecutive matches. */
        private double benchConsecutiveExtra = -2;
        private int benchConsecutiveThreshold = 5;

        /** Chance to drop wantsTransfer when player has played > minMatchesContent matches. */
        private double contentmentDropChance = 0.3;
        private int minMatchesContent = 5;

        /** Transfer-demand chance per bench-streak length (7+/5+/3+ matches). */
        private double transferDemandChance7Plus = 0.5;
        private double transferDemandChance5Plus = 0.3;
        private double transferDemandChance3Plus = 0.1;

        /** Per-player random variance applied on top of base morale change. */
        private double individualVariance = 1.0;

        public SwingRange getWinFavoriteBig() { return winFavoriteBig; }
        public void setWinFavoriteBig(SwingRange v) { this.winFavoriteBig = v; }
        public SwingRange getWinFavorite() { return winFavorite; }
        public void setWinFavorite(SwingRange v) { this.winFavorite = v; }
        public SwingRange getWinSlightFavorite() { return winSlightFavorite; }
        public void setWinSlightFavorite(SwingRange v) { this.winSlightFavorite = v; }
        public SwingRange getWinSlightUnderdog() { return winSlightUnderdog; }
        public void setWinSlightUnderdog(SwingRange v) { this.winSlightUnderdog = v; }
        public SwingRange getWinUnderdog() { return winUnderdog; }
        public void setWinUnderdog(SwingRange v) { this.winUnderdog = v; }
        public SwingRange getWinGiantKilling() { return winGiantKilling; }
        public void setWinGiantKilling(SwingRange v) { this.winGiantKilling = v; }
        public SwingRange getDrawFavoriteBig() { return drawFavoriteBig; }
        public void setDrawFavoriteBig(SwingRange v) { this.drawFavoriteBig = v; }
        public SwingRange getDrawFavorite() { return drawFavorite; }
        public void setDrawFavorite(SwingRange v) { this.drawFavorite = v; }
        public SwingRange getDrawSlightFavorite() { return drawSlightFavorite; }
        public void setDrawSlightFavorite(SwingRange v) { this.drawSlightFavorite = v; }
        public SwingRange getDrawSlightUnderdog() { return drawSlightUnderdog; }
        public void setDrawSlightUnderdog(SwingRange v) { this.drawSlightUnderdog = v; }
        public SwingRange getDrawUnderdog() { return drawUnderdog; }
        public void setDrawUnderdog(SwingRange v) { this.drawUnderdog = v; }
        public SwingRange getDrawGiantHold() { return drawGiantHold; }
        public void setDrawGiantHold(SwingRange v) { this.drawGiantHold = v; }
        public SwingRange getLossFavoriteBig() { return lossFavoriteBig; }
        public void setLossFavoriteBig(SwingRange v) { this.lossFavoriteBig = v; }
        public SwingRange getLossFavorite() { return lossFavorite; }
        public void setLossFavorite(SwingRange v) { this.lossFavorite = v; }
        public SwingRange getLossSlightFavorite() { return lossSlightFavorite; }
        public void setLossSlightFavorite(SwingRange v) { this.lossSlightFavorite = v; }
        public SwingRange getLossSlightUnderdog() { return lossSlightUnderdog; }
        public void setLossSlightUnderdog(SwingRange v) { this.lossSlightUnderdog = v; }
        public SwingRange getLossUnderdog() { return lossUnderdog; }
        public void setLossUnderdog(SwingRange v) { this.lossUnderdog = v; }
        public SwingRange getLossExpected() { return lossExpected; }
        public void setLossExpected(SwingRange v) { this.lossExpected = v; }
        public double getTierBigDiff() { return tierBigDiff; }
        public void setTierBigDiff(double v) { this.tierBigDiff = v; }
        public double getTierMidDiff() { return tierMidDiff; }
        public void setTierMidDiff(double v) { this.tierMidDiff = v; }
        public double getTierSmallDiff() { return tierSmallDiff; }
        public void setTierSmallDiff(double v) { this.tierSmallDiff = v; }
        public double getBenchLossPenalty() { return benchLossPenalty; }
        public void setBenchLossPenalty(double v) { this.benchLossPenalty = v; }
        public double getBenchDrawPenalty() { return benchDrawPenalty; }
        public void setBenchDrawPenalty(double v) { this.benchDrawPenalty = v; }
        public double getBenchWinPenalty() { return benchWinPenalty; }
        public void setBenchWinPenalty(double v) { this.benchWinPenalty = v; }
        public double getBenchConsecutiveExtra() { return benchConsecutiveExtra; }
        public void setBenchConsecutiveExtra(double v) { this.benchConsecutiveExtra = v; }
        public int getBenchConsecutiveThreshold() { return benchConsecutiveThreshold; }
        public void setBenchConsecutiveThreshold(int v) { this.benchConsecutiveThreshold = v; }
        public double getContentmentDropChance() { return contentmentDropChance; }
        public void setContentmentDropChance(double v) { this.contentmentDropChance = v; }
        public int getMinMatchesContent() { return minMatchesContent; }
        public void setMinMatchesContent(int v) { this.minMatchesContent = v; }
        public double getTransferDemandChance7Plus() { return transferDemandChance7Plus; }
        public void setTransferDemandChance7Plus(double v) { this.transferDemandChance7Plus = v; }
        public double getTransferDemandChance5Plus() { return transferDemandChance5Plus; }
        public void setTransferDemandChance5Plus(double v) { this.transferDemandChance5Plus = v; }
        public double getTransferDemandChance3Plus() { return transferDemandChance3Plus; }
        public void setTransferDemandChance3Plus(double v) { this.transferDemandChance3Plus = v; }
        public double getIndividualVariance() { return individualVariance; }
        public void setIndividualVariance(double v) { this.individualVariance = v; }
    }

    /** Closed range [min, max] for nextDouble(min, max). */
    public static class SwingRange {
        private double min;
        private double max;
        public SwingRange() {}
        public SwingRange(double min, double max) { this.min = min; this.max = max; }
        public double getMin() { return min; }
        public void setMin(double v) { this.min = v; }
        public double getMax() { return max; }
        public void setMax(double v) { this.max = v; }
    }

    // ==================== TACTIC ====================
    public static class Tactic {
        /** Per-minute stamina cost multiplier per tempo. Standard = 1.0. */
        private double tempoHighMultiplier = 1.25;
        private double tempoLowMultiplier = 0.85;

        /** Attack-chance multiplier when team is down to N players on pitch. */
        private double manAdvantageAttackEleven = 1.0;
        private double manAdvantageAttackTen = 0.7;
        private double manAdvantageAttackNine = 0.5;
        private double manAdvantageAttackEight = 0.35;
        /** Per-player gap in possession share when team is down men. */
        private double possessionShiftPerMissing = 0.08;

        public double getTempoHighMultiplier() { return tempoHighMultiplier; }
        public void setTempoHighMultiplier(double v) { this.tempoHighMultiplier = v; }
        public double getTempoLowMultiplier() { return tempoLowMultiplier; }
        public void setTempoLowMultiplier(double v) { this.tempoLowMultiplier = v; }
        public double getManAdvantageAttackEleven() { return manAdvantageAttackEleven; }
        public void setManAdvantageAttackEleven(double v) { this.manAdvantageAttackEleven = v; }
        public double getManAdvantageAttackTen() { return manAdvantageAttackTen; }
        public void setManAdvantageAttackTen(double v) { this.manAdvantageAttackTen = v; }
        public double getManAdvantageAttackNine() { return manAdvantageAttackNine; }
        public void setManAdvantageAttackNine(double v) { this.manAdvantageAttackNine = v; }
        public double getManAdvantageAttackEight() { return manAdvantageAttackEight; }
        public void setManAdvantageAttackEight(double v) { this.manAdvantageAttackEight = v; }
        public double getPossessionShiftPerMissing() { return possessionShiftPerMissing; }
        public void setPossessionShiftPerMissing(double v) { this.possessionShiftPerMissing = v; }
    }

    // ==================== STAMINA / FITNESS ====================
    public static class Stamina {
        /** Base per-minute stamina cost (multiplied by tempo/position/pace). */
        private double baseCostPerMinute = 0.5;
        /** Min attribute-derived multiplier (i.e. for stamina attr = 20). */
        private double minAttributeMultiplier = 0.15;
        /** Pace discount on high-tempo cost: cost *= (1 - paceDiscount * pace/20). */
        private double paceDiscountOnTempo = 0.5;
        /** Natural fitness recovery per minute: nfMax * (naturalFitness/20). */
        private double naturalFitnessRecoveryMax = 0.15;
        /** Position-based stamina multipliers. */
        private double positionGoalkeeper = 0.4;
        private double positionDefender = 0.75;
        private double positionDefMid = 1.0;
        private double positionMidfielder = 1.15;
        private double positionAttMid = 1.05;
        private double positionStriker = 0.85;
        /** Stamina-to-weight when picking attackers: 0.5 + 0.5*(stamina/100). */
        private double staminaPickFloor = 0.5;
        private double staminaPickRange = 0.5;
        /** Post-match fitness loss damping: actualLoss = inMatchLoss * dampening. */
        private double postMatchDampening = 0.7;
        /** Minimum fitness floor after a match (never drops below). */
        private double postMatchFloor = 20.0;
        /** Start-of-match stamina clamping. */
        private double startStaminaMin = 20.0;
        private double startStaminaMax = 100.0;
        /** Fitness lost by each player who played an instant/batch match (the live
         *  engine uses the per-minute model instead). Recovered via training; never
         *  drops below {@link #postMatchFloor}. */
        private double batchMatchFitnessDrain = 8.0;

        public double getBaseCostPerMinute() { return baseCostPerMinute; }
        public void setBaseCostPerMinute(double v) { this.baseCostPerMinute = v; }
        public double getMinAttributeMultiplier() { return minAttributeMultiplier; }
        public void setMinAttributeMultiplier(double v) { this.minAttributeMultiplier = v; }
        public double getPaceDiscountOnTempo() { return paceDiscountOnTempo; }
        public void setPaceDiscountOnTempo(double v) { this.paceDiscountOnTempo = v; }
        public double getNaturalFitnessRecoveryMax() { return naturalFitnessRecoveryMax; }
        public void setNaturalFitnessRecoveryMax(double v) { this.naturalFitnessRecoveryMax = v; }
        public double getPositionGoalkeeper() { return positionGoalkeeper; }
        public void setPositionGoalkeeper(double v) { this.positionGoalkeeper = v; }
        public double getPositionDefender() { return positionDefender; }
        public void setPositionDefender(double v) { this.positionDefender = v; }
        public double getPositionDefMid() { return positionDefMid; }
        public void setPositionDefMid(double v) { this.positionDefMid = v; }
        public double getPositionMidfielder() { return positionMidfielder; }
        public void setPositionMidfielder(double v) { this.positionMidfielder = v; }
        public double getPositionAttMid() { return positionAttMid; }
        public void setPositionAttMid(double v) { this.positionAttMid = v; }
        public double getPositionStriker() { return positionStriker; }
        public void setPositionStriker(double v) { this.positionStriker = v; }
        public double getStaminaPickFloor() { return staminaPickFloor; }
        public void setStaminaPickFloor(double v) { this.staminaPickFloor = v; }
        public double getStaminaPickRange() { return staminaPickRange; }
        public void setStaminaPickRange(double v) { this.staminaPickRange = v; }
        public double getPostMatchDampening() { return postMatchDampening; }
        public void setPostMatchDampening(double v) { this.postMatchDampening = v; }
        public double getPostMatchFloor() { return postMatchFloor; }
        public void setPostMatchFloor(double v) { this.postMatchFloor = v; }
        public double getStartStaminaMin() { return startStaminaMin; }
        public void setStartStaminaMin(double v) { this.startStaminaMin = v; }
        public double getStartStaminaMax() { return startStaminaMax; }
        public void setStartStaminaMax(double v) { this.startStaminaMax = v; }
        public double getBatchMatchFitnessDrain() { return batchMatchFitnessDrain; }
        public void setBatchMatchFitnessDrain(double v) { this.batchMatchFitnessDrain = v; }
    }

    // ==================== INJURIES ====================
    public static class Injuries {
        /** Base per-player per-match injury chance. */
        private double baseChance = 0.002;
        /** Additional chance when fitness < lowFitnessThreshold. */
        private double lowFitnessBonus = 0.001;
        private double lowFitnessThreshold = 50.0;
        /** Cumulative severity probabilities (minor → moderate → serious). */
        private double minorThreshold = 0.55;
        private double moderateThreshold = 0.85;
        /** Recovery days per severity band. */
        private int minorMinDays = 1;
        private int minorMaxDays = 3;
        private int moderateMinDays = 4;
        private int moderateMaxDays = 8;
        private int seriousMinDays = 10;
        private int seriousMaxDays = 20;

        public double getBaseChance() { return baseChance; }
        public void setBaseChance(double v) { this.baseChance = v; }
        public double getLowFitnessBonus() { return lowFitnessBonus; }
        public void setLowFitnessBonus(double v) { this.lowFitnessBonus = v; }
        public double getLowFitnessThreshold() { return lowFitnessThreshold; }
        public void setLowFitnessThreshold(double v) { this.lowFitnessThreshold = v; }
        public double getMinorThreshold() { return minorThreshold; }
        public void setMinorThreshold(double v) { this.minorThreshold = v; }
        public double getModerateThreshold() { return moderateThreshold; }
        public void setModerateThreshold(double v) { this.moderateThreshold = v; }
        public int getMinorMinDays() { return minorMinDays; }
        public void setMinorMinDays(int v) { this.minorMinDays = v; }
        public int getMinorMaxDays() { return minorMaxDays; }
        public void setMinorMaxDays(int v) { this.minorMaxDays = v; }
        public int getModerateMinDays() { return moderateMinDays; }
        public void setModerateMinDays(int v) { this.moderateMinDays = v; }
        public int getModerateMaxDays() { return moderateMaxDays; }
        public void setModerateMaxDays(int v) { this.moderateMaxDays = v; }
        public int getSeriousMinDays() { return seriousMinDays; }
        public void setSeriousMinDays(int v) { this.seriousMinDays = v; }
        public int getSeriousMaxDays() { return seriousMaxDays; }
        public void setSeriousMaxDays(int v) { this.seriousMaxDays = v; }
    }

    // ==================== FOULS / CARDS ====================
    public static class Fouls {
        /** Synthetic yellow cards per team in non-live engine: nextInt(0..max). */
        private int syntheticMaxYellowCardsPerTeam = 5;
        /** Per-match red-card chance (non-live engine). */
        private double syntheticRedCardChance = 0.05;
        /** Red-card minute uniform range [min, max-1]. */
        private int syntheticRedCardMinMinute = 20;
        private int syntheticRedCardMaxMinute = 91;
        /** Live engine: yellow-card rate per foul. Drawn from [min, min+spread]. */
        private double liveYellowCardRateMin = 0.14;
        private double liveYellowCardRateSpread = 0.08;
        /** Live engine: red-card base chance + foulCount multiplier. */
        private double liveRedCardBase = 0.005;
        private double liveRedCardPerFoul = 0.002;

        public int getSyntheticMaxYellowCardsPerTeam() { return syntheticMaxYellowCardsPerTeam; }
        public void setSyntheticMaxYellowCardsPerTeam(int v) { this.syntheticMaxYellowCardsPerTeam = v; }
        public double getSyntheticRedCardChance() { return syntheticRedCardChance; }
        public void setSyntheticRedCardChance(double v) { this.syntheticRedCardChance = v; }
        public int getSyntheticRedCardMinMinute() { return syntheticRedCardMinMinute; }
        public void setSyntheticRedCardMinMinute(int v) { this.syntheticRedCardMinMinute = v; }
        public int getSyntheticRedCardMaxMinute() { return syntheticRedCardMaxMinute; }
        public void setSyntheticRedCardMaxMinute(int v) { this.syntheticRedCardMaxMinute = v; }
        public double getLiveYellowCardRateMin() { return liveYellowCardRateMin; }
        public void setLiveYellowCardRateMin(double v) { this.liveYellowCardRateMin = v; }
        public double getLiveYellowCardRateSpread() { return liveYellowCardRateSpread; }
        public void setLiveYellowCardRateSpread(double v) { this.liveYellowCardRateSpread = v; }
        public double getLiveRedCardBase() { return liveRedCardBase; }
        public void setLiveRedCardBase(double v) { this.liveRedCardBase = v; }
        public double getLiveRedCardPerFoul() { return liveRedCardPerFoul; }
        public void setLiveRedCardPerFoul(double v) { this.liveRedCardPerFoul = v; }
    }

    // ==================== LIVE ENGINE ====================
    public static class Live {
        /** Big chances per team: 0.5 + rawRatio*scale, capped at hardCap. */
        private double bigChancesBaseline = 0.5;
        private double bigChancesScale = 3.0;
        private int bigChancesHardCap = 4;
        /** Penalty share of play types in non-miss roll. */
        private double penaltyShare = 0.15;
        /** Free-kick share of play types in non-miss roll. */
        private double freeKickShare = 0.35;
        /** Attacker pick: 0.8 + 0.4*(pace/20). */
        private double attackerPaceFloor = 0.8;
        private double attackerPaceRange = 0.4;
        /** Fouler pick: 1.2 - pace/20 (slower players foul more). */
        private double foulerPaceInverseBase = 1.2;
        /** Earliest minute a fatigue sub may fire. */
        private int subEarliestMinute = 35;
        /** Minute boundaries that change the stamina threshold for fatigue subs. */
        private int subMinuteBoundary2 = 55;
        private int subMinuteBoundary3 = 70;
        private int subMinuteBoundary4 = 80;
        /** Stamina thresholds per phase ("sub-if-tired-below"). */
        private double subStaminaPhase2 = 60.0;
        private double subStaminaPhase3 = 70.0;
        private double subStaminaPhase4 = 78.0;
        private double subStaminaPhase5 = 85.0;
        /** Tactical sub triggers (minute / goal-diff). */
        private int subOffensiveMinute = 75;
        private int subOffensiveGoalDiff = -2;   // own - opp ≤ this triggers OFFENSIVE
        private int subDefensiveMinute = 80;
        private int subDefensiveGoalDiff = 1;    // own - opp ≥ this triggers DEFENSIVE
        /** Score multipliers for stamina vs pace inside attacker / fouler picks. */
        private double staminaFactorFloor = 0.5;
        private double staminaFactorRange = 0.5;

        public double getBigChancesBaseline() { return bigChancesBaseline; }
        public void setBigChancesBaseline(double v) { this.bigChancesBaseline = v; }
        public double getBigChancesScale() { return bigChancesScale; }
        public void setBigChancesScale(double v) { this.bigChancesScale = v; }
        public int getBigChancesHardCap() { return bigChancesHardCap; }
        public void setBigChancesHardCap(int v) { this.bigChancesHardCap = v; }
        public double getPenaltyShare() { return penaltyShare; }
        public void setPenaltyShare(double v) { this.penaltyShare = v; }
        public double getFreeKickShare() { return freeKickShare; }
        public void setFreeKickShare(double v) { this.freeKickShare = v; }
        public double getAttackerPaceFloor() { return attackerPaceFloor; }
        public void setAttackerPaceFloor(double v) { this.attackerPaceFloor = v; }
        public double getAttackerPaceRange() { return attackerPaceRange; }
        public void setAttackerPaceRange(double v) { this.attackerPaceRange = v; }
        public double getFoulerPaceInverseBase() { return foulerPaceInverseBase; }
        public void setFoulerPaceInverseBase(double v) { this.foulerPaceInverseBase = v; }
        public int getSubEarliestMinute() { return subEarliestMinute; }
        public void setSubEarliestMinute(int v) { this.subEarliestMinute = v; }
        public int getSubMinuteBoundary2() { return subMinuteBoundary2; }
        public void setSubMinuteBoundary2(int v) { this.subMinuteBoundary2 = v; }
        public int getSubMinuteBoundary3() { return subMinuteBoundary3; }
        public void setSubMinuteBoundary3(int v) { this.subMinuteBoundary3 = v; }
        public int getSubMinuteBoundary4() { return subMinuteBoundary4; }
        public void setSubMinuteBoundary4(int v) { this.subMinuteBoundary4 = v; }
        public double getSubStaminaPhase2() { return subStaminaPhase2; }
        public void setSubStaminaPhase2(double v) { this.subStaminaPhase2 = v; }
        public double getSubStaminaPhase3() { return subStaminaPhase3; }
        public void setSubStaminaPhase3(double v) { this.subStaminaPhase3 = v; }
        public double getSubStaminaPhase4() { return subStaminaPhase4; }
        public void setSubStaminaPhase4(double v) { this.subStaminaPhase4 = v; }
        public double getSubStaminaPhase5() { return subStaminaPhase5; }
        public void setSubStaminaPhase5(double v) { this.subStaminaPhase5 = v; }
        public int getSubOffensiveMinute() { return subOffensiveMinute; }
        public void setSubOffensiveMinute(int v) { this.subOffensiveMinute = v; }
        public int getSubOffensiveGoalDiff() { return subOffensiveGoalDiff; }
        public void setSubOffensiveGoalDiff(int v) { this.subOffensiveGoalDiff = v; }
        public int getSubDefensiveMinute() { return subDefensiveMinute; }
        public void setSubDefensiveMinute(int v) { this.subDefensiveMinute = v; }
        public int getSubDefensiveGoalDiff() { return subDefensiveGoalDiff; }
        public void setSubDefensiveGoalDiff(int v) { this.subDefensiveGoalDiff = v; }
        public double getStaminaFactorFloor() { return staminaFactorFloor; }
        public void setStaminaFactorFloor(double v) { this.staminaFactorFloor = v; }
        public double getStaminaFactorRange() { return staminaFactorRange; }
        public void setStaminaFactorRange(double v) { this.staminaFactorRange = v; }
    }

    // ==================== EVENTS GENERATION ====================
    public static class Events {
        /** Goal minute uniform range [min, max-1]. */
        private int goalMinuteMin = 1;
        private int goalMinuteMax = 91;
        /** Probability of generating an assist event for a non-penalty goal. */
        private double assistProbability = 0.7;
        /** Number of substitutions to generate per team in non-live engine. */
        private int substitutionsPerTeam = 3;
        /** Substitution minute uniform range [min, max-1]. */
        private int substitutionMinuteMin = 46;
        private int substitutionMinuteMax = 86;
        /** Yellow card minute uniform range [min, max-1] (synthetic engine). */
        private int yellowCardMinuteMin = 1;
        private int yellowCardMinuteMax = 91;
        /** Max sub insertions per match (excl) when building possible-scorers list. */
        private int subInsertionsExclusiveMax = 6;

        public int getGoalMinuteMin() { return goalMinuteMin; }
        public void setGoalMinuteMin(int v) { this.goalMinuteMin = v; }
        public int getGoalMinuteMax() { return goalMinuteMax; }
        public void setGoalMinuteMax(int v) { this.goalMinuteMax = v; }
        public double getAssistProbability() { return assistProbability; }
        public void setAssistProbability(double v) { this.assistProbability = v; }
        public int getSubstitutionsPerTeam() { return substitutionsPerTeam; }
        public void setSubstitutionsPerTeam(int v) { this.substitutionsPerTeam = v; }
        public int getSubstitutionMinuteMin() { return substitutionMinuteMin; }
        public void setSubstitutionMinuteMin(int v) { this.substitutionMinuteMin = v; }
        public int getSubstitutionMinuteMax() { return substitutionMinuteMax; }
        public void setSubstitutionMinuteMax(int v) { this.substitutionMinuteMax = v; }
        public int getYellowCardMinuteMin() { return yellowCardMinuteMin; }
        public void setYellowCardMinuteMin(int v) { this.yellowCardMinuteMin = v; }
        public int getYellowCardMinuteMax() { return yellowCardMinuteMax; }
        public void setYellowCardMinuteMax(int v) { this.yellowCardMinuteMax = v; }
        public int getSubInsertionsExclusiveMax() { return subInsertionsExclusiveMax; }
        public void setSubInsertionsExclusiveMax(int v) { this.subInsertionsExclusiveMax = v; }
    }

    // ==================== MATCH RATINGS ====================
    public static class Ratings {
        /** Baseline match rating before contributions. */
        private double base = 6.0;
        /** Per-goal rating delta and cap. */
        private double perGoal = 1.0;
        private double goalContributionMax = 3.0;
        /** Per-assist rating delta and cap. */
        private double perAssist = 0.5;
        private double assistContributionMax = 1.5;
        /** Clean-sheet bonus for GK/DC/DL/DR. */
        private double cleanSheetBonus = 0.5;
        /** Result bonuses applied per outcome. */
        private double winBonus = 0.3;
        private double lossPenalty = -0.3;
        /** Substitute baseline penalty (off the bench). */
        private double substitutePenalty = -0.2;
        /** Gaussian σ of random per-player variance. */
        private double varianceSigma = 0.4;
        /** Hard clamps. */
        private double min = 1.0;
        private double max = 10.0;

        public double getBase() { return base; }
        public void setBase(double v) { this.base = v; }
        public double getPerGoal() { return perGoal; }
        public void setPerGoal(double v) { this.perGoal = v; }
        public double getGoalContributionMax() { return goalContributionMax; }
        public void setGoalContributionMax(double v) { this.goalContributionMax = v; }
        public double getPerAssist() { return perAssist; }
        public void setPerAssist(double v) { this.perAssist = v; }
        public double getAssistContributionMax() { return assistContributionMax; }
        public void setAssistContributionMax(double v) { this.assistContributionMax = v; }
        public double getCleanSheetBonus() { return cleanSheetBonus; }
        public void setCleanSheetBonus(double v) { this.cleanSheetBonus = v; }
        public double getWinBonus() { return winBonus; }
        public void setWinBonus(double v) { this.winBonus = v; }
        public double getLossPenalty() { return lossPenalty; }
        public void setLossPenalty(double v) { this.lossPenalty = v; }
        public double getSubstitutePenalty() { return substitutePenalty; }
        public void setSubstitutePenalty(double v) { this.substitutePenalty = v; }
        public double getVarianceSigma() { return varianceSigma; }
        public void setVarianceSigma(double v) { this.varianceSigma = v; }
        public double getMin() { return min; }
        public void setMin(double v) { this.min = v; }
        public double getMax() { return max; }
        public void setMax(double v) { this.max = v; }
    }

    // ==================== REPUTATION ====================
    public static class Reputation {
        /** strengthFactor = clamp(1.0 + repDiff/divisor, clampMin, clampMax). */
        private double strengthFactorDivisor = 50.0;
        private double strengthFactorMin = 0.2;
        private double strengthFactorMax = 5.0;
        /** Threshold to qualify as "shocking" win/loss (vs much-stronger/weaker opp). */
        private double shockingThreshold = 50.0;
        /** Win rep change base values. Kept small so a normal win moves reputation
         *  by well under 1% — big jumps are reserved for trophies (season-end). */
        private double winShockingBase = 2.0;
        private double winExpectedBase = 0.3;
        /** Draw rep change values. */
        private double drawFavoredOpp = 0.15;
        private double drawDisfavoredOpp = -0.15;
        /** Loss rep change base values. */
        private double lossShockingBase = -1.5;
        private double lossExpectedBase = -0.3;

        public double getStrengthFactorDivisor() { return strengthFactorDivisor; }
        public void setStrengthFactorDivisor(double v) { this.strengthFactorDivisor = v; }
        public double getStrengthFactorMin() { return strengthFactorMin; }
        public void setStrengthFactorMin(double v) { this.strengthFactorMin = v; }
        public double getStrengthFactorMax() { return strengthFactorMax; }
        public void setStrengthFactorMax(double v) { this.strengthFactorMax = v; }
        public double getShockingThreshold() { return shockingThreshold; }
        public void setShockingThreshold(double v) { this.shockingThreshold = v; }
        public double getWinShockingBase() { return winShockingBase; }
        public void setWinShockingBase(double v) { this.winShockingBase = v; }
        public double getWinExpectedBase() { return winExpectedBase; }
        public void setWinExpectedBase(double v) { this.winExpectedBase = v; }
        public double getDrawFavoredOpp() { return drawFavoredOpp; }
        public void setDrawFavoredOpp(double v) { this.drawFavoredOpp = v; }
        public double getDrawDisfavoredOpp() { return drawDisfavoredOpp; }
        public void setDrawDisfavoredOpp(double v) { this.drawDisfavoredOpp = v; }
        public double getLossShockingBase() { return lossShockingBase; }
        public void setLossShockingBase(double v) { this.lossShockingBase = v; }
        public double getLossExpectedBase() { return lossExpectedBase; }
        public void setLossExpectedBase(double v) { this.lossExpectedBase = v; }
    }

    // ==================== STATS GENERATION ====================
    public static class Stats {
        /** Base possession with home boost: 50 + edge*30 + (home?2:0) ± sigma. */
        private double possessionBase = 50.0;
        private double possessionEdgeScale = 30.0;
        private double possessionNoiseSigma = 2.0;
        private double homePossessionBoost = 2.0;
        /** Pass count: base + ratio scaling ± sigma. */
        private double passesBase = 450.0;
        private double passesNoiseSigma = 20.0;
        /** Pass accuracy: 78 + edge*30 ± sigma + tactical bonus. */
        private double passAccuracyBase = 78.0;
        private double passAccuracyEdgeScale = 30.0;
        private double passAccuracyNoiseSigma = 1.5;
        private double passAccuracyKeepBallBonus = 3.0;
        private double passAccuracyLongBallPenalty = -5.0;
        /** Shots: base + edge*scale + goals*goalsBonus. */
        private double shotsBase = 6.0;
        private double shotsEdgeScale = 16.0;
        private double shotsGoalsBonus = 1.5;
        /** Shots-on-target rate: base ± edge*span ± noise. */
        private double shotsOnTargetBase = 0.40;
        private double shotsOnTargetEdgeSpan = 0.10;
        private double shotsOnTargetNoise = 0.08;
        /** Blocked shots fraction: base + noiseSpan*rng. */
        private double blockedShotsBase = 0.22;
        private double blockedShotsNoiseSpan = 0.08;
        /** Corners: base + shots*coefficient ± noise. */
        private double cornersBase = 3.0;
        private double cornersPerShot = 0.3;
        private double cornersNoise = 1.0;
        /** Fouls: base ± edge*span (defensive team higher). */
        private double foulsBase = 12.0;
        private double foulsEdgeSpan = 8.0;
        /** Offsides: base + (ratio - threshold) * scale. */
        private double offsidesBase = 1.5;
        private double offsidesPivotRatio = 0.3;
        private double offsidesScale = 4.0;
        /** Tackles: base + (50 - possession) * coefficient. */
        private double tacklesBase = 18.0;
        private double tacklesPossessionCoefficient = 0.25;
        /** Interceptions: base + (50 - possession) * coefficient. */
        private double interceptionsBase = 11.0;
        private double interceptionsPossessionCoefficient = 0.18;
        /** Clearances: base + (50 - possession) * coefficient + shots * shotBonus. */
        private double clearancesBase = 18.0;
        private double clearancesPossessionCoefficient = 0.2;
        private double clearancesShotBonus = 0.5;
        /** Duels base + noise. */
        private double duelsBase = 55.0;
        private double duelsNoise = 4.0;
        /** Duel win % base + edge*scale ± noise. */
        private double duelWinBase = 0.50;
        private double duelWinEdgeScale = 0.35;
        private double duelWinNoise = 0.025;
        /** Aerial duels won: base + edge*scale ± noise. */
        private double aerialDuelsBase = 14.0;
        private double aerialDuelsEdgeScale = 8.0;
        private double aerialDuelsNoise = 1.5;
        /** Cross accuracy: base + edge*scale + noise. */
        private double crossAccuracyBase = 0.28;
        private double crossAccuracyEdgeScale = 0.08;
        private double crossAccuracyNoise = 0.08;
        /** Big chances = max(homeGoals, clamp((int)(homeSoT * ratio ...))). */
        private double bigChancesSoTRatio = 0.6;
        /** xG weights for big chance / SoT-miss / wide shot. */
        private double xgPerBigChance = 0.35;
        private double xgPerSotMiss = 0.12;
        private double xgPerWideShot = 0.05;
        /** Tactical possession bonus deltas (applied in getTacticalPossessionBonus). */
        private double tacticalPossessionKeepBall = 5.0;
        private double tacticalPossessionFreeBallEarly = -3.0;
        private double tacticalPossessionShortPassing = 3.0;
        private double tacticalPossessionLongBall = -4.0;
        private double tacticalPossessionTempoLow = 2.0;
        private double tacticalPossessionTempoHigh = -1.0;
        /** Attacking-mentality shot bonus per mentality tier. */
        private double shotBonusVeryAttacking = 4.0;
        private double shotBonusAttacking = 2.0;
        private double shotBonusDefensive = -2.0;
        private double shotBonusVeryDefensive = -4.0;
        /** "Very Defensive" mentality also adds extra fouls per side. */
        private double veryDefensiveFoulBonus = 3.0;

        public double getPossessionBase() { return possessionBase; }
        public void setPossessionBase(double v) { this.possessionBase = v; }
        public double getPossessionEdgeScale() { return possessionEdgeScale; }
        public void setPossessionEdgeScale(double v) { this.possessionEdgeScale = v; }
        public double getPossessionNoiseSigma() { return possessionNoiseSigma; }
        public void setPossessionNoiseSigma(double v) { this.possessionNoiseSigma = v; }
        public double getHomePossessionBoost() { return homePossessionBoost; }
        public void setHomePossessionBoost(double v) { this.homePossessionBoost = v; }
        public double getPassesBase() { return passesBase; }
        public void setPassesBase(double v) { this.passesBase = v; }
        public double getPassesNoiseSigma() { return passesNoiseSigma; }
        public void setPassesNoiseSigma(double v) { this.passesNoiseSigma = v; }
        public double getPassAccuracyBase() { return passAccuracyBase; }
        public void setPassAccuracyBase(double v) { this.passAccuracyBase = v; }
        public double getPassAccuracyEdgeScale() { return passAccuracyEdgeScale; }
        public void setPassAccuracyEdgeScale(double v) { this.passAccuracyEdgeScale = v; }
        public double getPassAccuracyNoiseSigma() { return passAccuracyNoiseSigma; }
        public void setPassAccuracyNoiseSigma(double v) { this.passAccuracyNoiseSigma = v; }
        public double getPassAccuracyKeepBallBonus() { return passAccuracyKeepBallBonus; }
        public void setPassAccuracyKeepBallBonus(double v) { this.passAccuracyKeepBallBonus = v; }
        public double getPassAccuracyLongBallPenalty() { return passAccuracyLongBallPenalty; }
        public void setPassAccuracyLongBallPenalty(double v) { this.passAccuracyLongBallPenalty = v; }
        public double getShotsBase() { return shotsBase; }
        public void setShotsBase(double v) { this.shotsBase = v; }
        public double getShotsEdgeScale() { return shotsEdgeScale; }
        public void setShotsEdgeScale(double v) { this.shotsEdgeScale = v; }
        public double getShotsGoalsBonus() { return shotsGoalsBonus; }
        public void setShotsGoalsBonus(double v) { this.shotsGoalsBonus = v; }
        public double getShotsOnTargetBase() { return shotsOnTargetBase; }
        public void setShotsOnTargetBase(double v) { this.shotsOnTargetBase = v; }
        public double getShotsOnTargetEdgeSpan() { return shotsOnTargetEdgeSpan; }
        public void setShotsOnTargetEdgeSpan(double v) { this.shotsOnTargetEdgeSpan = v; }
        public double getShotsOnTargetNoise() { return shotsOnTargetNoise; }
        public void setShotsOnTargetNoise(double v) { this.shotsOnTargetNoise = v; }
        public double getBlockedShotsBase() { return blockedShotsBase; }
        public void setBlockedShotsBase(double v) { this.blockedShotsBase = v; }
        public double getBlockedShotsNoiseSpan() { return blockedShotsNoiseSpan; }
        public void setBlockedShotsNoiseSpan(double v) { this.blockedShotsNoiseSpan = v; }
        public double getCornersBase() { return cornersBase; }
        public void setCornersBase(double v) { this.cornersBase = v; }
        public double getCornersPerShot() { return cornersPerShot; }
        public void setCornersPerShot(double v) { this.cornersPerShot = v; }
        public double getCornersNoise() { return cornersNoise; }
        public void setCornersNoise(double v) { this.cornersNoise = v; }
        public double getFoulsBase() { return foulsBase; }
        public void setFoulsBase(double v) { this.foulsBase = v; }
        public double getFoulsEdgeSpan() { return foulsEdgeSpan; }
        public void setFoulsEdgeSpan(double v) { this.foulsEdgeSpan = v; }
        public double getOffsidesBase() { return offsidesBase; }
        public void setOffsidesBase(double v) { this.offsidesBase = v; }
        public double getOffsidesPivotRatio() { return offsidesPivotRatio; }
        public void setOffsidesPivotRatio(double v) { this.offsidesPivotRatio = v; }
        public double getOffsidesScale() { return offsidesScale; }
        public void setOffsidesScale(double v) { this.offsidesScale = v; }
        public double getTacklesBase() { return tacklesBase; }
        public void setTacklesBase(double v) { this.tacklesBase = v; }
        public double getTacklesPossessionCoefficient() { return tacklesPossessionCoefficient; }
        public void setTacklesPossessionCoefficient(double v) { this.tacklesPossessionCoefficient = v; }
        public double getInterceptionsBase() { return interceptionsBase; }
        public void setInterceptionsBase(double v) { this.interceptionsBase = v; }
        public double getInterceptionsPossessionCoefficient() { return interceptionsPossessionCoefficient; }
        public void setInterceptionsPossessionCoefficient(double v) { this.interceptionsPossessionCoefficient = v; }
        public double getClearancesBase() { return clearancesBase; }
        public void setClearancesBase(double v) { this.clearancesBase = v; }
        public double getClearancesPossessionCoefficient() { return clearancesPossessionCoefficient; }
        public void setClearancesPossessionCoefficient(double v) { this.clearancesPossessionCoefficient = v; }
        public double getClearancesShotBonus() { return clearancesShotBonus; }
        public void setClearancesShotBonus(double v) { this.clearancesShotBonus = v; }
        public double getDuelsBase() { return duelsBase; }
        public void setDuelsBase(double v) { this.duelsBase = v; }
        public double getDuelsNoise() { return duelsNoise; }
        public void setDuelsNoise(double v) { this.duelsNoise = v; }
        public double getDuelWinBase() { return duelWinBase; }
        public void setDuelWinBase(double v) { this.duelWinBase = v; }
        public double getDuelWinEdgeScale() { return duelWinEdgeScale; }
        public void setDuelWinEdgeScale(double v) { this.duelWinEdgeScale = v; }
        public double getDuelWinNoise() { return duelWinNoise; }
        public void setDuelWinNoise(double v) { this.duelWinNoise = v; }
        public double getAerialDuelsBase() { return aerialDuelsBase; }
        public void setAerialDuelsBase(double v) { this.aerialDuelsBase = v; }
        public double getAerialDuelsEdgeScale() { return aerialDuelsEdgeScale; }
        public void setAerialDuelsEdgeScale(double v) { this.aerialDuelsEdgeScale = v; }
        public double getAerialDuelsNoise() { return aerialDuelsNoise; }
        public void setAerialDuelsNoise(double v) { this.aerialDuelsNoise = v; }
        public double getCrossAccuracyBase() { return crossAccuracyBase; }
        public void setCrossAccuracyBase(double v) { this.crossAccuracyBase = v; }
        public double getCrossAccuracyEdgeScale() { return crossAccuracyEdgeScale; }
        public void setCrossAccuracyEdgeScale(double v) { this.crossAccuracyEdgeScale = v; }
        public double getCrossAccuracyNoise() { return crossAccuracyNoise; }
        public void setCrossAccuracyNoise(double v) { this.crossAccuracyNoise = v; }
        public double getBigChancesSoTRatio() { return bigChancesSoTRatio; }
        public void setBigChancesSoTRatio(double v) { this.bigChancesSoTRatio = v; }
        public double getXgPerBigChance() { return xgPerBigChance; }
        public void setXgPerBigChance(double v) { this.xgPerBigChance = v; }
        public double getXgPerSotMiss() { return xgPerSotMiss; }
        public void setXgPerSotMiss(double v) { this.xgPerSotMiss = v; }
        public double getXgPerWideShot() { return xgPerWideShot; }
        public void setXgPerWideShot(double v) { this.xgPerWideShot = v; }
        public double getTacticalPossessionKeepBall() { return tacticalPossessionKeepBall; }
        public void setTacticalPossessionKeepBall(double v) { this.tacticalPossessionKeepBall = v; }
        public double getTacticalPossessionFreeBallEarly() { return tacticalPossessionFreeBallEarly; }
        public void setTacticalPossessionFreeBallEarly(double v) { this.tacticalPossessionFreeBallEarly = v; }
        public double getTacticalPossessionShortPassing() { return tacticalPossessionShortPassing; }
        public void setTacticalPossessionShortPassing(double v) { this.tacticalPossessionShortPassing = v; }
        public double getTacticalPossessionLongBall() { return tacticalPossessionLongBall; }
        public void setTacticalPossessionLongBall(double v) { this.tacticalPossessionLongBall = v; }
        public double getTacticalPossessionTempoLow() { return tacticalPossessionTempoLow; }
        public void setTacticalPossessionTempoLow(double v) { this.tacticalPossessionTempoLow = v; }
        public double getTacticalPossessionTempoHigh() { return tacticalPossessionTempoHigh; }
        public void setTacticalPossessionTempoHigh(double v) { this.tacticalPossessionTempoHigh = v; }
        public double getShotBonusVeryAttacking() { return shotBonusVeryAttacking; }
        public void setShotBonusVeryAttacking(double v) { this.shotBonusVeryAttacking = v; }
        public double getShotBonusAttacking() { return shotBonusAttacking; }
        public void setShotBonusAttacking(double v) { this.shotBonusAttacking = v; }
        public double getShotBonusDefensive() { return shotBonusDefensive; }
        public void setShotBonusDefensive(double v) { this.shotBonusDefensive = v; }
        public double getShotBonusVeryDefensive() { return shotBonusVeryDefensive; }
        public void setShotBonusVeryDefensive(double v) { this.shotBonusVeryDefensive = v; }
        public double getVeryDefensiveFoulBonus() { return veryDefensiveFoulBonus; }
        public void setVeryDefensiveFoulBonus(double v) { this.veryDefensiveFoulBonus = v; }
    }

    // ==================== KNOCKOUT ====================
    public static class Knockout {
        /** AET win chance baseline + power-share weight: base + weight * (myPower / totalPower).
         *  Used by the legacy single-shot tiebreak in {@code MatchRoundSimulator}. */
        private double aetWinChanceBase = 0.35;
        private double aetWinChanceWeight = 0.3;

        /** Expected total goals for the 30-minute extra-time "mini match" played when a
         *  tie is level after normal time (single-leg) or on aggregate (two-leg).
         *  Much lower than the full-match {@code power.expectedGoalsTotal} (default 3.0)
         *  because extra time is only 30 minutes (two 15-minute halves). */
        private double extraTimeExpectedGoals = 1.0;

        /** Probability the WEAKER team wins a penalty shootout. Penalties are modelled as
         *  a near-coin-flip: 0.5 = pure 50/50; 0.55 gives the weaker side a slight edge
         *  (shootouts are a lottery that levels the playing field). */
        private double penaltyWeakerTeamWinChance = 0.5;

        // NOTE: which knockout rounds are two-leg is tournament *structure*, not scoring —
        // it lives in CompetitionFormat / CompetitionFormatConfig (the format single-source).

        public double getAetWinChanceBase() { return aetWinChanceBase; }
        public void setAetWinChanceBase(double v) { this.aetWinChanceBase = v; }
        public double getAetWinChanceWeight() { return aetWinChanceWeight; }
        public void setAetWinChanceWeight(double v) { this.aetWinChanceWeight = v; }
        public double getExtraTimeExpectedGoals() { return extraTimeExpectedGoals; }
        public void setExtraTimeExpectedGoals(double v) { this.extraTimeExpectedGoals = v; }
        public double getPenaltyWeakerTeamWinChance() { return penaltyWeakerTeamWinChance; }
        public void setPenaltyWeakerTeamWinChance(double v) { this.penaltyWeakerTeamWinChance = v; }
    }

    // ==================== TRAINING (facility-scaled development) ====================
    public static class Training {
        /** Facility development factor: base + level / divisor. Level is the club's
         *  TeamFacilities training level (1..20) chosen by age (youth vs senior).
         *  Defaults (0.5, 20) reproduce the previous hardcoded {@code 0.5 + level/20}
         *  → factor 0.55 (level 1) .. 1.5 (level 20). The factor scales positive
         *  attribute growth (decline is unaffected) and, when enabled, fitness gain. */
        private double facilityBase = 0.5;
        private double facilityDivisor = 20.0;
        /** Players aged ≤ this use the youth training facility level; older players use
         *  the senior training facility level. */
        private int youthMaxAge = 22;
        /** When true, per-session fitness recovery is also multiplied by the facility
         *  factor (better facilities recover condition faster). */
        private boolean scaleFitnessGain = true;

        public double getFacilityBase() { return facilityBase; }
        public void setFacilityBase(double v) { this.facilityBase = v; }
        public double getFacilityDivisor() { return facilityDivisor; }
        public void setFacilityDivisor(double v) { this.facilityDivisor = v; }
        public int getYouthMaxAge() { return youthMaxAge; }
        public void setYouthMaxAge(int v) { this.youthMaxAge = v; }
        public boolean isScaleFitnessGain() { return scaleFitnessGain; }
        public void setScaleFitnessGain(boolean v) { this.scaleFitnessGain = v; }
    }

    // ==================== PLAYER MATCH VALUE (weighted, config-driven) ====================
    /**
     * Drives {@code PlayerValueService} — the matchday team rating. A starter's match
     * value is {@code clamp(weightedAvgAttrs × scaleMultiplier, floor, ceil) × familiarity
     * × moraleFactor × fitnessFactor}. Team value = Σ over the 11 starters. This is the
     * single source of truth for the power fed to {@code calculateScores}.
     *
     * <p>Unlike {@code Human.rating} (the generic skill used only for squad-selection
     * sorting, transfer values and UI), this value is computed only at matchday and uses
     * per-position attribute weights so designers can express what matters per position.
     */
    public static class PlayerValue {
        /** weightedAvg of attrs (1..20) × this → the ~1..300 scale. Default 15 matches
         *  {@code PlayerSkillsService.computeOverallRating} so there is no scale shock. */
        private double scaleMultiplier = 15.0;
        private double ratingFloor = 1.0;
        private double ratingCeil = 300.0;

        /** Per-player morale factor: {@code 1.0 + (morale - moraleNeutral) × moraleSlope}. */
        private double moraleNeutral = 70.0;
        private double moraleSlope = 0.0004;
        /** Per-player fitness factor: {@code max(fitnessFloor, fitness / 100)}. */
        private double fitnessFloor = 0.7;

        /** Familiarity factor used when a (natural → used) position pair is absent from the matrix. */
        private double defaultFamiliarityPenalty = 0.5;

        /**
         * User OVERRIDES: position → (attributeName → weight 0..5). Attribute keys use the 36
         * {@code PlayerSkillsService.GETTER_MAP} display names ("First Touch", "Off The Ball", …).
         * Entries here win per-attribute over the shipped {@link #DEFAULT_WEIGHTS} profiles, so
         * designers only list the few weights they want to change. Set a weight to 0 to mark an
         * attribute irrelevant to that position.
         */
        private Map<String, Map<String, Double>> weights = new HashMap<>();

        /**
         * User OVERRIDES: natural base position → (used base position → factor in (0,1]). Entries
         * here win over the shipped {@link #DEFAULT_FAMILIARITY} matrix; missing pairs fall back
         * to the default matrix and then to {@link #defaultFamiliarityPenalty}.
         */
        private Map<String, Map<String, Double>> familiarityPenalty = new HashMap<>();

        /**
         * Resolved weight for (position, attribute):
         * user override → shipped default profile → 1.0. Within a defined default profile an
         * unlisted attribute is treated as irrelevant (0.0); positions with no profile are neutral (1.0).
         */
        public double weight(String position, String attribute) {
            Map<String, Double> override = weights.get(position);
            if (override != null && override.containsKey(attribute)) {
                return override.get(attribute);
            }
            Map<String, Double> profile = DEFAULT_WEIGHTS.get(position);
            if (profile != null) {
                return profile.getOrDefault(attribute, 0.0);
            }
            return 1.0;
        }

        /**
         * Resolved familiarity for (natural → used):
         * 1.0 on the natural position, else user override → shipped default matrix → defaultFamiliarityPenalty.
         */
        public double familiarity(String natural, String used) {
            if (natural != null && natural.equals(used)) return 1.0;
            Map<String, Double> override = familiarityPenalty.get(natural);
            if (override != null && override.containsKey(used)) {
                return override.get(used);
            }
            Map<String, Double> def = DEFAULT_FAMILIARITY.get(natural);
            if (def != null && def.containsKey(used)) {
                return def.get(used);
            }
            return defaultFamiliarityPenalty;
        }

        public double getScaleMultiplier() { return scaleMultiplier; }
        public void setScaleMultiplier(double v) { this.scaleMultiplier = v; }
        public double getRatingFloor() { return ratingFloor; }
        public void setRatingFloor(double v) { this.ratingFloor = v; }
        public double getRatingCeil() { return ratingCeil; }
        public void setRatingCeil(double v) { this.ratingCeil = v; }
        public double getMoraleNeutral() { return moraleNeutral; }
        public void setMoraleNeutral(double v) { this.moraleNeutral = v; }
        public double getMoraleSlope() { return moraleSlope; }
        public void setMoraleSlope(double v) { this.moraleSlope = v; }
        public double getFitnessFloor() { return fitnessFloor; }
        public void setFitnessFloor(double v) { this.fitnessFloor = v; }
        public double getDefaultFamiliarityPenalty() { return defaultFamiliarityPenalty; }
        public void setDefaultFamiliarityPenalty(double v) { this.defaultFamiliarityPenalty = v; }
        public Map<String, Map<String, Double>> getWeights() { return weights; }
        public void setWeights(Map<String, Map<String, Double>> v) { this.weights = v; }
        public Map<String, Map<String, Double>> getFamiliarityPenalty() { return familiarityPenalty; }
        public void setFamiliarityPenalty(Map<String, Map<String, Double>> v) { this.familiarityPenalty = v; }

        // ---- Shipped defaults (football-informed starting point; tune via the override maps) ----

        /** Build a sparse weight profile from alternating (attributeName, weight) pairs. */
        private static Map<String, Double> prof(Object... kv) {
            Map<String, Double> m = new HashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
            }
            return m;
        }

        /**
         * Per-position attribute weights (0..5). Sparse: attributes not listed for a position are
         * treated as irrelevant (0.0) by {@link #weight}. DL/DR share the full-back profile and
         * ML/MR share the wide profile.
         */
        private static final Map<String, Map<String, Double>> DEFAULT_WEIGHTS = new HashMap<>();

        /** Per-position familiarity when used out of position (natural → used → factor in (0,1]). */
        private static final Map<String, Map<String, Double>> DEFAULT_FAMILIARITY = new HashMap<>();

        static {
            DEFAULT_WEIGHTS.put("GK", prof(
                    "Reflexes", 5, "Handling", 5, "One On Ones", 4, "Positioning", 4,
                    "Command Of Area", 4, "Anticipation", 4, "Concentration", 3, "Agility", 3,
                    "Composure", 3, "Decisions", 3, "Kicking", 3, "Throwing", 2, "First Touch", 2,
                    "Bravery", 2, "Jumping Reach", 2, "Passing", 1, "Vision", 1, "Determination", 1,
                    "Leadership", 1, "Balance", 1, "Strength", 1, "Natural Fitness", 1));

            DEFAULT_WEIGHTS.put("DC", prof(
                    "Tackling", 5, "Marking", 5, "Positioning", 5, "Heading", 4, "Strength", 4,
                    "Anticipation", 4, "Concentration", 4, "Bravery", 3, "Jumping Reach", 3,
                    "Composure", 3, "Decisions", 3, "Aggression", 2, "Pace", 2, "Acceleration", 2,
                    "Passing", 2, "Determination", 2, "Leadership", 2, "Teamwork", 2, "Work Rate", 1,
                    "Balance", 1, "Agility", 1, "Stamina", 1, "First Touch", 1, "Technique", 1,
                    "Natural Fitness", 1));

            Map<String, Double> fullBack = prof(
                    "Pace", 5, "Tackling", 4, "Marking", 4, "Positioning", 4, "Acceleration", 4,
                    "Stamina", 4, "Work Rate", 4, "Crossing", 4, "Anticipation", 3, "Concentration", 3,
                    "Teamwork", 3, "Decisions", 3, "Dribbling", 3, "Passing", 3, "Technique", 2,
                    "First Touch", 2, "Agility", 2, "Balance", 2, "Strength", 2, "Off The Ball", 2,
                    "Determination", 2, "Aggression", 1, "Heading", 1, "Natural Fitness", 1,
                    "Composure", 1, "Vision", 1);
            DEFAULT_WEIGHTS.put("DL", fullBack);
            DEFAULT_WEIGHTS.put("DR", fullBack);

            DEFAULT_WEIGHTS.put("MC", prof(
                    "Passing", 5, "Decisions", 4, "Vision", 4, "Teamwork", 4, "Work Rate", 4,
                    "Stamina", 4, "Technique", 3, "First Touch", 3, "Composure", 3, "Positioning", 3,
                    "Anticipation", 3, "Concentration", 3, "Tackling", 3, "Determination", 3,
                    "Dribbling", 2, "Off The Ball", 2, "Long Shots", 2, "Marking", 2, "Balance", 2,
                    "Agility", 2, "Strength", 2, "Flair", 2, "Leadership", 2, "Aggression", 1,
                    "Pace", 1, "Acceleration", 1));

            Map<String, Double> wide = prof(
                    "Crossing", 5, "Dribbling", 5, "Pace", 5, "Acceleration", 4, "Technique", 4,
                    "Off The Ball", 4, "Stamina", 4, "First Touch", 3, "Flair", 3, "Agility", 3,
                    "Work Rate", 3, "Passing", 3, "Decisions", 3, "Anticipation", 3, "Finishing", 3,
                    "Long Shots", 2, "Balance", 2, "Composure", 2, "Teamwork", 2, "Determination", 2,
                    "Vision", 2, "Marking", 1, "Tackling", 1, "Positioning", 1, "Strength", 1,
                    "Concentration", 1);
            DEFAULT_WEIGHTS.put("ML", wide);
            DEFAULT_WEIGHTS.put("MR", wide);

            DEFAULT_WEIGHTS.put("ST", prof(
                    "Finishing", 5, "Off The Ball", 5, "Composure", 4, "First Touch", 4,
                    "Anticipation", 4, "Heading", 4, "Pace", 4, "Acceleration", 4, "Dribbling", 3,
                    "Technique", 3, "Decisions", 3, "Strength", 3, "Flair", 3, "Balance", 3,
                    "Agility", 3, "Jumping Reach", 3, "Long Shots", 3, "Determination", 3,
                    "Bravery", 2, "Aggression", 2, "Teamwork", 2, "Work Rate", 2, "Passing", 2,
                    "Vision", 2, "Stamina", 2, "Concentration", 2, "Penalty Taking", 2));

            // Familiarity: goalkeepers are unusable outfield (and vice versa); within a line the
            // penalty is mild, across lines it grows, defender↔striker is the harshest.
            DEFAULT_FAMILIARITY.put("GK", prof(
                    "DL", 0.1, "DR", 0.1, "DC", 0.1, "MC", 0.1, "ML", 0.1, "MR", 0.1, "ST", 0.1));
            DEFAULT_FAMILIARITY.put("DC", prof(
                    "GK", 0.1, "DL", 0.7, "DR", 0.7, "MC", 0.6, "ML", 0.4, "MR", 0.4, "ST", 0.2));
            DEFAULT_FAMILIARITY.put("DL", prof(
                    "GK", 0.1, "DR", 0.85, "DC", 0.7, "ML", 0.75, "MR", 0.6, "MC", 0.55, "ST", 0.3));
            DEFAULT_FAMILIARITY.put("DR", prof(
                    "GK", 0.1, "DL", 0.85, "DC", 0.7, "MR", 0.75, "ML", 0.6, "MC", 0.55, "ST", 0.3));
            DEFAULT_FAMILIARITY.put("MC", prof(
                    "GK", 0.1, "ML", 0.7, "MR", 0.7, "DC", 0.6, "DL", 0.55, "DR", 0.55, "ST", 0.65));
            DEFAULT_FAMILIARITY.put("ML", prof(
                    "GK", 0.1, "MR", 0.8, "MC", 0.7, "DL", 0.75, "DR", 0.6, "ST", 0.7, "DC", 0.4));
            DEFAULT_FAMILIARITY.put("MR", prof(
                    "GK", 0.1, "ML", 0.8, "MC", 0.7, "DR", 0.75, "DL", 0.6, "ST", 0.7, "DC", 0.4));
            DEFAULT_FAMILIARITY.put("ST", prof(
                    "GK", 0.1, "ML", 0.7, "MR", 0.7, "MC", 0.6, "DC", 0.2, "DL", 0.3, "DR", 0.3));
        }
    }

    // ==================== ROLE SUITABILITY BLEND + ATTRIBUTE TABLES ====================
    /**
     * Tunable knobs for {@code PlayerRoleService}. Formula levers: effectiveRating =
     * overall × {@code overallBlend} + suitability × {@code roleBlend}; suitability =
     * clamp(weightedAvg × {@code suitabilityScale}, 1, 100).
     *
     * <p>The per-role attribute tables (the key attributes + weights of each role) ship as
     * defaults inside {@code PlayerRoleService.RoleDef}; {@link #attributes} lets designers
     * override or extend them per role from config — keyed by role name, then attribute name
     * (the 36 {@code PlayerSkillsService.GETTER_MAP} display names). An entry here wins over
     * the shipped default weight for that (role, attribute); a new attribute is added to the
     * role's table.
     */
    public static class RoleWeights {
        private double overallBlend = 0.4;
        private double roleBlend = 0.6;
        private double suitabilityScale = 5.0;
        private Map<String, Map<String, Double>> attributes = new HashMap<>();

        public double getOverallBlend() { return overallBlend; }
        public void setOverallBlend(double v) { this.overallBlend = v; }
        public double getRoleBlend() { return roleBlend; }
        public void setRoleBlend(double v) { this.roleBlend = v; }
        public double getSuitabilityScale() { return suitabilityScale; }
        public void setSuitabilityScale(double v) { this.suitabilityScale = v; }
        public Map<String, Map<String, Double>> getAttributes() { return attributes; }
        public void setAttributes(Map<String, Map<String, Double>> v) { this.attributes = v; }

        /** Config override map for a role's attribute weights, or {@code null} if none. */
        public Map<String, Double> attributesFor(String roleName) {
            return attributes.get(roleName);
        }
    }

    // ==================== INSTRUCTION MULTIPLIER ====================
    /**
     * Tunable knobs for {@code PlayerInstructionService.computeInstructionMultiplier}.
     * {@code bonusScale} globally scales the accumulated per-instruction bonus (1.0 = unchanged),
     * and the clamp bounds / conflict penalty are externalized.
     *
     * <p>The per-instruction bonus table ships as defaults in {@link #DEFAULT_BONUSES}; each
     * instruction has a {@code base} bonus plus per-position exceptions. {@link #bonuses} lets
     * designers override an instruction's whole bonus entry from config (keyed by instruction
     * name); a present override replaces the shipped default for that instruction.
     */
    public static class InstructionWeights {
        private double bonusScale = 1.0;
        private double conflictPenalty = 0.02;
        private double clampMin = 0.92;
        private double clampMax = 1.08;
        private Map<String, InstructionBonus> bonuses = new HashMap<>();
        /** Mutually-exclusive instruction pairs; each present pair subtracts {@link #conflictPenalty}.
         *  Defaults to the seven shipped pairs; set in config to replace the whole list (add/remove pairs). */
        private List<ConflictPair> conflicts = defaultConflicts();

        public double getBonusScale() { return bonusScale; }
        public void setBonusScale(double v) { this.bonusScale = v; }
        public double getConflictPenalty() { return conflictPenalty; }
        public void setConflictPenalty(double v) { this.conflictPenalty = v; }
        public double getClampMin() { return clampMin; }
        public void setClampMin(double v) { this.clampMin = v; }
        public double getClampMax() { return clampMax; }
        public void setClampMax(double v) { this.clampMax = v; }
        public Map<String, InstructionBonus> getBonuses() { return bonuses; }
        public void setBonuses(Map<String, InstructionBonus> v) { this.bonuses = v; }
        public List<ConflictPair> getConflicts() { return conflicts; }
        public void setConflicts(List<ConflictPair> v) { this.conflicts = v; }

        /** An ordered pair of mutually-exclusive instruction names. */
        public static class ConflictPair {
            private String a;
            private String b;

            public ConflictPair() {}
            public ConflictPair(String a, String b) { this.a = a; this.b = b; }

            public String getA() { return a; }
            public void setA(String v) { this.a = v; }
            public String getB() { return b; }
            public void setB(String v) { this.b = v; }
        }

        private static List<ConflictPair> defaultConflicts() {
            List<ConflictPair> c = new ArrayList<>();
            c.add(new ConflictPair("Close Down More", "Close Down Less"));
            c.add(new ConflictPair("Shoot More Often", "Shoot Less Often"));
            c.add(new ConflictPair("Dribble More", "Dribble Less"));
            c.add(new ConflictPair("Sit Narrower", "Stay Wider"));
            c.add(new ConflictPair("Cross From Byline", "Cross From Deep"));
            c.add(new ConflictPair("Tackle Harder", "Ease Off Tackles"));
            c.add(new ConflictPair("Get Further Forward", "Hold Position"));
            return c;
        }

        /**
         * Resolved bonus for an (instruction, position): config override → shipped default → 0.0.
         * Within an entry the position-specific value wins over the entry's {@code base}.
         */
        public double bonus(String instruction, String position) {
            InstructionBonus b = bonuses.get(instruction);
            if (b == null) b = DEFAULT_BONUSES.get(instruction);
            if (b == null) return 0.0;
            return b.valueFor(position);
        }

        /** A single instruction's bonus: a {@code base} plus per-position exceptions. */
        public static class InstructionBonus {
            private double base = 0.0;
            private Map<String, Double> byPosition = new HashMap<>();

            public double getBase() { return base; }
            public void setBase(double v) { this.base = v; }
            public Map<String, Double> getByPosition() { return byPosition; }
            public void setByPosition(Map<String, Double> v) { this.byPosition = v; }

            public double valueFor(String position) {
                Double v = byPosition.get(position);
                return v == null ? base : v;
            }
        }

        /** Build an InstructionBonus from a base and alternating (position, value) exceptions. */
        private static InstructionBonus bonus(double base, Object... kv) {
            InstructionBonus b = new InstructionBonus();
            b.base = base;
            for (int i = 0; i < kv.length; i += 2) {
                b.byPosition.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
            }
            return b;
        }

        /** Shipped per-instruction bonus defaults (reproduce the previous hardcoded switch). */
        private static final Map<String, InstructionBonus> DEFAULT_BONUSES = new HashMap<>();

        static {
            // Defensive
            DEFAULT_BONUSES.put("Mark Tighter", bonus(-0.005, "DC", 0.01, "DL", 0.01, "DR", 0.01));
            DEFAULT_BONUSES.put("Close Down More", bonus(0.005, "ST", 0.01, "DC", -0.005));
            DEFAULT_BONUSES.put("Close Down Less", bonus(-0.005, "DC", 0.005));
            DEFAULT_BONUSES.put("Tackle Harder", bonus(0.005));
            DEFAULT_BONUSES.put("Stay On Feet", bonus(0.003));
            DEFAULT_BONUSES.put("Ease Off Tackles", bonus(-0.003));
            // Attacking
            DEFAULT_BONUSES.put("Get Further Forward", bonus(0.0, "DL", 0.01, "DR", 0.01, "ML", 0.01, "MR", 0.01, "MC", 0.005));
            DEFAULT_BONUSES.put("Hold Position", bonus(0.0, "DC", 0.005));
            DEFAULT_BONUSES.put("Shoot More Often", bonus(0.005, "ST", 0.01));
            DEFAULT_BONUSES.put("Shoot Less Often", bonus(0.003));
            DEFAULT_BONUSES.put("Dribble More", bonus(0.003, "ML", 0.01, "MR", 0.01));
            DEFAULT_BONUSES.put("Dribble Less", bonus(0.003));
            // Movement
            DEFAULT_BONUSES.put("Roam From Position", bonus(0.0, "MC", 0.005, "ST", 0.005));
            DEFAULT_BONUSES.put("Stay Wider", bonus(0.0, "ML", 0.008, "MR", 0.008));
            DEFAULT_BONUSES.put("Sit Narrower", bonus(0.0, "ML", 0.005, "MR", 0.005));
            DEFAULT_BONUSES.put("Move Into Channels", bonus(0.005));
            DEFAULT_BONUSES.put("Drop Deeper", bonus(0.0, "ST", 0.005));
            // Passing
            DEFAULT_BONUSES.put("Pass It Shorter", bonus(0.003));
            DEFAULT_BONUSES.put("Try More Direct Passes", bonus(0.003));
            DEFAULT_BONUSES.put("Cross From Byline", bonus(0.0, "ML", 0.008, "MR", 0.008, "DL", 0.008, "DR", 0.008));
            DEFAULT_BONUSES.put("Cross From Deep", bonus(0.0, "ML", 0.005, "MR", 0.005, "DL", 0.005, "DR", 0.005));
            DEFAULT_BONUSES.put("Play Through Balls", bonus(0.005));
        }
    }

    // ==================== TEAM TALK ====================
    /**
     * Team-level "team talk" multiplier applied (alongside home advantage) to the summed team
     * value in {@code MatchSimulationService.effectiveTeamPower}. A better man-manager rallies
     * the squad to extract a little more; a poor one does not. Quality is read from the manager's
     * reputation (the manager's man-management proxy) and mapped to a small multiplier:
     * {@code 1 + maxSwing × clamp((reputation - neutralReputation) / reputationSpan, -1, 1)}.
     *
     * <p>Deterministic (no RNG) so match seeds are unaffected. Set {@code enabled=false} to pin
     * the multiplier at a neutral 1.0.
     */
    public static class TeamTalk {
        private boolean enabled = true;
        /** Maximum deviation from neutral 1.0 at the reputation extremes (e.g. 0.04 = ±4%). */
        private double maxSwing = 0.04;
        /** Manager reputation at which the team talk is neutral (multiplier 1.0). */
        private double neutralReputation = 1500.0;
        /** Reputation distance from neutral that maps to the full {@link #maxSwing}. */
        private double reputationSpan = 1500.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public double getMaxSwing() { return maxSwing; }
        public void setMaxSwing(double v) { this.maxSwing = v; }
        public double getNeutralReputation() { return neutralReputation; }
        public void setNeutralReputation(double v) { this.neutralReputation = v; }
        public double getReputationSpan() { return reputationSpan; }
        public void setReputationSpan(double v) { this.reputationSpan = v; }

        /** The team-talk multiplier for a manager of the given reputation. */
        public double multiplier(double managerReputation) {
            if (!enabled) return 1.0;
            double f = (managerReputation - neutralReputation) / reputationSpan;
            f = Math.max(-1.0, Math.min(1.0, f));
            return 1.0 + maxSwing * f;
        }
    }

    // ==================== TWO-AXIS TACTICAL MODEL (trade-off + matchup) ====================
    /**
     * Knobs for {@code TacticalScoreService} — the attack/defense match model where tactic
     * settings redistribute a squad's value between attacking and defending (a trade-off) and
     * open/slow the game, and goals come from each side's attack vs the other's defense (matchup).
     * Replaces flat additive percentage bonuses. The categorical setting → numeric mapping
     * (mentality → bias, tempo → risk, …) lives in {@code TacticalScoreService}; these are the
     * strength scalars and the per-position attack share.
     */
    public static class TacticalModel {
        /** When true, production match scoring uses this two-axis model instead of the scalar
         *  {@code calculateScores} + additive {@code adjustTeamPowerByTacticalProperties} path.
         *  Default ON (cutover, 2026-05-30): the two-axis model is the production engine. The scalar
         *  methods are retained (and some legacy scalar-engine tests still exercise them directly),
         *  but production scoring no longer routes through them. Set false to fall back to the scalar engine. */
        private boolean enabled = true;
        /** How far mentality shifts value between attack and defense (trade-off magnitude). */
        private double biasStrength = 0.30;
        /** How much "control" settings (keep ball / time-wasting) raise effective defense. */
        private double controlStrength = 0.20;
        /** How much tempo/risk opens the game (raises total goals). */
        private double opennessStrength = 0.40;
        /** How much control settings slow the game (lower total goals). */
        private double controlOpennessStrength = 0.25;
        /** Base total-goals scale when both sides are balanced. */
        private double baseOpenness = 3.0;
        /** Amplifies the attack-vs-defense gap: each side's xG ratio uses {@code att^exp/(att^exp+def^exp)}.
         *  >1 makes stronger squads dominate more (squad value decisive); 1.0 = raw ratio. */
        private double ratioExponent = 2.0;
        /** Home-side attack bonus (multiplicative on xG). */
        private double homeAttackBonus = 0.08;
        /** Max ± boost a coach's offensive/defensive ability gives to the squad's attack/defense
         *  (e.g. 0.12 → a 100-ability coach is +12%, a 0-ability coach −12%, 50 is neutral). */
        private double coachStrength = 0.12;
        /** Hard cap per team. */
        private int maxGoalsPerTeam = 7;
        /** Opponent panel for the expected-points tactic ranking: the team's own profile scaled to a
         *  weaker / equal / stronger opponent. Averaging expected points across this non-mirror panel
         *  (instead of self-mirror xGD) is what makes the tactic landscape differentiate. */
        private double[] opponentPanel = {0.7, 1.0, 1.3};
        /** Openness multiplier for a knockout extra-time mini-match (30' vs a full 90'): scales the
         *  matchup goal scale down. Default ≈ {@code knockout.extraTimeExpectedGoals / power.expectedGoalsTotal}
         *  (1.0/3.0). Used by {@code TacticalScoreService.scoreExtraTime}. */
        private double extraTimeOpennessScale = 0.33;
        /** OVERRIDE: used base position → share of a player's value assigned to attack (rest = defense). */
        private Map<String, Double> attackShare = new HashMap<>();
        /** OVERRIDE: categorical tactic setting → numeric axis contribution (see DEFAULT_* below). */
        private Map<String, Double> mentalityBias = new HashMap<>();
        private Map<String, Double> possessionBias = new HashMap<>();
        private Map<String, Double> tempoRisk = new HashMap<>();
        private Map<String, Double> passingRisk = new HashMap<>();
        private Map<String, Double> possessionControl = new HashMap<>();
        private Map<String, Double> timeWastingControl = new HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public double getBiasStrength() { return biasStrength; }
        public void setBiasStrength(double v) { this.biasStrength = v; }
        public double getControlStrength() { return controlStrength; }
        public void setControlStrength(double v) { this.controlStrength = v; }
        public double getOpennessStrength() { return opennessStrength; }
        public void setOpennessStrength(double v) { this.opennessStrength = v; }
        public double getControlOpennessStrength() { return controlOpennessStrength; }
        public void setControlOpennessStrength(double v) { this.controlOpennessStrength = v; }
        public double getBaseOpenness() { return baseOpenness; }
        public void setBaseOpenness(double v) { this.baseOpenness = v; }
        public double getRatioExponent() { return ratioExponent; }
        public void setRatioExponent(double v) { this.ratioExponent = v; }
        public double getHomeAttackBonus() { return homeAttackBonus; }
        public void setHomeAttackBonus(double v) { this.homeAttackBonus = v; }
        public double getCoachStrength() { return coachStrength; }
        public void setCoachStrength(double v) { this.coachStrength = v; }
        public int getMaxGoalsPerTeam() { return maxGoalsPerTeam; }
        public void setMaxGoalsPerTeam(int v) { this.maxGoalsPerTeam = v; }
        public double[] getOpponentPanel() { return opponentPanel; }
        public void setOpponentPanel(double[] v) { this.opponentPanel = v; }
        public double getExtraTimeOpennessScale() { return extraTimeOpennessScale; }
        public void setExtraTimeOpennessScale(double v) { this.extraTimeOpennessScale = v; }
        public Map<String, Double> getAttackShare() { return attackShare; }
        public void setAttackShare(Map<String, Double> v) { this.attackShare = v; }
        public Map<String, Double> getMentalityBias() { return mentalityBias; }
        public void setMentalityBias(Map<String, Double> v) { this.mentalityBias = v; }
        public Map<String, Double> getPossessionBias() { return possessionBias; }
        public void setPossessionBias(Map<String, Double> v) { this.possessionBias = v; }
        public Map<String, Double> getTempoRisk() { return tempoRisk; }
        public void setTempoRisk(Map<String, Double> v) { this.tempoRisk = v; }
        public Map<String, Double> getPassingRisk() { return passingRisk; }
        public void setPassingRisk(Map<String, Double> v) { this.passingRisk = v; }
        public Map<String, Double> getPossessionControl() { return possessionControl; }
        public void setPossessionControl(Map<String, Double> v) { this.possessionControl = v; }
        public Map<String, Double> getTimeWastingControl() { return timeWastingControl; }
        public void setTimeWastingControl(Map<String, Double> v) { this.timeWastingControl = v; }

        // Resolver accessors: override → shipped default → 0.0 (unknown key), matching prior getOrDefault semantics.
        public double mentalityBias(String k) { return resolve(mentalityBias, DEFAULT_MENTALITY_BIAS, k); }
        public double possessionBias(String k) { return resolve(possessionBias, DEFAULT_POSSESSION_BIAS, k); }
        public double tempoRisk(String k) { return resolve(tempoRisk, DEFAULT_TEMPO_RISK, k); }
        public double passingRisk(String k) { return resolve(passingRisk, DEFAULT_PASSING_RISK, k); }
        public double possessionControl(String k) { return resolve(possessionControl, DEFAULT_POSSESSION_CONTROL, k); }
        public double timeWastingControl(String k) { return resolve(timeWastingControl, DEFAULT_TIME_WASTING_CONTROL, k); }

        private static double resolve(Map<String, Double> override, Map<String, Double> shipped, String key) {
            Double o = override.get(key);
            if (o != null) return o;
            Double d = shipped.get(key);
            return d == null ? 0.0 : d;
        }

        /** Attack share for a used base position: override → shipped default → 0.5. */
        public double attackShareFor(String position) {
            Double o = attackShare.get(position);
            if (o != null) return o;
            Double d = DEFAULT_ATTACK_SHARE.get(position);
            return d == null ? 0.5 : d;
        }

        /** Shipped attack/defense split per position (1.0 = pure attack, 0.0 = pure defense). */
        private static final Map<String, Double> DEFAULT_ATTACK_SHARE = new HashMap<>();
        static {
            DEFAULT_ATTACK_SHARE.put("ST", 0.95);
            DEFAULT_ATTACK_SHARE.put("ML", 0.80);
            DEFAULT_ATTACK_SHARE.put("MR", 0.80);
            DEFAULT_ATTACK_SHARE.put("MC", 0.50);
            DEFAULT_ATTACK_SHARE.put("DL", 0.45);
            DEFAULT_ATTACK_SHARE.put("DR", 0.45);
            DEFAULT_ATTACK_SHARE.put("DC", 0.12);
            DEFAULT_ATTACK_SHARE.put("GK", 0.00);
        }

        /** Shipped categorical setting → numeric axis contributions (tunable; override via config maps). */
        private static final Map<String, Double> DEFAULT_MENTALITY_BIAS = Map.of(
                "Very Defensive", -1.0, "Defensive", -0.5, "Balanced", 0.0, "Attacking", 0.5, "Very Attacking", 1.0);
        private static final Map<String, Double> DEFAULT_POSSESSION_BIAS = Map.of(
                "Standard", 0.0, "Keep Ball", -0.10, "Free Ball Early", 0.15);
        private static final Map<String, Double> DEFAULT_TEMPO_RISK = Map.of(
                "Much Lower", -1.0, "Lower", -0.5, "Standard", 0.0, "Higher", 0.5, "Much Higher", 1.0);
        private static final Map<String, Double> DEFAULT_PASSING_RISK = Map.of(
                "Short", -0.15, "Normal", 0.0, "Long", 0.20, "Direct", 0.20);
        private static final Map<String, Double> DEFAULT_POSSESSION_CONTROL = Map.of(
                "Standard", 0.0, "Keep Ball", 0.6, "Free Ball Early", -0.1);
        private static final Map<String, Double> DEFAULT_TIME_WASTING_CONTROL = Map.of(
                "Never", -0.1, "Sometimes", 0.0, "Frequently", 0.4, "Always", 0.6);
    }
}
