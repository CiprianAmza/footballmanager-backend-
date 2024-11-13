package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class ManagerBestTeamTacticView {

    private ManagerTeamTacticView managerTeamTacticView;
    private double bestPossibleTacticRating;
    private String bestPossibleTacticName;
}
