package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.StadiumRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubValuationServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final StadiumRepository stadiums = mock(StadiumRepository.class);
    private final TeamFacilitiesRepository facilities = mock(TeamFacilitiesRepository.class);
    private final CompetitionHistoryRepository histories = mock(CompetitionHistoryRepository.class);
    private final ClubFinancialObligationRepository obligations = mock(ClubFinancialObligationRepository.class);
    private final RegentEconomyProperties properties = properties();
    private final ClubValuationService service = new ClubValuationService(
            teams, humans, stadiums, facilities, histories, obligations, properties);

    @Test
    void formulaUsesEveryConfiguredComponentCapsPerformanceAndVersionsState() {
        Team team = team(1_000, 100, 100);
        Human first = player(500, false);
        Human second = player(300, false);
        Human retired = player(9_999, true);
        Stadium stadium = new Stadium();
        stadium.setCapacity(100);
        stadium.setExpansionLevel(2);
        stadium.setVipBoxesLevel(1);
        stadium.setCateringLevel(1);
        stadium.setFanShopLevel(1);
        stadium.setFastFoodLevel(1);
        stadium.setHeadquartersLevel(1);
        stadium.setTrainingPitchLevel(1);
        stadium.setParkingLevel(1);
        TeamFacilities facility = new TeamFacilities();
        facility.setYouthAcademyLevel(1);
        facility.setYouthTrainingLevel(2);
        facility.setSeniorTrainingLevel(3);
        facility.setScoutingLevel(4);
        CompetitionHistory history = new CompetitionHistory();
        history.setSeasonNumber(4);
        history.setCompetitionId(1);
        history.setGames(10);
        history.setPoints(30);
        ClubFinancialObligation obligation = new ClubFinancialObligation();
        obligation.setAmountRemaining(200);

        when(humans.findAllByTeamIdAndTypeId(7, TypeNames.PLAYER_TYPE))
                .thenReturn(List.of(first, second, retired));
        when(stadiums.findByTeamId(7)).thenReturn(Optional.of(stadium));
        when(facilities.findByTeamId(7)).thenReturn(facility);
        when(histories.findByTeamId(7)).thenReturn(List.of(history));
        when(obligations.findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(7))
                .thenReturn(List.of(obligation));

        ClubValuationService.Valuation value = service.value(team);
        assertThat(value.squadMarketValue()).isEqualTo(800);
        assertThat(value.netCash()).isEqualTo(700);
        assertThat(value.stadiumFacilitiesValue()).isEqualTo(102_400);
        assertThat(value.reputationBrandValue()).isEqualTo(200);
        assertThat(value.recentPerformanceBps()).isEqualTo(1_000);
        assertThat(value.recentPerformanceValue()).isEqualTo(10_410);
        assertThat(value.totalValue()).isEqualTo(114_510);
        assertThat(value.formulaVersion()).isEqualTo("test-club-v1");
        assertThat(value.stateVersion()).hasSize(64);

        String firstVersion = value.stateVersion();
        team.setTotalFinances(1_001);
        assertThat(service.value(team).stateVersion()).isNotEqualTo(firstVersion);
        properties.getClub().setValuationVersion("test-club-v2");
        assertThat(service.value(team).formulaVersion()).isEqualTo("test-club-v2");
    }

    @Test
    void overflowIsRejectedAndEquityUsesExactFiniteSupply() {
        Team team = team(Long.MIN_VALUE, 1, 0);
        when(humans.findAllByTeamIdAndTypeId(7, TypeNames.PLAYER_TYPE)).thenReturn(List.of());
        when(stadiums.findByTeamId(7)).thenReturn(Optional.empty());
        when(facilities.findByTeamId(7)).thenReturn(null);
        when(histories.findByTeamId(7)).thenReturn(List.of());
        when(obligations.findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(7))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.value(team)).isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("MONEY_OVERFLOW"));

        ClubValuationService.Valuation valuation = new ClubValuationService.Valuation(
                7, "v", "state", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1_000_001);
        assertThat(service.perSharePrice(valuation, 1_000_000)).isEqualTo(2);
        assertThat(service.equityValue(valuation, 500_000, 1_000_000)).isEqualTo(500_000);
    }

    private Team team(long cash, long debt, int reputation) {
        Team value = new Team();
        value.setId(7);
        value.setTotalFinances(cash);
        value.setDebt(debt);
        value.setReputation(reputation);
        value.setStadiumCapacity(0);
        return value;
    }

    private Human player(long value, boolean retired) {
        Human human = new Human();
        human.setTransferValue(value);
        human.setRetired(retired);
        return human;
    }

    private static RegentEconomyProperties properties() {
        RegentEconomyProperties value = new RegentEconomyProperties();
        RegentEconomyProperties.Club club = value.getClub();
        club.setValuationVersion("test-club-v1");
        club.setMinimumValuation(1);
        club.setStadiumSeatValue(10);
        club.setStadiumLevelValue(100);
        club.setFacilityLevelValue(50);
        club.setReputationPointValue(2);
        club.setPerformanceCapBps(1_000);
        club.setPerformanceLookbackSeasons(1);
        return value;
    }
}
