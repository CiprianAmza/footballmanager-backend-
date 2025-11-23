package com.footballmanagergamesimulator.frontend;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PlayerCompetitionWinnerLeaderboardView {

    private long playerId;
    private long teamId;
    private int competitionWins;
    private int championships;
    private int cups;
    private int totalPoints;

    private double rating;

    @JsonProperty("isActive")
    private boolean isActive;
}
