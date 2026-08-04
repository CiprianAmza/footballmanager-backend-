package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PossessionProgression;
import com.footballmanagergamesimulator.repository.PossessionProgressionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PossessionProgressionLedgerServiceTest {
    private final PossessionProgressionLedgerService service =
            new PossessionProgressionLedgerService(mock(PossessionProgressionRepository.class));

    @Test
    void generatesDeterministicAndInternallyConsistentRowsForBothTeams() {
        MatchStats match = match();

        List<PossessionProgression> first = service.generate(match);
        List<PossessionProgression> second = service.generate(match);
        PossessionProgression home = first.stream().filter(row -> row.getTeamId() == 10).findFirst().orElseThrow();
        PossessionProgression away = first.stream().filter(row -> row.getTeamId() == 20).findFirst().orElseThrow();

        assertThat(first).hasSize(2);
        assertThat(second).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id")
                .containsExactlyElementsOf(first);
        assertThat(home.getProgressivePasses()).isLessThanOrEqualTo(home.getCompletedPasses());
        assertThat(home.getLineBreakingPasses()).isLessThanOrEqualTo(home.getProgressivePasses());
        assertThat(home.getPenaltyAreaEntries()).isLessThanOrEqualTo(home.getFinalThirdEntries());
        assertThat(home.getPassesIntoBox()).isLessThanOrEqualTo(home.getPenaltyAreaEntries());
        assertThat(home.getPassesPerPossession()).isPositive();
        assertThat(home.getAveragePossessionDurationSeconds()).isPositive();
        assertThat(home.getDataQuality()).isEqualTo("MODELED_FROM_MATCH_STATS");
        assertThat(home.getFieldTiltPercentage() + away.getFieldTiltPercentage()).isCloseTo(100.0,
                org.assertj.core.data.Offset.offset(.02));
    }

    static MatchStats match() {
        MatchStats match = new MatchStats();
        match.setId(99); match.setCompetitionId(1); match.setSeasonNumber(2); match.setRoundNumber(4);
        match.setTeam1Id(10); match.setTeam2Id(20);
        match.setHomePossession(58); match.setAwayPossession(42);
        match.setHomePasses(520); match.setAwayPasses(390);
        match.setHomePassAccuracy(87); match.setAwayPassAccuracy(79);
        match.setHomeShots(15); match.setAwayShots(7);
        match.setHomeCorners(7); match.setAwayCorners(3);
        match.setHomeCrosses(21); match.setAwayCrosses(14);
        match.setHomeCrossesAccurate(7); match.setAwayCrossesAccurate(3);
        return match;
    }
}
