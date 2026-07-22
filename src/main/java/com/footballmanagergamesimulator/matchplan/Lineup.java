package com.footballmanagergamesimulator.matchplan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One team's on-pitch timeline for a match: the starting eleven, the bench, plus
 * an ordered list of substitutions. {@link #onPitchAt(int)} returns exactly who
 * is playing at a given minute, which is what the {@link ContributionResolver}
 * needs so a goal is credited only to a player actually on the pitch then.
 *
 * <p>Instant/AI path: the substitutions are pre-simulated and deterministic.
 * Live path: the same structure is fed from the user's real substitutions, so a
 * subbed-off player can no longer score after his exit minute.
 */
public class Lineup {

    /**
     * A substitution: {@code on} replaces {@code offPlayerId} at {@code minute}.
     * {@code sequence} fixes the order of substitutions made at the SAME minute
     * (0,1,2… as decided), so the timeline is fully determined and not left to
     * sort stability at persist time.
     */
    public record SubMove(int sequence, int minute, long offPlayerId, Contributor on) {}

    /**
     * A player's spell on the pitch. Starters start at 0; a substitute at his
     * entry minute. {@code exitMinute} is null when the player finished the match
     * (so he counts as on the pitch right up to and including the final minute —
     * a goal at 90' or 120' is his); otherwise it is his substitution minute.
     */
    public record Appearance(long playerId, int startMinute, Integer exitMinute) {
        public boolean onPitchAt(int minute) {
            return minute >= startMinute && (exitMinute == null || minute < exitMinute);
        }
        public int minutesPlayed(int duration) {
            return Math.max(0, (exitMinute != null ? exitMinute : duration) - startMinute);
        }
    }

    private final List<Contributor> startingXI;
    private final List<Contributor> bench;
    private final List<SubMove> subs; // assumed sorted by minute

    public Lineup(List<Contributor> startingXI, List<SubMove> subs) {
        this(startingXI, List.of(), subs);
    }

    public Lineup(List<Contributor> startingXI, List<Contributor> bench, List<SubMove> subs) {
        // Defensive, immutable copies so callers cannot mutate the lineup after construction.
        this.startingXI = startingXI != null ? List.copyOf(startingXI) : List.of();
        this.bench = bench != null ? List.copyOf(bench) : List.of();
        // Single ordering rule: by sequence (consecutive per team, minutes
        // non-decreasing), so execution and reload agree.
        List<SubMove> sorted = subs != null ? new ArrayList<>(subs) : new ArrayList<>();
        sorted.sort(Comparator.comparingInt(SubMove::sequence));
        this.subs = List.copyOf(sorted);
    }

    public List<Contributor> getStartingXI() { return startingXI; }
    public List<Contributor> getBench() { return bench; }
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

    /**
     * The canonical appearance timeline: one {@link Appearance} per player who
     * took part (starters + substitutes brought on), with minutes played. Derived
     * from the SAME starters + subs the resolver reads, so it can never disagree
     * with {@link #onPitchAt(int)}. A finisher gets {@code exitMinute == null}.
     */
    public List<Appearance> appearances() {
        Map<Long, Integer[]> span = new LinkedHashMap<>(); // playerId -> [start, exit(null=finished)]
        for (Contributor c : startingXI) span.put(c.playerId(), new Integer[]{0, null});
        for (SubMove sub : subs) {
            Integer[] off = span.get(sub.offPlayerId());
            if (off != null) off[1] = sub.minute();          // subbed off at this minute
            span.put(sub.on().playerId(), new Integer[]{sub.minute(), null});
        }
        List<Appearance> out = new ArrayList<>();
        span.forEach((id, s) -> out.add(new Appearance(id, s[0], s[1])));
        return out;
    }
}
