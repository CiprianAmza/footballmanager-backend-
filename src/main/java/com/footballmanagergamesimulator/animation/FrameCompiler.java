package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Versioned script -> 151 continuous physical frames.
 *
 * <p>Two properties are guaranteed by construction rather than by validator
 * tolerance:
 * <ul>
 *   <li>Every player step and acceleration stays under the profile cap. The
 *       seek-with-arrival integrator only ever moves a velocity toward a point
 *       inside the step-cap disc, so the velocity magnitude can never leave it,
 *       and the acceleration change is explicitly clamped.</li>
 *   <li>Every ball flight is framed from a rigorous bound on the Bezier
 *       derivative, so no ball step exceeds the cap.</li>
 * </ul>
 * Arrival times are scheduled from the slower of ball flight and the receiver's
 * physical travel, and a self-check rejects any schedule where a receiver does
 * not actually reach the declared target — the director then falls back.
 */
public final class FrameCompiler implements AnimationCompiler {
    /** Generator version 2: the remediated engine, current for all new moments. Version 1 is frozen legacy. */
    public static final int VERSION = 2;
    public static final double GOAL_MIN_Y = 44;
    public static final double GOAL_MAX_Y = 56;

    private static final int MIN_DWELL = 3;
    private static final int SHOT_TAIL = 2;

    /** Frozen cosmetic tuning for this version. */
    public record CompilerTuning(double patrolAmplitudeX, double patrolAmplitudeY,
                                 double ambientPushScale, int shotSettleBase) { }

    public static final CompilerTuning TUNING = new CompilerTuning(1.2, 1.4, 1.0, 18);

    private enum BallKind { DEAD, CARRIED, FLIGHT }

    private record Span(int from, int to, PitchPoint target, boolean patrol) { }
    private record BallLeg(BallKind kind, int from, int to, int carrier, int toPlayer,
                           PitchPoint fixedStart, PitchPoint fixedEnd, double bend) { }
    private record Schedule(PlayScript script, int[] arrival, int[] release, int shotFrame) { }

    private final AnimationPhysicsProfile profile;
    private final int version;
    private final CompilerTuning tuning;
    private final double stepCap;
    private final double accelCap;
    private final double ballCap;
    /** Profile-adaptive number of animated frames for this render (the replay holds totalFrames + 1). */
    private final int totalFrames;
    /**
     * "On the ball" radius: how close a mover must be to the declared target to
     * count as physically arriving. A discrete integrator can overshoot a target
     * by up to one residual step, so the tolerance scales with the step cap. This
     * is roughly 1% of the pitch — an order of magnitude tighter than the metres
     * of drift that this bound is here to forbid.
     */
    private final double reachTolerance;

    public FrameCompiler(AnimationPhysicsProfile profile) {
        this(profile, VERSION, TUNING);
    }

    public FrameCompiler(AnimationPhysicsProfile profile, int version, CompilerTuning tuning) {
        this.profile = profile;
        this.version = version;
        this.tuning = tuning;
        this.stepCap = profile.playerStepCap();
        this.accelCap = profile.playerAccelerationCap();
        this.ballCap = profile.ballStepCap();
        this.totalFrames = AnimationFrameBudget.framesFor(profile);
        this.reachTolerance = stepCap + 0.15;
    }

    public int version() {
        return version;
    }

    public AnimationReplay compile(MatchMomentSpec spec, PlayScript original, Random random) {
        List<PlayerSnapshot> players = new ArrayList<>(spec.attackers());
        players.addAll(spec.defenders());
        int attackingCount = spec.attackers().size();
        int playerCount = players.size();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < playerCount; i++) index.put(players.get(i).playerId(), i);

        PitchPoint[] formation = new PitchPoint[playerCount];
        assignFormation(spec.attackers(), false, formation, 0);
        assignFormation(spec.defenders(), true, formation, attackingCount);
        int goalkeeper = goalkeeperIndex(spec.defenders(), attackingCount, formation);

        // Start positions. Everyone begins in their formation slot, except:
        //  - a dead-ball taker already stands over the stopped ball (not an open-play teleport);
        //  - the safe fallback places its two participants at fixed advanced points and starts them
        //    there, so this degraded, last-resort render needs neither long player travel nor long ball
        //    flights and therefore fits the frame budget for every accepted profile and every position,
        //    including a deep goalkeeper acting as scorer or assister.
        PitchPoint[] starts = startPositions(original, index, formation);
        Schedule schedule = fit(original, index, starts);
        PlayScript script = schedule.script();
        List<PlayScript.Touch> touches = script.touches();
        int finalTouch = touches.size() - 1;
        int shotFrame = schedule.shotFrame();

        PitchPoint shotOrigin = touches.get(finalTouch).target();
        if (touches.size() == 1 && script.deadBallSpot() != null) shotOrigin = script.deadBallSpot();

        double targetY = switch (spec.outcome()) {
            case MISS -> random.nextBoolean() ? between(random, 28, 39) : between(random, 61, 72);
            default -> between(random, 46, 54);
        };
        double reboundDirection = random.nextBoolean() ? 1 : -1;
        double keeperDirection = random.nextBoolean() ? 1 : -1;

        // Estimate the shot destination to frame the shot flight, then bind to the
        // actor's real position for SAVE/BLOCKED.
        int blocker = -1;
        PitchPoint blockPoint = null;
        if (spec.outcome() == AnimationOutcome.BLOCKED) {
            blockPoint = new PitchPoint(shotOrigin.x() + (100 - shotOrigin.x()) * 0.55,
                    shotOrigin.y() + (targetY - shotOrigin.y()) * 0.55);
            double nearest = Double.MAX_VALUE;
            for (int i = attackingCount; i < playerCount; i++) {
                if (i == goalkeeper) continue;
                double distance = formation[i].distanceTo(blockPoint);
                if (distance < nearest) { nearest = distance; blocker = i; }
            }
            if (blocker < 0) blocker = goalkeeper;
        }
        // For SAVE/BLOCKED the ball ends on the moving keeper/blocker, whose real position lies on the
        // segment formation->target. Frame the flight for the FARTHER of those two endpoints (a segment's
        // farthest point from the shooter is an endpoint), so however far the actor has actually moved the
        // ball step still fits under the cap.
        int shotFlight = switch (spec.outcome()) {
            case SAVE -> maxFlightFrames(shotOrigin, new PitchPoint(98, targetY),
                    formation[goalkeeper], script.shotBend());
            case BLOCKED -> maxFlightFrames(shotOrigin, blockPoint, formation[blocker], script.shotBend());
            default -> ballFlightFrames(shotOrigin, new PitchPoint(100, targetY), script.shotBend());
        };
        int shotArrival = shotFrame + shotFlight;
        if (shotArrival > totalFrames - SHOT_TAIL)
            throw new RenderException("shot does not fit frame budget: " + script.pattern());

        List<List<Span>> tracks = buildTracks(spec, players, formation, attackingCount, goalkeeper,
                blocker, blockPoint, index, schedule, shotOrigin, targetY, keeperDirection);

        PitchPoint[][] positions = integrate(tracks, starts, players);

        verifyReachability(touches, index, positions, schedule, shotOrigin, goalkeeper, script);

        // Ball trajectory.
        PitchPoint shotEnd = switch (spec.outcome()) {
            case SAVE -> positions[shotArrival][goalkeeper];
            case BLOCKED -> positions[shotArrival][blocker];
            default -> new PitchPoint(100, targetY);
        };
        List<BallLeg> legs = ballLegs(spec, script, touches, index, schedule, shotFrame, shotArrival,
                shotOrigin, shotEnd);
        PitchPoint[] ball = compileBall(legs, positions, shotArrival, spec.outcome(), shotEnd, reboundDirection);
        long[] carrier = compileCarriers(legs, players);

        List<AnimationEvent> events = compileEvents(spec, touches, index, schedule, shotFrame, shotArrival,
                players, goalkeeper, blocker);

        boolean scoringAttacksRight = spec.scoringTeamAttacksRight();
        boolean mirror = !scoringAttacksRight;
        if (mirror) {
            for (int frame = 0; frame <= totalFrames; frame++) {
                ball[frame] = ball[frame].mirrorX();
                for (int player = 0; player < playerCount; player++)
                    positions[frame][player] = positions[frame][player].mirrorX();
            }
        }

        List<AnimationFrame> frames = new ArrayList<>(totalFrames + 1);
        for (int frame = 0; frame <= totalFrames; frame++) {
            List<PitchPoint> framePositions = new ArrayList<>(playerCount);
            for (int player = 0; player < playerCount; player++)
                framePositions.add(positions[frame][player].rounded());
            frames.add(new AnimationFrame(ball[frame].rounded(), carrier[frame], framePositions));
        }
        return new AnimationReplay(spec.fixtureKey(), spec.slotIndex(), spec.minute(), spec.firstHalfStoppage(),
                spec.period(), spec.scoringTeamId(), spec.defendingTeamId(), spec.homeTeamId(),
                spec.phase(), spec.outcome(), script.pattern(), version, spec.scorerId(), spec.assisterId(),
                spec.homeAttacksRight(), scoringAttacksRight, players, frames, events);
    }

    // ---- Scheduling -------------------------------------------------------

    /**
     * Per-player start positions: formation slots, with a dead-ball taker settled over the spot and the
     * two safe-fallback participants placed at their fixed advanced touch points (so the fallback needs
     * no long travel). Positions are the reference for scheduling and integration.
     */
    private static PitchPoint[] startPositions(PlayScript script, Map<Long, Integer> index, PitchPoint[] formation) {
        PitchPoint[] starts = formation.clone();
        if (script.deadBallSpot() != null) {
            PitchPoint spot = script.deadBallSpot();
            starts[index.get(script.touches().get(0).playerId())] =
                    clamp(new PitchPoint(spot.x() - 3, spot.y() + (spot.y() > 50 ? -2 : 2)));
        }
        return starts;
    }

    private Schedule fit(PlayScript original, Map<Long, Integer> index, PitchPoint[] starts) {
        PlayScript script = original;
        while (true) {
            Schedule schedule = trySchedule(script, index, starts);
            if (schedule != null) return schedule;
            if (script.touches().size() <= 3)
                throw new RenderException("script does not fit " + (totalFrames + 1) + " frames: " + script.pattern());
            List<PlayScript.Touch> tail = script.touches()
                    .subList(script.touches().size() - 3, script.touches().size());
            script = new PlayScript(script.pattern(), tail, script.deadBallSpot(),
                    Math.min(script.preludeFrames(), 24), script.shotBend());
        }
    }

    /** Returns a schedule, or null if it does not fit (caller trims and retries). */
    private Schedule trySchedule(PlayScript script, Map<Long, Integer> index, PitchPoint[] starts) {
        List<PlayScript.Touch> touches = script.touches();
        int n = touches.size();
        int[] arrival = new int[n];
        int[] release = new int[n];
        Map<Long, Integer> lastRelease = new HashMap<>();
        Map<Long, PitchPoint> lastPos = new HashMap<>();

        PlayScript.Touch first = touches.get(0);
        PitchPoint firstTarget = script.deadBallSpot() != null ? script.deadBallSpot() : first.target();
        int firstIndex = index.get(first.playerId());
        arrival[0] = 0;
        // The first toucher runs from their real start slot onto the ball and is never teleported.
        // (Dead-ball takers and fallback participants already start on their point, so this is ~0.)
        int reachFirst = framesToReach(starts[firstIndex].distanceTo(firstTarget));
        int preludeMin = script.deadBallSpot() != null ? Math.max(20, script.preludeFrames()) : 0;
        release[0] = Math.max(Math.max(MIN_DWELL, first.dwellFrames()), Math.max(reachFirst, preludeMin));
        lastRelease.put(first.playerId(), release[0]);
        lastPos.put(first.playerId(), firstTarget);

        PitchPoint prevTarget = firstTarget;
        int prevRelease = release[0];
        for (int t = 1; t < n; t++) {
            PlayScript.Touch touch = touches.get(t);
            Integer pIdx = index.get(touch.playerId());
            if (pIdx == null) throw new RenderException("script uses non-snapshot player " + touch.playerId());
            PitchPoint target = touch.target();
            int startMove = lastRelease.getOrDefault(touch.playerId(), 0);
            PitchPoint refPos = lastPos.getOrDefault(touch.playerId(), starts[pIdx]);
            int earliestPlayer = startMove + framesToReach(refPos.distanceTo(target));
            if (touch.receiveKind() == PlayScript.ReceiveKind.CARRY) {
                arrival[t] = earliestPlayer;
            } else {
                int earliestBall = prevRelease + ballFlightFrames(prevTarget, target, touch.arrivalBend());
                arrival[t] = Math.max(earliestBall, earliestPlayer);
            }
            release[t] = arrival[t] + Math.max(MIN_DWELL, touch.dwellFrames());
            lastRelease.put(touch.playerId(), release[t]);
            lastPos.put(touch.playerId(), target);
            prevTarget = target;
            prevRelease = release[t];
        }
        int shotFrame = release[n - 1];
        if (shotFrame > totalFrames - 25) return null;
        return new Schedule(script, arrival, release, shotFrame);
    }

    private int framesToReach(double distance) {
        if (distance < 1e-9) return MIN_DWELL;
        int cruise = (int) Math.ceil(distance / (0.85 * stepCap));
        int ramp = (int) Math.ceil(stepCap / accelCap);
        return cruise + ramp + 5;
    }

    private int ballFlightFrames(PitchPoint start, PitchPoint end, double bend) {
        PitchPoint control = control(start, end, bend);
        double maxDerivative = 2 * Math.max(start.distanceTo(control), control.distanceTo(end));
        return Math.max(4, (int) Math.ceil(maxDerivative / ballCap));
    }

    /**
     * Frames enough for a flight ending anywhere on the segment {@code start->[a|b]}, with a small
     * headroom for the ~one-step drift between the framing estimate and the actor's real arrival spot.
     */
    private int maxFlightFrames(PitchPoint start, PitchPoint a, PitchPoint b, double bend) {
        int frames = Math.max(ballFlightFrames(start, a, bend), ballFlightFrames(start, b, bend));
        return frames + 2;
    }

    // ---- Track construction ----------------------------------------------

    private List<List<Span>> buildTracks(MatchMomentSpec spec, List<PlayerSnapshot> players,
                                         PitchPoint[] formation, int attackingCount, int goalkeeper,
                                         int blocker, PitchPoint blockPoint, Map<Long, Integer> index,
                                         Schedule schedule, PitchPoint shotOrigin, double targetY,
                                         double keeperDirection) {
        PlayScript script = schedule.script();
        List<PlayScript.Touch> touches = script.touches();
        int finalTouch = touches.size() - 1;
        int shotFrame = schedule.shotFrame();
        int playerCount = players.size();

        List<List<Span>> tracks = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            tracks.add(new ArrayList<>());
            boolean attacking = i < attackingCount;
            PitchPoint ambient = ambientTarget(formation[i], positionGroup(players.get(i).tacticalPosition()),
                    attacking, players.get(i).playerId());
            tracks.get(i).add(new Span(0, totalFrames + 1, ambient, true));
        }

        for (int t = 0; t < touches.size(); t++) {
            PlayScript.Touch touch = touches.get(t);
            int p = index.get(touch.playerId());
            PitchPoint target = t == 0 && script.deadBallSpot() != null ? script.deadBallSpot() : touch.target();
            int release = t == finalTouch ? shotFrame : schedule.release()[t];
            // One continuous seek from the moment this player is free until the ball leaves them:
            // run onto the declared target and stay on it. The seek integrator bounds the motion.
            int startMove = previousRelease(touches, index, schedule, t, p);
            tracks.get(p).add(new Span(startMove, release, target, false));
            if (t < finalTouch) {
                PitchPoint drift = clamp(new PitchPoint(Math.min(92, target.x() + 6),
                        target.y() + (50 - target.y()) * 0.2));
                tracks.get(p).add(new Span(release, totalFrames + 1, drift, false));
            }
        }

        // Scorer follow-through after the shot.
        int scorer = index.get(touches.get(finalTouch).playerId());
        tracks.get(scorer).add(new Span(shotFrame, totalFrames + 1,
                clamp(new PitchPoint(Math.min(95, shotOrigin.x() + 4),
                        shotOrigin.y() + (targetY - shotOrigin.y()) * 0.12)), false));

        if (goalkeeper >= 0) {
            tracks.get(goalkeeper).add(new Span(0, shotFrame, new PitchPoint(97.5, 50), true));
            double diveY = switch (spec.outcome()) {
                case SAVE -> targetY;
                case GOAL -> clampY(50 + keeperDirection * 9);
                default -> clampY(50 + keeperDirection * 2);
            };
            tracks.get(goalkeeper).add(new Span(shotFrame, totalFrames + 1, new PitchPoint(98, diveY), false));
        }
        if (blocker >= 0 && blockPoint != null) {
            tracks.get(blocker).add(new Span(Math.max(0, shotFrame - framesToReach(
                    formation[blocker].distanceTo(blockPoint))), totalFrames + 1, blockPoint, false));
        }
        return tracks;
    }

    private static int previousRelease(List<PlayScript.Touch> touches, Map<Long, Integer> index,
                                       Schedule schedule, int t, int playerIndex) {
        for (int k = t - 1; k >= 0; k--) {
            if (index.get(touches.get(k).playerId()) == playerIndex) return schedule.release()[k];
        }
        return 0;
    }

    // ---- Ball -------------------------------------------------------------

    private List<BallLeg> ballLegs(MatchMomentSpec spec, PlayScript script, List<PlayScript.Touch> touches,
                                   Map<Long, Integer> index, Schedule schedule, int shotFrame, int shotArrival,
                                   PitchPoint shotOrigin, PitchPoint shotEnd) {
        List<BallLeg> legs = new ArrayList<>();
        int finalTouch = touches.size() - 1;
        int firstCarrier = index.get(touches.get(0).playerId());
        if (script.deadBallSpot() != null) {
            legs.add(new BallLeg(BallKind.DEAD, 0, schedule.release()[0], -1, -1, script.deadBallSpot(), null, 0));
        } else {
            legs.add(new BallLeg(BallKind.CARRIED, 0, schedule.release()[0], firstCarrier, -1, null, null, 0));
        }
        for (int t = 1; t < touches.size(); t++) {
            PlayScript.Touch touch = touches.get(t);
            int carrier = index.get(touch.playerId());
            int release = t == finalTouch ? shotFrame : schedule.release()[t];
            if (touch.receiveKind() == PlayScript.ReceiveKind.CARRY) {
                legs.add(new BallLeg(BallKind.CARRIED, schedule.release()[t - 1], release, carrier, -1, null, null, 0));
            } else {
                // The flight ends at the receiver's real position; reachability is enforced
                // separately, so this real position coincides with the declared target.
                legs.add(new BallLeg(BallKind.FLIGHT, schedule.release()[t - 1], schedule.arrival()[t],
                        -1, carrier, null, null, touch.arrivalBend()));
                legs.add(new BallLeg(BallKind.CARRIED, schedule.arrival()[t], release, carrier, -1, null, null, 0));
            }
        }
        // shotEnd is already the actor's real position for SAVE/BLOCKED, or fixed goal geometry otherwise.
        legs.add(new BallLeg(BallKind.FLIGHT, shotFrame, shotArrival, -1, -1, null, shotEnd, script.shotBend()));
        return legs;
    }

    private PitchPoint[] compileBall(List<BallLeg> legs, PitchPoint[][] positions, int shotArrival,
                                     AnimationOutcome outcome, PitchPoint shotEnd, double reboundDirection) {
        PitchPoint[] ball = new PitchPoint[totalFrames + 1];
        for (BallLeg leg : legs) {
            for (int frame = leg.from(); frame <= Math.min(leg.to(), totalFrames); frame++) {
                switch (leg.kind()) {
                    case DEAD -> ball[frame] = leg.fixedStart();
                    case CARRIED -> ball[frame] = positions[frame][leg.carrier()];
                    case FLIGHT -> {
                        PitchPoint start = leg.fixedStart() != null ? leg.fixedStart() : ball[leg.from()];
                        PitchPoint end = leg.fixedEnd() != null ? leg.fixedEnd() : positions[leg.to()][leg.toPlayer()];
                        double t = leg.to() == leg.from() ? 1 : (double) (frame - leg.from()) / (leg.to() - leg.from());
                        ball[frame] = bezier(start, end, leg.bend(), t);
                    }
                }
            }
        }
        int settle = Math.max(tuning.shotSettleBase(), (int) Math.ceil(
                2 * shotEnd.distanceTo(rest(outcome, shotEnd, reboundDirection)) / ballCap));
        PitchPoint rest = rest(outcome, shotEnd, reboundDirection);
        for (int frame = shotArrival + 1; frame <= totalFrames; frame++) {
            double t = Math.min(1, (double) (frame - shotArrival) / settle);
            double eased = 1 - (1 - t) * (1 - t);
            ball[frame] = lerp(shotEnd, rest, eased);
        }
        return ball;
    }

    private PitchPoint rest(AnimationOutcome outcome, PitchPoint shotEnd, double reboundDirection) {
        return switch (outcome) {
            case SAVE -> clamp(new PitchPoint(shotEnd.x() - 8, shotEnd.y() + reboundDirection * 9));
            case BLOCKED -> clamp(new PitchPoint(shotEnd.x() - 6, shotEnd.y() + reboundDirection * 5));
            default -> shotEnd;
        };
    }

    private long[] compileCarriers(List<BallLeg> legs, List<PlayerSnapshot> players) {
        long[] carriers = new long[totalFrames + 1];
        for (BallLeg leg : legs) {
            if (leg.kind() != BallKind.CARRIED) continue;
            for (int frame = leg.from(); frame < Math.min(leg.to(), totalFrames + 1); frame++)
                carriers[frame] = players.get(leg.carrier()).playerId();
        }
        return carriers;
    }

    // ---- Events -----------------------------------------------------------

    private List<AnimationEvent> compileEvents(MatchMomentSpec spec, List<PlayScript.Touch> touches,
                                               Map<Long, Integer> index, Schedule schedule, int shotFrame,
                                               int shotArrival, List<PlayerSnapshot> players,
                                               int goalkeeper, int blocker) {
        int finalTouch = touches.size() - 1;
        List<AnimationEvent> events = new ArrayList<>();
        for (int t = 1; t < touches.size(); t++) {
            PlayScript.ReceiveKind kind = touches.get(t).receiveKind();
            if (kind == PlayScript.ReceiveKind.CARRY) continue;
            String type = kind == PlayScript.ReceiveKind.PASS ? "PASS" : "LOOSE";
            events.add(new AnimationEvent(schedule.release()[t - 1], type,
                    touches.get(t - 1).playerId(), touches.get(t).playerId()));
        }
        events.add(new AnimationEvent(shotFrame, "SHOT", touches.get(finalTouch).playerId(), 0));
        long outcomeActor = switch (spec.outcome()) {
            case SAVE -> players.get(goalkeeper).playerId();
            case BLOCKED -> players.get(blocker).playerId();
            default -> touches.get(finalTouch).playerId();
        };
        events.add(new AnimationEvent(shotArrival, spec.outcome().name(), outcomeActor, 0));
        return events;
    }

    // ---- Integration ------------------------------------------------------

    private PitchPoint[][] integrate(List<List<Span>> tracks, PitchPoint[] starts, List<PlayerSnapshot> players) {
        int count = starts.length;
        PitchPoint[][] positions = new PitchPoint[totalFrames + 1][count];
        double[][] velocity = new double[count][2];
        for (int player = 0; player < count; player++) positions[0][player] = starts[player];
        for (int frame = 1; frame <= totalFrames; frame++) {
            for (int player = 0; player < count; player++) {
                Span active = activeSpan(tracks.get(player), frame);
                PitchPoint target = active.target();
                if (active.patrol()) {
                    long id = players.get(player).playerId();
                    target = new PitchPoint(
                            target.x() + Math.sin(frame * (0.05 + id % 7 * 0.01) + id) * tuning.patrolAmplitudeX(),
                            target.y() + Math.cos(frame * (0.04 + id % 5 * 0.012) + id) * tuning.patrolAmplitudeY());
                }
                target = clamp(target);
                PitchPoint previous = positions[frame - 1][player];
                double dx = target.x() - previous.x();
                double dy = target.y() - previous.y();
                double distance = Math.hypot(dx, dy);
                // Seek-with-arrival: cap cruise speed and decelerate so we can stop on the target.
                double arrivalSpeed = Math.sqrt(2 * accelCap * distance);
                double desiredSpeed = Math.min(Math.min(stepCap, arrivalSpeed), distance);
                double desiredX = distance > 1e-9 ? dx / distance * desiredSpeed : 0;
                double desiredY = distance > 1e-9 ? dy / distance * desiredSpeed : 0;
                double ax = desiredX - velocity[player][0];
                double ay = desiredY - velocity[player][1];
                double acceleration = Math.hypot(ax, ay);
                if (acceleration > accelCap) {
                    ax *= accelCap / acceleration;
                    ay *= accelCap / acceleration;
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

    private void verifyReachability(List<PlayScript.Touch> touches, Map<Long, Integer> index,
                                    PitchPoint[][] positions, Schedule schedule, PitchPoint shotOrigin,
                                    int goalkeeper, PlayScript script) {
        for (int t = 0; t < touches.size(); t++) {
            PlayScript.Touch touch = touches.get(t);
            int p = index.get(touch.playerId());
            PitchPoint target = t == 0 && script.deadBallSpot() != null ? script.deadBallSpot() : touch.target();
            int frame = t == 0 ? schedule.release()[0] : schedule.arrival()[t];
            if (positions[frame][p].distanceTo(target) > reachTolerance)
                throw new RenderException("participant cannot reach declared target: " + script.pattern());
        }
        int scorer = index.get(touches.get(touches.size() - 1).playerId());
        if (positions[schedule.shotFrame()][scorer].distanceTo(shotOrigin) > reachTolerance)
            throw new RenderException("scorer cannot reach the shot: " + script.pattern());
    }

    // ---- Geometry ---------------------------------------------------------

    private static PitchPoint control(PitchPoint start, PitchPoint end, double bend) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double length = Math.max(1e-9, Math.hypot(dx, dy));
        return new PitchPoint((start.x() + end.x()) / 2 - dy / length * bend,
                (start.y() + end.y()) / 2 + dx / length * bend);
    }

    private static PitchPoint bezier(PitchPoint start, PitchPoint end, double bend, double t) {
        PitchPoint control = control(start, end, bend);
        double u = 1 - t;
        return new PitchPoint(u * u * start.x() + 2 * u * t * control.x() + t * t * end.x(),
                u * u * start.y() + 2 * u * t * control.y() + t * t * end.y());
    }

    private static PitchPoint lerp(PitchPoint from, PitchPoint to, double t) {
        return new PitchPoint(from.x() + (to.x() - from.x()) * t, from.y() + (to.y() - from.y()) * t);
    }

    private static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private void assignFormation(List<PlayerSnapshot> side, boolean mirror, PitchPoint[] output, int offset) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < side.size(); i++)
            groups.computeIfAbsent(side.get(i).tacticalPosition(), ignored -> new ArrayList<>()).add(i);
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            PitchPoint base = basePosition(entry.getKey());
            int size = entry.getValue().size();
            for (int member = 0; member < size; member++) {
                double spread = (size - 1) * 12.0;
                double y = size == 1 ? base.y() : base.y() - spread + member * 24.0;
                double x = mirror ? 100 - base.x() : base.x();
                output[offset + entry.getValue().get(member)] = clamp(new PitchPoint(x, y));
            }
        }
    }

    private static PitchPoint basePosition(String position) {
        return switch (position) {
            case "GK" -> new PitchPoint(4, 50);
            case "DL" -> new PitchPoint(25, 14);
            case "WBL" -> new PitchPoint(30, 10);
            case "DC" -> new PitchPoint(25, 50);
            case "DR" -> new PitchPoint(25, 86);
            case "WBR" -> new PitchPoint(30, 90);
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
            case "DL", "DC", "DR", "WBL", "WBR" -> 1;
            case "DM", "ML", "MC", "MR" -> 2;
            default -> 3;
        };
    }

    private PitchPoint ambientTarget(PitchPoint base, int group, boolean attacking, long id) {
        double push = (attacking ? switch (group) { case 0 -> 2; case 1 -> 6; case 2 -> 10; default -> 13; }
                : switch (group) { case 0 -> 0.5; case 1 -> 5; case 2 -> 4; default -> 2; }) * tuning.ambientPushScale();
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
