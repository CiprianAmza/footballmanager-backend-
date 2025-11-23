package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class MediaPredictionView {

    private ManagerTeamTacticView managerTeamTacticView;
    private List<PlayerView> playerViews;
    private String managerName;
}
