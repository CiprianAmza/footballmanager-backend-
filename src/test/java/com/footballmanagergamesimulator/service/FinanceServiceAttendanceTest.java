package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.StadiumRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceServiceAttendanceTest {

    @Test
    void awayFansAreTheOpponentsShareOfTheOccupancy() {
        // 0.04 of a 0.80 occupancy: a twentieth of the crowd came for the opponent.
        assertThat(FinanceService.awayAttendance(10_000, 0.80, 0.04)).isEqualTo(500);
        assertThat(FinanceService.awayAttendance(2_727, 0.99, 0.0)).isZero();
        assertThat(FinanceService.awayAttendance(0, 0.70, 0.05)).isZero();
        assertThat(FinanceService.awayAttendance(1_000, 0.05, 0.10)).isEqualTo(1_000);
    }

    @Test
    void batchReturnsOneCrowdPerInputInInputOrder() {
        FinanceService service = new FinanceService();
        TeamRepository teams = mock(TeamRepository.class);
        StadiumRepository stadiums = mock(StadiumRepository.class);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "stadiumRepository", stadiums);
        ReflectionTestUtils.setField(service, "financialRecordRepository", mock(FinancialRecordRepository.class));

        Team home = team(1, 0, 20_000);
        Team away = team(2, 1_500, 10_000);
        when(teams.findAllById(any())).thenReturn(List.of(home, away));
        when(stadiums.findAllByTeamIdIn(any())).thenReturn(List.of());

        List<FinanceService.MatchAttendance> crowds = service.processMatchDayIncomeBatch(16, List.of(
                new FinanceService.MatchDayIncomeInput(99, 40, 2, "First League"),
                new FinanceService.MatchDayIncomeInput(1, 40, 2, "First League")));

        assertThat(crowds).hasSize(2);
        assertThat(crowds.get(0)).isNull();
        FinanceService.MatchAttendance crowd = crowds.get(1);
        // Occupancy 0.60-0.80 plus 0.05 for the opponent's reputation, on a 20,000 ground.
        assertThat(crowd.attendance()).isBetween(13_000, 17_000);
        assertThat(crowd.awayAttendance()).isPositive();
        assertThat(crowd.awayAttendance()).isLessThan(crowd.attendance() / 12);
    }

    @Test
    void emptyBatchHasNoCrowds() {
        assertThat(new FinanceService().processMatchDayIncomeBatch(16, List.of())).isEmpty();
    }

    private static Team team(long id, int reputation, int capacity) {
        Team team = new Team();
        team.setId(id);
        team.setName("Team " + id);
        team.setReputation(reputation);
        team.setStadiumCapacity(capacity);
        return team;
    }
}
