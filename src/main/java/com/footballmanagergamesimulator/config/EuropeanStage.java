package com.footballmanagergamesimulator.config;

/**
 * One round of a European competition's derived format plan (see
 * {@link EuropeanFormatPlan}). Carries everything the dispatcher, calendar, and
 * coefficient/prize logic need so none of them hardcode round numbers.
 *
 * @param round       0-based round number within the competition
 * @param matchday    1-based matchday (round + 1)
 * @param phase       PRELIMINARY / GROUP / KNOCKOUT
 * @param bracketSize teams contesting this round (prelim field size; group
 *                    stage = total group slots; knockout = teams in that round)
 * @param groupDraw   true on the first group round (groups are drawn here)
 * @param qualify     true on the last group round (group qualifiers are decided)
 * @param seededDraw  true when the round's pairing/draw is coefficient-seeded
 * @param twoLeg      true when the round is played over two legs
 * @param roundsFromFinal for KNOCKOUT rounds: 1 = Final, 2 = SF, 3 = QF, …;
 *                    0 for non-knockout rounds
 */
public record EuropeanStage(
        int round,
        int matchday,
        EuropeanPhase phase,
        int bracketSize,
        boolean groupDraw,
        boolean qualify,
        boolean seededDraw,
        boolean twoLeg,
        int roundsFromFinal) {
}
