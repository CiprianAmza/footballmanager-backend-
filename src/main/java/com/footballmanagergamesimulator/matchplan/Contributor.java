package com.footballmanagergamesimulator.matchplan;

/**
 * A player eligible to score or assist a goal, decoupled from the live engine's
 * {@code PlayerMatchState} and the batch path's {@code Human}+{@code PlayerSkills}
 * so the single {@link ContributionResolver} can serve both. Callers adapt their
 * own player representation into this before resolving a {@link GoalSlot}.
 *
 * @param fitness current match fitness 0..100 (100 when unmodelled, e.g. instant path)
 */
public record Contributor(
        long playerId,
        String name,
        String position,
        double rating,
        int finishing,
        int passing,
        int vision,
        double fitness,
        boolean designatedPenaltyTaker,
        boolean designatedFreeKickTaker) {

    public boolean isGoalkeeper() {
        return "GK".equals(position);
    }

    /** Same player, fielded in a different tactical position (e.g. a substitute
     *  who enters into the slot vacated by the player he replaces). */
    public Contributor withPosition(String newPosition) {
        return new Contributor(playerId, name, newPosition, rating, finishing, passing, vision,
                fitness, designatedPenaltyTaker, designatedFreeKickTaker);
    }
}
