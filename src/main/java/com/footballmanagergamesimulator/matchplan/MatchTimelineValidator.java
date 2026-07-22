package com.footballmanagergamesimulator.matchplan;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Strict validation of a team's on-pitch timeline before it is persisted or
 * executed. Guards the invariants both the instant simulator and (later) the live
 * session must uphold, so a malformed lineup never reaches the canonical record.
 */
public final class MatchTimelineValidator {

    /** A full starting eleven. */
    public static final int STARTERS = 11;

    private MatchTimelineValidator() {}

    /**
     * Validate {@code lineup} for a match of {@code duration} minutes. Throws
     * {@link IllegalStateException} on the first violation:
     * <ul>
     *   <li>exactly {@value #STARTERS} starters, with no duplicates;</li>
     *   <li>the bench has no duplicates and no overlap with the starting eleven;</li>
     *   <li>every substitution minute is within {@code [1, duration]} and in order;</li>
     *   <li>the player coming on belongs to the bench;</li>
     *   <li>the player coming off is on the pitch at that minute;</li>
     *   <li>the player coming on is not already on the pitch;</li>
     *   <li>no player re-enters after being substituted off.</li>
     * </ul>
     */
    public static void validate(Lineup lineup, int duration) {
        Set<Long> starters = new LinkedHashSet<>();
        for (Contributor c : lineup.getStartingXI()) {
            if (!starters.add(c.playerId())) {
                throw new IllegalStateException("Duplicate player in starting XI: " + c.playerId());
            }
        }
        if (starters.size() != STARTERS) {
            throw new IllegalStateException("Starting XI must have " + STARTERS + " players, got " + starters.size());
        }

        Set<Long> bench = new HashSet<>();
        for (Contributor c : lineup.getBench()) {
            if (!bench.add(c.playerId())) {
                throw new IllegalStateException("Duplicate player on the bench: " + c.playerId());
            }
            if (starters.contains(c.playerId())) {
                throw new IllegalStateException("Player is both a starter and on the bench: " + c.playerId());
            }
        }

        Set<Long> onPitch = new HashSet<>(starters);
        Set<Long> leftMatch = new HashSet<>();
        int lastMinute = 0;
        int expectedSequence = 0;
        for (Lineup.SubMove sub : lineup.getSubs()) { // getSubs() is ordered by sequence
            if (sub.sequence() != expectedSequence) {
                throw new IllegalStateException("Substitution sequence must be consecutive from 0; got "
                        + sub.sequence() + " expected " + expectedSequence);
            }
            expectedSequence++;
            if (sub.minute() < 1 || sub.minute() > duration) {
                throw new IllegalStateException("Substitution minute out of range: " + sub.minute());
            }
            if (sub.minute() < lastMinute) {
                throw new IllegalStateException("Substitution minutes must be non-decreasing at " + sub.minute());
            }
            lastMinute = sub.minute();

            long off = sub.offPlayerId();
            long on = sub.on().playerId();
            if (!bench.contains(on)) {
                throw new IllegalStateException("Player subbed on is not on the bench: " + on);
            }
            if (!onPitch.contains(off)) {
                throw new IllegalStateException("Player subbed off is not on the pitch: " + off);
            }
            if (onPitch.contains(on)) {
                throw new IllegalStateException("Player subbed on is already on the pitch: " + on);
            }
            if (leftMatch.contains(on)) {
                throw new IllegalStateException("Player re-enters after being subbed off: " + on);
            }

            onPitch.remove(off);
            leftMatch.add(off);
            onPitch.add(on);
        }
    }
}
