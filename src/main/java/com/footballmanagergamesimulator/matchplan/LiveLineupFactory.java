package com.footballmanagergamesimulator.matchplan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one team's canonical {@link Lineup} for a LIVE match from the user's
 * <em>actual</em> substitutions, so the shared {@link ContributionResolver}
 * credits each canonical goal to a player who was really on the pitch at that
 * minute. This is the live counterpart of the AI/instant path's pre-simulated
 * {@code Lineup}: no AI-invented substitutions are ever mixed in — only the moves
 * the user made are recorded.
 *
 * <p>The resulting {@link Lineup.SubMove}s carry a consecutive per-team sequence
 * (0,1,2…) in nondecreasing-minute order, which is the single ordering rule the
 * timeline validator, persistence and reload all agree on; appearances and minutes
 * played are then derived from exactly that timeline via {@link Lineup#appearances()}.
 */
public final class LiveLineupFactory {

    private LiveLineupFactory() {}

    /** One real substitution the user made during live play: {@code on} replaced
     *  {@code offPlayerId} at {@code minute}. */
    public record UserSub(long offPlayerId, Contributor on, int minute) {}

    /**
     * Assemble the canonical lineup. Substitutions are ordered by minute (stable, so
     * two subs at the same minute keep the order the user made them) and assigned a
     * consecutive sequence. Starters/bench are taken verbatim.
     */
    public static Lineup build(List<Contributor> startingXI, List<Contributor> bench,
                               List<UserSub> userSubs) {
        List<UserSub> ordered = new ArrayList<>(userSubs != null ? userSubs : List.of());
        // Stable sort: preserves the user's order for subs made in the same minute,
        // and guarantees nondecreasing minutes for the consecutive sequence.
        ordered.sort(Comparator.comparingInt(UserSub::minute));

        List<Lineup.SubMove> moves = new ArrayList<>();
        int sequence = 0;
        for (UserSub s : ordered) {
            moves.add(new Lineup.SubMove(sequence++, s.minute(), s.offPlayerId(), s.on()));
        }
        return new Lineup(startingXI, bench, moves);
    }

    /** A persisted substitution as reloaded for recovery: who went off/on and when,
     *  with the already-assigned per-team {@code subIndex}. */
    public record SubRecord(int subIndex, int minute, long offPlayerId, long onPlayerId) {}

    /**
     * Rebuild one team's canonical {@link Lineup} for recovery, from the persisted
     * kickoff snapshot (starters + bench, each with its fielded/natural position and
     * attributes) plus the substitutions recorded so far. The incoming player is
     * fielded in the tactical position currently occupied by the player he replaces
     * — so a substitute is weighted in the role he actually enters, and a chain of
     * subs into one slot keeps that slot's position. Substitutions are applied in
     * {@code subIndex} order (consecutive per team).
     */
    public static Lineup rebuild(List<Contributor> starters, List<Contributor> bench,
                                 List<SubRecord> subs) {
        Map<Long, Contributor> benchById = new HashMap<>();
        for (Contributor c : bench) benchById.put(c.playerId(), c);

        // Track the position currently held by each on-pitch player, seeded by the XI.
        Map<Long, String> heldPosition = new HashMap<>();
        for (Contributor c : starters) heldPosition.put(c.playerId(), c.position());

        List<SubRecord> ordered = new ArrayList<>(subs != null ? subs : List.of());
        ordered.sort(Comparator.comparingInt(SubRecord::subIndex));

        List<Lineup.SubMove> moves = new ArrayList<>();
        for (SubRecord s : ordered) {
            Contributor benchC = benchById.get(s.onPlayerId());
            if (benchC == null) continue; // defensive: unknown bench id
            String fielded = heldPosition.getOrDefault(s.offPlayerId(), benchC.position());
            Contributor entering = benchC.withPosition(fielded);
            moves.add(new Lineup.SubMove(s.subIndex(), s.minute(), s.offPlayerId(), entering));
            heldPosition.remove(s.offPlayerId());
            heldPosition.put(s.onPlayerId(), fielded);
        }
        return new Lineup(starters, bench, moves);
    }
}
