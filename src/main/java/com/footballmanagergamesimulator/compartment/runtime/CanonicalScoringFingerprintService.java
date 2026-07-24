package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
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
                + "|playerValue=" + match.getPlayerValue().getScaleMultiplier() + ','
                + match.getPlayerValue().getRatingFloor() + ',' + match.getPlayerValue().getRatingCeil()
                + ',' + match.getPlayerValue().getMoraleNeutral() + ',' + match.getPlayerValue().getMoraleSlope()
                + ',' + match.getPlayerValue().getFitnessFloor() + ','
                + match.getPlayerValue().getDefaultFamiliarityPenalty() + "|weights="
                + ordered(match.getPlayerValue().getWeights()) + "|familiarity="
                + ordered(match.getPlayerValue().getFamiliarityPenalty());
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
                                           double homePower, double awayPower, Object homeVector,
                                           Object awayVector, Object homeTalk, Object awayTalk,
                                           ScoreEngineKind engine) {
        return sha256("fallback|engine=" + engine.name() + "|fixture=" + fixtureKey + "|home=" + homeTeamId
                + "|away=" + awayTeamId + "|powers=" + homePower + ',' + awayPower
                + "|vectors=" + String.valueOf(homeVector) + ',' + String.valueOf(awayVector)
                + "|talk=" + String.valueOf(homeTalk) + ',' + String.valueOf(awayTalk));
    }

    public String adminOverrideFingerprint(String fixtureKey, int homeScore, int awayScore) {
        return sha256("admin|fixture=" + fixtureKey + "|score=" + homeScore + ':' + awayScore);
    }

    public String fallbackConfigFingerprint(MatchEngineConfig match, ScoreEngineKind engine) {
        return sha256("fallback-config|engine=" + engine.name() + "|playerValue="
                + match.getPlayerValue().getScaleMultiplier() + ',' + match.getPlayerValue().getRatingFloor()
                + ',' + match.getPlayerValue().getRatingCeil() + ',' + match.getTeamTalk()
                + "|tactical=" + match.getTacticalModel());
    }

    private static String team(CanonicalRuntimeTeamInput team) {
        return team.mentality() + "|lineup=" + team.lineup().stream()
                .map(CanonicalScoringFingerprintService::player).collect(Collectors.joining(","))
                + "|contexts=" + team.tacticalContexts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ':' + context(e.getValue())).collect(Collectors.joining(","));
    }

    private static String player(CanonicalLineupPlayer p) {
        PlayerCapabilitySnapshot c = p.capability();
        return p.playerId() + ':' + p.usedPosition() + ':' + p.occurrence() + ':' + p.role() + ':' + p.duty()
                + ":attrs=" + ordered(p.attributes()) + ":fitness=" + p.fitness() + ":morale=" + p.morale()
                + ":roleSuitability=" + p.roleSuitability() + ":traits=" + p.traits()
                + ":instruction=" + p.forwardInstruction() + ":cap=" + c.playerId() + ':' + c.primaryPosition()
                + ':' + ordered(c.positionFamiliarity()) + ':' + ordered(c.roleFamiliarity()) + ':'
                + c.leftFootRating() + ':' + c.rightFootRating() + ':' + c.positionFallbackUsed()
                + ':' + c.roleFallbackUsed() + ':' + c.footFallbackUsed();
    }

    private static String context(TacticalContextInput c) {
        return c.mentality() + ':' + c.tempo() + ':' + c.passingType() + ':' + c.defensiveLine()
                + ':' + c.pressing() + ':' + c.width() + ':' + c.playerInstructions();
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
                + "|first11=" + tactic.getFirst11() + "|tactic=" + tactic.getTactic();
    }

    private static String ordered(Map<?, ?> map) {
        return map.entrySet().stream().sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                .map(e -> String.valueOf(e.getKey()) + '=' + String.valueOf(e.getValue()))
                .collect(Collectors.joining("{", "{", "}"));
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
