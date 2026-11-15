package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One team exactly as the 2D match engine (the external Java microservice) receives it:
 * the starting eleven with every attribute, plus the whole tactic. The engine reads
 * nothing from the database, so everything that defines the team in this match is here.
 */
public record Match2dTeam(
        long id,
        String name,
        /** Formation code as stored on the tactic, e.g. {@code 442} or {@code 31411}. */
        String formation,
        /** Kit colours as stored on the club (CSS colour names); the engine maps them. */
        String color,
        String gkColor,
        /** Every tactical choice of the club, including the ones the engine does not model yet. */
        Map<String, Object> tactic,
        List<Player> players) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Player(
            long id,
            String name,
            int number,
            /** Position of the slot he plays in (from the formation grid), not his natural one. */
            String position,
            String naturalPosition,
            double rating,
            boolean stayForward,
            String specialRole,
            String role,
            String duty,
            List<String> instructions,
            boolean shadow,
            /** All attributes, keyed by the {@code PlayerSkills} field name. */
            Map<String, Integer> ratings) {
    }
}
