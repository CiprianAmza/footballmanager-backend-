package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

import static com.footballmanagergamesimulator.animation.AnimationPhysics.BLOCK_REACH;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.GK_REACH;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.GOAL_MOUTH_MAX_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.GOAL_MOUTH_MIN_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.MAX_BALL_STEP;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.TOTAL_FRAMES;

/**
 * Re-checks every physical/canonical invariant on a finished replay.
 * Used by tests for all patterns × outcomes, and by the director as a last
 * line of defence (a violating replay is rebuilt with the safe fallback).
 */
public final class AnimationPhysicsValidator {

    /** Rounding slack: coordinates are rounded to 0.1 before validation. */
    private static final double EPS = 0.15;
    private static final double ACCEL_ROUNDING_EPS = 0.30;

    private final AnimationMotionLimits motionLimits;

    public AnimationPhysicsValidator() {
        this(AnimationMotionLimits.defaults());
    }

    public AnimationPhysicsValidator(AnimationMotionLimits motionLimits) {
        this.motionLimits = Objects.requireNonNull(motionLimits, "motionLimits");
    }

    public List<String> validate(AnimationReplay replay, MatchMomentSpec spec) {
        List<String> v = new ArrayList<>();
        List<ReplayFrame> frames = replay.frames();
        int n = replay.players().size();

        if (!replay.fixtureKey().equals(spec.fixtureKey())
                || replay.slotIndex() != spec.slotIndex()) v.add("canonical identity changed");
        if (replay.minute() != spec.minute()) v.add("canonical minute changed");
        if (replay.firstHalfStoppage() != spec.firstHalfStoppage())
            v.add("first-half stoppage changed");
        if (replay.scoringTeamId() != spec.scoringTeamId()
                || replay.defendingTeamId() != spec.defendingTeamId()
                || replay.homeTeamId() != spec.homeTeamId()) v.add("canonical teams changed");
        if (replay.phase() != spec.phase()) v.add("canonical phase changed");
        if (replay.outcome() != spec.outcome()) v.add("canonical outcome changed");
        if (replay.scorerId() != spec.scorerId()) v.add("canonical scorer changed");
        if (!java.util.Objects.equals(replay.assisterId(), spec.assisterId()))
            v.add("canonical assister changed");
        if (replay.renderedWithVersion() != spec.generatorVersion())
            v.add("generator version changed");

        if (frames.size() != TOTAL_FRAMES + 1) {
            v.add("expected " + (TOTAL_FRAMES + 1) + " frames, got " + frames.size());
            return v;
        }

        // Roster: exactly the snapshot players, attacking side first.
        List<PlayerSnapshot> expected = new ArrayList<>(spec.attackingPlayers());
        expected.addAll(spec.defendingPlayers());
        if (!replay.players().equals(expected)) v.add("player list differs from snapshots");
        Set<Long> ids = new HashSet<>();
        for (PlayerSnapshot p : replay.players()) ids.add(p.playerId());

        // Per-frame checks.
        for (int f = 0; f <= TOTAL_FRAMES; f++) {
            ReplayFrame fr = frames.get(f);
            if (fr.positions().size() != n) {
                v.add("frame " + f + ": positions size " + fr.positions().size());
                return v;
            }
            for (int i = 0; i < n; i++) {
                double[] p = fr.positions().get(i);
                if (p[0] < 0.3 || p[0] > 99.7 || p[1] < 0.8 || p[1] > 99.2) {
                    v.add("frame " + f + ": player " + i + " out of bounds (" + p[0] + "," + p[1] + ")");
                }
                if (f > 0) {
                    double[] q = frames.get(f - 1).positions().get(i);
                    if (dist(p, q) > motionLimits.maxPlayerStep() + EPS) {
                        v.add("frame " + f + ": player " + i + " step " + dist(p, q));
                    }
                    if (f > 1) {
                        double[] before = frames.get(f - 2).positions().get(i);
                        double previousVx = q[0] - before[0];
                        double previousVy = q[1] - before[1];
                        double currentVx = p[0] - q[0];
                        double currentVy = p[1] - q[1];
                        double acceleration = Math.hypot(
                                currentVx - previousVx, currentVy - previousVy);
                        if (acceleration > motionLimits.maxPlayerAcceleration() + ACCEL_ROUNDING_EPS) {
                            v.add("frame " + f + ": player " + i
                                    + " acceleration " + acceleration);
                        }
                    }
                }
            }
            if (f > 0) {
                double step = dist(new double[]{fr.ballX(), fr.ballY()},
                        new double[]{frames.get(f - 1).ballX(), frames.get(f - 1).ballY()});
                if (step > MAX_BALL_STEP + EPS) v.add("frame " + f + ": ball step " + step);
            }
            long carrier = fr.ballCarrierId();
            if (carrier != 0) {
                if (!ids.contains(carrier)) {
                    v.add("frame " + f + ": unknown carrier " + carrier);
                } else {
                    double[] cp = fr.positions().get(indexOf(replay.players(), carrier));
                    if (dist(cp, new double[]{fr.ballX(), fr.ballY()}) > EPS) {
                        v.add("frame " + f + ": ball not at carrier " + carrier);
                    }
                }
            }
        }

        // Events.
        List<ReplayEvent> events = replay.events();
        ReplayEvent shot = null;
        ReplayEvent outcomeEvent = events.isEmpty() ? null : events.get(events.size() - 1);
        ReplayEvent lastPass = null;
        for (ReplayEvent e : events) {
            if ("SHOT".equals(e.type())) {
                if (shot != null) v.add("more than one SHOT event");
                shot = e;
            }
            if ("PASS".equals(e.type())) lastPass = e;
            if (e.fromPlayerId() != 0 && !ids.contains(e.fromPlayerId()))
                v.add("event with unknown actor " + e.fromPlayerId());
            if (e.toPlayerId() != 0 && !ids.contains(e.toPlayerId()))
                v.add("event with unknown receiver " + e.toPlayerId());
            if ("PASS".equals(e.type())) validatePassTransfer(replay, e, v);
        }
        if (shot == null) {
            v.add("missing SHOT event");
            return v;
        }
        if (shot.fromPlayerId() != spec.scorerId())
            v.add("SHOT by " + shot.fromPlayerId() + ", canonical scorer is " + spec.scorerId());
        if (outcomeEvent == null || !outcomeEvent.type().equals(spec.outcome().name()))
            v.add("final event is not the canonical outcome " + spec.outcome());
        if (spec.assisterId() != null) {
            if (lastPass == null) {
                v.add("assister present but no pass in the move");
            } else if (lastPass.fromPlayerId() != spec.assisterId()
                    || lastPass.toPlayerId() != spec.scorerId()) {
                v.add("final pass " + lastPass.fromPlayerId() + "→" + lastPass.toPlayerId()
                        + " is not assister→scorer");
            }
        }

        // The scorer takes the final touch: at the shot frame the ball is at his feet.
        ReplayFrame shotFrame = frames.get(Math.min(shot.frame(), TOTAL_FRAMES));
        double[] scorerPos = shotFrame.positions().get(indexOf(replay.players(), spec.scorerId()));
        if (dist(scorerPos, new double[]{shotFrame.ballX(), shotFrame.ballY()}) > 3.0)
            v.add("scorer is not at the ball on the SHOT frame");

        // Outcome geometry (orientation-aware).
        double goalLineX = replay.scoringTeamAttacksRight() ? 100 : 0;
        int of = outcomeEvent != null ? Math.min(outcomeEvent.frame(), TOTAL_FRAMES) : TOTAL_FRAMES;
        ReplayFrame at = frames.get(of);
        switch (spec.outcome()) {
            case GOAL -> {
                if (Math.abs(at.ballX() - goalLineX) > 0.2)
                    v.add("GOAL: ball not on the goal line (" + at.ballX() + ")");
                if (at.ballY() <= GOAL_MOUTH_MIN_Y + 0.4 || at.ballY() >= GOAL_MOUTH_MAX_Y - 0.4)
                    v.add("GOAL: ball not between the posts (" + at.ballY() + ")");
            }
            case MISS -> {
                if (Math.abs(at.ballX() - goalLineX) > 1.0)
                    v.add("MISS: ball did not reach the goal line area");
                if (mouthCrossed(frames, goalLineX)) v.add("MISS: ball crossed inside the goal mouth");
            }
            case SAVE -> {
                double[] gk = at.positions().get(indexOf(replay.players(), outcomeEvent.fromPlayerId()));
                if (dist(gk, new double[]{at.ballX(), at.ballY()}) > GK_REACH)
                    v.add("SAVE: ball outside the goalkeeper's reach");
                if (mouthCrossed(frames, goalLineX)) v.add("SAVE: ball crossed inside the goal mouth");
            }
            case BLOCKED -> {
                int bi = indexOf(replay.players(), outcomeEvent.fromPlayerId());
                if (bi < spec.attackingPlayers().size())
                    v.add("BLOCKED: blocker is not a defending player");
                double[] blocker = at.positions().get(bi);
                if (dist(blocker, new double[]{at.ballX(), at.ballY()}) > BLOCK_REACH)
                    v.add("BLOCKED: ball does not intersect the blocking defender");
                if (mouthCrossed(frames, goalLineX)) v.add("BLOCKED: ball crossed inside the goal mouth");
            }
        }
        return v;
    }

    /** True when some frame puts the ball ON the goal line inside the mouth. */
    private boolean mouthCrossed(List<ReplayFrame> frames, double goalLineX) {
        for (ReplayFrame f : frames) {
            if (Math.abs(f.ballX() - goalLineX) <= 0.3
                    && f.ballY() > GOAL_MOUTH_MIN_Y && f.ballY() < GOAL_MOUTH_MAX_Y) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<PlayerSnapshot> players, long id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == id) return i;
        return -1;
    }

    /** A receiver acquires possession only after the continuous flight reaches him. */
    private static void validatePassTransfer(AnimationReplay replay, ReplayEvent pass,
                                             List<String> violations) {
        int release = Math.max(0, Math.min(pass.frame(), TOTAL_FRAMES));
        ReplayFrame atRelease = replay.frames().get(release);
        int passerIndex = indexOf(replay.players(), pass.fromPlayerId());
        if (passerIndex < 0) return;
        double[] passer = atRelease.positions().get(passerIndex);
        if (dist(passer, new double[]{atRelease.ballX(), atRelease.ballY()}) > 3.0) {
            violations.add("PASS at frame " + release + ": ball not released by passer");
        }

        int acquiredAt = -1;
        long acquiredBy = 0;
        for (int f = release + 1; f <= TOTAL_FRAMES; f++) {
            long carrier = replay.frames().get(f).ballCarrierId();
            if (carrier != 0) {
                acquiredAt = f;
                acquiredBy = carrier;
                break;
            }
        }
        if (acquiredAt < 0) {
            violations.add("PASS at frame " + release + ": receiver never acquired possession");
        } else if (acquiredBy != pass.toPlayerId()) {
            violations.add("PASS at frame " + release + ": possession transferred to "
                    + acquiredBy + " instead of " + pass.toPlayerId());
        }
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }
}
