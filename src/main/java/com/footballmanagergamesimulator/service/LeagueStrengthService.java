package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Ranks domestic championships by the average strength of every club's best XI. */
@Service
public class LeagueStrengthService {

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private AwardWeightingConfig weightingConfig;

    public LeagueStrengthTable calculate(int season) {
        Map<Long, Competition> leagues = competitionRepository.findAll().stream()
                .filter(competition -> competition.getTypeId() == 1 || competition.getTypeId() == 3)
                .collect(Collectors.toMap(Competition::getId, competition -> competition));

        Map<Long, Set<Long>> teamIdsByLeague = new HashMap<>();
        for (CompetitionTeamInfo membership : competitionTeamInfoRepository.findAllBySeasonNumber(season)) {
            if (!leagues.containsKey(membership.getCompetitionId())) continue;
            teamIdsByLeague.computeIfAbsent(membership.getCompetitionId(), ignored -> new LinkedHashSet<>())
                    .add(membership.getTeamId());
        }

        Set<Long> allTeamIds = teamIdsByLeague.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Team> teams = teamRepository.findAllById(allTeamIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        Map<Long, List<Human>> playersByTeam = allTeamIds.isEmpty() ? Map.of()
                : humanRepository.findAllByTeamIdInAndTypeId(allTeamIds, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .collect(Collectors.groupingBy(player -> player.getTeamId() == null ? 0L : player.getTeamId()));

        int topPlayers = Math.max(1, weightingConfig.getLeagueStrength().getTopPlayersPerTeam());
        Map<Long, Double> teamRatings = new HashMap<>();
        Map<Long, Integer> ratedPlayersByTeam = new HashMap<>();
        for (long teamId : allTeamIds) {
            List<Double> ratings = playersByTeam.getOrDefault(teamId, List.of()).stream()
                    .map(Human::getRating)
                    .sorted(Comparator.reverseOrder())
                    .limit(topPlayers)
                    .toList();
            ratedPlayersByTeam.put(teamId, ratings.size());
            teamRatings.put(teamId, ratings.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
        }

        List<UnrankedLeague> unranked = new ArrayList<>();
        for (Competition league : leagues.values()) {
            Set<Long> leagueTeamIds = teamIdsByLeague.getOrDefault(league.getId(), Set.of());
            List<TeamStrength> teamStrengths = leagueTeamIds.stream()
                    .map(teamId -> new TeamStrength(teamId,
                            teams.containsKey(teamId) ? teams.get(teamId).getName() : "Unknown",
                            round(teamRatings.getOrDefault(teamId, 0.0), 2),
                            ratedPlayersByTeam.getOrDefault(teamId, 0)))
                    .sorted(Comparator.comparingDouble(TeamStrength::topElevenRating).reversed()
                            .thenComparing(TeamStrength::teamName))
                    .toList();
            double championshipRating = teamStrengths.stream()
                    .mapToDouble(TeamStrength::topElevenRating)
                    .average().orElse(0.0);
            unranked.add(new UnrankedLeague(league, championshipRating, teamStrengths));
        }
        unranked.sort(Comparator.comparingDouble(UnrankedLeague::rating).reversed()
                .thenComparing(entry -> entry.competition().getName()));

        List<LeagueStrengthEntry> ranking = new ArrayList<>();
        Map<Long, Double> multiplierByCompetition = new LinkedHashMap<>();
        Map<Long, Double> multiplierByTeam = new LinkedHashMap<>();
        for (int index = 0; index < unranked.size(); index++) {
            UnrankedLeague row = unranked.get(index);
            int rank = index + 1;
            double multiplier = weightingConfig.getLeagueStrength().multiplierForRank(rank);
            LeagueStrengthEntry entry = new LeagueStrengthEntry(
                    rank,
                    row.competition().getId(),
                    row.competition().getName(),
                    (int) row.competition().getTypeId(),
                    round(row.rating(), 2),
                    multiplier,
                    row.teams().size(),
                    (int) row.teams().stream().filter(team -> team.ratedPlayerCount() >= topPlayers).count(),
                    row.teams());
            ranking.add(entry);
            multiplierByCompetition.put(entry.competitionId(), multiplier);
            for (TeamStrength team : row.teams()) {
                multiplierByTeam.merge(team.teamId(), multiplier, Math::max);
            }
        }

        List<RankMultiplierTier> tiers = new TreeMap<>(weightingConfig.getLeagueStrength().getRankMultipliers())
                .entrySet().stream()
                .map(entry -> new RankMultiplierTier(entry.getKey(), entry.getValue()))
                .toList();
        return new LeagueStrengthTable(
                season,
                topPlayers,
                weightingConfig.getLeagueStrength().getDefaultMultiplier(),
                tiers,
                ranking,
                multiplierByCompetition,
                multiplierByTeam);
    }

    private double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private record UnrankedLeague(Competition competition, double rating, List<TeamStrength> teams) {}

    public record RankMultiplierTier(int maximumRank, double multiplier) {}
    public record TeamStrength(long teamId, String teamName, double topElevenRating, int ratedPlayerCount) {}
    public record LeagueStrengthEntry(int rank, long competitionId, String competitionName,
                                      int competitionTypeId, double averageTopElevenRating,
                                      double multiplier, int teamCount, int completeTeamCount,
                                      List<TeamStrength> teams) {}
    public record LeagueStrengthTable(int season, int topPlayersPerTeam, double defaultMultiplier,
                                      List<RankMultiplierTier> tiers,
                                      List<LeagueStrengthEntry> ranking,
                                      Map<Long, Double> multiplierByCompetition,
                                      Map<Long, Double> multiplierByTeam) {
        public double competitionMultiplier(long competitionId) {
            return multiplierByCompetition.getOrDefault(competitionId, defaultMultiplier);
        }

        public double teamMultiplier(long teamId) {
            return multiplierByTeam.getOrDefault(teamId, defaultMultiplier);
        }
    }
}
