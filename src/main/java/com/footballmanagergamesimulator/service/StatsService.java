package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionEntry;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StatsService {

    @Autowired
    HumanRepository humanRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    TeamPlayerHistoricalRelationRepository careerRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionHistoryRepository competitionHistoryRepository;

    /** Include roster seasons even when the player did not make a recorded appearance. */
    @Transactional(readOnly = true)
    public Map<Integer, ScorerEntry> getPlayerCareerEntries(long playerId) {
        Map<Integer, ScorerEntry> entries = getScorerEntriesFromScorers(scorerRepository.findAllByPlayerId(playerId));
        Map<Integer, TeamPlayerHistoricalRelation> latestClubBySeason = new HashMap<>();
        for (TeamPlayerHistoricalRelation relation : careerRepository.findByPlayerId(playerId)) {
            int season = Math.toIntExact(relation.getSeasonNumber());
            latestClubBySeason.merge(season, relation,
                    (first, second) -> first.getId() >= second.getId() ? first : second);
        }
        Set<Long> missingClubIds = latestClubBySeason.entrySet().stream()
                .filter(entry -> !entries.containsKey(entry.getKey()))
                .map(entry -> entry.getValue().getTeamId())
                .collect(Collectors.toSet());
        if (missingClubIds.isEmpty()) return entries;

        Map<Long, Team> clubsById = new HashMap<>();
        teamRepository.findAllById(missingClubIds).forEach(team -> clubsById.put(team.getId(), team));
        Map<Integer, List<CompetitionHistory>> clubCompetitionsBySeason = new HashMap<>();
        for (CompetitionHistory history : competitionHistoryRepository.findAllByTeamIdIn(missingClubIds)) {
            int season = Math.toIntExact(history.getSeasonNumber());
            TeamPlayerHistoricalRelation roster = latestClubBySeason.get(season);
            if (roster == null || roster.getTeamId() != history.getTeamId() || entries.containsKey(season)) continue;
            clubCompetitionsBySeason.computeIfAbsent(season, ignored -> new ArrayList<>()).add(history);
        }
        String playerName = entries.values().stream().findFirst().map(ScorerEntry::getName)
                .orElseGet(() -> humanRepository.findById(playerId).map(Human::getName).orElse("Unknown"));
        for (Map.Entry<Integer, TeamPlayerHistoricalRelation> season : latestClubBySeason.entrySet()) {
            if (entries.containsKey(season.getKey())) continue;
            TeamPlayerHistoricalRelation relation = season.getValue();
            Team club = clubsById.get(relation.getTeamId());
            ScorerEntry empty = new ScorerEntry();
            empty.setName(playerName);
            empty.setSeasonNumber(season.getKey());
            empty.setTeamId(relation.getTeamId());
            empty.setTeamName(club == null ? "Team #" + relation.getTeamId() : club.getName());
            empty.setCompetitionEntries(new ArrayList<>());
            for (CompetitionHistory competition : clubCompetitionsBySeason.getOrDefault(season.getKey(), List.of())) {
                if (competition.getCompetitionTypeId() == 1 || competition.getCompetitionTypeId() == 3)
                    empty.setSeasonLeagueName(competition.getCompetitionName());
                else if (competition.getCompetitionTypeId() == 2)
                    empty.setSeasonCupName(competition.getCompetitionName());
            }
            entries.put(season.getKey(), empty);
        }
        return entries;
    }

    public Map<Integer, ScorerEntry> getScorerEntriesFromScorers(List<Scorer> scorers) {

        Map<Integer, ScorerEntry> scorerEntryMap = new HashMap<>();
        String playerName = scorers.isEmpty() ? "Unknown" : humanRepository.findById(scorers.get(0).getPlayerId())
                .map(Human::getName).orElse("Unknown");
        for (Scorer scorer: scorers) {

            ScorerEntry scorerEntry = scorerEntryMap.getOrDefault(scorer.getSeasonNumber(), new ScorerEntry());
            scorerEntry.setSeasonNumber(scorer.getSeasonNumber());
            scorerEntry.setTeamId(scorer.getTeamId());
            scorerEntry.setTeamName(scorer.getTeamName());
            scorerEntry.setName(playerName);

            CompetitionEntry competitionEntry = new CompetitionEntry();
            if (scorerEntry.getCompetitionEntries() == null)
                scorerEntry.setCompetitionEntries(new ArrayList<>());
            boolean isNew = true;
            for (CompetitionEntry competitionEntry1: scorerEntry.getCompetitionEntries()) {
                if (competitionEntry1.getCompetitionId() == scorer.getCompetitionId()) {
                    competitionEntry = competitionEntry1;
                    isNew = false;
                }
            }

            competitionEntry.setCompetitionId(scorer.getCompetitionId());
            competitionEntry.setCompetitionName(scorer.getCompetitionName());
            competitionEntry.setCompetitionTypeId(scorer.getCompetitionTypeId());
            if (scorer.getTeamScore() >= 0) { // if -1, then it did not play, just default set
                if (scorer.isSubstitute())
                    competitionEntry.setGamesAsSubstitute(competitionEntry.getGamesAsSubstitute() + 1);
                else
                    competitionEntry.setGames(competitionEntry.getGames() + 1);
            }
            competitionEntry.setGoals(competitionEntry.getGoals() + scorer.getGoals());
            competitionEntry.setAssists(competitionEntry.getAssists() + scorer.getAssists());
            if (scorer.getTeamScore() >= 0) {
                competitionEntry.setTotalRating(competitionEntry.getTotalRating() + scorer.getRating());
                int totalApps = competitionEntry.getGames() + competitionEntry.getGamesAsSubstitute();
                if (totalApps > 0) {
                    competitionEntry.setAvgRating(Math.round(competitionEntry.getTotalRating() / totalApps * 10.0) / 10.0);
                }
            }

            if (isNew) {
                scorerEntry.getCompetitionEntries().add(competitionEntry);
            }

            if (scorer.getTeamScore() >= 0) {
                if (scorer.isSubstitute())
                    scorerEntry.setTotalGamesAsSubstitute(scorerEntry.getTotalGamesAsSubstitute() + 1);
                else
                    scorerEntry.setTotalGames(scorerEntry.getTotalGames() + 1);
            }
            scorerEntry.setTotalGoals(scorerEntry.getTotalGoals() + scorer.getGoals());
            scorerEntry.setTotalAssists(scorerEntry.getTotalAssists() + scorer.getAssists());

            // Calculate overall avg rating
            int totalApps = scorerEntry.getTotalGames() + scorerEntry.getTotalGamesAsSubstitute();
            double totalRating = scorerEntry.getCompetitionEntries().stream()
                    .mapToDouble(CompetitionEntry::getTotalRating).sum();
            if (totalApps > 0) {
                scorerEntry.setAvgRating(Math.round(totalRating / totalApps * 10.0) / 10.0);
            }

            scorerEntryMap.put(scorerEntry.getSeasonNumber(), scorerEntry);
        }

        return scorerEntryMap;
    }
}
