package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class LiveMatchData {

    // Goal animations keyed by minute (only present when watchGoalHighlights is enabled)
    private Map<Integer, GoalAnimationData> goalAnimations;

    private long homeTeamId;
    private long awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String competitionName;
    private long competitionId;
    private int round;

    private List<LiveMatchMinute> timeline;

    // Final stats
    private int homeScore;
    private int awayScore;
    private int homePossession;
    private int awayPossession;
    private int homeShots;
    private int awayShots;
    private int homeShotsOnTarget;
    private int awayShotsOnTarget;
    private int homeCorners;
    private int awayCorners;
    private int homeFouls;
    private int awayFouls;
    private int homeYellowCards;
    private int awayYellowCards;
    private int homeRedCards;
    private int awayRedCards;
    private int homeOffsides;
    private int awayOffsides;

    // Added time per half, generated randomly per match (0-5 minutes each).
    // The frontend uses these to display minutes in "45+X" / "90+X" form
    // during the stoppage portions of each half.
    private int firstHalfStoppage;
    private int secondHalfStoppage;

    // Per-minute stamina snapshots captured every 5 in-game minutes. Frontend
    // uses these to render fitness bars under each player during playback.
    private List<StaminaSnapshot> staminaSnapshots;

    // ===== Interactive (Faza 3) — live state for /state, /advance, /substitute =====

    /** Current engine minute (0 at kickoff, totalMinutes when finished). */
    private int currentMinute;
    /** True once full-time has fired. */
    private boolean finished;
    /** Remaining substitutions per side (0..3). */
    private int homeSubsRemaining;
    private int awaySubsRemaining;
    /** Players currently on the pitch, with up-to-the-minute stamina. */
    private List<PlayerStaminaInfo> homePitch;
    private List<PlayerStaminaInfo> awayPitch;
    /** Players available on the bench (never came on, or were never starters). */
    private List<PlayerStaminaInfo> homeBench;
    private List<PlayerStaminaInfo> awayBench;

    @Data
    public static class LiveMatchMinute {
        private int minute;
        private int homeScore;
        private int awayScore;
        private String eventType;
        private String commentary;
        private String playerName;
        private long playerId;
        private long teamId;
        private String teamName;
    }

    @Data
    public static class StaminaSnapshot {
        private int minute;
        private List<PlayerStaminaInfo> homePlayers;
        private List<PlayerStaminaInfo> awayPlayers;
    }

    @Data
    public static class PlayerStaminaInfo {
        private long playerId;
        private String name;
        private String position;
        private int stamina;        // 0-100, current condition
        private int minutesPlayed;
        private boolean onPitch;
        /** Minute when this player picked up a yellow card; 0 = never. */
        private int yellowCardMinute;
        /** Minute when this player was sent off with a red; 0 = never. */
        private int redCardMinute;
    }
}
