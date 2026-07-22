package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collision-safe frontend playback boundary. */
public final class AnimationQueue {
    private static final Comparator<AnimationReplay> ORDER =
            Comparator.comparingInt(AnimationReplay::minute).thenComparingInt(AnimationReplay::slotIndex);
    private final Map<AnimationKey, AnimationReplay> entries = new LinkedHashMap<>();

    public void enqueue(AnimationReplay replay) {
        Objects.requireNonNull(replay, "replay");
        AnimationReplay existing = entries.get(replay.key());
        if (existing != null
                && (!sameCanonicalFacts(existing, replay) || existing.fingerprint() != replay.fingerprint())) {
            throw new IllegalArgumentException("canonical identity reused with a different replay: " + replay.key());
        }
        entries.put(replay.key(), replay);
    }

    private static boolean sameCanonicalFacts(AnimationReplay left, AnimationReplay right) {
        return left.minute() == right.minute()
                && left.firstHalfStoppage() == right.firstHalfStoppage()
                && left.scoringTeamId() == right.scoringTeamId()
                && left.defendingTeamId() == right.defendingTeamId()
                && left.homeTeamId() == right.homeTeamId()
                && left.phase() == right.phase()
                && left.outcome() == right.outcome()
                && left.period() == right.period()
                && left.pattern() == right.pattern()
                && left.renderedWithVersion() == right.renderedWithVersion()
                && left.scorerId() == right.scorerId()
                && Objects.equals(left.assisterId(), right.assisterId())
                && left.homeAttacksRight() == right.homeAttacksRight()
                && left.scoringTeamAttacksRight() == right.scoringTeamAttacksRight()
                && left.players().equals(right.players())
                && left.events().equals(right.events());
    }

    public int size() {
        return entries.size();
    }

    public AnimationReplay get(AnimationKey key) {
        return entries.get(key);
    }

    public List<AnimationReplay> ordered() {
        List<AnimationReplay> result = new ArrayList<>(entries.values());
        result.sort(ORDER);
        return List.copyOf(result);
    }

    public List<AnimationReplay> atMinute(int minute) {
        return ordered().stream().filter(replay -> replay.minute() == minute).toList();
    }
}
