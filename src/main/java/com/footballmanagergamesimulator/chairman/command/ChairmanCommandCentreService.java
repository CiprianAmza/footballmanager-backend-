package com.footballmanagergamesimulator.chairman.command;

import com.footballmanagergamesimulator.economy.ClubDtos;
import com.footballmanagergamesimulator.economy.ClubQueryService;
import com.footballmanagergamesimulator.economy.EconomyConflictException;
import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.Scout;
import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamDataHubStats;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ChairmanCommandCentreService {
    private final ClubQueryService clubQueryService;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final StadiumRepository stadiumRepository;
    private final HumanRepository humanRepository;
    private final ScoutRepository scoutRepository;
    private final InjuryRepository injuryRepository;
    private final SuspensionRepository suspensionRepository;
    private final GameCalendarRepository gameCalendarRepository;
    private final CompetitionTeamInfoMatchRepository matchRepository;
    private final FinancialRecordRepository financialRecordRepository;
    private final CompetitionDisplayService competitionDisplayService;
    private final MatchService matchService;
    private final StatsAggregationService statsAggregationService;

    public ChairmanCommandCentreService(ClubQueryService clubQueryService,
                                        TeamRepository teamRepository,
                                        CompetitionRepository competitionRepository,
                                        StadiumRepository stadiumRepository,
                                        HumanRepository humanRepository,
                                        ScoutRepository scoutRepository,
                                        InjuryRepository injuryRepository,
                                        SuspensionRepository suspensionRepository,
                                        GameCalendarRepository gameCalendarRepository,
                                        CompetitionTeamInfoMatchRepository matchRepository,
                                        FinancialRecordRepository financialRecordRepository,
                                        CompetitionDisplayService competitionDisplayService,
                                        MatchService matchService,
                                        StatsAggregationService statsAggregationService) {
        this.clubQueryService = clubQueryService;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
        this.stadiumRepository = stadiumRepository;
        this.humanRepository = humanRepository;
        this.scoutRepository = scoutRepository;
        this.injuryRepository = injuryRepository;
        this.suspensionRepository = suspensionRepository;
        this.gameCalendarRepository = gameCalendarRepository;
        this.matchRepository = matchRepository;
        this.financialRecordRepository = financialRecordRepository;
        this.competitionDisplayService = competitionDisplayService;
        this.matchService = matchService;
        this.statsAggregationService = statsAggregationService;
    }

    @Transactional(readOnly = true)
    public ChairmanCommandCentreDtos.CommandCentreView commandCentre(long teamId,
                                                                      PersonProfile principal) {
        // This must remain the first operation: it is the canonical Chairman,
        // club-existence, control, valuation and treasury gate.
        ClubDtos.Dashboard dashboard = clubQueryService.dashboard(teamId, principal);

        GameCalendar calendar = gameCalendarRepository.findTopByOrderBySeasonDesc()
                .orElseThrow(() -> new EconomyConflictException("GAME_STATE_UNAVAILABLE",
                        "The global game calendar is unavailable"));
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
        List<Human> humans = humanRepository.findAllByTeamId(teamId);
        List<Human> players = humans.stream()
                .filter(human -> human.getTypeId() == TypeNames.PLAYER_TYPE)
                .toList();

        return new ChairmanCommandCentreDtos.CommandCentreView(
                team.getId(), team.getName(), team.getColor1(), team.getColor2(),
                stadium(team), competition(team), manager(humans), staff(humans, teamId),
                standing(team), recentForm(teamId, calendar.getSeason()),
                nextFixtures(teamId, calendar), squad(players, teamId),
                finances(team, dashboard, calendar), ownership(principal, dashboard),
                calendar.getSeason(), calendar.getCurrentDay(), calendar.getCurrentPhase());
    }

    private ChairmanCommandCentreDtos.StadiumView stadium(Team team) {
        Stadium stadium = stadiumRepository.findByTeamId(team.getId()).orElse(null);
        if (stadium == null) {
            return new ChairmanCommandCentreDtos.StadiumView(
                    validName(team.getStadiumName()), Math.max(0, team.getStadiumCapacity()));
        }
        String name = validName(stadium.getStadiumName());
        if (name == null) name = validName(team.getStadiumName());
        return new ChairmanCommandCentreDtos.StadiumView(name,
                Math.max(0, stadium.getEffectiveCapacity()));
    }

    private ChairmanCommandCentreDtos.CompetitionView competition(Team team) {
        return competitionRepository.findById(team.getCompetitionId())
                .map(value -> new ChairmanCommandCentreDtos.CompetitionView(
                        value.getId(), value.getName(), value.getTypeId()))
                .orElse(null);
    }

    private ChairmanCommandCentreDtos.ManagerView manager(List<Human> humans) {
        return humans.stream()
                .filter(human -> human.getTypeId() == TypeNames.MANAGER_TYPE)
                .sorted(Comparator.comparingLong(Human::getId))
                .findFirst()
                .map(value -> new ChairmanCommandCentreDtos.ManagerView(value.getId(), value.getName(),
                        value.getAge(), value.getContractEndSeason(), value.getWage()))
                .orElse(null);
    }

    private ChairmanCommandCentreDtos.StaffSummary staff(List<Human> humans, long teamId) {
        int managers = (int) humans.stream()
                .filter(human -> human.getTypeId() == TypeNames.MANAGER_TYPE).count();
        int coaches = (int) humans.stream()
                .filter(human -> TypeNames.isCoachType(human.getTypeId())).count();
        int scouts = scoutRepository.findAllByTeamId(teamId).size();
        return new ChairmanCommandCentreDtos.StaffSummary(managers, coaches, scouts,
                managers + coaches + scouts);
    }

    private ChairmanCommandCentreDtos.StandingView standing(Team team) {
        return competitionDisplayService.getTeamCompetitions(team.getId()).stream()
                .filter(value -> number(value.get("competitionId")) == team.getCompetitionId())
                .filter(value -> value.containsKey("position"))
                .findFirst()
                .map(value -> new ChairmanCommandCentreDtos.StandingView(
                        integer(value.get("position")), integer(value.get("totalTeams")),
                        integer(value.get("games")), integer(value.get("wins")),
                        integer(value.get("draws")), integer(value.get("loses")),
                        integer(value.get("goalsFor")), integer(value.get("goalsAgainst")),
                        integer(value.get("goalDifference")), integer(value.get("points"))))
                .orElse(null);
    }

    private List<String> recentForm(long teamId, int season) {
        TeamDataHubStats stats = statsAggregationService.getTeamDataHubStats(teamId, season);
        if (stats == null || stats.getRecentForm() == null) return List.of();
        return stats.getRecentForm().stream()
                .filter(value -> Objects.equals(value, "W") || Objects.equals(value, "D")
                        || Objects.equals(value, "L"))
                .limit(5)
                .toList();
    }

    private List<ChairmanCommandCentreDtos.FixtureView> nextFixtures(long teamId,
                                                                       GameCalendar calendar) {
        List<CompetitionTeamInfoMatch> matches = matchRepository.findAllBySeasonNumberAndTeamId(
                String.valueOf(calendar.getSeason()), teamId);
        return matchService.getCalendarEntries(matches, teamId, calendar.getSeason()).stream()
                .filter(value -> "upcoming".equalsIgnoreCase(value.getStatus()))
                .filter(value -> value.getDay() >= calendar.getCurrentDay())
                .sorted(Comparator.comparingInt(com.footballmanagergamesimulator.frontend.CalendarEntryView::getDay)
                        .thenComparingLong(com.footballmanagergamesimulator.frontend.CalendarEntryView::getCompetitionId)
                        .thenComparingInt(com.footballmanagergamesimulator.frontend.CalendarEntryView::getRoundNumber)
                        .thenComparingLong(com.footballmanagergamesimulator.frontend.CalendarEntryView::getTeamId1)
                        .thenComparingLong(com.footballmanagergamesimulator.frontend.CalendarEntryView::getTeamId2))
                .limit(5)
                .map(value -> new ChairmanCommandCentreDtos.FixtureView(value.getCompetitionId(),
                        value.getCompetitionName(), value.getSeasonNumber(), value.getRoundNumber(),
                        value.getTeamId1(), value.getTeamId2(), value.getOpponentTeamId(),
                        value.getOpponentTeamName(), value.getHomeOrAway(), value.getDay(),
                        value.getDateDisplay(), value.getStatus()))
                .toList();
    }

    private ChairmanCommandCentreDtos.SquadSummary squad(List<Human> players, long teamId) {
        double averageAge = players.stream().mapToInt(Human::getAge).average().orElse(0d);
        averageAge = BigDecimal.valueOf(averageAge).setScale(2, RoundingMode.HALF_UP).doubleValue();
        Set<Long> playerIds = players.stream().map(Human::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> injured = injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0).stream()
                .map(Injury::getPlayerId).filter(playerIds::contains).collect(HashSet::new, Set::add, Set::addAll);
        Set<Long> suspended = suspensionRepository.findAllByTeamIdAndActive(teamId, true).stream()
                .map(Suspension::getPlayerId).filter(playerIds::contains).collect(HashSet::new, Set::add, Set::addAll);
        return new ChairmanCommandCentreDtos.SquadSummary(players.size(), averageAge,
                injured.size(), suspended.size());
    }

    private ChairmanCommandCentreDtos.FinanceSummary finances(Team team, ClubDtos.Dashboard dashboard,
                                                                GameCalendar calendar) {
        int fromDay = Math.max(1, calendar.getCurrentDay() - 29);
        long income = 0;
        long expenses = 0;
        for (com.footballmanagergamesimulator.model.FinancialRecord record
                : financialRecordRepository.findAllByTeamIdAndSeasonNumber(team.getId(), calendar.getSeason())) {
            if (record.getDay() < fromDay || record.getDay() > calendar.getCurrentDay()) continue;
            if (record.getAmount() > 0) income = Math.addExact(income, record.getAmount());
            if (record.getAmount() < 0) expenses = Math.addExact(expenses, -record.getAmount());
        }
        return new ChairmanCommandCentreDtos.FinanceSummary(dashboard.valuation(), dashboard.treasury(),
                team.getTransferBudget(), team.getSalaryBudget(), income, expenses);
    }

    private ChairmanCommandCentreDtos.OwnershipSummary ownership(PersonProfile principal,
                                                                  ClubDtos.Dashboard dashboard) {
        List<ClubDtos.HoldingView> controllingHoldings = dashboard.capTable().holdings().stream()
                .filter(ClubDtos.HoldingView::controlling).toList();
        if (controllingHoldings.size() != 1) {
            throw new EconomyConflictException("CAP_TABLE_INVALID",
                    "Controlled holding is missing or ambiguous");
        }
        ClubDtos.HoldingView holding = controllingHoldings.get(0);
        return new ChairmanCommandCentreDtos.OwnershipSummary(principal.getId(), holding.quantity(),
                holding.stakeBps(), holding.equityValue(), true);
    }

    private static String validName(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
