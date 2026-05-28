package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationEvent;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationFrame;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationPlayer;
import com.footballmanagergamesimulator.model.Human;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static com.footballmanagergamesimulator.service.GoalAnimationContext.TOTAL_FRAMES;
import static com.footballmanagergamesimulator.service.GoalAnimationContext.clamp;
import static com.footballmanagergamesimulator.service.GoalAnimationContext.easeInOut;
import static com.footballmanagergamesimulator.service.GoalAnimationContext.lerp;
import static com.footballmanagergamesimulator.service.GoalAnimationContext.selectEleven;
import static com.footballmanagergamesimulator.service.GoalAnimationContext.toAnimPlayer;

/**
 * Free kick animation generator. Ball placed 20-35m from goal, taker approaches,
 * wall of 4-5 defenders. Ball curves via quadratic bezier toward goal. Outcomes:
 * GOAL, SAVE, or MISS (ball goes out).
 */
@Service
public class GoalAnimationFreeKickGenerator {

    @Autowired private GoalAnimationContext context;

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
            int g = GoalAnimationOpenPlayGenerator.posGroup(p.getPosition());
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
        boolean isFirstHalf = minute <= (45 + context.firstHalfStoppage());
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
        context.attachKits(data, scoringTeamId, defendingTeamId);
        return data;
    }
}
