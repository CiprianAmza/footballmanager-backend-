package com.footballmanagergamesimulator.frontend;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PlayerCompetitionWinnerView {

    private long playerId;
    private long teamId;
    private long competitionId;
    private long seasonNumber;
}
