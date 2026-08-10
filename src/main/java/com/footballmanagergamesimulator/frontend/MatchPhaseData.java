package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One in-game minute of continuous spatial play: a possession chain of discrete
 * on-pitch actions (passes — forward, back, lateral —, dribbles, tackles, shots,
 * corners, throw-ins, fouls, offside flags) with ball coordinates in the same
 * 0-100 pitch space as {@link GoalAnimationData} (x: left goal line → right goal
 * line, y: bottom sideline → top sideline).
 *
 * <p>Purely presentational: chains are generated AFTER the authoritative minute
 * outcome is decided by the live engine, from a dedicated deterministic RNG, so
 * they never influence scores, stats, or the session's checkpointed RNG stream.
 * The chain's terminal actions always match the minute's timeline events.
 */
@Data
public class MatchPhaseData {

    private int minute;
    /** Team in possession for the bulk of the chain (kickoff side of the chain). */
    private long teamId;
    /** True when the team in possession attacks toward x=100 this half. */
    private boolean attackingRight;
    /** Timeline eventType the chain resolves into ("goal", "corner", "offside",
     *  "foul", …) or "possession" when the minute had no terminal event. */
    private String endEvent;
    /** Strategy family the buildup was generated from ("HIGH_PRESS",
     *  "POSSESSION", "COUNTER", "WING_PLAY", "DIRECT", "INDIVIDUAL") — the
     *  engine's declaration of WHY this attack happened, for display. */
    private String strategy;
    /** Concrete scenario within the strategy (e.g. "PRESS_TRAP"). */
    private String scenario;
    private List<PhaseAction> actions = new ArrayList<>();

    @Data
    public static class PhaseAction {
        /** KICKOFF, PASS, BACK_PASS, LATERAL_PASS, THROUGH_BALL, DRIBBLE, CROSS,
         *  SHOT, GOAL, SAVE, MISS, BLOCK, CLEARANCE, CORNER_KICK, HEADER,
         *  TACKLE, INTERCEPTION, THROW_IN, FOUL, FREE_KICK, PENALTY_KICK,
         *  YELLOW_CARD, RED_CARD, OFFSIDE_FLAG, TURNOVER */
        private String type;
        private long playerId;
        private String playerName;
        /** Receiving player for passes; 0 when not applicable (shots, cards…). */
        private long targetPlayerId;
        private String targetPlayerName;
        /** Team performing the action (defensive actions belong to the other side). */
        private long teamId;
        /** Ball position when the action starts. */
        private double x, y;
        /** Ball position when the action ends (pass target, shot destination…). */
        private double endX, endY;
        /** Suggested playback duration for the frontend clock. */
        private int durationMs;

        /** Peak height of the ball's flight arc in world units (≈ metres), 0 =
         *  along the ground. Set for shots, crosses, goal kicks, clearances —
         *  a miss OVER the bar carries a peak above the 2.35 crossbar. */
        private double peakHeight;

        // Faza B: match officials, computed per action. The referee trails play
        // on the classic diagonal and walks to the spot for fouls/cards/penalties;
        // the assistants patrol their sideline halves tracking the offside line
        // (bottom = y≈0 sideline covering x<50, top = y≈100 covering x>50).
        private double refX, refY;
        private double assistantBottomX;
        private double assistantTopX;
    }
}
