package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class ManagerTeamTacticView {

    private String managerName;
    private String teamName;

    private double tacticRating;
    private String tactic;
}
