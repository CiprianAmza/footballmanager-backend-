package com.footballmanagergamesimulator.frontend;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class FormationData {

    private int positionIndex;
    private long playerId;
    private String role;   // e.g. "Advanced Forward", "Ball-Winning Midfielder"
    private String duty;   // "Attack", "Support", "Defend"
    private List<String> instructions; // e.g. ["Mark Tighter", "Shoot More Often", "Get Further Forward"]
}
