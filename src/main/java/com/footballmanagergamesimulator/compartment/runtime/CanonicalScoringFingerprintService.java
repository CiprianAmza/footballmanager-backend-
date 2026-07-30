package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.ContextRuleNormalizer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
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
                + ',' + compartment.getRating().getDefaultRoleMultiplier() + ','
                + compartment.getRating().getExceptionalAttributeValue()
                + "|contextRules=" + ordered(ContextRuleNormalizer.effective(compartment.getContextRules()))
                + "|roleWeights=" + roleWeights(match.getRoleWeights())
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
                + ',' + compartment.getProbability().getGoalCap() + ','
                + compartment.getProbability().getExtraTimeScale()
                + "|aggregation=" + compartment.getAggregation().getWideRedistributionShare()
                + "|shooter=" + shooter(compartment.getShooter())
                + "|passingStyle=" + passingStyle(compartment.getPassingStyle());
        return sha256(material);
    }

    public String inputFingerprint(CanonicalRuntimeScoringService.RuntimeScoringRequest request,
                                   CanonicalRuntimeTeamInput home, CanonicalRuntimeTeamInput away) {
        return sha256("input|fixture=" + request.fixtureKey() + "|competition=" + request.competitionId()
                + "|season=" + request.season() + "|round=" + request.round() + "|home=" + request.homeTeamId()
                + "|away=" + request.awayTeamId() + "|home=" + team(home)
                + "|away=" + team(away));
    }

    public String legacyInputFingerprint(String fixtureKey, long competitionId, int season, int round,
                                         long homeTeamId, long awayTeamId, ScoreEngineKind engine) {
        return sha256("legacy|fixture=" + fixtureKey + "|competition=" + competitionId
                + "|season=" + season + "|round=" + round + "|home=" + homeTeamId
                + "|away=" + awayTeamId + "|engine=" + engine.name());
    }

    public String adminOverrideConfigFingerprint() {
        return sha256("admin-override-1|config");
    }

    public String adminOverrideInputFingerprint(String fixtureKey, int homeScore, int awayScore) {
        return sha256("admin-override-1|fixture=" + fixtureKey + "|score=" + homeScore + ':' + awayScore);
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
                + ":overallRating=" + p.overallRating()
                + ":roleSuitability=" + p.roleSuitability() + ":traits=" + p.traits().stream()
                .map(Enum::name).sorted().collect(Collectors.joining(",", "[", "]"))
                + ":instruction=" + p.forwardInstruction() + ":cap=" + c.playerId() + ':' + c.primaryPosition()
                + ':' + ordered(c.positionFamiliarity()) + ':' + ordered(c.roleFamiliarity()) + ':'
                + c.leftFootRating() + ":" + c.rightFootRating() + ":" + c.positionFallbackUsed()
                + ':' + c.roleFallbackUsed() + ':' + c.footFallbackUsed();
    }

    private static String context(TacticalContextInput c) {
        return c.mentality() + ':' + c.tempo() + ':' + c.passingType() + ':' + c.defensiveLine()
                + ':' + c.pressing() + ':' + c.width() + ':' + c.recovery() + ':' + c.playerInstructions().stream()
                .sorted().collect(Collectors.joining(",", "[", "]"));
    }

    private static String roleWeights(MatchEngineConfig.RoleWeights weights) {
        return weights.getOverallBlend() + "," + weights.getRoleBlend() + ","
                + weights.getSuitabilityScale() + "|attributes=" + ordered(weights.getAttributes());
    }

    private static String shooter(CompartmentEngineConfig.Shooter shooter) {
        return shooter.getAttackContribution() + "," + shooter.getMidfieldContribution() + ","
                + shooter.getDefenseContribution() + "," + shooter.getRegularLongShotsCeiling() + ","
                + shooter.getRegularLongShotsExponent() + ",shots=" + shooter.getStandardShotDistribution()
                + ",positioning20=" + shooter.getExceptionalPositioningShotDistribution()
                + ",pressing=" + ordered(shooter.getPressing());
    }

    private static String passingStyle(CompartmentEngineConfig.PassingStyle style) {
        return style.getMidfieldThreshold() + "," + style.getBaseSuppression() + ","
                + style.getAggressiveSuppression() + "," + style.getVeryAggressiveSuppression() + ","
                + style.getLongPassingSuppression() + "," + style.getPace20Bonus() + ","
                + style.getNonPace20Penalty() + "," + style.getOpponentPace20Penalty() + ","
                + style.getFinishing19Factor() + ","
                + style.getPace19Chance() + "," + style.getStrikerOpportunityDistribution();
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
        if (value instanceof CompartmentEngineConfig.PressingRule p) {
            return p.getShotReduction() + "," + p.getRedCardChance();
        }
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof String s) return s;
        if (value instanceof Double d) return Double.toString(d);
        if (value instanceof Float f) return Float.toString(f);
        if (value instanceof Long l) return Long.toString(l);
        if (value instanceof Integer i) return Integer.toString(i);
        if (value instanceof Short s) return Short.toString(s);
        if (value instanceof Byte b) return Byte.toString(b);
        if (value instanceof Boolean b) return Boolean.toString(b);
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
