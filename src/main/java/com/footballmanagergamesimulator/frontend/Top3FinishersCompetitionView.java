package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class Top3FinishersCompetitionView {

    private long competitionId;
    private long seasonNumber;
    private String teamPlace1;
    private String teamPlace2;
    private String teamPlace3;
}
