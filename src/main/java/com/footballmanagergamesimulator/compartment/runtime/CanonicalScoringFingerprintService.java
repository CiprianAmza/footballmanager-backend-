package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/** Explicit, entity-free fingerprint material for persisted scoring decisions. */
@Service
public final class CanonicalScoringFingerprintService {
    public String configFingerprint(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        String material = "compartment|rating=" + compartment.getRating().getAttributeMin() + ','
                + compartment.getRating().getAttributeMax() + ',' + compartment.getRating().getScoreScale()
                + ',' + compartment.getRating().getContextFactorMin() + ',' + compartment.getRating().getContextFactorMax()
                + ',' + compartment.getRating().getTotalContextMin() + ',' + compartment.getRating().getTotalContextMax()
                + ',' + compartment.getRating().getContextCoefficientMin() + ',' + compartment.getRating().getContextCoefficientMax()
                + ',' + compartment.getRating().getRoleFitBase() + ',' + compartment.getRating().getRoleFitRange()
                + ',' + compartment.getRating().getFitnessFloor() + ',' + compartment.getRating().getMoraleNeutral()
                + ',' + compartment.getRating().getMoraleSlope() + ',' + compartment.getRating().getDefaultPositionMultiplier()
                + ',' + compartment.getRating().getDefaultRoleMultiplier()
                + "|compartments=" + ordered(compartment.getCompartments())
                + "|positionOverrides=" + ordered(compartment.getPositionCompartmentOverrides())
                + "|positions=" + ordered(compartment.getPositions())
                + "|roles=" + ordered(compartment.getRoles())
                + "|duties=" + ordered(compartment.getDuties())
                + "|mentalities=" + ordered(compartment.getMentalities())
                + "|workRate=" + ordered(compartment.getWorkRate().getTraits()) + ordered(compartment.getWorkRate().getInstructions())
                + "|exposure=" + ordered(compartment.getExposure().getZoneWeights()) + ','
                + compartment.getExposure().getCoverageReduction() + ',' + compartment.getExposure().getSecondDmWeight()
                + ',' + compartment.getExposure().getCbRecoveryPaceCap() + ',' + compartment.getExposure().getPenaltyStrength()
                + ',' + compartment.getExposure().getPenaltyExponent()
                + "|probability=" + compartment.getProbability().getMatchupExponent() + ','
                + compartment.getProbability().getHomeAdvantage() + ',' + compartment.getProbability().getGammaShape()
                + ',' + compartment.getProbability().getGoalCap() + ',' + compartment.getProbability().getExtraTimeScale()
                + ',' + compartment.getProbability().getIntervalLowerQuantile() + ','
                + compartment.getProbability().getIntervalUpperQuantile()
                + "|playerValue=" + match.getPlayerValue().getScaleMultiplier() + ','
                + match.getPlayerValue().getRatingFloor() + ',' + match.getPlayerValue().getRatingCeil()
                + ',' + match.getPlayerValue().getMoraleNeutral() + ',' + match.getPlayerValue().getMoraleSlope()
                + ',' + match.getPlayerValue().getFitnessFloor() + ','
                + match.getPlayerValue().getDefaultFamiliarityPenalty() + "|weights="
                + ordered(match.getPlayerValue().getWeights()) + "|familiarity="
                + ordered(match.getPlayerValue().getFamiliarityPenalty())
                + "|teamTalk=" + teamTalk(match.getTeamTalk())
                + "|tactical=" + tactical(match.getTacticalModel());
        return sha256(material);
    }

    public String inputFingerprint(CanonicalRuntimeScoringService.RuntimeScoringRequest request,
                                   CanonicalRuntimeTeamInput home, CanonicalRuntimeTeamInput away) {
        return sha256("input|fixture=" + request.fixtureKey() + "|competition=" + request.competitionId()
                + "|season=" + request.season() + "|round=" + request.round() + "|home=" + request.homeTeamId()
                + "|away=" + request.awayTeamId() + "|homeTactic=" + tactic(request.homeTactic())
                + "|awayTactic=" + tactic(request.awayTactic()) + "|home=" + team(home)
                + "|away=" + team(away));
    }

    public String legacyInputFingerprint(String fixtureKey, long competitionId, int season, int round,
                                         long homeTeamId, long awayTeamId, ScoreEngineKind engine) {
        return sha256("legacy|fixture=" + fixtureKey + "|competition=" + competitionId
                + "|season=" + season + "|round=" + round + "|home=" + homeTeamId
                + "|away=" + awayTeamId + "|engine=" + engine.name());
    }

    public String fallbackInputFingerprint(String fixtureKey, long homeTeamId, long awayTeamId,
                                           double homePower, double awayPower, TacticalScoreService.TacticVector homeVector,
                                           TacticalScoreService.TacticVector awayVector, Double homeTalk, Double awayTalk,
                                           ScoreEngineKind engine) {
        return sha256("fallback|engine=" + engine.name() + "|fixture=" + fixtureKey + "|home=" + homeTeamId
                + "|away=" + awayTeamId + "|powers=" + homePower + ',' + awayPower
                + "|vectors=" + vector(homeVector) + ',' + vector(awayVector)
                + "|talk=" + scalar(homeTalk) + ',' + scalar(awayTalk));
    }

    public String adminOverrideFingerprint(String fixtureKey, int homeScore, int awayScore) {
        return sha256("admin-override-1|fixture=" + fixtureKey + "|score=" + homeScore + ':' + awayScore);
    }

    public String fallbackConfigFingerprint(MatchEngineConfig match, ScoreEngineKind engine) {
        return sha256("fallback-config|engine=" + engine.name() + "|playerValue="
                + match.getPlayerValue().getScaleMultiplier() + ',' + match.getPlayerValue().getRatingFloor()
                + ',' + match.getPlayerValue().getRatingCeil()
                + "|teamTalk=" + teamTalk(match.getTeamTalk())
                + "|tactical=" + tactical(match.getTacticalModel()));
    }

    private static String team(CanonicalRuntimeTeamInput team) {
        return team.mentality() + "|lineup=" + team.lineup().stream()
                .sorted(Comparator.comparingLong(CanonicalLineupPlayer::playerId)
                        .thenComparing(p -> p.usedPosition().code()).thenComparingInt(CanonicalLineupPlayer::occurrence))
                .map(CanonicalScoringFingerprintService::player).collect(Collectors.joining(","))
                + "|contexts=" + team.tacticalContexts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ':' + context(e.getValue())).collect(Collectors.joining(","));
    }

    private static String player(CanonicalLineupPlayer p) {
        PlayerCapabilitySnapshot c = p.capability();
        return p.playerId() + ":" + p.usedPosition() + ":" + p.occurrence() + ":" + p.role() + ":" + p.duty()
                + ":attrs=" + ordered(p.attributes()) + ":fitness=" + p.fitness() + ":morale=" + p.morale()
                + ":roleSuitability=" + p.roleSuitability() + ":traits=" + p.traits().stream()
                .map(Enum::name).sorted().collect(Collectors.joining(",", "[", "]"))
                + ":instruction=" + p.forwardInstruction() + ":cap=" + c.playerId() + ':' + c.primaryPosition()
                + ':' + ordered(c.positionFamiliarity()) + ':' + ordered(c.roleFamiliarity()) + ':'
                + c.leftFootRating() + ":" + c.rightFootRating() + ":" + c.positionFallbackUsed()
                + ':' + c.roleFallbackUsed() + ':' + c.footFallbackUsed();
    }

    private static String context(TacticalContextInput c) {
        return c.mentality() + ':' + c.tempo() + ':' + c.passingType() + ':' + c.defensiveLine()
                + ':' + c.pressing() + ':' + c.width() + ':' + c.playerInstructions().stream()
                .sorted().collect(Collectors.joining(",", "[", "]"));
    }

    private static String vector(TacticalScoreService.TacticVector value) {
        if (value == null) return "null";
        TacticalScoreService.TacticVector v = value;
        return v.attackBias() + "," + v.risk() + "," + v.control() + "," + v.directness()
                + "," + v.line() + "," + v.press() + "," + v.width();
    }

    private static String scalar(Double value) {
        if (value == null) return "null";
        return Double.toString(value);
    }

    private static String teamTalk(MatchEngineConfig.TeamTalk t) {
        return t.isEnabled() + "," + t.getMaxSwing() + "," + t.getNeutralReputation() + ',' + t.getReputationSpan();
    }

    private static String tactical(MatchEngineConfig.TacticalModel t) {
        return t.isEnabled() + "," + t.getBiasStrength() + "," + t.getControlStrength() + ","
                + t.getControlAttackCost() + ',' + t.getOpennessStrength() + ',' + t.getControlOpennessStrength()
                + ',' + t.getBaseOpenness() + ',' + t.getRatioExponent() + ',' + t.getHomeAttackBonus()
                + ',' + t.getCoachStrength() + ',' + t.getLineHeightSupport() + ',' + t.getLineHeightVulnerability()
                + ',' + t.getPressDisruption() + ',' + t.getPressStaminaCost() + ',' + t.getPressLineCompound()
                + ',' + t.getDirectnessAttackCost() + ',' + t.getPressBypassVulnerability() + ',' + t.getWidthStrength()
                + ',' + t.getAptitudeGateStrength() + ',' + t.getAptitudeBaseline() + ',' + t.getAptitudeMultMin()
                + ',' + t.getAptitudeMultMax() + ',' + t.getAiWidthWideThreshold() + ',' + t.getAiWidthNarrowThreshold()
                + ',' + t.getMaxGoalsPerTeam() + ',' + t.getExtraTimeOpennessScale();
    }

    private static String tactic(PersonalizedTactic tactic) {
        if (tactic == null) return "null";
        return tactic.getMentality() + '|' + tactic.getTempo() + '|' + tactic.getPassingType()
                + '|' + tactic.getDefensiveLine() + '|' + tactic.getPressing() + '|' + tactic.getWidth()
                + '|' + tactic.getInPossession() + '|' + tactic.getTimeWasting() + '|' + tactic.getDribbling()
                + '|' + tactic.getFoulFrequency() + '|' + tactic.getFoulHardness() + '|'
                + tactic.getTempoFragmentation() + '|' + tactic.getWidePlay() + '|' + tactic.getTransition()
                + "|setPieces=" + tactic.getPenaltyTakerId() + ',' + tactic.getFreeKickTakerId()
                + ',' + tactic.getCornerTakerLeftId() + ',' + tactic.getCornerTakerRightId()
                + "|tactic=" + tactic.getTactic();
    }

    private static String ordered(Map<?, ?> map) {
        return map.entrySet().stream().sorted(Comparator.comparing(e -> canonical(e.getKey())))
                .map(e -> canonical(e.getKey()) + '=' + canonical(e.getValue()))
                .collect(Collectors.joining("{", "{", "}"));
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) return ordered(map);
        if (value instanceof CompartmentEngineConfig.CompartmentWeights w) return ordered(w.getAttributes());
        if (value instanceof CompartmentEngineConfig.CompartmentMultipliers m) {
            return m.getAttack() + "," + m.getMidfield() + "," + m.getDefense();
        }
        if (value instanceof CompartmentEngineConfig.MentalityRule m) {
            return m.getMidfieldToAttack() + "," + m.getMidfieldToDefense() + ','
                    + canonical(m.getTransferFrom()) + ',' + canonical(m.getTransferTo()) + ','
                    + m.getTransferShare() + ',' + m.getOpenness();
        }
        if (value instanceof CompartmentEngineConfig.WorkRule w) {
            return w.getEngagement() + "," + w.getAttackMultiplier() + ','
                    + w.isIgnoresDefensiveInstructions() + ',' + w.getForcedDefensiveMoraleDelta();
        }
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value.toString();
        throw new IllegalArgumentException("unsupported fingerprint value type: " + value.getClass().getName());
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
