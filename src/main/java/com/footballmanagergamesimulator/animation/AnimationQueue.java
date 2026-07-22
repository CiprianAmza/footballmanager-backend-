package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered animation queue for one match — the v2 replacement for the lossy
 * {@code Map<Integer, GoalAnimationData>} boundary. Entries are keyed by
 * {@link AnimationKey} (fixtureKey + slotIndex) and played in
 * {@code (minute, slotIndex)} order, so two goals in the same minute remain
 * two distinct animations shown back-to-back. Minutes are never shifted.
 *
 * <p>Re-adding the same key replaces that entry (idempotent regeneration);
 * it can never overwrite a different canonical event.
 */
public class AnimationQueue {

    private static final Comparator<AnimationReplay> PLAYBACK_ORDER =
            Comparator.comparingInt(AnimationReplay::minute)
                    .thenComparingInt(AnimationReplay::slotIndex);

    private final Map<AnimationKey, AnimationReplay> byKey = new LinkedHashMap<>();

    public void enqueue(AnimationReplay replay) {
        Objects.requireNonNull(replay, "replay");
        AnimationReplay existing = byKey.get(replay.key());
        if (existing != null && (!sameCanonicalFacts(existing, replay)
                || existing.fingerprint() != replay.fingerprint())) {
            throw new IllegalArgumentException("animation identity " + replay.key()
                    + " was reused with different canonical facts or frames");
        }
        byKey.put(replay.key(), replay);
    }

    public AnimationReplay get(AnimationKey key) {
        return byKey.get(key);
    }

    public int size() {
        return byKey.size();
    }

    /** Playback order: minute ascending, then slotIndex ascending. */
    public List<AnimationReplay> ordered() {
        List<AnimationReplay> list = new ArrayList<>(byKey.values());
        list.sort(PLAYBACK_ORDER);
        return List.copyOf(list);
    }

    /** All animations for a given displayed minute, in slotIndex order. */
    public List<AnimationReplay> atMinute(int minute) {
        return ordered().stream().filter(r -> r.minute() == minute).toList();
    }

    private static boolean sameCanonicalFacts(AnimationReplay a, AnimationReplay b) {
        return a.minute() == b.minute()
                && a.firstHalfStoppage() == b.firstHalfStoppage()
                && a.scoringTeamId() == b.scoringTeamId()
                && a.defendingTeamId() == b.defendingTeamId()
                && a.homeTeamId() == b.homeTeamId()
                && a.phase() == b.phase()
                && a.outcome() == b.outcome()
                && a.scorerId() == b.scorerId()
                && Objects.equals(a.assisterId(), b.assisterId());
    }
}
