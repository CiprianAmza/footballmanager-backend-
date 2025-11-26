package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.PlayerCompetitionWinnerLeaderboardView;
import com.footballmanagergamesimulator.frontend.PlayerCompetitionWinnerView;
import com.footballmanagergamesimulator.frontend.Top3FinishersCompetitionView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/history")
@CrossOrigin(origins = "*")
public class HistoryController {

    @Autowired
    private CompetitionHistoryRepository competitionHistoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;

    @Autowired
    private CompetitionRepository competitionRepository;

    @Autowired
    private HumanRepository humanRepository;

    private static final List<Long> TOP_THREE_POSITIONS = List.of(1L, 2L, 3L);

    @GetMapping("/top3Finishers/{competitionId}")
    public List<Top3FinishersCompetitionView> getTop3FinishersByCompetitionIdInHistory(@PathVariable(name = "competitionId") long competitionId) {

        List<Top3FinishersCompetitionView> top3FinishersList = new ArrayList<>();

        List<CompetitionHistory> competitionHistories = competitionHistoryRepository
                .findByCompetitionId(competitionId)
                .stream()
                .filter(competitionHistory -> TOP_THREE_POSITIONS.contains(competitionHistory.getLastPosition()))
                .sorted((o1, o2) -> {
                    if (o1.getSeasonNumber() != o2.getSeasonNumber())
                        return o1.getSeasonNumber() < o2.getSeasonNumber() ? -1 : 1;
                    else {
                        if (o1.getLastPosition() < o2.getLastPosition())
                            return -1;
                        return 0;
                    }
                }).toList();

        for (int i = 2; i < competitionHistories.size(); i += 3) {

            Top3FinishersCompetitionView top3FinishersCompetitionView = new Top3FinishersCompetitionView();
            top3FinishersCompetitionView.setCompetitionId(competitionId);
            top3FinishersCompetitionView.setSeasonNumber(competitionHistories.get(i).getSeasonNumber());
            top3FinishersCompetitionView.setTeamPlace1(teamRepository.findNameById(competitionHistories.get(i - 2).getTeamId()));
            top3FinishersCompetitionView.setTeamPlace2(teamRepository.findNameById(competitionHistories.get(i - 1).getTeamId()));
            top3FinishersCompetitionView.setTeamPlace3(teamRepository.findNameById(competitionHistories.get(i).getTeamId()));

            top3FinishersList.add(top3FinishersCompetitionView);
        }

        return top3FinishersList;
    }

    @GetMapping("/playerCompetitionWins/{playerId}")
    public Set<PlayerCompetitionWinnerView> getPlayerCompetitionWinsByPlayerId(@PathVariable(name = "playerId") long playerId) {

        List<TeamPlayerHistoricalRelation> allTeamPlayerRelations = teamPlayerHistoricalRelationRepository.findByPlayerId(playerId);
        Set<PlayerCompetitionWinnerView> playerCompetitionWinnerViews = new HashSet<>();

        for (TeamPlayerHistoricalRelation teamPlayerHistoricalRelation : allTeamPlayerRelations) {

            long teamId = teamPlayerHistoricalRelation.getTeamId();
            long seasonNumber = teamPlayerHistoricalRelation.getSeasonNumber();
            List<CompetitionHistory> competitionHistoryEntries = competitionHistoryRepository.findByTeamIdAndSeasonNumber(teamId, seasonNumber);

            for (CompetitionHistory competitionHistory : competitionHistoryEntries) {
                if (competitionHistory.getLastPosition() == 1) {

                    long competitionId = competitionHistory.getCompetitionId();
                    PlayerCompetitionWinnerView playerCompetitionWinnerView = new PlayerCompetitionWinnerView();
                    playerCompetitionWinnerView.setSeasonNumber(seasonNumber);
                    playerCompetitionWinnerView.setPlayerId(playerId);
                    playerCompetitionWinnerView.setTeamId(teamId);
                    playerCompetitionWinnerView.setCompetitionId(competitionId);

                    playerCompetitionWinnerViews.add(playerCompetitionWinnerView);
                }
            }
        }

        return playerCompetitionWinnerViews;
    }

    @GetMapping("/teamCompetitionWins/{teamId}")
    public List<CompetitionHistory> getTeamCompetitionWinsByTeamId(@PathVariable(name = "teamId") long teamId) {

        return competitionHistoryRepository.findByTeamId(teamId);
    }

    @GetMapping("/playerCompetitionWins/leaderboard")
    public Map<Long, PlayerCompetitionWinnerLeaderboardView> getPlayerCompetitionWinsByAllPlayers() {

        List<TeamPlayerHistoricalRelation> allTeamPlayerRelations = teamPlayerHistoricalRelationRepository.findAll();
        Map<Long, List<TeamPlayerHistoricalRelation>> playerIdToTeamPlayerHistoricalRelation = new HashMap<>();

        for (TeamPlayerHistoricalRelation teamPlayerHistoricalRelation : allTeamPlayerRelations) {
            List<TeamPlayerHistoricalRelation> relations = playerIdToTeamPlayerHistoricalRelation.getOrDefault(teamPlayerHistoricalRelation.getPlayerId(), new ArrayList<>());
            relations.add(teamPlayerHistoricalRelation);
            playerIdToTeamPlayerHistoricalRelation.put(teamPlayerHistoricalRelation.getPlayerId(), relations);
        }

        Map<Long, PlayerCompetitionWinnerLeaderboardView> leaderboardViewMap = new HashMap<>();

        for (Long playerId : playerIdToTeamPlayerHistoricalRelation.keySet()) {

            List<TeamPlayerHistoricalRelation> relations = playerIdToTeamPlayerHistoricalRelation.get(playerId);

            PlayerCompetitionWinnerLeaderboardView leaderboardView = leaderboardViewMap.getOrDefault(playerId, new PlayerCompetitionWinnerLeaderboardView());

            Optional<Human> player = humanRepository.findById(playerId);
            player.ifPresent(human -> leaderboardView.setActive(!human.isRetired()));


            for (TeamPlayerHistoricalRelation relation : relations) {

                long teamId = relation.getTeamId();
                long seasonNumber = relation.getSeasonNumber();

                leaderboardView.setRating(relation.getRating());
                List<CompetitionHistory> competitionHistoryEntries = competitionHistoryRepository.findByTeamIdAndSeasonNumber(teamId, seasonNumber);

                for (CompetitionHistory competitionHistory : competitionHistoryEntries) {
                    if (competitionHistory.getLastPosition() == 1) {
                        leaderboardView.setCompetitionWins(leaderboardView.getCompetitionWins() + 1);

                        Optional<Competition> competition = competitionRepository.findById(competitionHistory.getCompetitionId());
                        if (competition.isPresent()) {
                            Competition comp = competition.get();
                            if (comp.getTypeId() == 1) {
                                leaderboardView.setChampionships(leaderboardView.getChampionships() + 1);
                                if (comp.getId() == 1 || comp.getId() == 3)
                                    leaderboardView.setTotalPoints(leaderboardView.getTotalPoints() + 50);
                                else
                                    leaderboardView.setTotalPoints(leaderboardView.getTotalPoints() + 5);
                            } else if (comp.getTypeId() == 2) {
                                leaderboardView.setCups(leaderboardView.getCups() + 1);
                                leaderboardView.setTotalPoints(leaderboardView.getTotalPoints() + 20);
                            }
                        }
                    }
                }
            }

            leaderboardViewMap.put(playerId, leaderboardView);
        }

        return leaderboardViewMap;
    }
}


