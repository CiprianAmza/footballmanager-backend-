package com.footballmanagergamesimulator.util;

import java.util.Locale;
import java.util.Set;

/**
 * Defaults used only when a new AI manager is created. The value is then stored
 * on the manager and can be changed independently by the Federation Editor.
 */
public final class ManagerTacticPolicy {

    private static final Set<String> DEFAULT_BEST_TACTIC_CLUBS = Set.of(
            "shadows",
            "tik tok",
            "inazuma",
            "inazuma japan",
            "fc san marino");

    private ManagerTacticPolicy() {
    }

    public static boolean defaultsToBestPossibleTactic(String teamName) {
        if (teamName == null) return false;
        return DEFAULT_BEST_TACTIC_CLUBS.contains(teamName.trim().toLowerCase(Locale.ROOT));
    }
}
