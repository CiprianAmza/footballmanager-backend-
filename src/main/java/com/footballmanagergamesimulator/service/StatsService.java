package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionEntry;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerEntry;
import com.footballmanagergamesimulator.repository.HumanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    @Autowired
    HumanRepository humanRepository;

    public Map<Integer, ScorerEntry> getScorerEntriesFromScorers(List<Scorer> scorers) {

        Map<Integer, ScorerEntry> scorerEntryMap = new HashMap<>();
        for (Scorer scorer: scorers) {

            ScorerEntry scorerEntry = scorerEntryMap.getOrDefault(scorer.getSeasonNumber(), new ScorerEntry());
            scorerEntry.setSeasonNumber(scorer.getSeasonNumber());
            scorerEntry.setTeamId(scorer.getTeamId());
            scorerEntry.setTeamName(scorer.getTeamName());
            scorerEntry.setName(humanRepository.findById(scorer.getPlayerId()).get().getName());

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
