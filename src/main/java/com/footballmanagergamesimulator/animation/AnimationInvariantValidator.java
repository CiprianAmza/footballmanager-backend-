package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executable canonical and physical contract for completed replays. */
public final class AnimationInvariantValidator {
    private static final double POSITION_ROUNDING = 0.15;
    private static final double ACCELERATION_ROUNDING = 0.30;
    private final AnimationPhysicsProfile profile;

    public AnimationInvariantValidator(AnimationPhysicsProfile profile) {
        this.profile = Objects.requireNonNull(profile);
    }

    public List<String> validate(AnimationReplay replay, MatchMomentSpec spec) {
        List<String> errors = new ArrayList<>();
        validateCanonical(replay, spec, errors);
        if (replay.frames().size() != FrameCompiler.TOTAL_FRAMES + 1) {
            errors.add("expected 151 frames, got " + replay.frames().size());
            return errors;
        }

        List<PlayerSnapshot> expected = new ArrayList<>(spec.attackers());
        expected.addAll(spec.defenders());
        if (!replay.players().equals(expected)) errors.add("replay participants differ from snapshot");
        Set<Long> ids = new HashSet<>();
        for (PlayerSnapshot player : replay.players()) ids.add(player.playerId());

        for (int frame = 0; frame < replay.frames().size(); frame++) {
            AnimationFrame current = replay.frames().get(frame);
            if (current.positions().size() != replay.players().size()) {
                errors.add("frame " + frame + " position count mismatch");
                return errors;
            }
            for (int player = 0; player < current.positions().size(); player++) {
                PitchPoint point = current.positions().get(player);
                if (point.x() < 0.3 || point.x() > 99.7 || point.y() < 0.8 || point.y() > 99.2)
                    errors.add("frame " + frame + " player " + player + " outside pitch");
                if (frame > 0) {
                    PitchPoint previous = replay.frames().get(frame - 1).positions().get(player);
                    double step = point.distanceTo(previous);
                    if (step > profile.maxPlayerStep() + POSITION_ROUNDING)
                        errors.add("frame " + frame + " player " + player + " speed " + step);
                }
                if (frame > 1) {
                    PitchPoint before = replay.frames().get(frame - 2).positions().get(player);
                    PitchPoint previous = replay.frames().get(frame - 1).positions().get(player);
                    double ax = (point.x() - previous.x()) - (previous.x() - before.x());
                    double ay = (point.y() - previous.y()) - (previous.y() - before.y());
                    double acceleration = Math.hypot(ax, ay);
                    if (acceleration > profile.maxPlayerAcceleration() + ACCELERATION_ROUNDING)
                        errors.add("frame " + frame + " player " + player + " acceleration " + acceleration);
                }
            }
            if (frame > 0) {
                double ballStep = current.ball().distanceTo(replay.frames().get(frame - 1).ball());
                if (ballStep > profile.maxBallStep() + POSITION_ROUNDING)
                    errors.add("frame " + frame + " ball speed " + ballStep);
            }
            if (current.ballCarrierId() != 0) {
                int carrier = indexOf(replay.players(), current.ballCarrierId());
                if (carrier < 0) errors.add("frame " + frame + " unknown carrier");
                else if (current.ball().distanceTo(current.positions().get(carrier)) > POSITION_ROUNDING)
                    errors.add("frame " + frame + " ball detached from carrier");
            }
        }

        validateEvents(replay, spec, ids, errors);
        return errors;
    }

    private static void validateCanonical(AnimationReplay replay, MatchMomentSpec spec, List<String> errors) {
        if (!replay.key().equals(spec.key())) errors.add("identity changed");
        if (replay.minute() != spec.minute()) errors.add("minute changed");
        if (replay.firstHalfStoppage() != spec.firstHalfStoppage()) errors.add("stoppage changed");
        if (replay.scoringTeamId() != spec.scoringTeamId()
                || replay.defendingTeamId() != spec.defendingTeamId()
                || replay.homeTeamId() != spec.homeTeamId()) errors.add("teams changed");
        if (replay.phase() != spec.phase()) errors.add("phase changed");
        if (replay.outcome() != spec.outcome()) errors.add("outcome changed");
        if (replay.scorerId() != spec.scorerId()) errors.add("scorer changed");
        if (!Objects.equals(replay.assisterId(), spec.assisterId())) errors.add("assist changed");
        if (replay.renderedWithVersion() != spec.generatorVersion()) errors.add("version changed");
    }

    private static void validateEvents(AnimationReplay replay, MatchMomentSpec spec,
                                       Set<Long> ids, List<String> errors) {
        AnimationEvent shot = null;
        AnimationEvent lastPass = null;
        for (AnimationEvent event : replay.events()) {
            if (event.frame() < 0 || event.frame() > FrameCompiler.TOTAL_FRAMES)
                errors.add("event outside frames");
            if (event.fromPlayerId() != 0 && !ids.contains(event.fromPlayerId()))
                errors.add("event actor outside snapshot");
            if (event.toPlayerId() != 0 && !ids.contains(event.toPlayerId()))
                errors.add("event receiver outside snapshot");
            if ("PASS".equals(event.type())) {
                lastPass = event;
                validatePass(replay, event, errors);
            }
            if ("SHOT".equals(event.type())) {
                if (shot != null) errors.add("multiple shots");
                shot = event;
            }
        }
        if (shot == null) { errors.add("missing shot"); return; }
        if (shot.fromPlayerId() != spec.scorerId()) errors.add("final shot not by canonical scorer");
        AnimationFrame shotFrame = replay.frames().get(shot.frame());
        int scorerIndex = indexOf(replay.players(), spec.scorerId());
        if (shotFrame.ball().distanceTo(shotFrame.positions().get(scorerIndex)) > 3.0)
            errors.add("scorer does not touch ball at shot");
        if (spec.assisterId() != null) {
            if (lastPass == null || lastPass.fromPlayerId() != spec.assisterId()
                    || lastPass.toPlayerId() != spec.scorerId()) errors.add("canonical assist is not final pass");
        }

        AnimationEvent result = replay.events().isEmpty() ? null : replay.events().get(replay.events().size() - 1);
        if (result == null || !result.type().equals(spec.outcome().name())) {
            errors.add("final event does not match outcome");
            return;
        }
        int resultFrame = Math.min(result.frame(), FrameCompiler.TOTAL_FRAMES);
        AnimationFrame atResult = replay.frames().get(resultFrame);
        double goalLine = replay.scoringTeamAttacksRight() ? 100 : 0;
        switch (spec.outcome()) {
            case GOAL -> {
                if (Math.abs(atResult.ball().x() - goalLine) > 0.2) errors.add("goal does not cross line");
                if (atResult.ball().y() <= FrameCompiler.GOAL_MIN_Y
                        || atResult.ball().y() >= FrameCompiler.GOAL_MAX_Y) errors.add("goal outside posts");
            }
            case MISS -> {
                if (Math.abs(atResult.ball().x() - goalLine) > 0.3) errors.add("miss does not reach goal line");
                if (mouthCrossed(replay.frames(), goalLine)) errors.add("miss enters goal");
            }
            case SAVE -> {
                int keeper = indexOf(replay.players(), result.fromPlayerId());
                if (keeper < 0 || !replay.players().get(keeper).goalkeeper()) errors.add("save actor is not goalkeeper");
                else if (atResult.ball().distanceTo(atResult.positions().get(keeper)) > 0.2)
                    errors.add("save does not intersect goalkeeper");
                if (mouthCrossed(replay.frames(), goalLine)) errors.add("saved ball crosses goal line");
            }
            case BLOCKED -> {
                int blocker = indexOf(replay.players(), result.fromPlayerId());
                if (blocker < spec.attackers().size()) errors.add("blocker is not defending");
                else if (atResult.ball().distanceTo(atResult.positions().get(blocker)) > 0.2)
                    errors.add("blocked ball misses defender");
                if (mouthCrossed(replay.frames(), goalLine)) errors.add("blocked ball crosses goal line");
            }
        }
    }

    private static void validatePass(AnimationReplay replay, AnimationEvent pass, List<String> errors) {
        AnimationFrame release = replay.frames().get(pass.frame());
        int passer = indexOf(replay.players(), pass.fromPlayerId());
        if (passer >= 0 && release.ball().distanceTo(release.positions().get(passer)) > 3.0)
            errors.add("pass not released by passer");
        for (int frame = pass.frame() + 1; frame < replay.frames().size(); frame++) {
            long carrier = replay.frames().get(frame).ballCarrierId();
            if (carrier == 0) continue;
            if (carrier != pass.toPlayerId()) errors.add("possession transfers before intended receiver");
            return;
        }
        errors.add("receiver never gains possession");
    }

    private static boolean mouthCrossed(List<AnimationFrame> frames, double goalLine) {
        for (AnimationFrame frame : frames) {
            if (Math.abs(frame.ball().x() - goalLine) <= 0.2
                    && frame.ball().y() > FrameCompiler.GOAL_MIN_Y
                    && frame.ball().y() < FrameCompiler.GOAL_MAX_Y) return true;
        }
        return false;
    }

    private static int indexOf(List<PlayerSnapshot> players, long playerId) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == playerId) return i;
        return -1;
    }
}
