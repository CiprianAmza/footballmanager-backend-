package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationEvent;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationFrame;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationPlayer;
import com.footballmanagergamesimulator.model.Human;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * Penalty kick animation generator. Taker approaches from behind the spot,
 * kicks at frame 70; other players stand outside the box, then rush in after
 * the kick. Outcomes: GOAL or SAVE (ball rebounds out of play).
 */
@Service
public class GoalAnimationPenaltyGenerator {

    @Autowired private GoalAnimationContext context;

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

        Long seedOverride = context.seedOverride();
        Random rng = new Random(seedOverride != null ? seedOverride : (taker.getId() * 41L + minute * 13L));

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
        data.setAnimationType("PENALTY");
        data.setOutcome(isGoal ? "GOAL" : "SAVE");
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
