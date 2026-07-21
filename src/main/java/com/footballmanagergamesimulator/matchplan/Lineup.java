package com.footballmanagergamesimulator.matchplan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One team's on-pitch timeline for a match: the starting eleven plus an ordered
 * list of substitutions. {@link #onPitchAt(int)} returns exactly who is playing
 * at a given minute, which is what the {@link ContributionResolver} needs so a
 * goal is credited only to a player actually on the pitch then.
 *
 * <p>Instant/AI path: the substitutions are pre-simulated and deterministic.
 * Live path: the same structure is fed from the user's real substitutions, so a
 * subbed-off player can no longer score after his exit minute.
 */
public class Lineup {

    /** A substitution: {@code on} replaces {@code offPlayerId} at {@code minute}. */
    public record SubMove(int minute, long offPlayerId, Contributor on) {}

    private final List<Contributor> startingXI;
    private final List<SubMove> subs; // assumed sorted by minute

    public Lineup(List<Contributor> startingXI, List<SubMove> subs) {
        this.startingXI = startingXI != null ? startingXI : List.of();
        this.subs = subs != null ? new ArrayList<>(subs) : new ArrayList<>();
        this.subs.sort((a, b) -> Integer.compare(a.minute(), b.minute()));
    }

    public List<Contributor> getStartingXI() { return startingXI; }
    public List<SubMove> getSubs() { return subs; }

    /** Players on the pitch at {@code minute}: starters minus those subbed off by
     *  then, plus substitutes brought on by then. Insertion order preserved. */
    public List<Contributor> onPitchAt(int minute) {
        Map<Long, Contributor> onPitch = new LinkedHashMap<>();
        for (Contributor c : startingXI) onPitch.put(c.playerId(), c);
        for (SubMove sub : subs) {
            if (sub.minute() > minute) break; // subs are sorted
            onPitch.remove(sub.offPlayerId());
            onPitch.put(sub.on().playerId(), sub.on());
        }
        return new ArrayList<>(onPitch.values());
    }
}
