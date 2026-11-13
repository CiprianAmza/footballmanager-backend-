package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.MatchPreviewView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchControllerFixturePreviewTest {

    @Test
    void previewIsBuiltForTheRequestedFixtureAndNullWhenItIsNotScheduled() {
        MatchController controller = new MatchController();
        controller.competitionTeamInfoMatchRepository = mock(CompetitionTeamInfoMatchRepository.class);
        controller.competitionTeamInfoDetailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        controller.teamCompetitionDetailRepository = mock(TeamCompetitionDetailRepository.class);
        controller.teamRepository = mock(TeamRepository.class);
        controller.competitionRepository = mock(CompetitionRepository.class);
        controller.humanRepository = mock(HumanRepository.class);

        when(controller.competitionTeamInfoMatchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(1L, 20L, "15"))
                .thenReturn(List.of(fixture(5, 6), fixture(86, 12)));
        when(controller.teamRepository.findNameById(86)).thenReturn("Sherlock FC");
        when(controller.teamRepository.findNameById(12)).thenReturn("Rival United");
        when(controller.competitionRepository.findNameById(1L)).thenReturn("First League");

        MatchPreviewView preview = controller.getMatchPreviewForFixture(1, 15, 20, 86, 12);

        assertThat(preview).isNotNull();
        assertThat(preview.getHomeTeamId()).isEqualTo(86);
        assertThat(preview.getAwayTeamId()).isEqualTo(12);
        assertThat(preview.getHomeTeamName()).isEqualTo("Sherlock FC");
        assertThat(preview.getAwayTeamName()).isEqualTo("Rival United");
        assertThat(preview.getCompetitionName()).isEqualTo("First League");
        assertThat(preview.getRound()).isEqualTo(20);
        assertThat(controller.getMatchPreviewForFixture(1, 15, 20, 12, 86)).isNull();
    }

    private static CompetitionTeamInfoMatch fixture(long team1, long team2) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setCompetitionId(1);
        match.setRound(20);
        match.setTeam1Id(team1);
        match.setTeam2Id(team2);
        match.setSeasonNumber("15");
        return match;
    }
}
