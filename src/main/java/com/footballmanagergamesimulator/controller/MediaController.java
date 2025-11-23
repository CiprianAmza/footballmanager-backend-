package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.ManagerBestTeamTacticView;
import com.footballmanagergamesimulator.frontend.MediaPredictionView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/media")
@CrossOrigin(origins = "*")
public class MediaController {

    @Autowired
    TacticController tacticController;

    @GetMapping("/mediaPrediction/{competitionId}")
    public List<MediaPredictionView> getMediaPrediction(@PathVariable(name = "competitionId") long competitionId) {

        List<MediaPredictionView> mediaPredictionViews = new ArrayList<>();
        List<ManagerBestTeamTacticView> managerBestTeamTacticViews = tacticController.getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic(competitionId, true);

        for (ManagerBestTeamTacticView managerBestTeamTacticView: managerBestTeamTacticViews) {

            String tactic = managerBestTeamTacticView.getManagerTeamTacticView().getTactic();
            List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(managerBestTeamTacticView.getManagerTeamTacticView().getTeamId()), tactic);

            MediaPredictionView mediaPredictionView = new MediaPredictionView();
            mediaPredictionView.setPlayerViews(playerViews);
            mediaPredictionView.setManagerTeamTacticView(managerBestTeamTacticView.getManagerTeamTacticView());

            mediaPredictionViews.add(mediaPredictionView);
        }

        return mediaPredictionViews;
    }
}
