package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Ownership;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.OwnershipRepository;
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
        OwnershipRepository ownerships = mock(OwnershipRepository.class);
        GameStateService gameState = mock(GameStateService.class);
        LegacyRecordsService service = new LegacyRecordsService(
                scorers, humans, teams, competitions, history, transfers, ownerships, gameState);

        Team team = new Team(); team.setId(86); team.setName("Sherlock FC");
        Human striker = player(1, "Academy Hero", "ST");
        striker.setTeamId(99L);
        Human creator = player(2, "Club Creator", "MC");
        creator.setRetired(true); creator.setAge(37);
        Human coach = player(3, "Defensive Graduate", "DC");
        coach.setTypeId(6); coach.setTeamId(77L);
        Human owner = player(4, "Captain Investor", "GK");
        owner.setRetired(true); owner.setAge(39);
        ScorerRepository.LegacyRecordAggregate hero = aggregate(1, 82, 61, 18, 7.6);
        ScorerRepository.LegacyRecordAggregate assist = aggregate(2, 105, 12, 44, 7.3);
        ScorerRepository.LegacyRecordAggregate coachRow = aggregate(3, 48, 0, 0, 7.1);
        ScorerRepository.LegacyRecordAggregate ownerRow = aggregate(4, 52, 0, 0, 7.2);
        CompetitionHistory title = new CompetitionHistory();
        title.setTeamId(86); title.setCompetitionId(7); title.setSeasonNumber(4); title.setLastPosition(1);
        ScorerRepository.TrophyParticipationAggregate participation = participation(1, 86, 7, 4);
        Transfer sale = new Transfer(); sale.setPlayerId(1); sale.setPlayerName("Academy Hero");
        sale.setSellTeamId(86); sale.setSellTeamName("Sherlock FC"); sale.setBuyTeamId(99);
        sale.setBuyTeamName("Yu Gi Oh"); sale.setPlayerTransferValue(75_000_000); sale.setSeasonNumber(5);
        ScorerRepository.PostSeasonCareerAggregate later = mock(ScorerRepository.PostSeasonCareerAggregate.class);
        when(later.getPlayerId()).thenReturn(1L); when(later.getFirstSeason()).thenReturn(5);
        when(later.getLastSeason()).thenReturn(6); when(later.getAppearances()).thenReturn(44L);
        when(later.getGoals()).thenReturn(27L); when(later.getAssists()).thenReturn(9L);
        Ownership clubOwner = new Ownership(); clubOwner.setHumanId(4); clubOwner.setTeamId(55);
        Team destination = new Team(); destination.setId(99); destination.setName("Yu Gi Oh");
        Team staffClub = new Team(); staffClub.setId(77); staffClub.setName("Coach City");
        Team ownedClub = new Team(); ownedClub.setId(55); ownedClub.setName("Investor Athletic");

        when(gameState.currentSeason()).thenReturn(5);
        when(teams.findById(86L)).thenReturn(Optional.of(team));
        when(scorers.findClubLegacySeasons(86)).thenReturn(List.of(4, 3, 2));
        when(scorers.aggregateClubLegacy(86)).thenReturn(List.of(hero, assist, coachRow, ownerRow));
        when(scorers.aggregateClubLegacySeason(86, 4)).thenReturn(List.of(hero, assist, coachRow, ownerRow));
        when(humans.findAllById(any())).thenReturn(List.of(striker, creator, coach, owner));
        when(teams.findAllById(any())).thenReturn(List.of(destination, staffClub, ownedClub));
        when(history.findByTeamId(86)).thenReturn(List.of(title));
        when(scorers.findTrophyParticipations(86L, null)).thenReturn(List.of(participation));
        when(transfers.findRecordSalesByTeam(any(Long.class), any())).thenReturn(List.of(sale));
        when(transfers.findAllByPlayerIdInOrderByPlayerIdAscSeasonNumberAscIdAsc(any())).thenReturn(List.of(sale));
        when(scorers.aggregatePlayerCareersAfterSeason(any(), any(Integer.class))).thenReturn(List.of(later));
        when(ownerships.findAllByHumanIdIn(any())).thenReturn(List.of(clubOwner));

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
        var heroNow = whereabouts(result, 1);
        assertThat(heroNow.status()).isEqualTo("ACTIVE_PLAYER");
        assertThat(heroNow.currentTeamName()).isEqualTo("Yu Gi Oh");
        assertThat(heroNow.transferJourney()).extracting("toTeamName").containsExactly("Yu Gi Oh");
        assertThat(heroNow.appearancesAfterSeason()).isEqualTo(44);
        assertThat(heroNow.summary()).contains("27 goals").contains("Season 6");
        assertThat(whereabouts(result, 2).status()).isEqualTo("RETIRED");
        assertThat(whereabouts(result, 3).statusLabel()).isEqualTo("First Team Coach");
        assertThat(whereabouts(result, 3).currentTeamName()).isEqualTo("Coach City");
        assertThat(whereabouts(result, 4).status()).isEqualTo("OWNER");
        assertThat(whereabouts(result, 4).ownedClubs()).extracting("teamName").containsExactly("Investor Athletic");
    }

    private Human player(long id, String name, String position) {
        Human player = new Human(); player.setId(id); player.setName(name); player.setPosition(position);
        player.setTypeId(1); return player;
    }

    private LegacyRecordsService.WhereAreTheyNow whereabouts(LegacyRecordsService.LegacyRecords records,
                                                              long playerId) {
        return records.seasonBestEleven().stream().filter(pick -> pick.player().playerId() == playerId)
                .findFirst().orElseThrow().whereAreTheyNow();
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
