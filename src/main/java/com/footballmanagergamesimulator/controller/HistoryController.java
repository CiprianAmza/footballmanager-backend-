package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.Top3FinishersCompetitionView;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/history")
@CrossOrigin(origins = "*")
public class HistoryController {

    @Autowired
    private CompetitionHistoryRepository competitionHistoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    private static final List<Long> TOP_THREE_POSITIONS = List.of(1L, 2L, 3L);

    @GetMapping("/top3Finishers/{competitionId}")
    public List<Top3FinishersCompetitionView>  getTop3FinishersByCompetitionIdInHistory(@PathVariable(name="competitionId") long competitionId) {

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
                            return o1.getLastPosition() < o2.getLastPosition() ? -1 : 1;
                        return 0;
                    }
                }).toList();

        for (int i = 2; i < competitionHistories.size(); i += 3) {

            Top3FinishersCompetitionView top3FinishersCompetitionView = new Top3FinishersCompetitionView();
            top3FinishersCompetitionView.setCompetitionId(competitionId);
            top3FinishersCompetitionView.setSeasonNumber(competitionHistories.get(i).getSeasonNumber());
            top3FinishersCompetitionView.setTeamPlace1(teamRepository.findNameById(competitionHistories.get(i-2).getTeamId()));
            top3FinishersCompetitionView.setTeamPlace2(teamRepository.findNameById(competitionHistories.get(i-1).getTeamId()));
            top3FinishersCompetitionView.setTeamPlace3(teamRepository.findNameById(competitionHistories.get(i).getTeamId()));

            top3FinishersList.add(top3FinishersCompetitionView);
        }

        return top3FinishersList;
    }
}
