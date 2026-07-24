package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ClubQueryServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final PersonalAccountRepository accounts = mock(PersonalAccountRepository.class);
    private final ClubCapTableService capTables = mock(ClubCapTableService.class);
    private final ClubValuationService valuations = mock(ClubValuationService.class);
    private final ClubFinancialPolicyService policies = mock(ClubFinancialPolicyService.class);
    private final TakeoverQuoteRepository quotes = mock(TakeoverQuoteRepository.class);
    private final RegentEconomyProperties properties = new RegentEconomyProperties();
    private final ClubQueryService service = new ClubQueryService(teams, competitions, accounts,
            capTables, valuations, policies, quotes, properties);

    private final PersonalAccount principalAccount = mock(PersonalAccount.class);
    private final PersonProfile chairman = profile(7L, CareerType.CHAIRMAN);

    @BeforeEach
    void setUp() {
        properties.getEconomy().setCurrency("EUR");
        when(principalAccount.getId()).thenReturn(70L);
        when(accounts.findByProfileId(7L)).thenReturn(Optional.of(principalAccount));
        when(competitions.findAll()).thenReturn(List.of(competition(20L, "Liga Real")));
        when(teams.findAll()).thenReturn(List.of(team(2L, 20L, "Beta"), team(1L, 20L, "Alpha")));
        when(capTables.view(anyLong())).thenAnswer(invocation -> capTable(invocation.getArgument(0)));
        when(capTables.viewBatch(any())).thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0)).stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, this::capTable)));
        when(valuations.value(anyLong())).thenAnswer(invocation -> valuation(invocation.getArgument(0)));
        when(valuations.valueBatch(any())).thenAnswer(invocation -> ((List<Team>) invocation.getArgument(0)).stream()
                .collect(java.util.stream.Collectors.toMap(Team::getId, team -> valuation(team.getId()))));
        when(valuations.equityValue(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.<Long>getArgument(1) * 100L);
    }

    @Test
    void allReturnsStableOrderAndCanonicalCompetitionAndPrincipalValues() {
        List<ClubDtos.ClubSummary> result = service.clubs(ClubCatalogScope.ALL, chairman.getId());

        assertThat(result).extracting(ClubDtos.ClubSummary::teamId).containsExactly(1L, 2L);
        ClubDtos.ClubSummary first = result.get(0);
        assertThat(first.competitionId()).isEqualTo(20L);
        assertThat(first.competitionName()).isEqualTo("Liga Real");
        assertThat(first.principalShares()).isEqualTo(6_000L);
        assertThat(first.principalStakeBps()).isEqualTo(6_000);
        assertThat(first.principalEquityValue().amount()).isEqualTo(600_000L);
        assertThat(first.heldByPrincipal()).isTrue();
        assertThat(first.controlledByPrincipal()).isTrue();
    }

    @Test
    void heldAndControlledFilterInTheBackend() {
        assertThat(service.clubs(ClubCatalogScope.HELD, chairman.getId()))
                .extracting(ClubDtos.ClubSummary::teamId).containsExactly(1L);
        assertThat(service.clubs(ClubCatalogScope.CONTROLLED, chairman.getId()))
                .extracting(ClubDtos.ClubSummary::teamId).containsExactly(1L);
    }

    @Test
    void missingCompetitionNameIsNullAndNeverInvented() {
        when(competitions.findAll()).thenReturn(List.of());

        assertThat(service.clubs(ClubCatalogScope.ALL, chairman.getId()).get(0).competitionName()).isNull();
    }

    @Test
    void anotherProfileCannotChangeTheAuthenticatedPrincipalResult() {
        PersonalAccount otherAccount = mock(PersonalAccount.class);
        when(otherAccount.getId()).thenReturn(71L);
        when(accounts.findByProfileId(8L)).thenReturn(Optional.of(otherAccount));

        List<ClubDtos.ClubSummary> authenticated = service.clubs(ClubCatalogScope.ALL, chairman.getId());
        List<ClubDtos.ClubSummary> other = service.clubs(ClubCatalogScope.ALL, 8L);

        assertThat(authenticated.get(0).principalShares()).isEqualTo(6_000L);
        assertThat(other.get(0).principalShares()).isEqualTo(0L);
        assertThat(other.get(0).controlledByPrincipal()).isFalse();
    }

    @Test
    void nonChairmanIsRejectedBeforePrivateDashboardDataIsCalculated() {
        PersonProfile manager = profile(8L, CareerType.MANAGER);

        assertThatThrownBy(() -> service.dashboard(1L, manager))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CHAIRMAN_REQUIRED");
        verifyNoInteractions(teams, capTables, valuations, policies);
    }

    @Test
    void chairmanWithoutCanonicalControlIsRejectedBeforeValuationAndTreasury() {
        when(teams.findById(2L)).thenReturn(Optional.of(team(2L, 20L, "Beta")));
        when(capTables.ensureMigrated(2L)).thenReturn(capTable(2L));

        assertThatThrownBy(() -> service.dashboard(2L, chairman))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CLUB_CONTROL_REQUIRED");
        verifyNoInteractions(valuations, policies);
    }

    @Test
    void controlledChairmanDashboardUsesExistingValuationCapTableAndTreasury() {
        Team team = team(1L, 20L, "Alpha");
        ClubValuationService.Valuation valuation = valuation(1L);
        when(teams.findById(1L)).thenReturn(Optional.of(team));
        when(capTables.ensureMigrated(1L)).thenReturn(capTable(1L));
        when(valuations.value(1L)).thenReturn(valuation);
        when(policies.policy(team)).thenReturn(new ClubFinancialPolicyService.Policy(
                5_000_000L, 100_000L, 20_000L, 60_000L, 10_000L, 4_930_000L, false));

        ClubDtos.Dashboard dashboard = service.dashboard(1L, chairman);

        assertThat(dashboard.controlledByPrincipal()).isTrue();
        assertThat(dashboard.valuation().totalValue().amount()).isEqualTo(1_000_000L);
        verify(valuations).equityValue(valuation, 6_000L, 10_000L);
        verify(policies).policy(team);
    }

    private ClubCapTableService.CapTable capTable(long teamId) {
        ClubCapTableService.Holding principal = teamId == 1L
                ? new ClubCapTableService.Holding(70L, 7L, "Chair", true, 6_000L, 0, true)
                : new ClubCapTableService.Holding(71L, 9L, "Other", true, 1_000L, 0, false);
        return new ClubCapTableService.CapTable(teamId, teamId + 100, 10_000L,
                10_000L - principal.quantity(), 5_001, teamId == 1L ? 70L : null,
                1L, List.of(principal));
    }

    private ClubValuationService.Valuation valuation(long teamId) {
        return new ClubValuationService.Valuation(teamId, "formula", "state", 0,
                1_000_000L, 0, 0, 1_000_000L, 0, 0, 0, 0, 1_000_000L);
    }

    private static Team team(long id, long competitionId, String name) {
        Team team = new Team();
        team.setId(id);
        team.setCompetitionId(competitionId);
        team.setName(name);
        return team;
    }

    private static Competition competition(long id, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setName(name);
        return competition;
    }

    private static PersonProfile profile(long id, CareerType type) {
        PersonProfile profile = new PersonProfile();
        profile.setId(id);
        profile.setCareerType(type);
        return profile;
    }
}
