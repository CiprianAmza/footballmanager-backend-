package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerPersonality;

/**
 * On the last day of the window a player wanted both by an AI club and by me picks one (design §3.6,
 * decided 2026-11-12). His personality decides what counts; on a tie he stays with the AI club's plan.
 */
public final class PlayerChoice {

    /** What one club offers him, as he sees it. */
    public record Club(long wage, int reputation, int betterPlayersAtHisPosition) {
    }

    private PlayerChoice() {
    }

    /** True when he chooses me over the AI club. */
    public static boolean choosesMe(PlayerPersonality personality, Club ai, Club me) {
        return switch (personality) {
            case MONEY -> me.wage() > ai.wage();
            case FAME -> me.reputation() > ai.reputation();
            case AMBITION -> me.betterPlayersAtHisPosition() < ai.betterPlayersAtHisPosition();
        };
    }
}
