package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyRecordsServiceTest {

    @Test
    void clubArchiveKeepsCareerRecordsBestElevenTrophiesAndSalesTogether() {
        ScorerRepository scorers = mock(ScorerRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionHistoryRepository history = mock(CompetitionHistoryRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        GameStateService gameState = mock(GameStateService.class);
        LegacyRecordsService service = new LegacyRecordsService(
                scorers, humans, teams, competitions, history, transfers, gameState);

        Team team = new Team(); team.setId(86); team.setName("Sherlock FC");
        Human striker = player(1, "Academy Hero", "ST");
        Human creator = player(2, "Club Creator", "MC");
        ScorerRepository.LegacyRecordAggregate hero = aggregate(1, 82, 61, 18, 7.6);
        ScorerRepository.LegacyRecordAggregate assist = aggregate(2, 105, 12, 44, 7.3);
        CompetitionHistory title = new CompetitionHistory();
        title.setTeamId(86); title.setCompetitionId(7); title.setSeasonNumber(4); title.setLastPosition(1);
        ScorerRepository.TrophyParticipationAggregate participation = participation(1, 86, 7, 4);
        Transfer sale = new Transfer(); sale.setPlayerId(1); sale.setPlayerName("Academy Hero");
        sale.setSellTeamId(86); sale.setSellTeamName("Sherlock FC"); sale.setBuyTeamId(99);
        sale.setBuyTeamName("Yu Gi Oh"); sale.setPlayerTransferValue(75_000_000); sale.setSeasonNumber(5);

        when(gameState.currentSeason()).thenReturn(5);
        when(teams.findById(86L)).thenReturn(Optional.of(team));
        when(scorers.findClubLegacySeasons(86)).thenReturn(List.of(4, 3, 2));
        when(scorers.aggregateClubLegacy(86)).thenReturn(List.of(hero, assist));
        when(scorers.aggregateClubLegacySeason(86, 4)).thenReturn(List.of(hero, assist));
        when(humans.findAllById(any())).thenReturn(List.of(striker, creator));
        when(history.findByTeamId(86)).thenReturn(List.of(title));
        when(scorers.findTrophyParticipations(86L, null)).thenReturn(List.of(participation));
        when(transfers.findRecordSalesByTeam(any(Long.class), any())).thenReturn(List.of(sale));

        LegacyRecordsService.LegacyRecords result = service.club(86, 4, 20);

        assertThat(result.scopeName()).isEqualTo("Sherlock FC");
        assertThat(result.allTimeScorers()).extracting("playerName", "recordValue")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Academy Hero", 61L),
                        org.assertj.core.groups.Tuple.tuple("Club Creator", 12L));
        assertThat(result.allTimeAssists().get(0).playerName()).isEqualTo("Club Creator");
        assertThat(result.allTimeAppearances().get(0).appearances()).isEqualTo(105);
        assertThat(result.trophyLeaders().get(0).trophies()).isEqualTo(1);
        assertThat(result.seasonBestEleven()).isNotEmpty();
        assertThat(result.allTimeBestEleven()).isNotEmpty();
        assertThat(result.recordSales().get(0).fee()).isEqualTo(75_000_000);
    }

    private Human player(long id, String name, String position) {
        Human player = new Human(); player.setId(id); player.setName(name); player.setPosition(position); return player;
    }

    private ScorerRepository.LegacyRecordAggregate aggregate(long id, long apps, long goals,
                                                               long assists, double average) {
        ScorerRepository.LegacyRecordAggregate row = mock(ScorerRepository.LegacyRecordAggregate.class);
        when(row.getPlayerId()).thenReturn(id); when(row.getFirstSeason()).thenReturn(2);
        when(row.getLastSeason()).thenReturn(4); when(row.getAppearances()).thenReturn(apps);
        when(row.getGoals()).thenReturn(goals); when(row.getAssists()).thenReturn(assists);
        when(row.getRatingCount()).thenReturn(apps); when(row.getRatingTotal()).thenReturn(apps * average);
        when(row.getTeamCount()).thenReturn(1L); when(row.getTeamId()).thenReturn(86L);
        when(row.getTeamName()).thenReturn("Sherlock FC"); return row;
    }

    private ScorerRepository.TrophyParticipationAggregate participation(long player, long team,
                                                                          long competition, int season) {
        ScorerRepository.TrophyParticipationAggregate row = mock(ScorerRepository.TrophyParticipationAggregate.class);
        when(row.getPlayerId()).thenReturn(player); when(row.getTeamId()).thenReturn(team);
        when(row.getCompetitionId()).thenReturn(competition); when(row.getSeasonNumber()).thenReturn(season);
        return row;
    }
}
