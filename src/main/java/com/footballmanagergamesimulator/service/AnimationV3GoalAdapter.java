package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.animation.AnimationDirector;
import com.footballmanagergamesimulator.animation.AnimationOutcome;
import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.AnimationReplay;
import com.footballmanagergamesimulator.animation.AnimationV3Settings;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.MatchPeriod;
import com.footballmanagergamesimulator.animation.PitchPoint;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.matchplan.Contributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Presentation-only bridge from a canonical goal moment to the isolated, deterministic
 * Animation Engine V3 ({@link AnimationDirector}), producing the same frontend
 * {@link GoalAnimationData} the legacy generators produce so the rest of the pipeline
 * (persistence, recovery, the frontend) is unchanged.
 *
 * <p>This is the single feature-flag seam for V3. It is strictly non-authoritative: it
 * turns already-decided canonical facts (scorer, assister, minute, slot, period, on-pitch
 * lot) into an animation and can never change them. When the flag is off — or when the
 * canonical inputs cannot form a valid {@link MatchMomentSpec}, or the engine cannot render
 * — it returns {@link Optional#empty()} and the caller falls back to the legacy engine, so
 * the existing behaviour is preserved bit-for-bit while the flag is off.
 */
@Component
public class AnimationV3GoalAdapter {

    private final AnimationV3Settings settings;
    private final AnimationDirector director;
    private final GoalAnimationContext animationContext;

    @Autowired
    public AnimationV3GoalAdapter(AnimationV3Settings settings,
                                  AnimationDirector director,
                                  GoalAnimationContext animationContext) {
        this.settings = settings;
        this.director = director;
        this.animationContext = animationContext;
    }

    /** Whether the V3 presentation engine is enabled (feature flag, default off). */
    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * Build a V3 goal animation for one canonical goal slot, or {@link Optional#empty()} when
     * the flag is off or the moment cannot be rendered (caller then uses the legacy engine).
     *
     * @param attackersOnPitch scoring team's canonical on-pitch contributors (includes the scorer)
     * @param defendersOnPitch defending team's canonical on-pitch contributors (must include a GK)
     * @param shirtNumbers     playerId → shirt number, for the frontend overlay
     */
    public Optional<GoalAnimationData> tryBuildCanonicalGoal(
            String fixtureKey, int slotIndex, long planSeed, int minute,
            boolean extraTime, long scoringTeamId, long defendingTeamId, long homeTeamId,
            String goalType, long scorerId, Long assisterId,
            List<Contributor> attackersOnPitch, List<Contributor> defendersOnPitch,
            Map<Long, Integer> shirtNumbers) {
        if (!settings.enabled()) return Optional.empty();
        try {
            int firstHalfStoppage = Math.max(0, animationContext.firstHalfStoppage());
            MatchPeriod period = periodFor(extraTime, minute, firstHalfStoppage);
            AnimationPhase phase = phaseFor(goalType);

            List<PlayerSnapshot> onPitch = new ArrayList<>(attackersOnPitch.size() + defendersOnPitch.size());
            addSnapshots(onPitch, attackersOnPitch, scoringTeamId, shirtNumbers);
            addSnapshots(onPitch, defendersOnPitch, defendingTeamId, shirtNumbers);

            MatchMomentSpec spec = new MatchMomentSpec(
                    fixtureKey, slotIndex, planSeed, AnimationDirector.CURRENT_GENERATOR_VERSION,
                    minute, firstHalfStoppage, period,
                    scoringTeamId, defendingTeamId, homeTeamId,
                    phase, AnimationOutcome.GOAL, scorerId, assisterId, onPitch, null);

            AnimationReplay replay = director.direct(spec).replay();
            GoalAnimationData data = toGoalAnimationData(replay, firstHalfStoppage);
            animationContext.attachKits(data, scoringTeamId, defendingTeamId);
            data.setFirstHalfStoppage(firstHalfStoppage);
            return Optional.of(data);
        } catch (RuntimeException ex) {
            // Any invalid canonical input (e.g. a GK sent off so the defending side has no
            // keeper) or render failure degrades to the legacy engine rather than dropping
            // the goal's animation. The canonical goal itself is already persisted upstream.
            return Optional.empty();
        }
    }

    // ---- Canonical → engine mapping --------------------------------------

    /** Half/period, honouring first-half stoppage (a 45+X' goal is still first half). */
    private static MatchPeriod periodFor(boolean extraTime, int minute, int firstHalfStoppage) {
        if (extraTime) {
            return minute <= 105 ? MatchPeriod.EXTRA_TIME_FIRST_HALF : MatchPeriod.EXTRA_TIME_SECOND_HALF;
        }
        return minute <= 45 + firstHalfStoppage ? MatchPeriod.FIRST_HALF : MatchPeriod.SECOND_HALF;
    }

    /** Canonical goal type → animation phase. HEADER is an open-play finish; there is no
     *  canonical corner goal type, so CORNER is never produced here. */
    private static AnimationPhase phaseFor(String goalType) {
        if (goalType == null) return AnimationPhase.OPEN_PLAY;
        return switch (goalType) {
            case "PENALTY" -> AnimationPhase.PENALTY;
            case "FREE_KICK" -> AnimationPhase.FREE_KICK;
            default -> AnimationPhase.OPEN_PLAY; // OPEN_PLAY, HEADER, anything else
        };
    }

    private static void addSnapshots(List<PlayerSnapshot> out, List<Contributor> contributors,
                                     long teamId, Map<Long, Integer> shirtNumbers) {
        for (Contributor c : contributors) {
            int shirt = shirtNumbers.getOrDefault(c.playerId(), 0);
            out.add(new PlayerSnapshot(
                    c.playerId(), teamId, c.name(), Math.max(0, shirt),
                    c.position(), "ROLE_" + c.position(), Math.max(0.0, c.rating())));
        }
    }

    // ---- Engine replay → frontend DTO ------------------------------------

    private static GoalAnimationData toGoalAnimationData(AnimationReplay replay, int firstHalfStoppage) {
        GoalAnimationData data = new GoalAnimationData();
        data.setMinute(replay.minute());
        data.setSlotIndex(replay.slotIndex());
        data.setFixtureKey(replay.fixtureKey());
        data.setGeneratorVersion(replay.renderedWithVersion());
        data.setScoringTeamId(replay.scoringTeamId());
        data.setDefendingTeamId(replay.defendingTeamId());
        data.setHomeTeamId(replay.homeTeamId());
        data.setTotalFrames(Math.max(0, replay.frames().size() - 1));
        data.setFirstHalfStoppage(firstHalfStoppage);
        data.setAnimationType(replay.phase().name());
        data.setOutcome(replay.outcome().name());
        data.setHomeAttacksRight(replay.homeAttacksRight());

        List<PlayerSnapshot> players = replay.players();
        data.setPlayers(toPlayers(players));
        data.setScorerPlayerId(replay.scorerId());
        PlayerSnapshot scorer = find(players, replay.scorerId());
        if (scorer != null) {
            data.setScorerName(scorer.name());
            data.setScorerNumber(scorer.shirtNumber());
        }
        if (replay.assisterId() != null) {
            data.setAssisterPlayerId(replay.assisterId());
            PlayerSnapshot assister = find(players, replay.assisterId());
            if (assister != null) data.setAssisterName(assister.name());
        }

        data.setFrames(toFrames(replay.frames()));
        data.setEvents(toEvents(replay.events()));
        return data;
    }

    private static List<GoalAnimationData.AnimationPlayer> toPlayers(List<PlayerSnapshot> players) {
        List<GoalAnimationData.AnimationPlayer> out = new ArrayList<>(players.size());
        for (PlayerSnapshot p : players) {
            GoalAnimationData.AnimationPlayer ap = new GoalAnimationData.AnimationPlayer();
            ap.setPlayerId(p.playerId());
            ap.setName(p.name());
            ap.setShirtNumber(p.shirtNumber());
            ap.setTeamId(p.teamId());
            ap.setPosition(p.tacticalPosition());
            out.add(ap);
        }
        return out;
    }

    private static List<GoalAnimationData.AnimationFrame> toFrames(
            List<com.footballmanagergamesimulator.animation.AnimationFrame> frames) {
        List<GoalAnimationData.AnimationFrame> out = new ArrayList<>(frames.size());
        for (com.footballmanagergamesimulator.animation.AnimationFrame f : frames) {
            GoalAnimationData.AnimationFrame af = new GoalAnimationData.AnimationFrame();
            af.setBallX(f.ball().x());
            af.setBallY(f.ball().y());
            af.setBallCarrierId(f.ballCarrierId());
            List<double[]> positions = new ArrayList<>(f.positions().size());
            for (PitchPoint pt : f.positions()) positions.add(new double[]{pt.x(), pt.y()});
            af.setPositions(positions);
            out.add(af);
        }
        return out;
    }

    private static List<GoalAnimationData.AnimationEvent> toEvents(
            List<com.footballmanagergamesimulator.animation.AnimationEvent> events) {
        List<GoalAnimationData.AnimationEvent> out = new ArrayList<>(events.size());
        for (com.footballmanagergamesimulator.animation.AnimationEvent e : events) {
            GoalAnimationData.AnimationEvent ae = new GoalAnimationData.AnimationEvent();
            ae.setFrame(e.frame());
            ae.setType(e.type());
            ae.setFromPlayerId(e.fromPlayerId());
            ae.setToPlayerId(e.toPlayerId());
            out.add(ae);
        }
        return out;
    }

    private static PlayerSnapshot find(List<PlayerSnapshot> players, long id) {
        for (PlayerSnapshot p : players) if (p.playerId() == id) return p;
        return null;
    }
}
