package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.EuropeanQualificationPolicy;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the exact domestic league zones shown in the frontend for one season. */
@Service
public class LeagueQualificationDisplayService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionHistoryRepository competitionHistoryRepository;
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository;
    private final CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    private final TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    private final TeamRepository teamRepository;
    private final RoundRepository roundRepository;
    private final EuropeanCoefficientService coefficientService;
    private final EuropeanQualificationPolicy qualificationPolicy;

    public LeagueQualificationDisplayService(
            CompetitionRepository competitionRepository,
            CompetitionHistoryRepository competitionHistoryRepository,
            CompetitionTeamInfoRepository competitionTeamInfoRepository,
            CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository,
            TeamCompetitionDetailRepository teamCompetitionDetailRepository,
            TeamRepository teamRepository,
            RoundRepository roundRepository,
            EuropeanCoefficientService coefficientService,
            EuropeanQualificationPolicy qualificationPolicy) {
        this.competitionRepository = competitionRepository;
        this.competitionHistoryRepository = competitionHistoryRepository;
        this.competitionTeamInfoRepository = competitionTeamInfoRepository;
        this.competitionTeamInfoDetailRepository = competitionTeamInfoDetailRepository;
        this.teamCompetitionDetailRepository = teamCompetitionDetailRepository;
        this.teamRepository = teamRepository;
        this.roundRepository = roundRepository;
        this.coefficientService = coefficientService;
        this.qualificationPolicy = qualificationPolicy;
    }

    @Transactional(readOnly = true)
    public LeagueQualificationContext context(long leagueId, long season) {
        Competition selectedLeague = competitionRepository.findById(leagueId)
                .filter(Competition::isLeague)
                .orElseThrow(() -> new IllegalArgumentException("Competition " + leagueId + " is not a league"));

        List<Competition> competitions = competitionRepository.findAll();
        boolean hasLowerTier = competitions.stream().anyMatch(candidate -> candidate.isLeague()
                && candidate.getNationId() == selectedLeague.getNationId()
                && candidate.getTier() == selectedLeague.getTier() + 1);
        List<Long> selectedStandings = standings(selectedLeague.getId(), season);
        Integer relegationFrom = hasLowerTier && selectedStandings.size() >= 2
                ? selectedStandings.size() - 1 : null;

        Competition topFlight = competitions.stream()
                .filter(candidate -> candidate.isTopFlight()
                        && candidate.getNationId() == selectedLeague.getNationId())
                .min(Comparator.comparingLong(Competition::getId))
                .orElse(selectedLeague.isTopFlight() ? selectedLeague : null);
        List<Long> topFlightStandings = topFlight == null
                ? List.of() : standings(topFlight.getId(), season);

        int associationRank = topFlight == null ? 0
                : coefficientService.getLeagueIdsSortedByCoefficient().indexOf(topFlight.getId()) + 1;
        int locSpots = associationRank >= 1 && associationRank <= 7
                ? qualificationPolicy.totalForRank(associationRank) : 0;
        int starsCupLeagueSpots = associationRank >= 1 && associationRank <= 4 ? 1 : 0;

        Map<Long, TeamQualification> qualificationByTeam = new LinkedHashMap<>();
        int position = 1;
        for (Long teamId : topFlightStandings) {
            if (position > locSpots) break;
            qualificationByTeam.put(teamId,
                    new TeamQualification(teamId, "LOC", "LEAGUE_POSITION", position));
            position++;
        }

        int leagueStarsGiven = 0;
        for (int index = 0; index < topFlightStandings.size() && leagueStarsGiven < starsCupLeagueSpots; index++) {
            long teamId = topFlightStandings.get(index);
            if (qualificationByTeam.containsKey(teamId)) continue;
            qualificationByTeam.put(teamId,
                    new TeamQualification(teamId, "STARS_CUP", "LEAGUE_POSITION", index + 1));
            leagueStarsGiven++;
        }

        Competition cup = competitions.stream()
                .filter(candidate -> candidate.getTypeId() == Competition.CUP
                        && candidate.getNationId() == selectedLeague.getNationId())
                .min(Comparator.comparingInt(Competition::getTier)
                        .thenComparingLong(Competition::getId))
                .orElse(null);
        Long cupWinnerTeamId = cup == null ? null : resolveCupWinner(cup.getId(), season);
        String cupWinnerTeamName = cupWinnerTeamId == null ? null
                : teamRepository.findById(cupWinnerTeamId).map(team -> team.getName())
                    .orElse("Team #" + cupWinnerTeamId);

        if (cupWinnerTeamId != null && !qualificationByTeam.containsKey(cupWinnerTeamId)) {
            qualificationByTeam.put(cupWinnerTeamId,
                    new TeamQualification(cupWinnerTeamId, "STARS_CUP", "CUP_WINNER", null));
        } else if (cupWinnerTeamId != null) {
            for (int index = 0; index < topFlightStandings.size(); index++) {
                long teamId = topFlightStandings.get(index);
                if (qualificationByTeam.containsKey(teamId)) continue;
                qualificationByTeam.put(teamId,
                        new TeamQualification(teamId, "STARS_CUP", "CUP_REALLOCATION", index + 1));
                break;
            }
        }

        String cupWinnerRoute = cupWinnerTeamId == null ? null
                : qualificationByTeam.get(cupWinnerTeamId).route();
        int configuredStarsCupSpots = associationRank >= 1 && associationRank <= 7 && cup != null
                ? starsCupLeagueSpots + 1 : starsCupLeagueSpots;

        return new LeagueQualificationContext(
                selectedLeague.getId(), season, hasLowerTier, relegationFrom,
                locSpots, configuredStarsCupSpots,
                cup == null ? null : cup.getId(), cup == null ? null : cup.getName(),
                cupWinnerTeamId, cupWinnerTeamName, cupWinnerRoute,
                new ArrayList<>(qualificationByTeam.values())
        );
    }

    private List<Long> standings(long competitionId, long season) {
        List<CompetitionHistory> history = competitionHistoryRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .filter(row -> row.getLastPosition() > 0)
                .sorted(Comparator.comparingLong(CompetitionHistory::getLastPosition))
                .toList();
        if (!history.isEmpty()) {
            return history.stream().map(CompetitionHistory::getTeamId).toList();
        }
        if (season != currentSeason()) return List.of();
        return teamCompetitionDetailRepository.findAllByCompetitionId(competitionId).stream()
                .sorted(Comparator.comparingInt(TeamCompetitionDetail::getPoints).reversed()
                        .thenComparing(Comparator.comparingInt(TeamCompetitionDetail::getGoalDifference).reversed())
                        .thenComparing(Comparator.comparingInt(TeamCompetitionDetail::getGoalsFor).reversed())
                        .thenComparingLong(TeamCompetitionDetail::getTeamId))
                .map(TeamCompetitionDetail::getTeamId)
                .toList();
    }

    private Long resolveCupWinner(long cupCompetitionId, long season) {
        Long snapshotWinner = competitionHistoryRepository
                .findAllByCompetitionIdAndSeasonNumber(cupCompetitionId, season).stream()
                .filter(history -> history.getLastPosition() == 1)
                .min(Comparator.comparingLong(CompetitionHistory::getId))
                .map(CompetitionHistory::getTeamId)
                .orElse(null);
        if (snapshotWinner != null) return snapshotWinner;
        if (season != currentSeason()) return null;

        long entrantCount = competitionTeamInfoRepository
                .findAllByCompetitionIdAndSeasonNumber(cupCompetitionId, season).stream()
                .map(entry -> entry.getTeamId())
                .distinct()
                .count();
        if (entrantCount < 2) return null;
        long finalRound = (long) Math.ceil(Math.log(entrantCount) / Math.log(2));

        return competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(cupCompetitionId, finalRound, season).stream()
                .filter(detail -> detail.getWinnerTeamId() != null)
                .filter(detail -> !"FIRST_LEG".equalsIgnoreCase(detail.getDecidedBy()))
                .max(Comparator.comparingInt(CompetitionTeamInfoDetail::getDay)
                        .thenComparingLong(CompetitionTeamInfoDetail::getId))
                .map(CompetitionTeamInfoDetail::getWinnerTeamId)
                .orElse(null);
    }

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L);
    }

    public record LeagueQualificationContext(
            long competitionId,
            long season,
            boolean hasLowerTier,
            Integer relegationFrom,
            int locSpots,
            int starsCupSpots,
            Long cupCompetitionId,
            String cupCompetitionName,
            Long cupWinnerTeamId,
            String cupWinnerTeamName,
            String cupWinnerRoute,
            List<TeamQualification> qualifications
    ) {}

    public record TeamQualification(
            long teamId,
            String route,
            String source,
            Integer leaguePosition
    ) {}
}
