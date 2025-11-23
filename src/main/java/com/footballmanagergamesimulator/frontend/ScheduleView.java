package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class ScheduleView {

    private String opponentTeam;
    private String homeOrAway;
    private String competitionName;
    private String score;
    private String date;
}
