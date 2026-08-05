package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubLegend;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ClubLegendRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormerPlayerStatementServiceTest {

    @Test
    void officialLegendWhoLeftTheClubCommentsOnAHeavyResult() {
        ScorerRepository scorers = mock(ScorerRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ClubLegendRepository legends = mock(ClubLegendRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FormerPlayerStatementService service = new FormerPlayerStatementService(scorers, humans, legends, inbox);

        Human formerPlayer = new Human();
        formerPlayer.setId(44); formerPlayer.setName("Academy Hero"); formerPlayer.setPosition("ST");
        formerPlayer.setTeamId(99L);
        ScorerRepository.LegacyRecordAggregate record = record(44, 210, 132, 41);
        ClubLegend legend = new ClubLegend(); legend.setTeamId(86); legend.setPlayerId(44);
        when(scorers.aggregateClubLegacy(86)).thenReturn(List.of(record));
        when(humans.findAllById(any())).thenReturn(List.of(formerPlayer));
        when(legends.findAllByTeamIdOrderByInductedSeasonDescInductedAtDesc(86)).thenReturn(List.of(legend));

        service.publishPostMatchStatement(86, "Sherlock FC", 90, "Rivals", 4, 0,
                "First Division", 8, 12);

        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("MEDIA_FORMER_PLAYER");
        assertThat(captor.getValue().getTitle()).contains("Academy Hero");
        assertThat(captor.getValue().getContent()).contains("Club legend").contains("210 appearances")
                .contains("personal view");
    }

    @Test
    void currentPlayersCannotBePresentedAsFormerPlayers() {
        ScorerRepository scorers = mock(ScorerRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ClubLegendRepository legends = mock(ClubLegendRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FormerPlayerStatementService service = new FormerPlayerStatementService(scorers, humans, legends, inbox);
        Human currentPlayer = new Human(); currentPlayer.setId(11); currentPlayer.setTeamId(86L);
        ScorerRepository.LegacyRecordAggregate currentRecord = record(11, 80, 10, 15);
        when(scorers.aggregateClubLegacy(86)).thenReturn(List.of(currentRecord));
        when(humans.findAllById(any())).thenReturn(List.of(currentPlayer));
        when(legends.findAllByTeamIdOrderByInductedSeasonDescInductedAtDesc(86)).thenReturn(List.of());

        service.publishPostMatchStatement(86, "Sherlock FC", 90, "Rivals", 0, 3,
                "First Division", 8, 12);

        verify(inbox, never()).save(any());
    }

    private ScorerRepository.LegacyRecordAggregate record(long playerId, long appearances,
                                                            long goals, long assists) {
        ScorerRepository.LegacyRecordAggregate record = mock(ScorerRepository.LegacyRecordAggregate.class);
        when(record.getPlayerId()).thenReturn(playerId);
        when(record.getAppearances()).thenReturn(appearances);
        when(record.getGoals()).thenReturn(goals);
        when(record.getAssists()).thenReturn(assists);
        return record;
    }
}
