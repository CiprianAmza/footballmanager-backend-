package com.footballmanagergamesimulator.match2d;

/**
 * Pure translation of the game's knockout ties into the {@code knockout} block of a 2D
 * request: the goals each side brings into the match. What is played (extra time, shootout)
 * is set by the request's {@link Match2dTarget}, i.e. by the classic engine; this block only
 * tells the 2D engine the aggregate it is drawing. No I/O.
 *
 * <p>Orientation: a request is always in the orientation of the match played,
 * {@code [home, away]} = {@code [team1, team2]} of that match row. A first leg is stored as
 * {@code [team1, team2]} of the first leg; the second leg is hosted by the first leg's away
 * side, so its {@code aggregate} is {@code [firstLeg[1], firstLeg[0]]}.
 */
public final class Match2dKnockout {

    private Match2dKnockout() {
    }

    /**
     * The {@code knockout} field for a match of a knockout round: none for a first leg (it
     * decides nothing on its own); the first-leg goals in this match's orientation for a second
     * leg; {@code [0, 0]} for a single match, and for a second leg whose first leg is unknown
     * (the game then decides it as a single match too).
     *
     * @param firstLeg the first leg's {@code [team1, team2]} score, or {@code null}
     */
    public static Match2dEngineClient.Knockout request(int legNumber, long tieId, int[] firstLeg) {
        if (legNumber == 1 && tieId != 0) return null;
        if (legNumber == 2 && tieId != 0 && firstLeg != null) {
            return Match2dEngineClient.Knockout.of(firstLeg[1], firstLeg[0]);
        }
        return Match2dEngineClient.Knockout.of(0, 0);
    }
}
