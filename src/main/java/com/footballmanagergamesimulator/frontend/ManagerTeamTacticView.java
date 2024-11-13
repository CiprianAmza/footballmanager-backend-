package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class ManagerTeamTacticView {

    private String managerName;
    private long managerId;
    private String teamName;
    private long teamId;

    private double tacticRating;
    private String tactic;
}
