package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CupStageLabelsTest {
    @Test
    void europeanGroupsAndKnockoutRoundsUseStageNames() {
        CompetitionFormatConfig formats = new CompetitionFormatConfig();
        Competition champions = competition(Competition.LEAGUE_OF_CHAMPIONS);
        assertEquals("Group Stage", CupStageLabels.forRound(champions, formats.get(4), 7, 10));
        assertEquals("Quarter-final", CupStageLabels.forRound(champions, formats.get(4), 8, 10));
        assertEquals("Semi-final", CupStageLabels.forRound(champions, formats.get(4), 9, 10));

        Competition stars = competition(Competition.STARS_CUP);
        assertEquals("Knockout Playoff", CupStageLabels.forRound(stars, formats.get(5), 7, 10));
    }

    @Test
    void domesticCupRoundNamesFollowItsActualFinalRound() {
        CompetitionFormatConfig formats = new CompetitionFormatConfig();
        Competition cup = competition(Competition.CUP);
        assertEquals("Last 32", CupStageLabels.forRound(cup, formats.get(2), 2, 6));
        assertEquals("Last 16", CupStageLabels.forRound(cup, formats.get(2), 3, 6));
        assertEquals("Quarter-final", CupStageLabels.forRound(cup, formats.get(2), 4, 6));
        assertEquals("Semi-final", CupStageLabels.forRound(cup, formats.get(2), 5, 6));
    }

    private static Competition competition(long type) {
        Competition competition = new Competition();
        competition.setTypeId(type);
        return competition;
    }
}
