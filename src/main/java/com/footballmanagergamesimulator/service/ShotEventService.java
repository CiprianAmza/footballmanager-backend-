package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.ShotEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds a deterministic, internally consistent shot ledger from the authoritative match stat line. */
@Service
public class ShotEventService {

    private final ShotEventRepository repository;

    public ShotEventService(ShotEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceForMatch(MatchStats stats) {
        if (stats == null || stats.getId() <= 0) return;
        repository.deleteAllByMatchStatsId(stats.getId());
        repository.saveAll(generate(stats));
    }

    /** Existing saves are upgraded in memory without mutating data during a GET request. */
    public List<ShotEvent> eventsForMatch(MatchStats stats) {
        List<ShotEvent> stored = repository.findAllByMatchStatsIdOrderByShotIndexAsc(stats.getId());
        return stored == null || stored.isEmpty() ? generate(stats) : stored;
    }

    public List<ShotEvent> generate(MatchStats stats) {
        List<ShotEvent> events = new ArrayList<>();
        events.addAll(generateTeam(stats, true));
        events.addAll(generateTeam(stats, false));
        return events;
    }

    private List<ShotEvent> generateTeam(MatchStats stats, boolean home) {
        int shots = Math.max(0, home ? stats.getHomeShots() : stats.getAwayShots());
        if (shots == 0) return List.of();
        int goals = Math.min(shots, Math.max(0, home ? stats.getHomeGoals() : stats.getAwayGoals()));
        int onTarget = Math.min(shots, Math.max(goals, home ? stats.getHomeShotsOnTarget() : stats.getAwayShotsOnTarget()));
        int blocked = Math.min(shots - onTarget, Math.max(0, home ? stats.getHomeShotsBlocked() : stats.getAwayShotsBlocked()));
        int bigChances = Math.min(shots, Math.max(0, home ? stats.getHomeBigChances() : stats.getAwayBigChances()));
        double totalXg = Math.max(0, (home ? stats.getHomeXg() : stats.getAwayXg()) / 100.0);
        long teamId = home ? stats.getTeam1Id() : stats.getTeam2Id();
        long opponentId = home ? stats.getTeam2Id() : stats.getTeam1Id();
        int corners = Math.max(0, home ? stats.getHomeCorners() : stats.getAwayCorners());
        int freeKicks = Math.max(0, home ? stats.getHomeFreeKicks() : stats.getAwayFreeKicks());
        Random rng = new Random(seed(stats, teamId));

        List<ShotDraft> drafts = new ArrayList<>();
        for (int index = 0; index < shots; index++) {
            boolean isBig = index < bigChances;
            boolean isGoal = index < goals;
            boolean isOnTarget = index < onTarget;
            String outcome = isGoal ? "GOAL" : isOnTarget ? "SAVED" : index < onTarget + blocked ? "BLOCKED" : "WIDE";
            String situation = situation(index, shots, bigChances, corners, freeKicks, rng);
            String creation = creationType(situation, rng);
            boolean insideBox = isBig || "PENALTY".equals(situation) || rng.nextDouble() < 0.72;
            double originX = insideBox ? 83.5 + rng.nextDouble() * 14.0 : 61 + rng.nextDouble() * 22.0;
            double originY = insideBox ? 25 + rng.nextDouble() * 50 : 12 + rng.nextDouble() * 76;
            if ("PENALTY".equals(situation)) { originX = 89.5; originY = 50; }
            double distance = distance(originX, originY);
            double angle = angle(originX, originY);
            double baseWeight = Math.max(.015, 1.15 / Math.max(5, distance));
            if (insideBox) baseWeight *= 1.45;
            if (isBig) baseWeight *= 3.4;
            if ("PENALTY".equals(situation)) baseWeight = .76;
            drafts.add(new ShotDraft(index, isBig, isOnTarget, outcome, situation, creation,
                    insideBox, originX, originY, distance, angle, baseWeight, rng.nextDouble() < (isBig ? .28 : .48)));
        }

        double weightTotal = drafts.stream().mapToDouble(ShotDraft::weight).sum();
        List<ShotEvent> events = new ArrayList<>();
        int totalXgUnits = toTenThousandths(totalXg);
        int assignedXgUnits = 0;
        for (int i = 0; i < drafts.size(); i++) {
            ShotDraft draft = drafts.get(i);
            int remainingXgUnits = Math.max(0, totalXgUnits - assignedXgUnits);
            int shotXgUnits = i == drafts.size() - 1
                    ? remainingXgUnits
                    : Math.min(remainingXgUnits,
                            (int) Math.round(totalXgUnits * draft.weight() / Math.max(.0001, weightTotal)));
            assignedXgUnits += shotXgUnits;
            double shotXg = shotXgUnits / 10_000.0;
            double mouthY = draft.onTarget() ? 8 + rng.nextDouble() * 84 : rng.nextBoolean() ? -20 + rng.nextDouble() * 20 : 100 + rng.nextDouble() * 20;
            double mouthZ = draft.onTarget() ? 8 + rng.nextDouble() * 82 : 25 + rng.nextDouble() * 95;
            double xgot = draft.onTarget() ? xgot(shotXg, mouthY, mouthZ, draft.outcome()) : 0;

            ShotEvent event = new ShotEvent();
            event.setMatchStatsId(stats.getId());
            event.setShotIndex(draft.index());
            event.setCompetitionId(stats.getCompetitionId());
            event.setSeasonNumber(stats.getSeasonNumber());
            event.setRoundNumber(stats.getRoundNumber());
            event.setTeamId(teamId);
            event.setOpponentTeamId(opponentId);
            event.setMinute(2 + rng.nextInt(89));
            event.setOriginX(round(draft.originX()));
            event.setOriginY(round(draft.originY()));
            event.setGoalMouthY(round(mouthY));
            event.setGoalMouthZ(round(mouthZ));
            event.setDistanceMeters(round(draft.distance()));
            event.setAngleDegrees(round(draft.angle()));
            event.setXg(shotXgUnits);
            event.setXgot(toTenThousandths(xgot));
            event.setOutcome(draft.outcome());
            event.setSituation(draft.situation());
            event.setCreationType(draft.creation());
            event.setChannel(channel(draft.originY()));
            event.setBodyPart(draft.situation().equals("CORNER") && rng.nextDouble() < .45 ? "HEAD" : rng.nextBoolean() ? "RIGHT_FOOT" : "LEFT_FOOT");
            event.setInsideBox(draft.insideBox());
            event.setBigChance(draft.bigChance());
            event.setUnderPressure(draft.underPressure());
            event.setOnTarget(draft.onTarget());
            event.setSequenceLabel(sequenceLabel(draft.creation(), draft.situation()));
            event.setDataQuality("MODELED");
            events.add(event);
        }
        return events;
    }

    private String situation(int index, int shots, int bigChances, int corners, int freeKicks, Random rng) {
        if (index == 0 && bigChances > 0 && rng.nextDouble() < .18) return "PENALTY";
        double cornerRate = Math.min(.24, corners * .22 / Math.max(1, shots));
        double freeKickRate = Math.min(.12, freeKicks * .035 / Math.max(1, shots));
        double roll = rng.nextDouble();
        if (roll < cornerRate) return "CORNER";
        if (roll < cornerRate + freeKickRate) return "FREE_KICK";
        return "OPEN_PLAY";
    }

    private String creationType(String situation, Random rng) {
        if (!"OPEN_PLAY".equals(situation)) return "SET_PIECE";
        double roll = rng.nextDouble();
        if (roll < .22) return "CROSS";
        if (roll < .38) return "THROUGH_BALL";
        if (roll < .54) return "RECOVERY";
        if (roll < .85) return "COMBINATION";
        return "INDIVIDUAL";
    }

    private String channel(double y) {
        return y < 35 ? "LEFT" : y > 65 ? "RIGHT" : "CENTRE";
    }

    private String sequenceLabel(String creation, String situation) {
        if (!"OPEN_PLAY".equals(situation)) return situation.replace('_', ' ') + " ATTACK";
        return switch (creation) {
            case "CROSS" -> "WIDE DELIVERY";
            case "THROUGH_BALL" -> "CENTRAL PENETRATION";
            case "RECOVERY" -> "HIGH TURNOVER";
            case "COMBINATION" -> "PASSING MOVE";
            default -> "INDIVIDUAL CARRY";
        };
    }

    private double xgot(double xg, double mouthY, double mouthZ, String outcome) {
        double horizontalPlacement = Math.min(1, Math.abs(mouthY - 50) / 42.0);
        double verticalPlacement = Math.min(1, mouthZ / 90.0);
        double execution = .70 + .85 * horizontalPlacement + .28 * verticalPlacement;
        if ("GOAL".equals(outcome)) execution += .18;
        return Math.max(.01, Math.min(.98, xg * execution));
    }

    private double distance(double x, double y) {
        return Math.hypot((100 - x) * 1.05, (50 - y) * .68);
    }

    private double angle(double x, double y) {
        double dx = (100 - x) * 1.05;
        double dy = (50 - y) * .68;
        double halfGoal = 3.66;
        double cross = Math.abs(dx * (dy + halfGoal) - dx * (dy - halfGoal));
        double dot = dx * dx + (dy - halfGoal) * (dy + halfGoal);
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    private long seed(MatchStats stats, long teamId) {
        long value = 1469598103934665603L;
        value = (value ^ stats.getCompetitionId()) * 1099511628211L;
        value = (value ^ stats.getSeasonNumber()) * 1099511628211L;
        value = (value ^ stats.getRoundNumber()) * 1099511628211L;
        value = (value ^ stats.getTeam1Id()) * 1099511628211L;
        value = (value ^ stats.getTeam2Id()) * 1099511628211L;
        return (value ^ teamId) * 1099511628211L;
    }

    private int toTenThousandths(double value) { return (int) Math.round(Math.max(0, value) * 10_000); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private record ShotDraft(int index, boolean bigChance, boolean onTarget, String outcome,
                             String situation, String creation, boolean insideBox,
                             double originX, double originY, double distance, double angle,
                             double weight, boolean underPressure) {}
}
