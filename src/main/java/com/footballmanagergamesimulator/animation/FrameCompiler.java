package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static com.footballmanagergamesimulator.animation.AnimationPhysics.GOAL_TARGET_MAX_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.GOAL_TARGET_MIN_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.PLAYER_MAX_X;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.PLAYER_MAX_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.PLAYER_MIN_X;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.PLAYER_MIN_Y;
import static com.footballmanagergamesimulator.animation.AnimationPhysics.TOTAL_FRAMES;

/**
 * Turns a {@link Choreography} into physically coherent frames. Owns timing,
 * the steering integrator (speed + acceleration caps, no teleports), the ball
 * track (carried / dead / continuous Bézier flights), the outcome geometry
 * and half-aware mirroring. Version 1 of the v2 engine — any behavioural
 * change here requires a generator-version bump (see ANIMATION_ENGINE_V2.md §3).
 *
 * <p>Builds in canonical orientation (attack → x=100), then mirrors.
 * Deterministic: consumes only the supplied {@link Random}.
 */
public final class FrameCompiler {

    public static final int VERSION = 1;

    private final AnimationMotionLimits motionLimits;

    public FrameCompiler() {
        this(AnimationMotionLimits.defaults());
    }

    public FrameCompiler(AnimationMotionLimits motionLimits) {
        this.motionLimits = Objects.requireNonNull(motionLimits, "motionLimits");
    }

    // ---- internal plan types ----

    /** Steering target active on [from, to); patrol adds a small deterministic sway. */
    private record Span(int from, int to, double x, double y, boolean patrol) {
    }

    /** One ball leg. kind: 0=dead, 1=carried, 2=flight. */
    private record BallLeg(int kind, int from, int to, int carrierIdx,
                           int fromIdx, int toIdx, double[] fixedStart, double[] fixedEnd,
                           double curve) {
    }

    private record Timeline(int[] arrival, int[] release, int shotFrame, int shotArrival,
                            List<Choreography.ChainStep> chain) {
    }

    public AnimationReplay compile(MatchMomentSpec spec, Choreography ch, Random rng) {
        List<PlayerSnapshot> players = new ArrayList<>(spec.attackingPlayers());
        players.addAll(spec.defendingPlayers());
        int n = players.size();
        int atkCount = spec.attackingPlayers().size();
        Map<Long, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(players.get(i).playerId(), i);

        // ---- base formation (canonical: attackers toward x=100) ----
        double[][] base = new double[n][2];
        assignFormation(spec.attackingPlayers(), false, base, 0);
        assignFormation(spec.defendingPlayers(), true, base, atkCount);

        int gkIdx = goalkeeperIndex(spec.defendingPlayers(), base, atkCount);

        // ---- timeline (frames of each receive/release/shot) ----
        boolean setPiece = ch.setPieceSpot() != null;
        Timeline tl = buildTimeline(ch, base, idx, setPiece, -1);
        if (tl.shotFrame > TOTAL_FRAMES - 22) tl = buildTimeline(ch, base, idx, setPiece, 4);
        if (tl.shotFrame > TOTAL_FRAMES - 22) {
            List<Choreography.ChainStep> tail =
                    ch.chain().subList(Math.max(0, ch.chain().size() - 3), ch.chain().size());
            ch = new Choreography(ch.patternId(), tail, ch.setPieceSpot(),
                    Math.min(ch.preKickFrames(), 30), ch.shotCurve());
            tl = buildTimeline(ch, base, idx, setPiece, 4);
        }
        List<Choreography.ChainStep> chain = tl.chain;
        int last = chain.size() - 1;

        // ---- deterministic outcome geometry (all rng draws before integration) ----
        double[] scorerPlanned = {chain.get(last).x(), chain.get(last).y()};
        double[] shotOrigin = setPiece && chain.size() == 1 ? ch.setPieceSpot() : scorerPlanned;
        double shotTargetY;
        switch (spec.outcome()) {
            case MISS -> shotTargetY = rng.nextBoolean()
                    ? 26 + rng.nextDouble() * 12      // wide left of the posts
                    : 62 + rng.nextDouble() * 12;     // wide right of the posts
            case SAVE -> shotTargetY = 45 + rng.nextDouble() * 10;
            default -> shotTargetY = GOAL_TARGET_MIN_Y
                    + rng.nextDouble() * (GOAL_TARGET_MAX_Y - GOAL_TARGET_MIN_Y);
        }
        double reboundSign = rng.nextBoolean() ? 1 : -1;
        double gkWrongSide = rng.nextBoolean() ? 1 : -1;

        // Blocker: nearest defending outfielder to a point 55% along shot line.
        int blockerIdx = -1;
        double[] blockPoint = null;
        if (spec.outcome() == AnimationOutcome.BLOCKED) {
            double bx = shotOrigin[0] + (100 - shotOrigin[0]) * 0.55;
            double by = shotOrigin[1] + (shotTargetY - shotOrigin[1]) * 0.55;
            double bestD = Double.MAX_VALUE;
            for (int i = atkCount; i < n; i++) {
                if (i == gkIdx) continue;
                double d = dist(base[i][0], base[i][1], bx, by);
                if (d < bestD) { bestD = d; blockerIdx = i; }
            }
            if (blockerIdx < 0) blockerIdx = gkIdx;
            blockPoint = new double[]{bx, by};
        }

        // Ambient jitter per player (fixed rng order: roster order).
        double[][] jitter = new double[n][2];
        for (int i = 0; i < n; i++) {
            jitter[i][0] = (rng.nextDouble() - 0.5) * 6;
            jitter[i][1] = (rng.nextDouble() - 0.5) * 6;
        }

        int shotFrame = tl.shotFrame;
        // Shot flight length from the ACTUAL planned end point (a wide MISS or
        // a block travels a different distance than the naive goal-centre
        // estimate used while sizing the timeline) so the ball speed cap holds.
        double[] plannedShotEnd = switch (spec.outcome()) {
            case SAVE -> new double[]{98, shotTargetY};
            case BLOCKED -> blockPoint;
            default -> new double[]{100, shotTargetY};
        };
        int shotArrival = shotFrame + clampI((int) Math.ceil(
                dist(shotOrigin[0], shotOrigin[1], plannedShotEnd[0], plannedShotEnd[1]) / 3.2), 6, 16);

        // ---- steering target schedules ----
        List<List<Span>> tracks = new ArrayList<>();
        double[][] start = new double[n][2];
        for (int i = 0; i < n; i++) {
            start[i] = base[i].clone();
            tracks.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            boolean attacking = i < atkCount;
            int group = positionGroup(players.get(i).position());
            double[] ambient = ambientTarget(base[i], group, attacking, jitter[i]);
            tracks.get(i).add(new Span(0, shotFrame, ambient[0], ambient[1], true));
            if (spec.outcome() == AnimationOutcome.GOAL && attacking && i != gkIdx) {
                tracks.get(i).add(new Span(shotFrame, TOTAL_FRAMES + 1,
                        clampX(shotOrigin[0] - 4 + (i % 5) * 2), clampY(shotOrigin[1] - 8 + (i % 4) * 5), false));
            } else {
                tracks.get(i).add(new Span(shotFrame, TOTAL_FRAMES + 1, ambient[0], ambient[1], true));
            }
        }

        // Chain overrides, in chain order (later spans shadow earlier ones).
        int[] roleEnd = new int[n];
        for (int c = 0; c < chain.size(); c++) {
            int pi = idx.get(chain.get(c).playerId());
            int arrive = tl.arrival[c];
            int release = c < last ? tl.release[c] : shotFrame;
            List<Span> t = tracks.get(pi);
            double px = chain.get(c).x(), py = chain.get(c).y();
            if (c == 0) {
                if (setPiece) {
                    start[pi] = new double[]{clampX(ch.setPieceSpot()[0] - 5),
                            clampY(ch.setPieceSpot()[1] + (ch.setPieceSpot()[1] > 50 ? -3 : 3))};
                    t.add(new Span(0, release, ch.setPieceSpot()[0], ch.setPieceSpot()[1], false));
                } else {
                    start[pi] = new double[]{clampX(px), clampY(py)};
                    t.add(new Span(0, release, clampX(px + 1.5), py, false));
                }
            } else {
                t.add(new Span(roleEnd[pi], arrive, px, py, false));
                t.add(new Span(arrive, release, clampX(px + 1.5), py, false));
            }
            if (c < last) {
                // Support run after releasing, until the shot goes off.
                t.add(new Span(release, shotFrame,
                        clampX(Math.min(92, px + 8)), clampY(py + (50 - py) * 0.3), false));
            } else {
                // Scorer: follow through after the shot.
                t.add(new Span(shotFrame, TOTAL_FRAMES + 1,
                        clampX(shotOrigin[0] + 3), clampY(shotOrigin[1] + (shotTargetY - shotOrigin[1]) * 0.15), false));
            }
            roleEnd[pi] = release;
        }

        // Defending goalkeeper: hold the line, then dive by outcome.
        if (gkIdx >= 0) {
            List<Span> t = tracks.get(gkIdx);
            t.add(new Span(0, shotFrame, 97.5, 50, true));
            double diveY = switch (spec.outcome()) {
                case SAVE -> shotTargetY;
                case GOAL -> clampY(50 + gkWrongSide * 8);
                default -> 50 + gkWrongSide * 2;
            };
            t.add(new Span(shotFrame, TOTAL_FRAMES + 1, 98, diveY, false));
        }

        // Blocker steps onto the shooting line just before the shot.
        if (blockerIdx >= 0 && blockPoint != null) {
            List<Span> t = tracks.get(blockerIdx);
            t.add(new Span(Math.max(0, shotFrame - 18), TOTAL_FRAMES + 1, blockPoint[0], blockPoint[1], false));
        }

        // ---- integrate all player tracks ----
        double[][][] pos = integrate(tracks, start, players);

        // ---- ball legs ----
        List<BallLeg> legs = new ArrayList<>();
        if (setPiece) {
            legs.add(new BallLeg(0, 0, tl.release[0], -1, -1, -1,
                    ch.setPieceSpot(), null, 0));
        } else {
            legs.add(new BallLeg(1, 0, tl.release[0], idx.get(chain.get(0).playerId()), -1, -1, null, null, 0));
        }
        for (int c = 1; c < chain.size(); c++) {
            int fromIdx = idx.get(chain.get(c - 1).playerId());
            int toIdx = idx.get(chain.get(c).playerId());
            double[] fixedStart = (c == 1 && setPiece) ? ch.setPieceSpot() : null;
            legs.add(new BallLeg(2, tl.release[c - 1], tl.arrival[c], -1, fromIdx, toIdx,
                    fixedStart, null, chain.get(c).passCurve()));
            int release = c < last ? tl.release[c] : shotFrame;
            legs.add(new BallLeg(1, tl.arrival[c], release, toIdx, -1, -1, null, null, 0));
        }
        // Shot leg.
        int scorerIdx = idx.get(chain.get(last).playerId());
        double[] shotStart = (setPiece && chain.size() == 1) ? ch.setPieceSpot() : null;
        double[] shotEnd;
        switch (spec.outcome()) {
            case SAVE -> shotEnd = new double[]{pos[shotArrival][gkIdx][0], pos[shotArrival][gkIdx][1]};
            case BLOCKED -> shotEnd = new double[]{pos[shotArrival][blockerIdx][0], pos[shotArrival][blockerIdx][1]};
            default -> shotEnd = new double[]{100, shotTargetY};
        }
        legs.add(new BallLeg(2, shotFrame, shotArrival, -1, scorerIdx, -1, shotStart, shotEnd, ch.shotCurve()));

        double[][] ball = compileBall(legs, pos, shotArrival, spec.outcome(), shotEnd, reboundSign);
        long[] carrier = compileCarrier(legs, players);

        // ---- events ----
        List<ReplayEvent> events = new ArrayList<>();
        for (int c = 1; c < chain.size(); c++) {
            events.add(new ReplayEvent(tl.release[c - 1], "PASS",
                    chain.get(c - 1).playerId(), chain.get(c).playerId()));
        }
        events.add(new ReplayEvent(shotFrame, "SHOT", chain.get(last).playerId(), 0));
        long outcomeActor = switch (spec.outcome()) {
            case SAVE -> players.get(gkIdx).playerId();
            case BLOCKED -> players.get(blockerIdx).playerId();
            default -> chain.get(last).playerId();
        };
        events.add(new ReplayEvent(shotArrival, spec.outcome().name(), outcomeActor, 0));

        // ---- half-aware mirroring ----
        boolean attackerIsHome = spec.scoringTeamId() == spec.homeTeamId();
        boolean homeAttacksRight = spec.isFirstHalf();
        boolean needsMirror = spec.isFirstHalf() != attackerIsHome;
        if (needsMirror) {
            for (int f = 0; f <= TOTAL_FRAMES; f++) {
                ball[f][0] = 100 - ball[f][0];
                for (int i = 0; i < n; i++) pos[f][i][0] = 100 - pos[f][i][0];
            }
        }

        // ---- assemble (rounded to 0.1) ----
        List<ReplayFrame> frames = new ArrayList<>(TOTAL_FRAMES + 1);
        for (int f = 0; f <= TOTAL_FRAMES; f++) {
            List<double[]> positions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                positions.add(new double[]{round1(pos[f][i][0]), round1(pos[f][i][1])});
            }
            frames.add(new ReplayFrame(round1(ball[f][0]), round1(ball[f][1]), carrier[f], positions));
        }

        return new AnimationReplay(spec.fixtureKey(), spec.slotIndex(), spec.minute(),
                spec.firstHalfStoppage(), spec.scoringTeamId(), spec.defendingTeamId(),
                spec.homeTeamId(), spec.phase(), spec.outcome(), ch.patternId(), VERSION,
                spec.scorerId(), spec.assisterId(), homeAttacksRight, !needsMirror,
                List.copyOf(players), List.copyOf(frames), List.copyOf(events));
    }

    // ==================== TIMELINE ====================

    private Timeline buildTimeline(Choreography ch, double[][] base, Map<Long, Integer> idx,
                                   boolean setPiece, int dwellOverride) {
        List<Choreography.ChainStep> chain = ch.chain();
        int size = chain.size();
        int[] arrival = new int[size];
        int[] release = new int[size];

        int firstRelease;
        if (setPiece) {
            firstRelease = clampI(ch.preKickFrames(), 20, 80);
        } else {
            firstRelease = Math.max(6, dwell(chain.get(0), dwellOverride));
        }
        arrival[0] = 0;
        release[0] = firstRelease;

        double prevX = setPiece ? ch.setPieceSpot()[0] : chain.get(0).x();
        double prevY = setPiece ? ch.setPieceSpot()[1] : chain.get(0).y();
        for (int c = 1; c < size; c++) {
            double d = dist(prevX, prevY, chain.get(c).x(), chain.get(c).y());
            int flight = clampI((int) Math.ceil(d / 2.2), 8, 22);
            arrival[c] = release[c - 1] + flight;
            if (c < size - 1) release[c] = arrival[c] + Math.max(4, dwell(chain.get(c), dwellOverride));
            prevX = chain.get(c).x();
            prevY = chain.get(c).y();
        }

        int shotFrame = size == 1 ? firstRelease
                : arrival[size - 1] + Math.max(5, dwell(chain.get(size - 1), dwellOverride));
        double[] origin = size == 1 && setPiece ? ch.setPieceSpot()
                : new double[]{chain.get(size - 1).x(), chain.get(size - 1).y()};
        double shotDist = dist(origin[0], origin[1], 100, 50);
        int shotFlight = clampI((int) Math.ceil(shotDist / 3.2), 6, 14);
        return new Timeline(arrival, release, shotFrame, shotFrame + shotFlight, chain);
    }

    private static int dwell(Choreography.ChainStep step, int override) {
        return override > 0 ? override : step.dwellFrames();
    }

    // ==================== PLAYER INTEGRATION ====================

    /** Steering integrator: chases the active target under speed + accel caps. */
    private double[][][] integrate(List<List<Span>> tracks, double[][] start, List<PlayerSnapshot> players) {
        int n = start.length;
        double[][][] pos = new double[TOTAL_FRAMES + 1][n][2];
        double[][] vel = new double[n][2];
        for (int i = 0; i < n; i++) pos[0][i] = start[i].clone();

        for (int f = 1; f <= TOTAL_FRAMES; f++) {
            for (int i = 0; i < n; i++) {
                Span span = activeSpan(tracks.get(i), f);
                double tx = span.x(), ty = span.y();
                if (span.patrol()) {
                    long id = players.get(i).playerId();
                    tx += Math.sin(f * (0.05 + (id % 7) * 0.012) + (id * 37 % 100)) * 1.4;
                    ty += Math.cos(f * (0.045 + (id % 5) * 0.014) + (id * 53 % 100)) * 1.6;
                }
                tx = clampX(tx);
                ty = clampY(ty);

                double px = pos[f - 1][i][0], py = pos[f - 1][i][1];
                double dx = tx - px, dy = ty - py;
                double d = Math.sqrt(dx * dx + dy * dy);
                double speed = Math.min(motionLimits.maxPlayerStep(), 0.32 * d + 0.03);
                double dvx = d > 1e-9 ? dx / d * speed : 0;
                double dvy = d > 1e-9 ? dy / d * speed : 0;
                double ax = dvx - vel[i][0], ay = dvy - vel[i][1];
                double a = Math.sqrt(ax * ax + ay * ay);
                if (a > motionLimits.maxPlayerAcceleration()) {
                    ax = ax / a * motionLimits.maxPlayerAcceleration();
                    ay = ay / a * motionLimits.maxPlayerAcceleration();
                }
                vel[i][0] += ax;
                vel[i][1] += ay;
                double nx = px + vel[i][0], ny = py + vel[i][1];
                if (nx < PLAYER_MIN_X || nx > PLAYER_MAX_X) { nx = clampX(nx); vel[i][0] = 0; }
                if (ny < PLAYER_MIN_Y || ny > PLAYER_MAX_Y) { ny = clampY(ny); vel[i][1] = 0; }
                pos[f][i][0] = nx;
                pos[f][i][1] = ny;
            }
        }
        return pos;
    }

    private Span activeSpan(List<Span> spans, int f) {
        Span active = spans.get(0);
        for (Span s : spans) if (f >= s.from() && f < s.to()) active = s;
        return active;
    }

    // ==================== BALL ====================

    private double[][] compileBall(List<BallLeg> legs, double[][][] pos, int shotArrival,
                                   AnimationOutcome outcome, double[] shotEnd, double reboundSign) {
        double[][] ball = new double[TOTAL_FRAMES + 1][2];
        for (BallLeg leg : legs) {
            int to = Math.min(leg.to(), TOTAL_FRAMES);
            for (int f = leg.from(); f <= to; f++) {
                switch (leg.kind()) {
                    case 0 -> { ball[f][0] = leg.fixedStart()[0]; ball[f][1] = leg.fixedStart()[1]; }
                    case 1 -> { ball[f][0] = pos[f][leg.carrierIdx()][0]; ball[f][1] = pos[f][leg.carrierIdx()][1]; }
                    default -> {
                        double[] s = leg.fixedStart() != null ? leg.fixedStart() : pos[leg.from()][leg.fromIdx()];
                        double[] e = leg.fixedEnd() != null ? leg.fixedEnd() : pos[Math.min(leg.to(), TOTAL_FRAMES)][leg.toIdx()];
                        double t = leg.to() == leg.from() ? 1 : (double) (f - leg.from()) / (leg.to() - leg.from());
                        double mx = (s[0] + e[0]) / 2, my = (s[1] + e[1]) / 2;
                        double dx = e[0] - s[0], dy = e[1] - s[1];
                        double len = Math.max(1e-9, Math.sqrt(dx * dx + dy * dy));
                        double cx = mx - dy / len * leg.curve();
                        double cy = my + dx / len * leg.curve();
                        double omt = 1 - t;
                        ball[f][0] = omt * omt * s[0] + 2 * omt * t * cx + t * t * e[0];
                        ball[f][1] = omt * omt * s[1] + 2 * omt * t * cy + t * t * e[1];
                    }
                }
            }
        }
        // Aftermath: from shot arrival to the end.
        double[] end = shotEnd;
        double[] rest;
        switch (outcome) {
            case SAVE -> rest = new double[]{Math.max(80, end[0] - 9), clampY(end[1] + reboundSign * 10)};
            case BLOCKED -> rest = new double[]{Math.max(60, end[0] - 7), clampY(end[1] + reboundSign * 5)};
            default -> rest = end; // GOAL in the net / MISS out of play: ball settles
        }
        int rollFrames = 22;
        for (int f = shotArrival + 1; f <= TOTAL_FRAMES; f++) {
            double t = Math.min(1.0, (double) (f - shotArrival) / rollFrames);
            double ease = 1 - (1 - t) * (1 - t);
            ball[f][0] = end[0] + (rest[0] - end[0]) * ease;
            ball[f][1] = end[1] + (rest[1] - end[1]) * ease;
        }
        return ball;
    }

    private long[] compileCarrier(List<BallLeg> legs, List<PlayerSnapshot> players) {
        long[] carrier = new long[TOTAL_FRAMES + 1];
        for (BallLeg leg : legs) {
            if (leg.kind() != 1) continue;
            int to = Math.min(leg.to(), TOTAL_FRAMES);
            for (int f = leg.from(); f < to; f++) carrier[f] = players.get(leg.carrierIdx()).playerId();
        }
        return carrier;
    }

    // ==================== FORMATION ====================

    private void assignFormation(List<PlayerSnapshot> side, boolean mirror, double[][] base, int offset) {
        Map<String, List<Integer>> byPos = new LinkedHashMap<>();
        for (int i = 0; i < side.size(); i++) {
            String p = side.get(i).position() != null ? side.get(i).position() : "MC";
            byPos.computeIfAbsent(p, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> e : byPos.entrySet()) {
            double[] bp = basePosition(e.getKey());
            List<Integer> group = e.getValue();
            for (int i = 0; i < group.size(); i++) {
                double x = bp[0];
                double y = group.size() == 1 ? bp[1]
                        : bp[1] - (group.size() - 1) * 13.0 + i * 26.0;
                if (mirror) x = 100 - x;
                base[offset + group.get(i)] = new double[]{clampX(x), clampY(y)};
            }
        }
    }

    static double[] basePosition(String position) {
        return switch (position != null ? position : "MC") {
            case "GK" -> new double[]{4, 50};
            case "DL" -> new double[]{25, 12};
            case "DC" -> new double[]{25, 50};
            case "DR" -> new double[]{25, 88};
            case "DM" -> new double[]{35, 50};
            case "ML" -> new double[]{48, 12};
            case "MC" -> new double[]{48, 50};
            case "MR" -> new double[]{48, 88};
            case "AML" -> new double[]{62, 18};
            case "AMC" -> new double[]{62, 50};
            case "AMR" -> new double[]{62, 82};
            case "ST" -> new double[]{72, 50};
            default -> new double[]{48, 50};
        };
    }

    static int positionGroup(String position) {
        return switch (position != null ? position : "MC") {
            case "GK" -> 0;
            case "DL", "DC", "DR" -> 1;
            case "DM", "ML", "MC", "MR" -> 2;
            default -> 3;
        };
    }

    private double[] ambientTarget(double[] base, int group, boolean attacking, double[] jitter) {
        double push;
        if (attacking) {
            push = switch (group) { case 0 -> 2; case 1 -> 6; case 2 -> 11; default -> 14; };
        } else {
            // Defending side retreats toward its own goal at x=100.
            push = switch (group) { case 0 -> 0.5; case 1 -> 6; case 2 -> 4; default -> 2; };
        }
        return new double[]{clampX(base[0] + push + jitter[0]), clampY(base[1] + jitter[1])};
    }

    private int goalkeeperIndex(List<PlayerSnapshot> defenders, double[][] base, int offset) {
        for (int i = 0; i < defenders.size(); i++) {
            if (defenders.get(i).isGoalkeeper()) return offset + i;
        }
        // Stand-in: the defender closest to their own goal line.
        int best = offset;
        for (int i = 0; i < defenders.size(); i++) {
            if (base[offset + i][0] > base[best][0]) best = offset + i;
        }
        return best;
    }

    // ==================== MATH ====================

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double clampX(double v) {
        return Math.max(PLAYER_MIN_X, Math.min(PLAYER_MAX_X, v));
    }

    private static double clampY(double v) {
        return Math.max(PLAYER_MIN_Y, Math.min(PLAYER_MAX_Y, v));
    }

    private static int clampI(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
