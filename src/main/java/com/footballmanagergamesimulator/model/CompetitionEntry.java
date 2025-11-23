package com.footballmanagergamesimulator.model;

import lombok.Data;

@Data
public class CompetitionEntry {

    private int id;

    private String competitionName;
    private long competitionId;
    private long competitionTypeId;
    private int games;
    private int gamesAsSubstitute;
    private int goals;
}
