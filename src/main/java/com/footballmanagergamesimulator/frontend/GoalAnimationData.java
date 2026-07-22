package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class GoalAnimationData {

    /** Bumped whenever the animation generators change in a way that invalidates a
     *  previously persisted/recovered animation, so a canonical recipe from an older
     *  generator can be detected and regenerated. */
    public static final int GENERATOR_VERSION = 1;

    private int minute;

    // Canonical identity (feature flag on): which persisted goal slot this animation
    // belongs to, for durable, collision-safe, ordered playback. -1 / null for legacy
    // (cosmetic or non-canonical) animations. The frontend queues by (minute, slotIndex).
    private int slotIndex = -1;
    private String fixtureKey;
    private int generatorVersion = GENERATOR_VERSION;

    private long scoringTeamId;
    private long defendingTeamId;
    private long homeTeamId;
    private int totalFrames;

    // First-half stoppage time for the parent match. The mirror logic needs
    // it so a stoppage-time goal scored at e.g. minute 47 is still treated as
    // first half. The frontend uses it to format the minute as "45+2'" instead
    // of "47'" on the animation header.
    private int firstHalfStoppage;

    // Team kits used by the frontend to colour outfield players + goalkeepers.
    // Backend resolves conflicts (e.g. both teams in blue → defending side swaps to its secondary)
    // and picks GK kits that contrast against BOTH outfield kits.
    private TeamKit scoringTeamKit;
    private TeamKit defendingTeamKit;

    // "OPEN_PLAY", "PENALTY", "FREE_KICK"
    private String animationType;
    // "GOAL", "SAVE", "MISS"
    private String outcome;

    // true = home team attacks toward x=100 (right goal), false = home attacks left
    // Switches at half time. Frontend uses this to draw goal posts on correct sides.
    private boolean homeAttacksRight;

    // Scorer/assister info for the overlay
    private long scorerPlayerId;
    private String scorerName;
    private int scorerNumber;
    private Long assisterPlayerId;
    private String assisterName;

    // Player metadata (ordered: attacking team first, then defending team)
    // The positions array in each frame follows this same order.
    private List<AnimationPlayer> players;

    // 151 frames (0-150), ~5 seconds at 30fps
    private List<AnimationFrame> frames;

    // Key events during the animation (passes, shot, goal)
    private List<AnimationEvent> events;

    @Data
    public static class AnimationPlayer {
        private long playerId;
        private String name;
        private int shirtNumber;
        private long teamId;
        private String position; // GK, DC, DL, DR, MC, ML, MR, AMC, AML, AMR, ST, DM
    }

    @Data
    public static class AnimationFrame {
        private double ballX;     // 0-100 (left goal line to right goal line)
        private double ballY;     // 0-100 (bottom sideline to top sideline)
        private long ballCarrierId; // playerId who has the ball, 0 if ball is in flight
        private List<double[]> positions; // [x, y] per player, same order as players list
    }

    @Data
    public static class AnimationEvent {
        private int frame;
        private String type;      // "PASS", "SHOT", "GOAL", "SAVE", "MISS"
        private long fromPlayerId;
        private long toPlayerId;  // 0 for SHOT/GOAL
    }

    @Data
    public static class TeamKit {
        // Outfield kit (shirt fill + border for the player circle).
        // CSS-compatible color names or hex strings ("blue", "#3498db", etc.).
        private String outfieldPrimary;
        private String outfieldSecondary;
        private String outfieldBorder;
        // Goalkeeper kit — chosen to contrast against BOTH outfield kits.
        private String gkPrimary;
        private String gkBorder;
    }
}
