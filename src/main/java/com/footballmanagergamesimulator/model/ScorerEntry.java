package com.footballmanagergamesimulator.model;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.util.List;

@Data
public class ScorerEntry {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private String name;

    private long teamId;
    private String teamName;
    private int seasonNumber;

    private List<CompetitionEntry> competitionEntries;

    private int totalGames;
    private int totalGamesAsSubstitute;
    private int totalGoals;
}
