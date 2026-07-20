package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScoutRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceServiceTest {

    @Test
    void reportIncludesEveryCategoryPresentInTheLedger() {
        FinanceService service = new FinanceService();
        TeamRepository teams = mock(TeamRepository.class);
        FinancialRecordRepository records = mock(FinancialRecordRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ScoutRepository scouts = mock(ScoutRepository.class);

        Team team = new Team();
        team.setId(4L);
        team.setBoardConfidence(50);
        when(teams.findById(4L)).thenReturn(Optional.of(team));
        when(records.findAllByTeamIdAndSeasonNumber(4L, 8)).thenReturn(List.of(
                record("OWNER_WITHDRAWAL", -2_000_000L),
                record("FINES", -500_000L),
                record("CUSTOM_LEGACY_CATEGORY", 300_000L)));
        when(humans.findAllByTeamId(4L)).thenReturn(List.of());
        when(scouts.findAllByTeamId(4L)).thenReturn(List.of());

        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "financialRecordRepository", records);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "scoutRepository", scouts);

        Map<String, Object> report = service.getFinancialReport(4L, 8);
        @SuppressWarnings("unchecked")
        Map<String, Long> breakdown = (Map<String, Long>) report.get("breakdown");

        assertEquals(-2_000_000L, breakdown.get("OWNER_WITHDRAWAL"));
        assertEquals(-500_000L, breakdown.get("FINES"));
        assertEquals(300_000L, breakdown.get("CUSTOM_LEGACY_CATEGORY"));
        assertEquals(300_000L, report.get("totalIncome"));
        assertEquals(2_500_000L, report.get("totalExpenses"));
        assertEquals(-2_200_000L, report.get("netProfit"));
    }

    private FinancialRecord record(String category, long amount) {
        FinancialRecord record = new FinancialRecord();
        record.setCategory(category);
        record.setAmount(amount);
        return record;
    }
}
