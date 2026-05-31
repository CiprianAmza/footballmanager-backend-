package com.footballmanagergamesimulator.model;

import lombok.Data;

/**
 * One row of a per-competition (and per-season) breakdown for a team. Unlike the
 * mixed totals exposed elsewhere, each line is scoped to a single
 * (competition, season) so League / Cup / LoC / Stars Cup never get summed together.
 * {@code leaguePosition} is populated only on league-type lines (typeId 1 or 3).
 */
@Data
public class CompetitionStatLine {

    private long competitionId;
    private int competitionTypeId;
    private String competitionName;
    private int seasonNumber;

    private int matches;
    private int wins;
    private int draws;
    private int losses;
    private int goalsFor;
    private int goalsAgainst;

    private Integer leaguePosition; // null on non-league lines

    public CompetitionStatLine() {
    }

    public CompetitionStatLine(long competitionId, int competitionTypeId, String competitionName, int seasonNumber) {
        this.competitionId = competitionId;
        this.competitionTypeId = competitionTypeId;
        this.competitionName = competitionName;
        this.seasonNumber = seasonNumber;
    }
}
