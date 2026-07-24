package com.footballmanagergamesimulator.chairman.command;

import com.footballmanagergamesimulator.economy.ClubDtos;
import com.footballmanagergamesimulator.economy.ClubQueryService;
import com.footballmanagergamesimulator.economy.EconomyConflictException;
import com.footballmanagergamesimulator.economy.EconomyDtos;
import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.Scout;
import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamDataHubStats;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.ScoutRepository;
import com.footballmanagergamesimulator.repository.StadiumRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CompetitionDisplayService;
import com.footballmanagergamesimulator.service.MatchService;
import com.footballmanagergamesimulator.service.StatsAggregationService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChairmanCommandCentreServiceTest {
    private final ClubQueryService clubQuery = mock(ClubQueryService.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final StadiumRepository stadiums = mock(StadiumRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final ScoutRepository scouts = mock(ScoutRepository.class);
    private final InjuryRepository injuries = mock(InjuryRepository.class);
    private final SuspensionRepository suspensions = mock(SuspensionRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final CompetitionTeamInfoMatchRepository matches = mock(CompetitionTeamInfoMatchRepository.class);
    private final FinancialRecordRepository financialRecords = mock(FinancialRecordRepository.class);
    private final CompetitionDisplayService competitionDisplay = mock(CompetitionDisplayService.class);
    private final MatchService matchService = mock(MatchService.class);
    private final StatsAggregationService stats = mock(StatsAggregationService.class);
    private final ChairmanCommandCentreService service = new ChairmanCommandCentreService(
            clubQuery, teams, competitions, stadiums, humans, scouts, injuries, suspensions,
            calendars, matches, financialRecords, competitionDisplay, matchService, stats);

    private final PersonProfile chairman = profile(44L, CareerType.CHAIRMAN);
    private final ClubDtos.Dashboard dashboard = dashboard();

    @BeforeEach
    void setUp() {
        when(clubQuery.dashboard(10L, chairman)).thenReturn(dashboard);
    }

    @Test
    void chairmanRequiredFailsBeforeAnySportOrFinanceQuery() {
        when(clubQuery.dashboard(10L, chairman))
                .thenThrow(new EconomyConflictException("CHAIRMAN_REQUIRED", "required"));

        assertThatThrownBy(() -> service.commandCentre(10L, chairman))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CHAIRMAN_REQUIRED");
        verifyNoInteractions(calendars, teams, competitions, stadiums, humans, scouts, injuries,
                suspensions, matches, financialRecords, competitionDisplay, matchService, stats);
    }

    @Test
    void controlRequiredFailsBeforeAnySportOrFinanceQuery() {
        when(clubQuery.dashboard(10L, chairman))
                .thenThrow(new EconomyConflictException("CLUB_CONTROL_REQUIRED", "control"));

        assertThatThrownBy(() -> service.commandCentre(10L, chairman))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CLUB_CONTROL_REQUIRED");
        verifyNoInteractions(calendars, teams, competitions, stadiums, humans, scouts, injuries,
                suspensions, matches, financialRecords, competitionDisplay, matchService, stats);
    }

    @Test
    void aggregatesCanonicalWorldDataAndReturnsImmutableLists() {
        Team team = team();
        Competition competition = new Competition();
        competition.setId(22L);
        competition.setName("Real League");
        competition.setTypeId(1L);
        Stadium stadium = new Stadium();
        stadium.setStadiumName("Command Arena");
        stadium.setCapacity(30_000);
        stadium.setExpansionLevel(2);
        GameCalendar calendar = calendar(3, 40, "MORNING");
        List<Human> squad = List.of(manager(2L), player(100L, 20), player(101L, 24),
                human(3L, TypeNames.ASSISTANT_MANAGER_TYPE), human(4L, TypeNames.FIRST_TEAM_COACH_TYPE));
        TeamDataHubStats statsValue = new TeamDataHubStats();
        statsValue.setRecentForm(new ArrayList<>(List.of("W", "D", "L", "W", "D", "L")));

        when(teams.findById(10L)).thenReturn(Optional.of(team));
        when(competitions.findById(22L)).thenReturn(Optional.of(competition));
        when(stadiums.findByTeamId(10L)).thenReturn(Optional.of(stadium));
        when(humans.findAllByTeamId(10L)).thenReturn(squad);
        when(scouts.findAllByTeamId(10L)).thenReturn(List.of(new Scout(), new Scout()));
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        when(competitionDisplay.getTeamCompetitions(10L)).thenReturn(List.of(Map.ofEntries(
                Map.entry("competitionId", 22L), Map.entry("position", 3),
                Map.entry("totalTeams", 18), Map.entry("games", 10),
                Map.entry("wins", 6), Map.entry("draws", 2), Map.entry("loses", 2),
                Map.entry("goalsFor", 18), Map.entry("goalsAgainst", 9),
                Map.entry("goalDifference", 9), Map.entry("points", 20))));
        when(stats.getTeamDataHubStats(10L, 3)).thenReturn(statsValue);
        when(matches.findAllBySeasonNumberAndTeamId("3", 10L)).thenReturn(List.of());
        when(matchService.getCalendarEntries(anyList(), eq(10L), eq(3L)))
                .thenReturn(upcomingFixtures(6));
        when(injuries.findAllByTeamIdAndDaysRemainingGreaterThan(10L, 0))
                .thenReturn(List.of(injury(100L), injury(100L), injury(101L)));
        when(suspensions.findAllByTeamIdAndActive(10L, true))
                .thenReturn(List.of(suspension(100L), suspension(100L)));
        when(financialRecords.findAllByTeamIdAndSeasonNumber(10L, 3)).thenReturn(List.of(
                financial(10, 999), financial(20, 100), financial(30, -50), financial(40, -25)));

        ChairmanCommandCentreDtos.CommandCentreView result = service.commandCentre(10L, chairman);

        assertThat(result.teamName()).isEqualTo("Command FC");
        assertThat(result.stadium().capacity()).isEqualTo(40_000);
        assertThat(result.primaryCompetition().competitionName()).isEqualTo("Real League");
        assertThat(result.manager().managerId()).isEqualTo(2L);
        assertThat(result.staff()).isEqualTo(new ChairmanCommandCentreDtos.StaffSummary(1, 2, 2, 5));
        assertThat(result.standing().position()).isEqualTo(3);
        assertThat(result.recentForm()).containsExactly("W", "D", "L", "W", "D");
        assertThat(result.nextFixtures()).hasSize(5);
        assertThat(result.squad().playerCount()).isEqualTo(2);
        assertThat(result.squad().averageAge()).isEqualTo(22.0);
        assertThat(result.squad().injuredPlayers()).isEqualTo(2);
        assertThat(result.squad().suspendedPlayers()).isEqualTo(1);
        assertThat(result.finances().transferBudget()).isEqualTo(700_000L);
        assertThat(result.finances().wageBudget()).isEqualTo(100_000L);
        assertThat(result.finances().recentIncome()).isEqualTo(100L);
        assertThat(result.finances().recentExpenses()).isEqualTo(75L);
        assertThat(result.ownership().principalProfileId()).isEqualTo(44L);
        assertThat(result.ownership().controlled()).isTrue();
        assertThatThrownBy(() -> result.recentForm().add("W"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.nextFixtures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingCalendarIsAnExplicitUnavailableState() {
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.commandCentre(10L, chairman))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "GAME_STATE_UNAVAILABLE");
        verifyNoInteractions(teams, humans, competitions, stadiums, matches, financialRecords);
    }

    @Test
    void managerMayBeAbsentAndEmptySquadHasZeroAverageAge() {
        Team team = team();
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar(3, 40, "MORNING")));
        when(teams.findById(10L)).thenReturn(Optional.of(team));
        when(humans.findAllByTeamId(10L)).thenReturn(List.of());
        when(scouts.findAllByTeamId(10L)).thenReturn(List.of());
        when(stadiums.findByTeamId(10L)).thenReturn(Optional.empty());
        when(competitions.findById(22L)).thenReturn(Optional.empty());
        when(competitionDisplay.getTeamCompetitions(10L)).thenReturn(List.of());
        when(stats.getTeamDataHubStats(10L, 3)).thenReturn(new TeamDataHubStats());
        when(matches.findAllBySeasonNumberAndTeamId("3", 10L)).thenReturn(List.of());
        when(matchService.getCalendarEntries(anyList(), eq(10L), eq(3L))).thenReturn(List.of());
        when(injuries.findAllByTeamIdAndDaysRemainingGreaterThan(10L, 0)).thenReturn(List.of());
        when(suspensions.findAllByTeamIdAndActive(10L, true)).thenReturn(List.of());
        when(financialRecords.findAllByTeamIdAndSeasonNumber(10L, 3)).thenReturn(List.of());

        ChairmanCommandCentreDtos.CommandCentreView result = service.commandCentre(10L, chairman);

        assertThat(result.manager()).isNull();
        assertThat(result.squad().playerCount()).isZero();
        assertThat(result.squad().averageAge()).isZero();
        assertThat(result.nextFixtures()).isEmpty();
    }

    private ClubDtos.Dashboard dashboard() {
        EconomyDtos.Money money = new EconomyDtos.Money(100, "EUR", 0);
        ClubDtos.ValuationView valuation = new ClubDtos.ValuationView("formula", "state", money, money,
                money, money, money, money, money, 0, money, money);
        ClubDtos.CapTableView capTable = new ClubDtos.CapTableView(10_000, 4_000, 5_001,
                44L, "Chairman", 1,
                List.of(new ClubDtos.HoldingView(44L, "Chairman", true, 6_000, 6_000, money, true)));
        ClubDtos.TreasuryView treasury = new ClubDtos.TreasuryView(money, money, money, money, money, money, false);
        return new ClubDtos.Dashboard(10L, "Command FC", valuation, capTable, treasury, true);
    }

    private static Team team() {
        Team team = new Team();
        team.setId(10L);
        team.setName("Command FC");
        team.setColor1("red");
        team.setColor2("white");
        team.setCompetitionId(22L);
        team.setStadiumName("Fallback Ground");
        team.setStadiumCapacity(25_000);
        team.setTransferBudget(700_000L);
        team.setSalaryBudget(100_000L);
        return team;
    }

    private static Human human(long id, long typeId) {
        Human human = new Human();
        human.setId(id);
        human.setTeamId(10L);
        human.setTypeId(typeId);
        return human;
    }

    private static Human manager(long id) {
        Human manager = human(id, TypeNames.MANAGER_TYPE);
        manager.setName("Current Manager");
        manager.setAge(45);
        manager.setContractEndSeason(5);
        manager.setWage(25_000L);
        return manager;
    }

    private static Human player(long id, int age) {
        Human player = human(id, TypeNames.PLAYER_TYPE);
        player.setAge(age);
        return player;
    }

    private static PersonProfile profile(long id, CareerType type) {
        PersonProfile profile = new PersonProfile();
        profile.setId(id);
        profile.setCareerType(type);
        return profile;
    }

    private static GameCalendar calendar(int season, int day, String phase) {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(season);
        calendar.setCurrentDay(day);
        calendar.setCurrentPhase(phase);
        return calendar;
    }

    private static List<CalendarEntryView> upcomingFixtures(int count) {
        List<CalendarEntryView> result = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            CalendarEntryView entry = new CalendarEntryView();
            entry.setCompetitionId(i % 2 == 0 ? 22L : 23L);
            entry.setCompetitionName(i % 2 == 0 ? "Real League" : "Cup");
            entry.setSeasonNumber(3);
            entry.setRoundNumber(i);
            entry.setTeamId1(10L);
            entry.setTeamId2(100L + i);
            entry.setOpponentTeamId(100L + i);
            entry.setOpponentTeamName("Opponent " + i);
            entry.setHomeOrAway("H");
            entry.setDay(i);
            entry.setDateDisplay("Day " + i);
            entry.setStatus("upcoming");
            result.add(entry);
        }
        return result;
    }

    private static Injury injury(long playerId) {
        Injury injury = new Injury();
        injury.setPlayerId(playerId);
        injury.setTeamId(10L);
        injury.setDaysRemaining(3);
        return injury;
    }

    private static Suspension suspension(long playerId) {
        Suspension suspension = new Suspension();
        suspension.setPlayerId(playerId);
        suspension.setTeamId(10L);
        suspension.setActive(true);
        return suspension;
    }

    private static FinancialRecord financial(int day, long amount) {
        FinancialRecord record = new FinancialRecord();
        record.setTeamId(10L);
        record.setSeasonNumber(3);
        record.setDay(day);
        record.setAmount(amount);
        return record;
    }
}
