package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generator version 1: script -> 151 continuous physical frames. */
public final class FrameCompiler {
    public static final int VERSION = 1;
    public static final int TOTAL_FRAMES = 150;
    public static final double GOAL_MIN_Y = 44;
    public static final double GOAL_MAX_Y = 56;

    private enum BallKind { DEAD, CARRIED, FLIGHT }
    private record Span(int from, int to, PitchPoint target, boolean patrol) { }
    private record BallLeg(BallKind kind, int from, int to, int carrier,
                           int fromPlayer, int toPlayer, PitchPoint fixedStart,
                           PitchPoint fixedEnd, double bend) { }
    private record Timeline(int[] arrival, int[] release, int shotFrame, List<PlayScript.Touch> touches) { }

    private final AnimationPhysicsProfile profile;

    public FrameCompiler(AnimationPhysicsProfile profile) {
        this.profile = profile;
    }

    public AnimationReplay compile(MatchMomentSpec spec, PlayScript original, Random random) {
        List<PlayerSnapshot> players = new ArrayList<>(spec.attackers());
        players.addAll(spec.defenders());
        int attackingCount = spec.attackers().size();
        int playerCount = players.size();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < playerCount; i++) index.put(players.get(i).playerId(), i);

        PlayScript script = fitTimeline(original, index);
        Timeline timeline = timeline(script, index);
        List<PlayScript.Touch> touches = timeline.touches();
        int finalTouch = touches.size() - 1;

        PitchPoint[] formation = new PitchPoint[playerCount];
        assignFormation(spec.attackers(), false, formation, 0);
        assignFormation(spec.defenders(), true, formation, attackingCount);
        int goalkeeper = goalkeeperIndex(spec.defenders(), attackingCount, formation);

        PitchPoint plannedShotOrigin = script.deadBallSpot() != null && touches.size() == 1
                ? script.deadBallSpot() : touches.get(finalTouch).target();
        double targetY = switch (spec.outcome()) {
            case MISS -> random.nextBoolean() ? between(random, 28, 39) : between(random, 61, 72);
            case SAVE -> between(random, 46, 54);
            default -> between(random, 46, 54);
        };
        double reboundDirection = random.nextBoolean() ? 1 : -1;
        double goalkeeperDirection = random.nextBoolean() ? 1 : -1;

        int blocker = -1;
        PitchPoint blockPoint = null;
        if (spec.outcome() == AnimationOutcome.BLOCKED) {
            blockPoint = new PitchPoint(
                    plannedShotOrigin.x() + (100 - plannedShotOrigin.x()) * 0.55,
                    plannedShotOrigin.y() + (targetY - plannedShotOrigin.y()) * 0.55);
            double nearest = Double.MAX_VALUE;
            for (int i = attackingCount; i < playerCount; i++) {
                if (i == goalkeeper) continue;
                double distance = formation[i].distanceTo(blockPoint);
                if (distance < nearest) { nearest = distance; blocker = i; }
            }
            if (blocker < 0) blocker = goalkeeper;
        }

        PitchPoint estimatedShotEnd = switch (spec.outcome()) {
            case SAVE -> new PitchPoint(98, targetY);
            case BLOCKED -> blockPoint;
            default -> new PitchPoint(100, targetY);
        };
        int shotArrival = timeline.shotFrame() + flightFrames(
                plannedShotOrigin.distanceTo(estimatedShotEnd), 5);
        shotArrival = Math.min(TOTAL_FRAMES - 20, shotArrival);

        List<List<Span>> tracks = new ArrayList<>();
        PitchPoint[] starts = new PitchPoint[playerCount];
        for (int i = 0; i < playerCount; i++) {
            starts[i] = formation[i];
            tracks.add(new ArrayList<>());
            boolean attacking = i < attackingCount;
            PitchPoint ambient = ambientTarget(formation[i], positionGroup(players.get(i).tacticalPosition()),
                    attacking, players.get(i).playerId());
            tracks.get(i).add(new Span(0, TOTAL_FRAMES + 1, ambient, true));
        }

        int[] roleEnd = new int[playerCount];
        for (int t = 0; t < touches.size(); t++) {
            PlayScript.Touch touch = touches.get(t);
            Integer playerIndex = index.get(touch.playerId());
            if (playerIndex == null) throw new IllegalArgumentException("script uses non-snapshot player " + touch.playerId());
            int arrive = timeline.arrival()[t];
            int release = t == finalTouch ? timeline.shotFrame() : timeline.release()[t];
            if (t == 0) {
                if (script.deadBallSpot() != null) {
                    PitchPoint spot = script.deadBallSpot();
                    starts[playerIndex] = clamp(new PitchPoint(spot.x() - 5, spot.y() + (spot.y() > 50 ? -3 : 3)));
                    tracks.get(playerIndex).add(new Span(0, release, spot, false));
                } else {
                    starts[playerIndex] = clamp(touch.target());
                    tracks.get(playerIndex).add(new Span(0, release, clamp(new PitchPoint(touch.target().x() + 1, touch.target().y())), false));
                }
            } else {
                tracks.get(playerIndex).add(new Span(roleEnd[playerIndex], arrive, touch.target(), false));
                tracks.get(playerIndex).add(new Span(arrive, release,
                        clamp(new PitchPoint(touch.target().x() + 1, touch.target().y())), false));
            }
            if (t < finalTouch) {
                tracks.get(playerIndex).add(new Span(release, timeline.shotFrame(),
                        clamp(new PitchPoint(Math.min(92, touch.target().x() + 7),
                                touch.target().y() + (50 - touch.target().y()) * 0.25)), false));
            } else {
                tracks.get(playerIndex).add(new Span(timeline.shotFrame(), TOTAL_FRAMES + 1,
                        clamp(new PitchPoint(plannedShotOrigin.x() + 3,
                                plannedShotOrigin.y() + (targetY - plannedShotOrigin.y()) * 0.12)), false));
            }
            roleEnd[playerIndex] = release;
        }

        if (goalkeeper >= 0) {
            tracks.get(goalkeeper).add(new Span(0, timeline.shotFrame(), new PitchPoint(97.5, 50), true));
            double diveY = switch (spec.outcome()) {
                case SAVE -> targetY;
                case GOAL -> clampY(50 + goalkeeperDirection * 9);
                default -> clampY(50 + goalkeeperDirection * 2);
            };
            tracks.get(goalkeeper).add(new Span(timeline.shotFrame(), TOTAL_FRAMES + 1,
                    new PitchPoint(98, diveY), false));
        }
        if (blocker >= 0 && blockPoint != null) {
            tracks.get(blocker).add(new Span(Math.max(0, timeline.shotFrame() - 20),
                    TOTAL_FRAMES + 1, blockPoint, false));
        }

        PitchPoint[][] positions = integrate(tracks, starts, players);

        List<BallLeg> ballLegs = new ArrayList<>();
        if (script.deadBallSpot() != null) {
            ballLegs.add(new BallLeg(BallKind.DEAD, 0, timeline.release()[0], -1,
                    -1, -1, script.deadBallSpot(), null, 0));
        } else {
            ballLegs.add(new BallLeg(BallKind.CARRIED, 0, timeline.release()[0],
                    index.get(touches.get(0).playerId()), -1, -1, null, null, 0));
        }
        for (int t = 1; t < touches.size(); t++) {
            int from = index.get(touches.get(t - 1).playerId());
            int to = index.get(touches.get(t).playerId());
            PitchPoint fixedStart = t == 1 && script.deadBallSpot() != null ? script.deadBallSpot() : null;
            ballLegs.add(new BallLeg(BallKind.FLIGHT, timeline.release()[t - 1], timeline.arrival()[t],
                    -1, from, to, fixedStart, null, touches.get(t).arrivalBend()));
            int release = t == finalTouch ? timeline.shotFrame() : timeline.release()[t];
            ballLegs.add(new BallLeg(BallKind.CARRIED, timeline.arrival()[t], release,
                    to, -1, -1, null, null, 0));
        }

        int scorerIndex = index.get(touches.get(finalTouch).playerId());
        PitchPoint fixedShotStart = script.deadBallSpot() != null && touches.size() == 1
                ? script.deadBallSpot() : null;
        PitchPoint shotEnd = switch (spec.outcome()) {
            case SAVE -> positions[shotArrival][goalkeeper];
            case BLOCKED -> positions[shotArrival][blocker];
            default -> new PitchPoint(100, targetY);
        };
        ballLegs.add(new BallLeg(BallKind.FLIGHT, timeline.shotFrame(), shotArrival,
                -1, scorerIndex, -1, fixedShotStart, shotEnd, script.shotBend()));

        PitchPoint[] ball = compileBall(ballLegs, positions, shotArrival, spec.outcome(), shotEnd, reboundDirection);
        long[] carrier = compileCarriers(ballLegs, players);

        List<AnimationEvent> events = new ArrayList<>();
        for (int t = 1; t < touches.size(); t++) {
            events.add(new AnimationEvent(timeline.release()[t - 1], "PASS",
                    touches.get(t - 1).playerId(), touches.get(t).playerId()));
        }
        events.add(new AnimationEvent(timeline.shotFrame(), "SHOT", touches.get(finalTouch).playerId(), 0));
        long outcomeActor = switch (spec.outcome()) {
            case SAVE -> players.get(goalkeeper).playerId();
            case BLOCKED -> players.get(blocker).playerId();
            default -> touches.get(finalTouch).playerId();
        };
        events.add(new AnimationEvent(shotArrival, spec.outcome().name(), outcomeActor, 0));

        boolean homeAttacksRight = spec.firstHalf();
        boolean scoringIsHome = spec.scoringTeamId() == spec.homeTeamId();
        boolean mirror = spec.firstHalf() != scoringIsHome;
        if (mirror) {
            for (int frame = 0; frame <= TOTAL_FRAMES; frame++) {
                ball[frame] = ball[frame].mirrorX();
                for (int player = 0; player < playerCount; player++)
                    positions[frame][player] = positions[frame][player].mirrorX();
            }
        }

        List<AnimationFrame> frames = new ArrayList<>(TOTAL_FRAMES + 1);
        for (int frame = 0; frame <= TOTAL_FRAMES; frame++) {
            List<PitchPoint> framePositions = new ArrayList<>(playerCount);
            for (int player = 0; player < playerCount; player++) framePositions.add(positions[frame][player].rounded());
            frames.add(new AnimationFrame(ball[frame].rounded(), carrier[frame], framePositions));
        }
        return new AnimationReplay(spec.fixtureKey(), spec.slotIndex(), spec.minute(), spec.firstHalfStoppage(),
                spec.scoringTeamId(), spec.defendingTeamId(), spec.homeTeamId(), spec.phase(), spec.outcome(),
                script.pattern(), VERSION, spec.scorerId(), spec.assisterId(), homeAttacksRight, !mirror,
                players, frames, events);
    }

    private PlayScript fitTimeline(PlayScript script, Map<Long, Integer> index) {
        PlayScript fitted = script;
        while (timeline(fitted, index).shotFrame() > TOTAL_FRAMES - 25 && fitted.touches().size() > 3) {
            List<PlayScript.Touch> tail = fitted.touches().subList(fitted.touches().size() - 3, fitted.touches().size());
            fitted = new PlayScript(fitted.pattern(), tail, fitted.deadBallSpot(),
                    Math.min(fitted.preludeFrames(), 30), fitted.shotBend());
        }
        if (timeline(fitted, index).shotFrame() > TOTAL_FRAMES - 25)
            throw new IllegalArgumentException("script does not fit 151 frames: " + script.pattern());
        return fitted;
    }

    private Timeline timeline(PlayScript script, Map<Long, Integer> index) {
        List<PlayScript.Touch> touches = script.touches();
        int[] arrival = new int[touches.size()];
        int[] release = new int[touches.size()];
        arrival[0] = 0;
        release[0] = script.deadBallSpot() == null
                ? Math.max(4, touches.get(0).dwellFrames()) : Math.max(20, script.preludeFrames());
        PitchPoint previous = script.deadBallSpot() == null ? touches.get(0).target() : script.deadBallSpot();
        for (int i = 1; i < touches.size(); i++) {
            arrival[i] = release[i - 1] + flightFrames(previous.distanceTo(touches.get(i).target()), 5);
            release[i] = arrival[i] + Math.max(4, touches.get(i).dwellFrames());
            previous = touches.get(i).target();
        }
        int shotFrame = touches.size() == 1 ? release[0] : release[touches.size() - 1];
        return new Timeline(arrival, release, shotFrame, touches);
    }

    private PitchPoint[][] integrate(List<List<Span>> tracks, PitchPoint[] starts,
                                     List<PlayerSnapshot> players) {
        PitchPoint[][] positions = new PitchPoint[TOTAL_FRAMES + 1][starts.length];
        double[][] velocity = new double[starts.length][2];
        for (int player = 0; player < starts.length; player++) positions[0][player] = starts[player];
        for (int frame = 1; frame <= TOTAL_FRAMES; frame++) {
            for (int player = 0; player < starts.length; player++) {
                Span active = activeSpan(tracks.get(player), frame);
                PitchPoint target = active.target();
                if (active.patrol()) {
                    long id = players.get(player).playerId();
                    target = new PitchPoint(target.x() + Math.sin(frame * (0.05 + id % 7 * 0.01) + id) * 1.2,
                            target.y() + Math.cos(frame * (0.04 + id % 5 * 0.012) + id) * 1.4);
                }
                target = clamp(target);
                PitchPoint previous = positions[frame - 1][player];
                double dx = target.x() - previous.x();
                double dy = target.y() - previous.y();
                double distance = Math.hypot(dx, dy);
                double desiredSpeed = Math.min(profile.maxPlayerStep(), distance * 0.30 + 0.02);
                double desiredX = distance > 1e-9 ? dx / distance * desiredSpeed : 0;
                double desiredY = distance > 1e-9 ? dy / distance * desiredSpeed : 0;
                double ax = desiredX - velocity[player][0];
                double ay = desiredY - velocity[player][1];
                double acceleration = Math.hypot(ax, ay);
                if (acceleration > profile.maxPlayerAcceleration()) {
                    ax *= profile.maxPlayerAcceleration() / acceleration;
                    ay *= profile.maxPlayerAcceleration() / acceleration;
                }
                velocity[player][0] += ax;
                velocity[player][1] += ay;
                PitchPoint next = clamp(new PitchPoint(previous.x() + velocity[player][0],
                        previous.y() + velocity[player][1]));
                if (next.x() <= 0.5 || next.x() >= 99.5) velocity[player][0] = 0;
                if (next.y() <= 1 || next.y() >= 99) velocity[player][1] = 0;
                positions[frame][player] = next;
            }
        }
        return positions;
    }

    private static Span activeSpan(List<Span> spans, int frame) {
        Span active = spans.get(0);
        for (Span span : spans) if (frame >= span.from() && frame < span.to()) active = span;
        return active;
    }

    private PitchPoint[] compileBall(List<BallLeg> legs, PitchPoint[][] positions, int shotArrival,
                                     AnimationOutcome outcome, PitchPoint shotEnd, double reboundDirection) {
        PitchPoint[] ball = new PitchPoint[TOTAL_FRAMES + 1];
        for (BallLeg leg : legs) {
            for (int frame = leg.from(); frame <= Math.min(leg.to(), TOTAL_FRAMES); frame++) {
                if (leg.kind() == BallKind.DEAD) ball[frame] = leg.fixedStart();
                else if (leg.kind() == BallKind.CARRIED) ball[frame] = positions[frame][leg.carrier()];
                else {
                    PitchPoint start = leg.fixedStart() != null ? leg.fixedStart() : positions[leg.from()][leg.fromPlayer()];
                    PitchPoint end = leg.fixedEnd() != null ? leg.fixedEnd() : positions[leg.to()][leg.toPlayer()];
                    double t = leg.to() == leg.from() ? 1 : (double) (frame - leg.from()) / (leg.to() - leg.from());
                    ball[frame] = bezier(start, end, leg.bend(), t);
                }
            }
        }
        PitchPoint rest = switch (outcome) {
            case SAVE -> clamp(new PitchPoint(shotEnd.x() - 8, shotEnd.y() + reboundDirection * 9));
            case BLOCKED -> clamp(new PitchPoint(shotEnd.x() - 6, shotEnd.y() + reboundDirection * 5));
            default -> shotEnd;
        };
        for (int frame = shotArrival + 1; frame <= TOTAL_FRAMES; frame++) {
            double t = Math.min(1, (double) (frame - shotArrival) / 24);
            double eased = 1 - (1 - t) * (1 - t);
            ball[frame] = lerp(shotEnd, rest, eased);
        }
        return ball;
    }

    private static long[] compileCarriers(List<BallLeg> legs, List<PlayerSnapshot> players) {
        long[] carriers = new long[TOTAL_FRAMES + 1];
        for (BallLeg leg : legs) {
            if (leg.kind() != BallKind.CARRIED) continue;
            for (int frame = leg.from(); frame < Math.min(leg.to(), TOTAL_FRAMES + 1); frame++)
                carriers[frame] = players.get(leg.carrier()).playerId();
        }
        return carriers;
    }

    private int flightFrames(double distance, int minimum) {
        // Reserve headroom for a quadratic curve whose travelled arc is longer
        // than the straight-line endpoint distance used by the scheduler.
        return Math.max(minimum, (int) Math.ceil(distance / (profile.maxBallStep() * 0.50)));
    }

    private static PitchPoint bezier(PitchPoint start, PitchPoint end, double bend, double t) {
        double dx = end.x() - start.x(); double dy = end.y() - start.y();
        double length = Math.max(1e-9, Math.hypot(dx, dy));
        PitchPoint control = new PitchPoint((start.x() + end.x()) / 2 - dy / length * bend,
                (start.y() + end.y()) / 2 + dx / length * bend);
        double u = 1 - t;
        return new PitchPoint(u * u * start.x() + 2 * u * t * control.x() + t * t * end.x(),
                u * u * start.y() + 2 * u * t * control.y() + t * t * end.y());
    }

    private static PitchPoint lerp(PitchPoint from, PitchPoint to, double t) {
        return new PitchPoint(from.x() + (to.x() - from.x()) * t,
                from.y() + (to.y() - from.y()) * t);
    }

    private static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static void assignFormation(List<PlayerSnapshot> side, boolean mirror,
                                        PitchPoint[] output, int offset) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < side.size(); i++)
            groups.computeIfAbsent(side.get(i).tacticalPosition(), ignored -> new ArrayList<>()).add(i);
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            PitchPoint base = basePosition(entry.getKey());
            for (int member = 0; member < entry.getValue().size(); member++) {
                double spread = (entry.getValue().size() - 1) * 12.0;
                double y = entry.getValue().size() == 1 ? base.y() : base.y() - spread + member * 24.0;
                double x = mirror ? 100 - base.x() : base.x();
                output[offset + entry.getValue().get(member)] = clamp(new PitchPoint(x, y));
            }
        }
    }

    private static PitchPoint basePosition(String position) {
        return switch (position) {
            case "GK" -> new PitchPoint(4, 50);
            case "DL" -> new PitchPoint(25, 14);
            case "DC" -> new PitchPoint(25, 50);
            case "DR" -> new PitchPoint(25, 86);
            case "DM" -> new PitchPoint(35, 50);
            case "ML" -> new PitchPoint(48, 14);
            case "MC" -> new PitchPoint(48, 50);
            case "MR" -> new PitchPoint(48, 86);
            case "AML" -> new PitchPoint(62, 20);
            case "AMC" -> new PitchPoint(62, 50);
            case "AMR" -> new PitchPoint(62, 80);
            case "ST" -> new PitchPoint(72, 50);
            default -> new PitchPoint(48, 50);
        };
    }

    private static int positionGroup(String position) {
        return switch (position) {
            case "GK" -> 0;
            case "DL", "DC", "DR" -> 1;
            case "DM", "ML", "MC", "MR" -> 2;
            default -> 3;
        };
    }

    private static PitchPoint ambientTarget(PitchPoint base, int group, boolean attacking, long id) {
        double push = attacking ? switch (group) { case 0 -> 2; case 1 -> 6; case 2 -> 10; default -> 13; }
                : switch (group) { case 0 -> 0.5; case 1 -> 5; case 2 -> 4; default -> 2; };
        double jitterX = ((id * 17) % 7) - 3;
        double jitterY = ((id * 29) % 7) - 3;
        return clamp(new PitchPoint(base.x() + push + jitterX, base.y() + jitterY));
    }

    private static int goalkeeperIndex(List<PlayerSnapshot> defenders, int offset, PitchPoint[] formation) {
        for (int i = 0; i < defenders.size(); i++) if (defenders.get(i).goalkeeper()) return offset + i;
        int closest = offset;
        for (int i = 1; i < defenders.size(); i++)
            if (formation[offset + i].x() > formation[closest].x()) closest = offset + i;
        return closest;
    }

    private static PitchPoint clamp(PitchPoint point) {
        return new PitchPoint(Math.max(0.5, Math.min(99.5, point.x())), clampY(point.y()));
    }

    private static double clampY(double y) {
        return Math.max(1, Math.min(99, y));
    }
}
