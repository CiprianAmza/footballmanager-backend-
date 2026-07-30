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
    /** Optional match-only designation. V1 accepts only SHOOTER and at most one starter may have it. */
    private String specialRole;
    /**
     * Match-only SHADOW designation. Unlike SHOOTER it is not unique and may coexist with it.
     * Players whose persistent {@code Human.stayForward} flag is true are forced to shadow=true
     * by the server, so the manager can see but cannot remove their behaviour.
     */
    private boolean shadow;
}
