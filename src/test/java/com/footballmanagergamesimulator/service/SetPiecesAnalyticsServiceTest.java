package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetPiecesAnalyticsServiceTest {
    @Test
    void buildsAttackingDefendingSplitsAndCompetitionBenchmark() {
        MatchStatsRepository matches = mock(MatchStatsRepository.class); SetPieceEventRepository setPieceRows = mock(SetPieceEventRepository.class);
        ShotEventRepository shotRows = mock(ShotEventRepository.class); CompetitionRepository competitions = mock(CompetitionRepository.class);
        HumanRepository humans = mock(HumanRepository.class); PlayerSkillsRepository skills = mock(PlayerSkillsRepository.class);
        PersonalizedTacticRepository tactics = mock(PersonalizedTacticRepository.class); MatchStats match = SetPieceEventServiceTest.match();
        when(matches.findAllByTeam1IdAndSeasonNumber(10, 2)).thenReturn(List.of(match));
        when(matches.findAllByTeam2IdAndSeasonNumber(10, 2)).thenReturn(List.of());
        when(matches.findAllByCompetitionIdAndSeasonNumber(1, 2)).thenReturn(List.of(match));
        when(setPieceRows.findAllByMatchStatsIdOrderByTeamIdAscEventIndexAsc(99)).thenReturn(List.of());
        when(shotRows.findAllByMatchStatsIdOrderByShotIndexAsc(99)).thenReturn(List.of());
        Competition competition = new Competition(); competition.setId(1); competition.setName("Test League");
        when(competitions.findById(1L)).thenReturn(Optional.of(competition));
        when(humans.findAllByTeamIdAndTypeId(10, 1)).thenReturn(List.of());
        when(skills.findAllByPlayerIdIn(any())).thenReturn(List.of()); when(tactics.findPersonalizedTacticByTeamId(10)).thenReturn(Optional.empty());
        SetPieceEventService eventService = new SetPieceEventService(setPieceRows, new ShotEventService(shotRows));
        SetPiecesAnalyticsService service = new SetPiecesAnalyticsService(matches, eventService, competitions, humans, skills, tactics);

        SetPiecesAnalyticsService.SetPiecesAnalytics result = service.analytics(10, 2);

        assertThat(result.matches()).isEqualTo(1); assertThat(result.summary().cornersFor()).isEqualTo(7);
        assertThat(result.summary().cornersAgainst()).isEqualTo(3); assertThat(result.benchmarkCompetitionName()).isEqualTo("Test League");
        assertThat(result.benchmark().teams()).isEqualTo(2); assertThat(result.cornerDeliveryStyles()).isNotEmpty();
        assertThat(result.cornerZonesAgainst()).isNotEmpty(); assertThat(result.typesFor()).extracting(SetPiecesAnalyticsService.SetPieceType::type)
                .contains("CORNER", "DIRECT_FREE_KICK", "INDIRECT_FREE_KICK", "LONG_THROW");
        assertThat(result.insights()).isNotEmpty(); assertThat(result.dataQuality().counts()).isEqualTo("OBSERVED_COUNTS");
    }
}
