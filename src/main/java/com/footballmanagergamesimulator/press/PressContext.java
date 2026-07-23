package com.footballmanagergamesimulator.press;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable context snapshot captured when a session is created. The generator
 * reads ONLY this snapshot (plus the seed and catalog) — never live game state —
 * so replaying the same snapshot + seed reproduces the same questions and
 * answers exactly. Serialized to JSON in
 * {@code PressConferenceSession.contextSnapshot}.
 *
 * <p>{@link #contextKeys} is the eligibility surface (e.g. "DERBY", "HOME",
 * "RESULT_WIN"); the remaining fields are display/telemetry values frozen for
 * prompt templating and post-match reporting.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressContext {

    /** Eligibility keys that drive deterministic question selection. */
    private Set<String> contextKeys = new LinkedHashSet<>();

    private String opponentName;
    private String competitionName;

    // Squad strength
    private long squadValue;
    private long opponentSquadValue;

    // Standings / form
    private int leaguePosition;
    private int opponentLeaguePosition;
    private String form; // e.g. "WWDLL"

    // Post-match facts (0 for pre-match)
    private int teamScore;
    private int opponentScore;
    private double teamXg;
    private double opponentXg;
    private int shots;
    private int opponentShots;
    private int possession; // 0-100

    public boolean hasKey(String key) {
        return contextKeys.contains(key);
    }

    public void addKey(String key) {
        if (key != null && !key.isBlank()) contextKeys.add(key);
    }
}
