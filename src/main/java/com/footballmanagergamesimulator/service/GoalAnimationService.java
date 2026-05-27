package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.*;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GoalAnimationService {

    /**
     * Open-play attack scenarios. Each one varies the pass chain composition
     * and the scorer's path through the 6 keyframes, so the same engine
     * produces visually different attacks instead of every goal looking like
     * a generic 3-pass build-up.
     */
    private enum OpenPlayScenario {
        BUILD_UP,          // 2-3 pases through midfield (the historic default)
        QUICK_COUNTER,     // recovery in own half, fast diagonal to scorer in space
        LONG_BALL,         // GK/DC plays a single long ball to scorer making a deep run
        INDIVIDUAL_DRIBBLE,// scorer carries from midfield with minimal passing
        CROSS_AND_FINISH,  // pass out wide → cross from flank → striker finish in box
        ONE_TWO,           // scorer ↔ assister give-and-go near the box
        LONG_RANGE_SHOT    // mid-range build-up, scorer shoots from outside the box
    }

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamKitResolver teamKitResolver;

    /** Populate scoring + defending kits on the result so the frontend can color
     *  players per-team instead of falling back to its hard-coded blue/red defaults.
     *  Safe to call with null data (no-op) so callers don't need to null-check. */
    private void attachKits(GoalAnimationData data, long scoringTeamId, long defendingTeamId) {
        if (data == null) return;
        // Propagate the match-level stoppage flag onto the result so the
        // frontend can format minute display correctly ("45+2'" not "47'").
        data.setFirstHalfStoppage(firstHalfStoppage());
        Team scoring = teamRepository.findById(scoringTeamId).orElse(null);
        Team defending = teamRepository.findById(defendingTeamId).orElse(null);
        if (scoring == null || defending == null) return;
        TeamKit[] kits = teamKitResolver.resolveKits(scoring, defending);
        data.setScoringTeamKit(kits[0]);
        data.setDefendingTeamKit(kits[1]);
    }

    /** Per-match stoppage time stash. Set by LiveMatchSimulationService at the
     *  start of a match (via {@link #setMatchStoppage}) and read by the three
     *  generate*() methods below for their mirror logic. Using a ThreadLocal is
     *  cleaner than threading the value through 6+ method signatures and the
     *  helper {@link #buildAttackAnimation}. Reset to 0 between matches. */
    private final ThreadLocal<Integer> firstHalfStoppageTl = ThreadLocal.withInitial(() -> 0);

    /** Tell the animation service how many minutes of first-half stoppage this
     *  match has. Anything generated until {@link #clearMatchStoppage} treats
     *  minutes ≤ {@code 45 + firstHalfStoppage} as first half. */
    public void setMatchStoppage(int firstHalfStoppage) {
        firstHalfStoppageTl.set(Math.max(0, firstHalfStoppage));
    }

    public void clearMatchStoppage() { firstHalfStoppageTl.remove(); }

    private int firstHalfStoppage() { return firstHalfStoppageTl.get(); }

    private static final int TOTAL_FRAMES = 150;
    private static final int BALL_FLIGHT_FRAMES = 8;

    // Keyframe timings (6 keyframes)
    private static final int[] KF = {0, 30, 60, 90, 120, 150};

    // Forward push per keyframe for attacking team: [GK, DEF, MID, ATK]
    private static final double[][] ATK_PUSH = {
            {0, 0, 0, 0},
            {1, 4, 6, 4},
            {2, 7, 12, 8},
            {2, 9, 15, 13},
            {2, 9, 15, 16},
            {2, 9, 15, 16}
    };

    // Drop-back per keyframe for defending team: [GK, DEF, MID, ATK]
    // Positive = toward their own goal (x increases since they defend the right goal)
    private static final double[][] DEF_DROP = {
            {0, 0, 0, 0},
            {0, 2, 4, 1},
            {0, 5, 8, 2},
            {1, 7, 11, 3},
            {1, 8, 13, 3},
            {1, 8, 13, 3}
    };

    // Base formation positions (attacking team, attacks left to right)
    private static double[] basePos(String position) {
        return switch (position != null ? position : "MC") {
            case "GK"  -> new double[]{5, 50};
            case "DL"  -> new double[]{25, 12};
            case "DC"  -> new double[]{25, 50};
            case "DR"  -> new double[]{25, 88};
            case "DM"  -> new double[]{33, 50};
            case "ML"  -> new double[]{48, 12};
            case "MC"  -> new double[]{48, 50};
            case "MR"  -> new double[]{48, 88};
            case "AML" -> new double[]{62, 18};
            case "AMC" -> new double[]{62, 50};
            case "AMR" -> new double[]{62, 82};
            case "ST"  -> new double[]{72, 50};
            default    -> new double[]{48, 50};
        };
    }

    // Position group: 0=GK, 1=DEF, 2=MID, 3=ATK
    private static int posGroup(String position) {
        return switch (position != null ? position : "MC") {
            case "GK" -> 0;
            case "DL", "DC", "DR" -> 1;
            case "DM", "ML", "MC", "MR" -> 2;
            case "AML", "AMC", "AMR", "ST" -> 3;
            default -> 2;
        };
    }

    /**
     * Permitted X range for a player given their role + which side they're on.
     * The animation has the attacking team running toward x=100. Defending team's
     * GK is near x=95, so the field is split "attacking team owns the LEFT half,
     * defending team owns the RIGHT half" with overlap in midfield.
     *
     * Returned as {@code [minX, maxX]}; the caller clamps the player's final X
     * to this band, which is what stops attackers tracking all the way back to
     * defend the box.
     */
    private static double[] zoneRangeX(int group, boolean isDefendingTeam) {
        if (isDefendingTeam) {
            // Defending team — own goal at x=100, attacks toward x=0.
            return switch (group) {
                case 0 -> new double[]{85, 99};   // GK
                case 1 -> new double[]{55, 95};   // Defenders — own third + push to clear
                case 2 -> new double[]{30, 75};   // Midfielders — broad middle band
                case 3 -> new double[]{15, 65};   // Strikers — stay high for counter
                default -> new double[]{30, 75};
            };
        }
        // Attacking team — own goal at x=0, attacks toward x=100.
        return switch (group) {
            case 0 -> new double[]{1, 15};        // GK
            case 1 -> new double[]{10, 55};       // Defenders — push to halfway but not past
            case 2 -> new double[]{25, 80};       // Midfielders — broad middle band
            case 3 -> new double[]{45, 95};       // Strikers — opposition half
            default -> new double[]{25, 80};
        };
    }

    /**
     * Generate a goal animation with 151 frames (0-150) of 22-player positions.
     * Each player is always in motion: patrolling, tracking the ball, or making runs.
     *
     * Coordinate system: 0-100 for both X (left goal to right goal) and Y (sideline to sideline).
     * First half: home attacks right (x=100), away attacks left (x=0).
     * Second half: sides swap.
     */
    public GoalAnimationData generate(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human scorer,
            Human assister,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute) {
        return generate(attackingAll, defendingAll, scorer, assister,
                scoringTeamId, defendingTeamId, homeTeamId, minute, "GOAL");
    }

    /**
     * Same as {@link #generate} but the {@code outcome} parameter controls where
     * the ball ends up: "GOAL" puts it in the net, "SAVE" sends it onto the GK's
     * gloves (GK dives the right way), "MISS" sends it wide/over the bar.
     */
    public GoalAnimationData generate(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human scorer,
            Human assister,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            String outcome) {

        if (scorer == null) return null;
        if (outcome == null) outcome = "GOAL";

        Random rng = new Random(scorer.getId() * 31L + minute * 17L);

        // 1. Select best 11 per side
        List<Human> atk11 = selectEleven(attackingAll, scorer, assister);
        List<Human> def11 = selectEleven(defendingAll, null, null);

        // 2. Assign base formation positions
        Map<Long, double[]> atkBase = assignPositions(atk11, false);
        Map<Long, double[]> defBase = assignPositions(def11, true);

        // 3. Pick a scenario flavour for this attack — counter, cross, dribble, etc.
        // Each scenario shapes the pass chain + the scorer's path differently so
        // the engine produces visually different goals from the same components.
        OpenPlayScenario scenario = selectScenario(scorer, assister, rng);

        // 4. Build pass chain shaped by the scenario
        List<Human> chain = buildPassChain(atk11, scorer, assister, scenario, rng);
        Set<Long> chainIds = chain.stream().map(Human::getId).collect(Collectors.toSet());

        // 5. Compute pass event timings
        int numPasses = chain.size() - 1;
        int[] passFrames = computePassFrames(numPasses);

        // 6. Generate 6 keyframes with target positions for all 22 players
        List<Map<Long, double[]>> keyframes = generateKeyframes(
                atk11, def11, atkBase, defBase, scorer, assister, scenario, rng);

        // 6. Ordered player list (attacking first, then defending)
        List<Human> allPlayers = new ArrayList<>(atk11);
        allPlayers.addAll(def11);

        // 7. Build events list (final event matches the outcome so the frontend
        // shows "GOAL!" / "SAVED!" / "MISSED!" text accurately).
        List<AnimationEvent> events = buildEvents(chain, scorer, passFrames, outcome);

        // 8. Goal target Y — depends on outcome. Y bounds must match the goal
        // posts drawn on the canvas: pitch height h * 0.12 centred at h/2,
        // so the goal mouth in world coordinates is roughly Y ∈ [44, 56].
        //    GOAL: ball lands INSIDE the posts (Y in 45-55 — slight inset so it
        //          never looks like the ball clipped the woodwork on the way in)
        //    SAVE: ball is on target somewhere across the goalmouth (Y in 40-60,
        //          a touch wider so saves at the post still look believable)
        //    MISS: ball goes wide or over (Y outside [0,22] or [78,100])
        double goalY;
        switch (outcome) {
            case "MISS":
                goalY = rng.nextBoolean()
                        ? rng.nextDouble() * 22                   // wide left
                        : 78 + rng.nextDouble() * 22;             // wide right
                break;
            case "SAVE":
                goalY = 40 + rng.nextDouble() * 20;               // on target
                break;
            case "GOAL":
            default:
                goalY = 45 + rng.nextDouble() * 10;               // inside the posts
                break;
        }

        // 9. Per-player patrol parameters (unique frequencies/phases for organic movement)
        Map<Long, double[]> patrolParams = new LinkedHashMap<>();
        for (Human p : allPlayers) {
            long id = p.getId();
            patrolParams.put(id, new double[]{
                    0.07 + (id % 7) * 0.015,       // freqX
                    0.055 + (id % 5) * 0.018,       // freqY
                    (id * 37L) % 100,                // phaseX
                    (id * 53L) % 100                 // phaseY
            });
        }

        // 10. Identify defending GK for dive behavior
        long defGkId = def11.stream()
                .filter(h -> "GK".equals(h.getPosition()))
                .map(Human::getId)
                .findFirst().orElse(0L);
        //   GOAL → GK dives the WRONG way (offset Y far from ball)
        //   SAVE → GK dives ON the ball (close to goalY)
        //   MISS → GK stays near centre (shot was missing anyway, no point committing)
        double gkDiveTargetY;
        switch (outcome) {
            case "SAVE":
                gkDiveTargetY = goalY + (rng.nextDouble() - 0.5) * 4;
                break;
            case "MISS":
                gkDiveTargetY = 50 + (rng.nextDouble() - 0.5) * 6;
                break;
            case "GOAL":
            default:
                gkDiveTargetY = goalY + (rng.nextDouble() < 0.5 ? -14 : 14);
                break;
        }

        // 11. Interpolate all frames with dynamic behavior
        List<AnimationFrame> frames = new ArrayList<>();

        long currentCarrier = chain.get(0).getId();
        int nextPassIdx = 0;

        for (int f = 0; f <= TOTAL_FRAMES; f++) {

            // --- Keyframe segment and interpolation factor ---
            int seg = 0;
            for (int k = KF.length - 2; k >= 0; k--) {
                if (f >= KF[k]) { seg = k; break; }
            }
            int segStart = KF[seg];
            int segEnd = KF[Math.min(seg + 1, KF.length - 1)];
            double t = segEnd > segStart ? (double) (f - segStart) / (segEnd - segStart) : 1.0;
            t = easeInOut(t);

            Map<Long, double[]> kfFrom = keyframes.get(seg);
            Map<Long, double[]> kfTo = keyframes.get(Math.min(seg + 1, keyframes.size() - 1));

            // --- Pre-compute base interpolated positions (before dynamics) ---
            Map<Long, double[]> baseInterp = new LinkedHashMap<>();
            for (Human p : allPlayers) {
                double[] pf = kfFrom.getOrDefault(p.getId(), new double[]{50, 50});
                double[] pt = kfTo.getOrDefault(p.getId(), new double[]{50, 50});
                baseInterp.put(p.getId(), new double[]{lerp(pf[0], pt[0], t), lerp(pf[1], pt[1], t)});
            }

            // --- Compute ball position (from carrier's base position) ---
            // Update carrier based on completed passes
            if (nextPassIdx < passFrames.length && f >= passFrames[nextPassIdx] + BALL_FLIGHT_FRAMES) {
                currentCarrier = chain.get(Math.min(nextPassIdx + 1, chain.size() - 1)).getId();
                nextPassIdx++;
            }

            double ballX = 50, ballY = 50;
            long ballCarrierId;
            boolean ballFlying = false;

            // Check if ball is in flight (during a pass)
            for (int pi = 0; pi < passFrames.length; pi++) {
                int pf = passFrames[pi];
                if (f >= pf && f < pf + BALL_FLIGHT_FRAMES) {
                    double ft = (double) (f - pf) / BALL_FLIGHT_FRAMES;
                    double[] fromPos = baseInterp.getOrDefault(chain.get(pi).getId(), new double[]{50, 50});
                    double[] toPos = baseInterp.getOrDefault(chain.get(Math.min(pi + 1, chain.size() - 1)).getId(), new double[]{50, 50});
                    ballX = lerp(fromPos[0], toPos[0], ft);
                    ballY = lerp(fromPos[1], toPos[1], ft);
                    ballFlying = true;
                    break;
                }
            }

            // Check if shot is in progress (frames 135-150). For SAVE the ball
            // stops at the keeper's gloves (x≈97); for GOAL/MISS it reaches the
            // goal line (x=100). For MISS the precomputed goalY is already
            // outside the post range so the ball visibly flies wide.
            if (!ballFlying && f >= 135) {
                double st = Math.min(1.0, (double) (f - 135) / 13.0);
                double[] scorerBase = baseInterp.getOrDefault(scorer.getId(), new double[]{90, 50});
                double shotTargetX = "SAVE".equals(outcome) ? 97 : 100;
                ballX = lerp(scorerBase[0], shotTargetX, st);
                ballY = lerp(scorerBase[1], goalY, st);
                ballFlying = true;
            }

            if (!ballFlying) {
                double[] carrierBase = baseInterp.getOrDefault(currentCarrier, new double[]{50, 50});
                ballX = carrierBase[0];
                ballY = carrierBase[1];
            }
            ballCarrierId = ballFlying ? 0 : currentCarrier;

            // --- Compute final positions with dynamic behaviors ---
            List<double[]> positions = new ArrayList<>();

            for (Human p : allPlayers) {
                double[] base = baseInterp.get(p.getId());
                double bx = base[0], by = base[1];
                boolean isCarrier = (p.getId() == currentCarrier && !ballFlying);
                boolean isInChain = chainIds.contains(p.getId());
                int group = posGroup(p.getPosition());
                boolean isDefendingTeam = def11.contains(p);

                // Zone constraints (in X axis). Each player has a band of the pitch
                // they're allowed to occupy — defenders shouldn't push past midfield
                // during their team's attack, attackers shouldn't track back into
                // their own box to defend. Y is left free since lateral tracking is
                // natural. Without these clamps, the ball-attraction loop pulled
                // every nearby outfield player toward the ball and produced visuals
                // where strikers were defending corners on their own goal line.
                double[] zone = zoneRangeX(group, isDefendingTeam);
                double zoneMinX = zone[0];
                double zoneMaxX = zone[1];

                // 1. Sinusoidal patrol (every player always moving)
                double[] pp = patrolParams.get(p.getId());
                double patrolAmp;
                if (isCarrier) {
                    patrolAmp = 0.4; // carrier barely patrols, moves with purpose
                } else if (group == 0) {
                    patrolAmp = 0.8; // GKs: small patrol
                } else if (isInChain) {
                    patrolAmp = 1.2; // chain players: moderate, directed movement
                } else {
                    patrolAmp = 2.5; // uninvolved players: visible patrolling
                }
                double patrolX = Math.sin(f * pp[0] + pp[2]) * patrolAmp;
                double patrolY = Math.cos(f * pp[1] + pp[3]) * patrolAmp;

                // 2. Ball attraction (nearby players drift toward the ball).
                // Off-zone players get a heavy damp so they don't abandon their
                // role: defending strikers don't drop into their own half to
                // help the GK; attacking defenders don't push up to the box.
                double dx = ballX - bx;
                double dy = ballY - by;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double attractX = 0, attractY = 0;
                if (!isCarrier && dist > 3 && dist < 35 && group != 0) {
                    double force = Math.min(2.5, 15.0 / dist);
                    if (!isDefendingTeam) force *= 1.3;
                    // Defending team's strikers: stay high for counter.
                    if (isDefendingTeam && group == 3) force *= 0.25;
                    // Attacking team's defenders: hold position, don't push up.
                    if (!isDefendingTeam && group == 1) force *= 0.4;
                    attractX = (dx / dist) * force;
                    attractY = (dy / dist) * force;
                }

                // 3. Lateral tracking (defenders and midfielders shift toward ball's Y)
                double trackY = 0;
                if (group == 1 || group == 2) {
                    trackY = (ballY - by) * 0.08;
                }

                // 4. Supporting runs (attacking players without ball make diagonal runs forward)
                double runX = 0, runY = 0;
                if (!isDefendingTeam && !isCarrier && group >= 2 && f > 60) {
                    double runProgress = Math.min(1.0, (f - 60.0) / 90.0);
                    runX = Math.sin(f * pp[0] * 0.5 + pp[2]) * 1.5 * runProgress;
                    runY = Math.cos(f * pp[1] * 0.7 + pp[3]) * 2.0 * runProgress;
                }

                // 5. Defending GK dive (frames 130+)
                if (p.getId() == defGkId && f >= 130) {
                    double diveT = Math.min(1.0, (f - 130.0) / 18.0);
                    diveT = easeInOut(diveT);
                    by = lerp(by, gkDiveTargetY, diveT);
                    bx = lerp(bx, 97, diveT * 0.4);
                    patrolX = 0; patrolY = 0;
                    attractX = 0; attractY = 0;
                    trackY = 0; runX = 0; runY = 0;
                }

                // Hard clamp to the role's allowed X range so the damping above
                // can't be overrun by an aggressive patrol/run + attract combo.
                double finalX = clamp(bx + patrolX + attractX + runX, zoneMinX, zoneMaxX);
                double finalY = clamp(by + patrolY + attractY + trackY + runY, 2, 98);
                // Ball carrier and goalkeeper on a dive bypass the zone clamp —
                // a striker carrying the ball IS allowed in the opposition box.
                if (isCarrier || (p.getId() == defGkId && f >= 130)) {
                    finalX = clamp(bx + patrolX + attractX + runX, 1, 99);
                }

                positions.add(new double[]{
                        Math.round(finalX * 10.0) / 10.0,
                        Math.round(finalY * 10.0) / 10.0
                });
            }

            AnimationFrame frame = new AnimationFrame();
            frame.setBallX(Math.round(ballX * 10.0) / 10.0);
            frame.setBallY(Math.round(ballY * 10.0) / 10.0);
            frame.setBallCarrierId(ballCarrierId);
            frame.setPositions(positions);
            frames.add(frame);
        }

        // 12. Build player info list
        List<AnimationPlayer> playerInfos = new ArrayList<>();
        for (Human p : atk11) playerInfos.add(toAnimPlayer(p, scoringTeamId));
        for (Human p : def11) playerInfos.add(toAnimPlayer(p, defendingTeamId));

        // 13. Half-aware mirroring
        // First half: home attacks right (x=100), away attacks left (x=0)
        // Second half: sides swap
        boolean isFirstHalf = minute <= (45 + firstHalfStoppage());
        boolean scorerIsHome = scoringTeamId == homeTeamId;
        boolean homeAttacksRight = isFirstHalf; // first half → right, second half → left
        // The animation was generated with scoring team attacking RIGHT.
        // Mirror if the scoring team should actually attack LEFT.
        boolean needsMirror = isFirstHalf != scorerIsHome;
        //  1st half + home scores → no mirror (home attacks right ✓)
        //  1st half + away scores → mirror (away should attack left)
        //  2nd half + home scores → mirror (home should attack left in 2nd half)
        //  2nd half + away scores → no mirror (away attacks right in 2nd half ✓)

        if (needsMirror) {
            for (AnimationFrame frame : frames) {
                frame.setBallX(Math.round((100 - frame.getBallX()) * 10.0) / 10.0);
                for (double[] pos : frame.getPositions()) {
                    pos[0] = Math.round((100 - pos[0]) * 10.0) / 10.0;
                }
            }
        }

        // 14. Assemble result
        GoalAnimationData data = new GoalAnimationData();
        data.setMinute(minute);
        data.setScoringTeamId(scoringTeamId);
        data.setDefendingTeamId(defendingTeamId);
        data.setHomeTeamId(homeTeamId);
        data.setTotalFrames(TOTAL_FRAMES);
        data.setHomeAttacksRight(homeAttacksRight);
        data.setScorerPlayerId(scorer.getId());
        data.setScorerName(scorer.getName());
        data.setScorerNumber(scorer.getShirtNumber());
        if (assister != null) {
            data.setAssisterPlayerId(assister.getId());
            data.setAssisterName(assister.getName());
        }
        data.setAnimationType("OPEN_PLAY");
        data.setOutcome(outcome);
        data.setPlayers(playerInfos);
        data.setFrames(frames);
        data.setEvents(events);
        attachKits(data, scoringTeamId, defendingTeamId);
        return data;
    }

    // ==================== PENALTY ====================

    /**
     * Generate a penalty animation.
     * Taker approaches from behind the spot, kicks at frame 70.
     * Other players stand outside the box, then rush in after the kick.
     * Outcomes: GOAL or SAVE (ball rebounds out of play).
     */
    public GoalAnimationData generatePenalty(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human taker,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            boolean isGoal) {

        if (taker == null) return null;

        Random rng = new Random(taker.getId() * 41L + minute * 13L);

        List<Human> atk11 = selectEleven(attackingAll, taker, null);
        List<Human> def11 = selectEleven(defendingAll, null, null);

        // Ordered player list
        List<Human> allPlayers = new ArrayList<>(atk11);
        allPlayers.addAll(def11);

        // Identify GK
        long defGkId = def11.stream()
                .filter(h -> "GK".equals(h.getPosition()))
                .map(Human::getId).findFirst().orElse(0L);

        // Penalty spot: x=88, y=50 (attacking toward x=100)
        double penaltyX = 88, penaltyY = 50;
        // GK on goal line
        double gkX = 99, gkY = 50;
        // Shot target (where taker aims)
        double shotTargetY = 35 + rng.nextDouble() * 30; // 35-65 (spread across goal)
        // GK dive direction (wrong way if goal, right way if save)
        double gkDiveY;
        if (isGoal) {
            gkDiveY = shotTargetY > 50 ? 50 - (shotTargetY - 50) * 0.8 : 50 + (50 - shotTargetY) * 0.8;
        } else {
            gkDiveY = shotTargetY + (rng.nextDouble() - 0.5) * 6; // close to ball
        }

        // Static positions for other players (outside penalty box, x~72-78)
        Map<Long, double[]> staticPositions = new LinkedHashMap<>();
        List<Human> atkOutfield = atk11.stream()
                .filter(h -> h.getId() != taker.getId()).collect(Collectors.toList());
        List<Human> defOutfield = def11.stream()
                .filter(h -> h.getId() != defGkId).collect(Collectors.toList());

        // Place attackers on left side of the box arc (y=20-45 and y=55-80)
        for (int i = 0; i < atkOutfield.size(); i++) {
            double px = 74 + rng.nextDouble() * 4;
            double py;
            if (i % 2 == 0) {
                py = 22 + i * 4 + rng.nextDouble() * 3;
            } else {
                py = 58 + i * 4 + rng.nextDouble() * 3;
            }
            staticPositions.put(atkOutfield.get(i).getId(), new double[]{clamp(px, 72, 78), clamp(py, 8, 92)});
        }

        // Place defenders similarly
        for (int i = 0; i < defOutfield.size(); i++) {
            double px = 74 + rng.nextDouble() * 4;
            double py;
            if (i % 2 == 0) {
                py = 20 + i * 4 + rng.nextDouble() * 3;
            } else {
                py = 60 + i * 4 + rng.nextDouble() * 3;
            }
            staticPositions.put(defOutfield.get(i).getId(), new double[]{clamp(px, 72, 78), clamp(py, 8, 92)});
        }

        // Per-player patrol params
        Map<Long, double[]> patrolParams = new LinkedHashMap<>();
        for (Human p : allPlayers) {
            long id = p.getId();
            patrolParams.put(id, new double[]{
                    0.06 + (id % 7) * 0.012, 0.05 + (id % 5) * 0.014,
                    (id * 37L) % 100, (id * 53L) % 100
            });
        }

        // Frame-by-frame generation
        List<AnimationFrame> frames = new ArrayList<>();
        int kickFrame = 70;
        int ballArriveFrame = 85;

        for (int f = 0; f <= TOTAL_FRAMES; f++) {
            List<double[]> positions = new ArrayList<>();
            double ballX, ballY;
            long ballCarrierId = 0;

            // --- Taker position ---
            double takerX, takerY;
            if (f <= kickFrame) {
                // Walk up to the ball from behind
                double approach = (double) f / kickFrame;
                approach = easeInOut(approach);
                takerX = lerp(80, penaltyX, approach);
                takerY = lerp(50, penaltyY, approach);
                ballCarrierId = (f < kickFrame) ? taker.getId() : 0;
            } else {
                // After kick, taker follows through toward goal
                double followT = Math.min(1.0, (f - kickFrame) / 30.0);
                takerX = lerp(penaltyX, 92, easeInOut(followT));
                takerY = lerp(penaltyY, shotTargetY, followT * 0.3);
            }

            // --- Ball position ---
            if (f < kickFrame) {
                ballX = lerp(80, penaltyX, easeInOut((double) f / kickFrame));
                ballY = penaltyY;
            } else if (f <= ballArriveFrame) {
                double shotT = (double) (f - kickFrame) / (ballArriveFrame - kickFrame);
                if (isGoal) {
                    ballX = lerp(penaltyX, 100, shotT);
                    ballY = lerp(penaltyY, shotTargetY, shotT);
                } else {
                    // Ball goes to GK area, then rebounds
                    ballX = lerp(penaltyX, 99, shotT);
                    ballY = lerp(penaltyY, shotTargetY, shotT);
                }
            } else {
                if (isGoal) {
                    // Ball in net
                    ballX = 100;
                    ballY = shotTargetY;
                } else {
                    // Ball rebounds out of play
                    double reboundT = Math.min(1.0, (double) (f - ballArriveFrame) / 25.0);
                    double reboundAngle = rng.nextDouble() < 0.5 ? -1 : 1;
                    ballX = lerp(99, 92, reboundT);
                    ballY = lerp(shotTargetY, shotTargetY + reboundAngle * 40, reboundT);
                }
            }

            // --- GK position ---
            double gkXPos, gkYPos;
            if (f <= kickFrame) {
                // GK bouncing on line
                double[] pp = patrolParams.get(defGkId);
                gkXPos = gkX + Math.sin(f * 0.15) * 0.3;
                gkYPos = gkY + Math.sin(f * (pp != null ? pp[0] : 0.1)) * 1.5;
            } else {
                // GK dives
                double diveT = Math.min(1.0, (double) (f - kickFrame) / 12.0);
                diveT = easeInOut(diveT);
                gkXPos = lerp(gkX, 99.5, diveT * 0.3);
                gkYPos = lerp(gkY, gkDiveY, diveT);
            }

            // Build positions in order: atk11 then def11
            for (Human p : allPlayers) {
                double px, py;
                if (p.getId() == taker.getId()) {
                    px = takerX;
                    py = takerY;
                } else if (p.getId() == defGkId) {
                    px = gkXPos;
                    py = gkYPos;
                } else {
                    double[] sp = staticPositions.getOrDefault(p.getId(), new double[]{50, 50});
                    double[] pp = patrolParams.get(p.getId());

                    if (f <= kickFrame) {
                        // Subtle patrol while waiting
                        px = sp[0] + Math.sin(f * pp[0] + pp[2]) * 0.6;
                        py = sp[1] + Math.cos(f * pp[1] + pp[3]) * 0.8;
                    } else {
                        // Rush toward the goal area after the kick
                        double rushT = Math.min(1.0, (double) (f - kickFrame) / 60.0);
                        rushT = easeInOut(rushT);
                        double targetX = 88 + rng.nextDouble() * 8;
                        double targetY = 30 + rng.nextDouble() * 40;
                        // Use deterministic target based on player id
                        targetX = 86 + (p.getId() % 10) * 1.2;
                        targetY = 25 + (p.getId() % 13) * 4;
                        px = lerp(sp[0], targetX, rushT) + Math.sin(f * pp[0] + pp[2]) * 0.4;
                        py = lerp(sp[1], targetY, rushT) + Math.cos(f * pp[1] + pp[3]) * 0.5;
                    }
                }
                positions.add(new double[]{
                        Math.round(clamp(px, 1, 99) * 10.0) / 10.0,
                        Math.round(clamp(py, 2, 98) * 10.0) / 10.0
                });
            }

            AnimationFrame frame = new AnimationFrame();
            frame.setBallX(Math.round(clamp(ballX, 0, 100) * 10.0) / 10.0);
            frame.setBallY(Math.round(clamp(ballY, 0, 100) * 10.0) / 10.0);
            frame.setBallCarrierId(ballCarrierId);
            frame.setPositions(positions);
            frames.add(frame);
        }

        // Events
        List<AnimationEvent> events = new ArrayList<>();
        AnimationEvent shot = new AnimationEvent();
        shot.setFrame(kickFrame);
        shot.setType("SHOT");
        shot.setFromPlayerId(taker.getId());
        events.add(shot);

        if (isGoal) {
            AnimationEvent goal = new AnimationEvent();
            goal.setFrame(ballArriveFrame);
            goal.setType("GOAL");
            goal.setFromPlayerId(taker.getId());
            events.add(goal);
        } else {
            AnimationEvent save = new AnimationEvent();
            save.setFrame(ballArriveFrame);
            save.setType("SAVE");
            save.setFromPlayerId(defGkId);
            events.add(save);
        }

        // Player infos
        List<AnimationPlayer> playerInfos = new ArrayList<>();
        for (Human p : atk11) playerInfos.add(toAnimPlayer(p, scoringTeamId));
        for (Human p : def11) playerInfos.add(toAnimPlayer(p, defendingTeamId));

        // Half-aware mirroring
        boolean isFirstHalf = minute <= (45 + firstHalfStoppage());
        boolean scorerIsHome = scoringTeamId == homeTeamId;
        boolean homeAttacksRight = isFirstHalf;
        boolean needsMirror = isFirstHalf != scorerIsHome;

        if (needsMirror) {
            for (AnimationFrame frame : frames) {
                frame.setBallX(Math.round((100 - frame.getBallX()) * 10.0) / 10.0);
                for (double[] pos : frame.getPositions()) {
                    pos[0] = Math.round((100 - pos[0]) * 10.0) / 10.0;
                }
            }
        }

        GoalAnimationData data = new GoalAnimationData();
        data.setMinute(minute);
        data.setScoringTeamId(scoringTeamId);
        data.setDefendingTeamId(defendingTeamId);
        data.setHomeTeamId(homeTeamId);
        data.setTotalFrames(TOTAL_FRAMES);
        data.setAnimationType("PENALTY");
        data.setOutcome(isGoal ? "GOAL" : "SAVE");
        data.setHomeAttacksRight(homeAttacksRight);
        data.setScorerPlayerId(taker.getId());
        data.setScorerName(taker.getName());
        data.setScorerNumber(taker.getShirtNumber());
        data.setPlayers(playerInfos);
        data.setFrames(frames);
        data.setEvents(events);
        attachKits(data, scoringTeamId, defendingTeamId);
        return data;
    }

    // ==================== FREE KICK ====================

    /**
     * Generate a free kick animation.
     * Ball placed 20-35m from goal, taker approaches, wall of 4-5 defenders.
     * Ball curves via quadratic bezier toward goal.
     * Outcomes: GOAL, SAVE, or MISS (ball goes out).
     */
    public GoalAnimationData generateFreeKick(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human taker,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            String outcome) { // "GOAL", "SAVE", "MISS"

        if (taker == null) return null;

        Random rng = new Random(taker.getId() * 47L + minute * 19L);

        List<Human> atk11 = selectEleven(attackingAll, taker, null);
        List<Human> def11 = selectEleven(defendingAll, null, null);

        List<Human> allPlayers = new ArrayList<>(atk11);
        allPlayers.addAll(def11);

        long defGkId = def11.stream()
                .filter(h -> "GK".equals(h.getPosition()))
                .map(Human::getId).findFirst().orElse(0L);

        // Free kick distance: 20-35m from goal = x 67-81 (100-yard pitch mapped to 0-100)
        double fkX = 67 + rng.nextDouble() * 14;
        // Random Y position (not too close to sideline)
        double fkY = 25 + rng.nextDouble() * 50;

        // Wall position: 9 units in front of ball (toward goal)
        double wallX = fkX + 9;
        int wallSize = 4 + (rng.nextDouble() < 0.4 ? 1 : 0); // 4 or 5

        // Shot target
        double shotTargetX = 100;
        double shotTargetY;
        if ("GOAL".equals(outcome)) {
            shotTargetY = 38 + rng.nextDouble() * 24; // inside goal (38-62)
        } else if ("SAVE".equals(outcome)) {
            shotTargetY = 40 + rng.nextDouble() * 20; // near center, GK reaches it
        } else {
            // MISS: ball goes wide or over
            shotTargetY = rng.nextDouble() < 0.5 ? 25 + rng.nextDouble() * 10 : 65 + rng.nextDouble() * 10;
        }

        // Bezier control point for ball curve
        double controlX = (fkX + shotTargetX) / 2;
        double curveBias = (rng.nextDouble() < 0.5 ? -1 : 1) * (8 + rng.nextDouble() * 6);
        double controlY = (fkY + shotTargetY) / 2 + curveBias;

        // GK position
        double gkStartX = 98;
        double gkStartY = 48 + rng.nextDouble() * 4;
        double gkDiveY;
        if ("GOAL".equals(outcome)) {
            // GK dives wrong way
            gkDiveY = shotTargetY > 50 ? shotTargetY - 18 : shotTargetY + 18;
        } else if ("SAVE".equals(outcome)) {
            gkDiveY = shotTargetY + (rng.nextDouble() - 0.5) * 5;
        } else {
            // MISS: GK dives toward where ball was going
            gkDiveY = shotTargetY + (rng.nextDouble() - 0.5) * 8;
        }

        // Place wall defenders
        List<Long> wallPlayerIds = new ArrayList<>();
        List<Human> defOutfield = def11.stream()
                .filter(h -> h.getId() != defGkId).collect(Collectors.toList());
        Collections.shuffle(defOutfield, rng);
        for (int i = 0; i < Math.min(wallSize, defOutfield.size()); i++) {
            wallPlayerIds.add(defOutfield.get(i).getId());
        }

        // Static positions for all non-special players
        Map<Long, double[]> staticPositions = new LinkedHashMap<>();

        // Wall players: lined up at wallX, centered on ball's Y
        for (int i = 0; i < wallPlayerIds.size(); i++) {
            double spread = (i - (wallPlayerIds.size() - 1) / 2.0) * 3;
            staticPositions.put(wallPlayerIds.get(i), new double[]{wallX, clamp(fkY + spread, 5, 95)});
        }

        // Remaining defenders: in and around the box
        List<Human> defRest = defOutfield.stream()
                .filter(h -> !wallPlayerIds.contains(h.getId())).collect(Collectors.toList());
        for (int i = 0; i < defRest.size(); i++) {
            double px = 88 + rng.nextDouble() * 8;
            double py = 25 + i * 10 + rng.nextDouble() * 5;
            staticPositions.put(defRest.get(i).getId(), new double[]{clamp(px, 82, 97), clamp(py, 15, 85)});
        }

        // Attacking players (except taker): in and around the box
        List<Human> atkOutfield = atk11.stream()
                .filter(h -> h.getId() != taker.getId()).collect(Collectors.toList());
        for (int i = 0; i < atkOutfield.size(); i++) {
            Human p = atkOutfield.get(i);
            int g = posGroup(p.getPosition());
            double px, py;
            if (g >= 2) {
                // Midfielders and forwards: in/near the box
                px = 82 + rng.nextDouble() * 10;
                py = 25 + rng.nextDouble() * 50;
            } else if (g == 1) {
                // Defenders: further back
                px = 55 + rng.nextDouble() * 15;
                py = 20 + rng.nextDouble() * 60;
            } else {
                // GK: own half
                px = 8 + rng.nextDouble() * 5;
                py = 40 + rng.nextDouble() * 20;
            }
            staticPositions.put(p.getId(), new double[]{clamp(px, 3, 97), clamp(py, 5, 95)});
        }

        // Patrol params
        Map<Long, double[]> patrolParams = new LinkedHashMap<>();
        for (Human p : allPlayers) {
            long id = p.getId();
            patrolParams.put(id, new double[]{
                    0.06 + (id % 7) * 0.012, 0.05 + (id % 5) * 0.014,
                    (id * 37L) % 100, (id * 53L) % 100
            });
        }

        // Frame generation
        List<AnimationFrame> frames = new ArrayList<>();
        int kickFrame = 60;
        int ballArriveFrame = 80;

        for (int f = 0; f <= TOTAL_FRAMES; f++) {
            List<double[]> positions = new ArrayList<>();
            double ballX, ballY;
            long ballCarrierId = 0;

            // --- Taker ---
            double takerPx, takerPy;
            if (f <= kickFrame) {
                double approach = (double) f / kickFrame;
                approach = easeInOut(approach);
                // Approach from 5 units behind the ball
                takerPx = lerp(fkX - 5, fkX, approach);
                takerPy = lerp(fkY + 2, fkY, approach);
                ballCarrierId = (f < kickFrame) ? taker.getId() : 0;
            } else {
                double followT = Math.min(1.0, (f - kickFrame) / 25.0);
                takerPx = lerp(fkX, fkX + 5, easeInOut(followT));
                takerPy = fkY + Math.sin(f * 0.08) * 1.5;
            }

            // --- Ball ---
            if (f < kickFrame) {
                ballX = fkX;
                ballY = fkY;
            } else if (f <= ballArriveFrame) {
                // Quadratic bezier curve
                double t = (double) (f - kickFrame) / (ballArriveFrame - kickFrame);
                double oneMinusT = 1 - t;
                ballX = oneMinusT * oneMinusT * fkX + 2 * oneMinusT * t * controlX + t * t * shotTargetX;
                ballY = oneMinusT * oneMinusT * fkY + 2 * oneMinusT * t * controlY + t * t * shotTargetY;
            } else {
                if ("GOAL".equals(outcome)) {
                    ballX = 100;
                    ballY = shotTargetY;
                } else if ("SAVE".equals(outcome)) {
                    double reboundT = Math.min(1.0, (double) (f - ballArriveFrame) / 25.0);
                    double reboundDir = rng.nextDouble() < 0.5 ? -1 : 1;
                    ballX = lerp(99, 88, reboundT);
                    ballY = lerp(shotTargetY, shotTargetY + reboundDir * 30, reboundT);
                } else {
                    // MISS: ball continues past the goal line
                    double missT = Math.min(1.0, (double) (f - ballArriveFrame) / 20.0);
                    ballX = lerp(shotTargetX, 100, missT);
                    ballY = lerp(shotTargetY, shotTargetY + (shotTargetY > 50 ? 10 : -10), missT);
                }
            }

            // --- GK ---
            double gkPx, gkPy;
            if (f <= kickFrame) {
                double[] pp = patrolParams.get(defGkId);
                gkPx = gkStartX + Math.sin(f * 0.12) * 0.3;
                gkPy = gkStartY + Math.sin(f * (pp != null ? pp[0] : 0.1)) * 1.2;
            } else {
                double diveT = Math.min(1.0, (double) (f - kickFrame) / 14.0);
                diveT = easeInOut(diveT);
                gkPx = lerp(gkStartX, 99.5, diveT * 0.3);
                gkPy = lerp(gkStartY, gkDiveY, diveT);
            }

            // --- All players ---
            for (Human p : allPlayers) {
                double px, py;
                if (p.getId() == taker.getId()) {
                    px = takerPx;
                    py = takerPy;
                } else if (p.getId() == defGkId) {
                    px = gkPx;
                    py = gkPy;
                } else {
                    double[] sp = staticPositions.getOrDefault(p.getId(), new double[]{50, 50});
                    double[] pp = patrolParams.get(p.getId());
                    boolean isWallPlayer = wallPlayerIds.contains(p.getId());

                    if (f <= kickFrame) {
                        if (isWallPlayer) {
                            // Wall players: very minimal movement
                            px = sp[0] + Math.sin(f * pp[0] + pp[2]) * 0.3;
                            py = sp[1] + Math.cos(f * pp[1] + pp[3]) * 0.3;
                        } else {
                            // Others jockeying for position
                            px = sp[0] + Math.sin(f * pp[0] + pp[2]) * 1.2;
                            py = sp[1] + Math.cos(f * pp[1] + pp[3]) * 1.5;
                        }
                    } else {
                        if (isWallPlayer) {
                            // Wall breaks apart, players scatter
                            double scatterT = Math.min(1.0, (f - kickFrame) / 40.0);
                            double scatterX = sp[0] + (p.getId() % 2 == 0 ? 3 : -3) * scatterT;
                            double scatterY = sp[1] + ((p.getId() % 3) - 1) * 6 * scatterT;
                            px = scatterX + Math.sin(f * pp[0] + pp[2]) * 0.8;
                            py = scatterY + Math.cos(f * pp[1] + pp[3]) * 1.0;
                        } else {
                            // React to outcome - move toward ball area
                            double reactT = Math.min(1.0, (f - kickFrame) / 50.0);
                            reactT = easeInOut(reactT);
                            double targetX = 85 + (p.getId() % 10) * 1.2;
                            double targetY = 30 + (p.getId() % 11) * 3.5;
                            px = lerp(sp[0], targetX, reactT * 0.6) + Math.sin(f * pp[0] + pp[2]) * 0.8;
                            py = lerp(sp[1], targetY, reactT * 0.4) + Math.cos(f * pp[1] + pp[3]) * 1.0;
                        }
                    }
                }
                positions.add(new double[]{
                        Math.round(clamp(px, 1, 99) * 10.0) / 10.0,
                        Math.round(clamp(py, 2, 98) * 10.0) / 10.0
                });
            }

            AnimationFrame frame = new AnimationFrame();
            frame.setBallX(Math.round(clamp(ballX, 0, 100) * 10.0) / 10.0);
            frame.setBallY(Math.round(clamp(ballY, 0, 100) * 10.0) / 10.0);
            frame.setBallCarrierId(ballCarrierId);
            frame.setPositions(positions);
            frames.add(frame);
        }

        // Events
        List<AnimationEvent> events = new ArrayList<>();
        AnimationEvent shot = new AnimationEvent();
        shot.setFrame(kickFrame);
        shot.setType("SHOT");
        shot.setFromPlayerId(taker.getId());
        events.add(shot);

        AnimationEvent outcomeEvent = new AnimationEvent();
        outcomeEvent.setFrame(ballArriveFrame);
        outcomeEvent.setType(outcome);
        outcomeEvent.setFromPlayerId("SAVE".equals(outcome) ? defGkId : taker.getId());
        events.add(outcomeEvent);

        // Player infos
        List<AnimationPlayer> playerInfos = new ArrayList<>();
        for (Human p : atk11) playerInfos.add(toAnimPlayer(p, scoringTeamId));
        for (Human p : def11) playerInfos.add(toAnimPlayer(p, defendingTeamId));

        // Half-aware mirroring
        boolean isFirstHalf = minute <= (45 + firstHalfStoppage());
        boolean scorerIsHome = scoringTeamId == homeTeamId;
        boolean homeAttacksRight = isFirstHalf;
        boolean needsMirror = isFirstHalf != scorerIsHome;

        if (needsMirror) {
            for (AnimationFrame frame : frames) {
                frame.setBallX(Math.round((100 - frame.getBallX()) * 10.0) / 10.0);
                for (double[] pos : frame.getPositions()) {
                    pos[0] = Math.round((100 - pos[0]) * 10.0) / 10.0;
                }
            }
        }

        GoalAnimationData data = new GoalAnimationData();
        data.setMinute(minute);
        data.setScoringTeamId(scoringTeamId);
        data.setDefendingTeamId(defendingTeamId);
        data.setHomeTeamId(homeTeamId);
        data.setTotalFrames(TOTAL_FRAMES);
        data.setAnimationType("FREE_KICK");
        data.setOutcome(outcome);
        data.setHomeAttacksRight(homeAttacksRight);
        data.setScorerPlayerId(taker.getId());
        data.setScorerName(taker.getName());
        data.setScorerNumber(taker.getShirtNumber());
        data.setPlayers(playerInfos);
        data.setFrames(frames);
        data.setEvents(events);
        attachKits(data, scoringTeamId, defendingTeamId);
        return data;
    }

    // ==================== KEYFRAME GENERATION ====================

    private List<Map<Long, double[]>> generateKeyframes(
            List<Human> atk11, List<Human> def11,
            Map<Long, double[]> atkBase, Map<Long, double[]> defBase,
            Human scorer, Human assister, Random rng) {
        return generateKeyframes(atk11, def11, atkBase, defBase, scorer, assister,
                OpenPlayScenario.BUILD_UP, rng);
    }

    /**
     * Build the 6 keyframes for an attack. The scenario dictates where the
     * scorer (and, where relevant, the assister) ends up at each keyframe —
     * for a long-range shot the scorer stops at ~70 instead of pressing into
     * the six-yard box; for a dribble they start deeper and carry the ball
     * across keyframes; for a cross the assister hugs the touchline and the
     * scorer arrives at the back post.
     */
    private List<Map<Long, double[]>> generateKeyframes(
            List<Human> atk11, List<Human> def11,
            Map<Long, double[]> atkBase, Map<Long, double[]> defBase,
            Human scorer, Human assister,
            OpenPlayScenario scenario, Random rng) {

        List<Map<Long, double[]>> keyframes = new ArrayList<>();

        for (int kf = 0; kf < KF.length; kf++) {
            Map<Long, double[]> pos = new LinkedHashMap<>();

            // Attacking team positions
            for (Human p : atk11) {
                double[] base = atkBase.get(p.getId());
                int g = posGroup(p.getPosition());
                double x = base[0] + ATK_PUSH[kf][g];
                double y = base[1] + (rng.nextDouble() - 0.5) * 4;

                // Scenario-specific scorer path. Each branch is purposely
                // a few lines so the variations are auditable side-by-side.
                if (p.getId() == scorer.getId()) {
                    double[] scorerXY = scorerKeyframe(scenario, kf, base, rng);
                    x = scorerXY[0]; y = scorerXY[1];
                }

                // Assister path. For most scenarios they drift into a creative
                // zone past the half-line; CROSS_AND_FINISH pins them to the
                // touchline; ONE_TWO keeps them next to the scorer.
                if (assister != null && p.getId() == assister.getId()) {
                    double[] assisterXY = assisterKeyframe(scenario, kf, base, rng);
                    if (assisterXY != null) { x = assisterXY[0]; y = assisterXY[1]; }
                }

                pos.put(p.getId(), new double[]{clamp(x, 1, 99), clamp(y, 2, 98)});
            }

            // Defending team positions
            for (Human p : def11) {
                double[] base = defBase.get(p.getId());
                int g = posGroup(p.getPosition());
                double x = base[0] + DEF_DROP[kf][g];
                double y = base[1] + (rng.nextDouble() - 0.5) * 3;

                // GK stays near goal, dives at shot keyframe
                if ("GK".equals(p.getPosition())) {
                    x = Math.min(x, 97);
                    if (kf == 5) {
                        x = 96 + rng.nextDouble() * 3;
                    }
                }

                pos.put(p.getId(), new double[]{clamp(x, 1, 99), clamp(y, 2, 98)});
            }

            keyframes.add(pos);
        }
        return keyframes;
    }

    // ==================== PLAYER SELECTION ====================

    private List<Human> selectEleven(List<Human> all, Human must1, Human must2) {
        List<Human> sorted = all.stream()
                .filter(h -> !h.isRetired())
                .sorted(Comparator.comparingDouble(Human::getRating).reversed())
                .collect(Collectors.toList());

        List<Human> result = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        // GK first
        sorted.stream().filter(h -> "GK".equals(h.getPosition())).findFirst().ifPresent(gk -> {
            result.add(gk);
            used.add(gk.getId());
        });

        // Must-includes
        for (Human m : new Human[]{must1, must2}) {
            if (m != null && !used.contains(m.getId())) {
                result.add(m);
                used.add(m.getId());
            }
        }

        // Fill remaining by rating — skip backup goalkeepers since one is already
        // in the squad. Without this filter, a high-rated backup GK could be added
        // and assignPositions would put both at the same goal-area X, visually
        // looking like the team has two keepers (one of which "wanders forward"
        // because of patrol oscillation). Scorer/assister are always outfielders
        // (the simulation never picks GKs as attackers), so we can't get a surplus
        // GK via the must-include path either.
        for (Human p : sorted) {
            if (result.size() >= 11) break;
            if (used.contains(p.getId())) continue;
            if ("GK".equals(p.getPosition())) continue;
            result.add(p);
            used.add(p.getId());
        }
        return result;
    }

    // ==================== POSITION ASSIGNMENT ====================

    private Map<Long, double[]> assignPositions(List<Human> players, boolean mirror) {
        Map<Long, double[]> result = new LinkedHashMap<>();

        // Group by position for Y-spreading when duplicates
        Map<String, List<Human>> byPos = new LinkedHashMap<>();
        for (Human p : players) {
            String pos = p.getPosition() != null ? p.getPosition() : "MC";
            byPos.computeIfAbsent(pos, k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<String, List<Human>> entry : byPos.entrySet()) {
            String pos = entry.getKey();
            List<Human> group = entry.getValue();
            double[] base = basePos(pos);

            for (int i = 0; i < group.size(); i++) {
                double x = base[0];
                double y;

                if (group.size() == 1) {
                    y = base[1];
                } else {
                    // Spread evenly around the base Y, 26 units apart
                    double totalSpread = (group.size() - 1) * 26.0;
                    y = base[1] - totalSpread / 2 + i * 26.0;
                }

                if (mirror) x = 100 - x;
                result.put(group.get(i).getId(), new double[]{clamp(x, 1, 99), clamp(y, 5, 95)});
            }
        }
        return result;
    }

    // ==================== PASS CHAIN ====================

    /**
     * Scorer's [x, y] at a given keyframe under the chosen scenario. The default
     * (BUILD_UP) presses the scorer into the six-yard box late in the attack;
     * other scenarios re-shape that path so the visual differs noticeably.
     */
    private double[] scorerKeyframe(OpenPlayScenario scenario, int kf, double[] base, Random rng) {
        switch (scenario) {
            case LONG_RANGE_SHOT -> {
                // Scorer pulls up well outside the box — shot launched from
                // x≈68 (≈25-30m from goal in real-world terms). The longer
                // ball travel is what sells the "long range" feel.
                if (kf == 2) return new double[]{Math.max(base[0], 55), 38 + rng.nextDouble() * 24};
                if (kf == 3) return new double[]{Math.max(base[0], 62 + rng.nextDouble() * 4), 36 + rng.nextDouble() * 28};
                if (kf >= 4) return new double[]{66 + rng.nextDouble() * 5, 40 + rng.nextDouble() * 20};
            }
            case QUICK_COUNTER -> {
                // Already advanced from the start (caught defenders flat).
                if (kf == 1) return new double[]{Math.max(base[0], 60), 38 + rng.nextDouble() * 24};
                if (kf == 2) return new double[]{Math.max(base[0], 72), 36 + rng.nextDouble() * 28};
                if (kf == 3) return new double[]{82 + rng.nextDouble() * 4, 35 + rng.nextDouble() * 30};
                if (kf >= 4) return new double[]{87 + rng.nextDouble() * 5, 38 + rng.nextDouble() * 24};
            }
            case LONG_BALL -> {
                // Deep run: starts on halfway line, sprints in behind.
                if (kf == 1) return new double[]{Math.max(base[0], 50), 35 + rng.nextDouble() * 30};
                if (kf == 2) return new double[]{Math.max(base[0], 70), 35 + rng.nextDouble() * 30};
                if (kf == 3) return new double[]{Math.max(base[0], 82), 40 + rng.nextDouble() * 20};
                if (kf >= 4) return new double[]{88 + rng.nextDouble() * 6, 40 + rng.nextDouble() * 20};
            }
            case INDIVIDUAL_DRIBBLE -> {
                // Carrier dribbles past several defenders — Y zig-zags between
                // ~30 and ~70 across keyframes so the path visibly cuts left
                // and right as if beating opponents, instead of a straight
                // diagonal line. The X marches steadily toward the goal.
                if (kf == 1) return new double[]{Math.max(base[0], 48), 32 + rng.nextDouble() * 6};
                if (kf == 2) return new double[]{Math.max(base[0], 60), 60 + rng.nextDouble() * 8};
                if (kf == 3) return new double[]{Math.max(base[0], 74), 36 + rng.nextDouble() * 6};
                if (kf == 4) return new double[]{Math.max(base[0], 84), 58 + rng.nextDouble() * 6};
                if (kf >= 5) return new double[]{88 + rng.nextDouble() * 4, 46 + rng.nextDouble() * 8};
            }
            case CROSS_AND_FINISH -> {
                // Striker arrives at the centre of the box late, for the header/volley.
                if (kf == 3) return new double[]{Math.max(base[0], 84), 42 + rng.nextDouble() * 16};
                if (kf >= 4) return new double[]{88 + rng.nextDouble() * 5, 43 + rng.nextDouble() * 14};
            }
            case ONE_TWO -> {
                // Combo lands near the penalty spot.
                if (kf == 3) return new double[]{Math.max(base[0], 82), 40 + rng.nextDouble() * 20};
                if (kf >= 4) return new double[]{87 + rng.nextDouble() * 4, 42 + rng.nextDouble() * 16};
            }
            default /* BUILD_UP */ -> {
                if (kf == 3) return new double[]{Math.max(base[0], 80 + rng.nextDouble() * 4), 33 + rng.nextDouble() * 34};
                if (kf >= 4) return new double[]{86 + rng.nextDouble() * 6, 35 + rng.nextDouble() * 30};
            }
        }
        // Early keyframes (0-1-2 for build-up) fall through to the base + push value
        // which is computed by the caller.
        return new double[]{base[0] + (rng.nextDouble() - 0.5) * 2, base[1] + (rng.nextDouble() - 0.5) * 4};
    }

    /**
     * Assister's [x, y] at a given keyframe under the chosen scenario.
     * Returns {@code null} to mean "no scenario-specific override; use the
     * default base + push computed by the caller".
     */
    private double[] assisterKeyframe(OpenPlayScenario scenario, int kf, double[] base, Random rng) {
        if (kf < 2) return null;
        switch (scenario) {
            case CROSS_AND_FINISH -> {
                // Wide and deep — hugs the touchline for the cross. Y picks the
                // closer wing based on the assister's base position.
                boolean leftWing = base[1] < 50;
                double wingY = leftWing ? 8 + rng.nextDouble() * 8 : 84 + rng.nextDouble() * 8;
                if (kf == 2) return new double[]{Math.max(base[0], 70), wingY};
                if (kf >= 3) return new double[]{82 + rng.nextDouble() * 6, wingY};
            }
            case ONE_TWO -> {
                // Plays the give-and-go right alongside the scorer.
                if (kf >= 2) return new double[]{Math.max(base[0], 70 + kf * 3), 35 + rng.nextDouble() * 30};
            }
            case QUICK_COUNTER -> {
                // Drives forward in support of the counter.
                if (kf == 2) return new double[]{Math.max(base[0], 55), 30 + rng.nextDouble() * 40};
                if (kf >= 3) return new double[]{Math.max(base[0], 70 + kf * 2), 25 + rng.nextDouble() * 50};
            }
            case LONG_BALL, LONG_RANGE_SHOT -> {
                // Stays in support but doesn't push forward (the pass is going past them).
                return null;
            }
            case INDIVIDUAL_DRIBBLE -> {
                // Decoy run — stays wide so defenders can't double-team the carrier.
                if (kf >= 3) return new double[]{Math.max(base[0], 72), base[1] < 50 ? 18 : 82};
            }
            default /* BUILD_UP */ -> {
                if (kf >= 2) {
                    double x = Math.max(base[0], 68 + kf * 2.5);
                    double y = kf >= 3 ? 15 + rng.nextDouble() * 70 : base[1] + (rng.nextDouble() - 0.5) * 4;
                    return new double[]{x, y};
                }
            }
        }
        return null;
    }

    /**
     * Pick a scenario based on the scorer's position. Each position has a small
     * weighted distribution favouring the scenarios that look natural for that
     * role: strikers get crosses and counters more than midfielders; central
     * midfielders score from long range; defenders only ever get build-ups.
     */
    private OpenPlayScenario selectScenario(Human scorer, Human assister, Random rng) {
        String pos = scorer.getPosition() != null ? scorer.getPosition() : "MC";
        // Weights are tiny lookup tables — keep BUILD_UP common as the safe default.
        double[] weights;
        OpenPlayScenario[] menu;
        switch (pos) {
            case "ST", "AMC":
                // Strikers get the widest menu — every visually distinct scenario
                // is in here so the user actually sees them across a season.
                menu = new OpenPlayScenario[]{
                        OpenPlayScenario.BUILD_UP, OpenPlayScenario.CROSS_AND_FINISH,
                        OpenPlayScenario.ONE_TWO,  OpenPlayScenario.QUICK_COUNTER,
                        OpenPlayScenario.INDIVIDUAL_DRIBBLE, OpenPlayScenario.LONG_BALL };
                weights = new double[]{0.25, 0.22, 0.18, 0.15, 0.12, 0.08};
                break;
            case "AML", "AMR":
                menu = new OpenPlayScenario[]{
                        OpenPlayScenario.BUILD_UP, OpenPlayScenario.INDIVIDUAL_DRIBBLE,
                        OpenPlayScenario.CROSS_AND_FINISH, OpenPlayScenario.QUICK_COUNTER };
                weights = new double[]{0.30, 0.35, 0.20, 0.15};
                break;
            case "MC", "ML", "MR", "DM":
                menu = new OpenPlayScenario[]{
                        OpenPlayScenario.BUILD_UP, OpenPlayScenario.LONG_RANGE_SHOT,
                        OpenPlayScenario.INDIVIDUAL_DRIBBLE, OpenPlayScenario.ONE_TWO };
                weights = new double[]{0.35, 0.35, 0.15, 0.15};
                break;
            default: // DC, DL, DR or unknown
                menu = new OpenPlayScenario[]{ OpenPlayScenario.BUILD_UP };
                weights = new double[]{1.0};
                break;
        }
        // ONE_TWO requires an assister — fall back to BUILD_UP if there isn't one.
        double r = rng.nextDouble();
        double cum = 0;
        for (int i = 0; i < menu.length; i++) {
            cum += weights[i];
            if (r <= cum) {
                if (menu[i] == OpenPlayScenario.ONE_TWO && assister == null) return OpenPlayScenario.BUILD_UP;
                return menu[i];
            }
        }
        return OpenPlayScenario.BUILD_UP;
    }

    private List<Human> buildPassChain(List<Human> team, Human scorer, Human assister, Random rng) {
        return buildPassChain(team, scorer, assister, OpenPlayScenario.BUILD_UP, rng);
    }

    /**
     * Build the chain of players the ball flows through before the shot. Each
     * scenario shapes the chain differently — a counter has just 1-2 passes
     * starting deep, a build-up has 3-4 through midfield, a dribble has effectively
     * one carrier. The scorer is always last in the chain.
     */
    private List<Human> buildPassChain(List<Human> team, Human scorer, Human assister,
                                       OpenPlayScenario scenario, Random rng) {
        List<Human> chain = new ArrayList<>();
        List<Human> mids = midfielders(team, scorer, assister);
        List<Human> defs = team.stream()
                .filter(h -> {
                    String p = h.getPosition();
                    return "DC".equals(p) || "DL".equals(p) || "DR".equals(p);
                })
                .filter(h -> h.getId() != scorer.getId() &&
                        (assister == null || h.getId() != assister.getId()))
                .collect(Collectors.toList());
        List<Human> wideMids = team.stream()
                .filter(h -> {
                    String p = h.getPosition();
                    return "ML".equals(p) || "MR".equals(p) || "AML".equals(p) || "AMR".equals(p);
                })
                .filter(h -> h.getId() != scorer.getId() &&
                        (assister == null || h.getId() != assister.getId()))
                .collect(Collectors.toList());
        Human gk = team.stream()
                .filter(h -> "GK".equals(h.getPosition()))
                .findFirst().orElse(null);

        switch (scenario) {
            case QUICK_COUNTER -> {
                // Recovery in own half: 1 midfielder pings the scorer running in.
                if (!mids.isEmpty()) chain.add(mids.get(rng.nextInt(mids.size())));
                if (assister != null) chain.add(assister);
            }
            case LONG_BALL -> {
                // GK or centre-back launches it: single long pass to scorer.
                Human starter = !defs.isEmpty()
                        ? defs.get(rng.nextInt(defs.size()))
                        : gk;
                if (starter != null) chain.add(starter);
            }
            case INDIVIDUAL_DRIBBLE -> {
                // Scorer carries from deep — at most one short release to them.
                if (!mids.isEmpty() && rng.nextDouble() < 0.5) {
                    chain.add(mids.get(rng.nextInt(mids.size())));
                }
            }
            case CROSS_AND_FINISH -> {
                // 1 mid → wide player → scorer (the cross is the last pass).
                if (!mids.isEmpty()) chain.add(mids.get(rng.nextInt(mids.size())));
                if (!wideMids.isEmpty()) {
                    chain.add(wideMids.get(rng.nextInt(wideMids.size())));
                } else if (assister != null) {
                    chain.add(assister);
                }
            }
            case ONE_TWO -> {
                // scorer → assister → scorer. Encoded as [assister, scorer] with
                // the assister opening as the first carrier (the give-and-go's
                // first leg is implicit; the chain shows the "go back to assister
                // → return to scorer" portion which is the visually interesting bit).
                if (!mids.isEmpty()) chain.add(mids.get(rng.nextInt(mids.size())));
                if (assister != null) chain.add(assister);
            }
            case LONG_RANGE_SHOT -> {
                // Build-up through midfield, scorer shoots from distance later.
                if (!mids.isEmpty()) chain.add(mids.get(rng.nextInt(mids.size())));
                if (assister != null) chain.add(assister);
            }
            default /* BUILD_UP */ -> {
                // Multi-stage build-up: ball played out from a centre-back,
                // through two distinct midfielders, then to the assister and
                // finally the scorer. Aims for 4-5 visible passes so the user
                // sees a proper team move, not just a one-touch combo.
                if (!defs.isEmpty()) chain.add(defs.get(rng.nextInt(defs.size())));
                if (!mids.isEmpty()) {
                    chain.add(mids.get(rng.nextInt(mids.size())));
                    // Add a second distinct midfielder ~80% of the time so the
                    // chain reliably feels like a multi-step build-up.
                    if (mids.size() > 1 && rng.nextDouble() < 0.8) {
                        Human second;
                        int attempts = 0;
                        do { second = mids.get(rng.nextInt(mids.size())); attempts++; }
                        while (second.getId() == chain.get(chain.size() - 1).getId() && attempts < 5);
                        if (second.getId() != chain.get(chain.size() - 1).getId()) chain.add(second);
                    }
                }
                if (assister != null) chain.add(assister);
            }
        }

        // Scorer always last (the shot finalises every scenario).
        chain.add(scorer);

        // Safety net: chain must be at least 2 so we have one pass to draw.
        if (chain.size() < 2) {
            Human fallback = team.stream()
                    .filter(h -> h.getId() != scorer.getId() && !h.isRetired())
                    .findFirst().orElse(null);
            if (fallback != null) chain.add(0, fallback);
        }
        return chain;
    }

    private List<Human> midfielders(List<Human> team, Human scorer, Human assister) {
        return team.stream()
                .filter(h -> {
                    String p = h.getPosition();
                    return "MC".equals(p) || "ML".equals(p) || "MR".equals(p) ||
                            "DM".equals(p) || "AMC".equals(p) || "AML".equals(p) || "AMR".equals(p);
                })
                .filter(h -> h.getId() != scorer.getId() &&
                        (assister == null || h.getId() != assister.getId()))
                .collect(Collectors.toList());
    }

    // ==================== EVENTS ====================

    private int[] computePassFrames(int numPasses) {
        if (numPasses <= 0) return new int[0];
        if (numPasses == 1) return new int[]{55};
        int[] frames = new int[numPasses];
        int start = 22;
        int spacing = Math.max(20, (105 - start) / (numPasses - 1));
        for (int i = 0; i < numPasses; i++) {
            frames[i] = start + i * spacing;
        }
        return frames;
    }

    private List<AnimationEvent> buildEvents(List<Human> chain, Human scorer, int[] passFrames, String outcome) {
        List<AnimationEvent> events = new ArrayList<>();

        for (int i = 0; i < passFrames.length && i < chain.size() - 1; i++) {
            AnimationEvent e = new AnimationEvent();
            e.setFrame(passFrames[i]);
            e.setType("PASS");
            e.setFromPlayerId(chain.get(i).getId());
            e.setToPlayerId(chain.get(i + 1).getId());
            events.add(e);
        }

        AnimationEvent shot = new AnimationEvent();
        shot.setFrame(135);
        shot.setType("SHOT");
        shot.setFromPlayerId(scorer.getId());
        events.add(shot);

        AnimationEvent finalEvent = new AnimationEvent();
        finalEvent.setFrame(148);
        finalEvent.setType(outcome != null ? outcome : "GOAL");
        finalEvent.setFromPlayerId(scorer.getId());
        events.add(finalEvent);

        return events;
    }

    // ==================== HELPERS ====================

    private AnimationPlayer toAnimPlayer(Human p, long teamId) {
        AnimationPlayer ap = new AnimationPlayer();
        ap.setPlayerId(p.getId());
        ap.setName(p.getName());
        ap.setShirtNumber(p.getShirtNumber());
        ap.setTeamId(teamId);
        ap.setPosition(p.getPosition());
        return ap;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double easeInOut(double t) {
        return t * t * (3 - 2 * t); // Hermite smoothstep
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
