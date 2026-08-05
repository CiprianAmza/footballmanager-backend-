package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialTroubleMediaServiceTest {

    @Test
    void seriousDebtProducesCriticalMediaCoverageWithRealFigures() {
        TeamRepository teams = mock(TeamRepository.class);
        FinancialRecordRepository records = mock(FinancialRecordRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FinancialTroubleMediaService service = new FinancialTroubleMediaService(teams, records, inbox);
        Team club = club(86, "Sherlock FC", 0, 12_000_000);
        when(teams.findById(86L)).thenReturn(Optional.of(club));
        when(records.sumByTeamIdAndSeasonNumber(86, 8)).thenReturn(-7_000_000L);

        service.publishIfNeeded(86, 8, 62, 1_000_000);

        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("MEDIA_FINANCIAL_TROUBLE");
        assertThat(captor.getValue().getTitle()).contains("Financial alarm");
        assertThat(captor.getValue().getContent()).contains("Status: Critical")
                .contains("€12,000,000").contains("urgent player sales");
    }

    @Test
    void healthyClubDoesNotAttractCrisisCoverage() {
        TeamRepository teams = mock(TeamRepository.class);
        FinancialRecordRepository records = mock(FinancialRecordRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FinancialTroubleMediaService service = new FinancialTroubleMediaService(teams, records, inbox);
        when(teams.findById(86L)).thenReturn(Optional.of(club(86, "Sherlock FC", 20_000_000, 0)));
        when(records.sumByTeamIdAndSeasonNumber(86, 8)).thenReturn(2_000_000L);

        service.publishIfNeeded(86, 8, 62, 1_000_000);

        verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sameSeverityIsPublishedOnlyOncePerSeason() {
        TeamRepository teams = mock(TeamRepository.class);
        FinancialRecordRepository records = mock(FinancialRecordRepository.class);
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FinancialTroubleMediaService service = new FinancialTroubleMediaService(teams, records, inbox);
        when(teams.findById(86L)).thenReturn(Optional.of(club(86, "Sherlock FC", 0, 3_000_000)));
        when(records.sumByTeamIdAndSeasonNumber(86, 8)).thenReturn(-3_000_000L);
        when(inbox.existsByTeamIdAndDeduplicationKey(86, "FINANCIAL_MEDIA:8:86:CRISIS")).thenReturn(true);

        service.publishIfNeeded(86, 8, 93, 1_000_000);

        verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private Team club(long id, String name, long balance, long debt) {
        Team team = new Team(); team.setId(id); team.setName(name);
        team.setTotalFinances(balance); team.setDebt(debt);
        return team;
    }
}
