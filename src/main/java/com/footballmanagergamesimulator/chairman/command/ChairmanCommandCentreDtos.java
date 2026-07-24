package com.footballmanagergamesimulator.chairman.command;

import com.footballmanagergamesimulator.economy.ClubDtos;
import com.footballmanagergamesimulator.economy.EconomyDtos;

import java.util.List;

public final class ChairmanCommandCentreDtos {
    private ChairmanCommandCentreDtos() { }

    public record CommandCentreView(long teamId, String teamName, String color1, String color2,
                                    StadiumView stadium, CompetitionView primaryCompetition,
                                    ManagerView manager, StaffSummary staff,
                                    StandingView standing, List<String> recentForm,
                                    List<FixtureView> nextFixtures, SquadSummary squad,
                                    FinanceSummary finances, OwnershipSummary ownership,
                                    int season, int currentDay, String currentPhase) {
        public CommandCentreView {
            recentForm = List.copyOf(recentForm == null ? List.of() : recentForm);
            nextFixtures = List.copyOf(nextFixtures == null ? List.of() : nextFixtures);
        }
    }

    public record StadiumView(String name, int capacity) { }

    public record CompetitionView(long competitionId, String competitionName,
                                  long competitionTypeId) { }

    public record ManagerView(long managerId, String managerName, int age,
                              int contractEndSeason, long wage) { }

    public record StaffSummary(int managers, int coaches, int scouts, int totalStaff) { }

    public record StandingView(int position, int totalTeams, int games, int wins, int draws,
                               int losses, int goalsFor, int goalsAgainst,
                               int goalDifference, int points) { }

    public record FixtureView(long competitionId, String competitionName, int seasonNumber,
                              int roundNumber, long teamId1, long teamId2, long opponentTeamId,
                              String opponentTeamName, String homeOrAway, int day,
                              String dateDisplay, String status) { }

    public record SquadSummary(int playerCount, double averageAge, int injuredPlayers,
                               int suspendedPlayers) { }

    public record FinanceSummary(ClubDtos.ValuationView valuation,
                                 ClubDtos.TreasuryView treasury, long transferBudget,
                                 long wageBudget, long recentIncome, long recentExpenses) { }

    public record OwnershipSummary(long principalProfileId, long shares, int stakeBps,
                                   EconomyDtos.Money equityValue, boolean controlled) { }
}
