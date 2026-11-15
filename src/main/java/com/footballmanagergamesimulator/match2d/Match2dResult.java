package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The result of one match played by the 2D match engine ({@code POST /results}), as the
 * engine sends it. The parts whose shape the engine may extend ({@code summary},
 * {@code tactics}, {@code tacticIgnored}, the per-player {@code stats}) stay as JSON, so a
 * new key there never breaks reading; unknown fields are ignored.
 *
 * <p>A match that failed inside a group carries only {@code ref} and {@code error}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Match2dResult(
        /** Echoed unchanged from the request, so the caller can match results to its own matches. */
        String ref,
        String id,
        /** Team names. */
        String home,
        String away,
        /** {@code [home goals, away goals]}. */
        List<Integer> score,
        Long seed,
        Integer minutes,
        String engine,
        String engineVersion,
        Long simulatedInMs,
        List<Goal> goals,
        /** Per-team statistics, keyed as the engine sends them. */
        JsonNode summary,
        JsonNode tactics,
        JsonNode tacticIgnored,
        List<PlayerLine> players,
        List<Highlight> highlights,
        /**
         * Extra time and shootout of a knockout match; sent only when the request asked for
         * {@code knockout}. {@code score} then includes the extra-time goals, never the shootout.
         */
        Knockout knockout,
        /** Set only when this match could not be played; everything else is then empty. */
        String error) {

    /** A match that could not be played: only the caller's reference and the reason. */
    public static Match2dResult failed(String ref, String error) {
        return new Match2dResult(ref, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, error);
    }

    @JsonIgnore
    public boolean isFailed() {
        return error != null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Goal(
            Integer minute,
            /** 0 = home, 1 = away. */
            Integer team,
            String text,
            Long playerId,
            String player,
            Long assistId,
            String assist,
            boolean own,
            /** {@code penalty}, {@code free_kick}, {@code header} or {@code null} for open play. */
            String how) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerLine(
            /** 0 = home, 1 = away. */
            Integer team,
            /** The engine's own player number in this match. */
            Long uid,
            /** The player's id as sent in the line-up (the database id). */
            Long id,
            String name,
            Integer number,
            String position,
            /** Match rating, one decimal. */
            Double rating,
            boolean motm,
            /** Counters (goals, assists, shots, ...), keyed as the engine sends them. */
            JsonNode stats) {
    }

    /**
     * How a knockout match ended, in the orientation of this match ({@code [home, away]};
     * team 0 = home, 1 = away). {@code aggregate} echoes the goals each side carried in from
     * the first leg ({@code [0, 0]} for a single match); extra time is played when
     * {@code score90 + aggregate} is level, the shootout when it is still level after it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Knockout(
            List<Integer> aggregate,
            List<Integer> score90,
            Boolean extraTime,
            List<Integer> extraTimeGoals,
            /** Shootout tally, {@code null} when there was none. */
            List<Integer> penalties,
            List<Kick> shootout,
            /** 0 = home, 1 = away. */
            Integer winner,
            /** {@code regular}, {@code extra_time} or {@code penalties}. */
            String decidedBy) {
    }

    /** One shootout kick. Not a goal: it is in no goal list and in no player's statistics. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Kick(Integer team, Long uid, Long playerId, String player, Boolean scored) {
    }

    /** A stretch of play worth replaying, in seconds of match time. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Highlight(Double from, Double to, String label, String kind) {
    }
}
