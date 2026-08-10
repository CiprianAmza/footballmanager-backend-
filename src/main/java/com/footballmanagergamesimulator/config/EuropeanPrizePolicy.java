package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.model.Competition;
import org.springframework.stereotype.Component;

/** Single source of truth for European participation, result and progression awards. */
@Component
public class EuropeanPrizePolicy {

    public long groupParticipation(long competitionTypeId) {
        return competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS ? 20_000_000L : 5_000_000L;
    }

    public long groupWin(long competitionTypeId) {
        return competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS ? 5_000_000L : 1_500_000L;
    }

    /** Amount credited to each club when a group match is drawn. */
    public long groupDrawPerTeam(long competitionTypeId) {
        return competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS ? 1_500_000L : 500_000L;
    }

    /** Per-club, per-fixture amount used by the current match settlement engine. */
    public long knockoutFixtureBonus(long competitionTypeId, int roundsFromFinal) {
        if (competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS) {
            if (roundsFromFinal == 2) return 40_000_000L;
            return roundsFromFinal > 1 ? 15_000_000L : 0L;
        }
        if (competitionTypeId == Competition.STARS_CUP) {
            if (roundsFromFinal == 2) return 10_000_000L;
            return roundsFromFinal > 1 ? 5_000_000L : 0L;
        }
        return 0L;
    }

    public long winnerPrize(long competitionTypeId) {
        return competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS ? 100_000_000L : 15_000_000L;
    }

    public long runnerUpPrize(long competitionTypeId) {
        return competitionTypeId == Competition.LEAGUE_OF_CHAMPIONS ? 50_000_000L : 8_000_000L;
    }
}
