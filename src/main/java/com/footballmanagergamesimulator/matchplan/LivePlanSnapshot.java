package com.footballmanagergamesimulator.matchplan;

import java.util.ArrayList;
import java.util.List;

/**
 * A fully-detached view of a live match plan for recovery (browser refresh or
 * server restart mid-playback). Built entirely inside the service transaction —
 * every lazy JPA collection (goal slots, participants, substitutions) is copied
 * into plain records here — so callers can read the goal schedule, the resolved
 * score-so-far, the kickoff squads and the substitutions after the transaction
 * has closed, with no {@code LazyInitializationException}.
 *
 * <p>The live engine uses this to: consume the fixed goal minutes/sides, recover
 * the already-resolved goals (which must NOT be replayed), and rebuild the current
 * on-pitch set from the kickoff snapshot + substitutions via
 * {@link LiveLineupFactory#rebuild}.
 */
public record LivePlanSnapshot(
        String fixtureKey,
        long seed,
        long homeTeamId,
        long awayTeamId,
        MatchPlan.Status status,
        int durationMinutes,
        int homeShootout,
        int awayShootout,
        List<SlotView> slots,
        List<ParticipantView> participants,
        List<SubView> substitutions) {

    /** True when the plan carries a shootout result (knockout decided on penalties). */
    public boolean hadShootout() { return homeShootout >= 0 && awayShootout >= 0; }

    /** Extra-time goal slots for a side, in slot order (minutes 91-120). */
    public List<SlotView> extraTimeSlots(long teamId) {
        List<SlotView> out = new ArrayList<>();
        for (SlotView s : slots) {
            if (s.teamId() == teamId && s.phase() == GoalPhase.EXTRA_TIME) out.add(s);
        }
        return out;
    }

    /** True when the plan schedules any extra-time goal slot (duration extends to 120'). */
    public boolean hadExtraTime() { return durationMinutes > 90; }

    /** One goal slot's fixed schedule + its resolution state. */
    public record SlotView(int slotIndex, long teamId, int minute, GoalPhase phase,
                           String goalType, boolean resolved, Long scorerId, Long assistId) {}

    /** One kickoff squad member (with the resolver snapshot as a {@link Contributor}). */
    public record ParticipantView(long teamId, int participantIndex, boolean starter, Contributor contributor) {}

    /** One recorded substitution (per-team consecutive {@code subIndex}). */
    public record SubView(long teamId, int subIndex, int minute, long offPlayerId, long onPlayerId) {}

    /** Regular-time goal slots for a side, in slot order — the minutes the live
     *  narration must land goals on for that team. */
    public List<SlotView> regularTimeSlots(long teamId) {
        List<SlotView> out = new ArrayList<>();
        for (SlotView s : slots) {
            if (s.teamId() == teamId && s.phase() == GoalPhase.REGULAR_TIME) out.add(s);
        }
        return out;
    }

    /** Starters for a team, in participant-index order. */
    public List<Contributor> starters(long teamId) {
        return squad(teamId, true);
    }

    /** Bench for a team, in participant-index order. */
    public List<Contributor> bench(long teamId) {
        return squad(teamId, false);
    }

    private List<Contributor> squad(long teamId, boolean starter) {
        List<ParticipantView> ps = new ArrayList<>();
        for (ParticipantView p : participants) {
            if (p.teamId() == teamId && p.starter() == starter) ps.add(p);
        }
        ps.sort((a, b) -> Integer.compare(a.participantIndex(), b.participantIndex()));
        List<Contributor> out = new ArrayList<>();
        for (ParticipantView p : ps) out.add(p.contributor());
        return out;
    }

    /** Substitutions recorded so far for a team, in {@code subIndex} order. */
    public List<LiveLineupFactory.SubRecord> subs(long teamId) {
        List<LiveLineupFactory.SubRecord> out = new ArrayList<>();
        for (SubView s : substitutions) {
            if (s.teamId() == teamId) {
                out.add(new LiveLineupFactory.SubRecord(s.subIndex(), s.minute(),
                        s.offPlayerId(), s.onPlayerId()));
            }
        }
        return out;
    }

    /** Rebuild the current canonical {@link Lineup} for a team from the kickoff
     *  snapshot + the team's substitutions so far. */
    public Lineup rebuildLineup(long teamId) {
        return LiveLineupFactory.rebuild(starters(teamId), bench(teamId), subs(teamId));
    }

    /** Goals already resolved for a team (recovered score-so-far; never replayed). */
    public int resolvedGoals(long teamId) {
        int n = 0;
        for (SlotView s : slots) if (s.teamId() == teamId && s.resolved()) n++;
        return n;
    }
}
