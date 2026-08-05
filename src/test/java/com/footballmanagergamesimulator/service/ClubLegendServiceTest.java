package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubLegend;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.ClubLegendRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubLegendServiceTest {

    @Test
    void formerPlayerCanBeInductedAndKeepsHistoricClubStatistics() {
        ClubLegendRepository legends = mock(ClubLegendRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        GameStateService gameState = mock(GameStateService.class);
        ClubLegendService service = new ClubLegendService(legends, humans, scorers, gameState);

        Human player = new Human();
        player.setId(44); player.setName("Academy Hero"); player.setPosition("ST"); player.setTeamId(99L);
        ScorerRepository.LegacyRecordAggregate record = mock(ScorerRepository.LegacyRecordAggregate.class);
        when(record.getPlayerId()).thenReturn(44L);
        when(record.getAppearances()).thenReturn(210L);
        when(record.getGoals()).thenReturn(132L);
        when(record.getAssists()).thenReturn(41L);
        when(record.getRatingCount()).thenReturn(210L);
        when(record.getRatingTotal()).thenReturn(1554.0);
        when(scorers.aggregateClubLegacy(86)).thenReturn(List.of(record));
        when(humans.findById(44L)).thenReturn(Optional.of(player));
        when(legends.findByTeamIdAndPlayerId(86, 44)).thenReturn(Optional.empty());
        when(gameState.currentSeason()).thenReturn(8);
        when(legends.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ClubLegend saved = invocation.getArgument(0);
            saved.setId(5);
            return saved;
        });

        ClubLegendService.ClubLegendView result = service.induct(86, 44, "Homegrown record scorer");

        ArgumentCaptor<ClubLegend> captor = ArgumentCaptor.forClass(ClubLegend.class);
        verify(legends).save(captor.capture());
        assertThat(captor.getValue().getInductedSeason()).isEqualTo(8);
        assertThat(result.playerName()).isEqualTo("Academy Hero");
        assertThat(result.appearances()).isEqualTo(210);
        assertThat(result.goals()).isEqualTo(132);
        assertThat(result.averageRating()).isEqualTo(7.4);
    }
}
