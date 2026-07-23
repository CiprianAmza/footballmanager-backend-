package com.footballmanagergamesimulator.press;

import com.footballmanagergamesimulator.model.PressConferenceType;
import com.footballmanagergamesimulator.press.catalog.PressCatalogAnswer;
import com.footballmanagergamesimulator.press.catalog.PressCatalogQuestion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, context-driven press-conference question generator.
 *
 * <p>Given a frozen {@link PressContext} snapshot and a seed, it always produces
 * the same 3–6 ordered, non-duplicate questions with the same answer options.
 * It reads no live game state — purity is what makes refresh/restart safe and
 * retries idempotent. No LLM is involved.</p>
 */
@Service
public class PressConferenceGenerator {

    private static final int MIN_QUESTIONS = 3;
    private static final int MAX_QUESTIONS = 6;

    @Autowired
    PressConferenceCatalogService catalog;

    /**
     * Stable 64-bit seed derived from the identity of the conference. FNV-1a over
     * a canonical string so it is reproducible across JVMs and databases.
     */
    public static long deterministicSeed(String generatorVersion, PressConferenceType type, String fixtureKey,
                                         long teamId, long opponentId, int season, int day) {
        String canonical = generatorVersion + "|" + type + "|" + fixtureKey + "|"
                + teamId + "|" + opponentId + "|" + season + "|" + day;
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (int i = 0; i < canonical.length(); i++) {
            hash ^= canonical.charAt(i);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    /**
     * Select and freeze the questions for a session. Pure function of
     * (type, context, seed, catalog).
     */
    public List<PressGeneratedQuestion> generate(PressConferenceType type, PressContext context, long seed) {
        List<PressCatalogQuestion> candidates = eligibleCandidates(type, context);
        // Stable ordering is the catalog order (questionsOfType preserves it).
        Random rng = new Random(seed);

        int desired = MIN_QUESTIONS + rng.nextInt(MAX_QUESTIONS - MIN_QUESTIONS + 1); // 3..6
        int target = Math.min(desired, candidates.size());

        List<PressCatalogQuestion> remaining = new ArrayList<>(candidates);
        List<PressGeneratedQuestion> selected = new ArrayList<>();
        java.util.Set<String> usedContextKeys = new java.util.HashSet<>();

        while (selected.size() < target && !remaining.isEmpty()) {
            PressCatalogQuestion picked = weightedPick(remaining, rng);
            remaining.remove(picked);
            // De-duplicate on context key: at most one question per context.
            if (!usedContextKeys.add(nullSafe(picked.getContextKey()))) {
                continue;
            }
            selected.add(freeze(picked, context));
        }
        return selected;
    }

    private List<PressCatalogQuestion> eligibleCandidates(PressConferenceType type, PressContext context) {
        List<PressCatalogQuestion> out = new ArrayList<>();
        for (PressCatalogQuestion q : catalog.questionsOfType(type.name())) {
            String ck = nullSafe(q.getContextKey());
            boolean contextPresent = "GENERIC".equals(ck) || context.hasKey(ck);
            if (!contextPresent) continue;
            if (q.getEligibility() != null && !q.getEligibility().matches(context.getContextKeys())) continue;
            out.add(q);
        }
        return out;
    }

    /** Deterministic weighted pick over the remaining candidates using {@code rng}. */
    private PressCatalogQuestion weightedPick(List<PressCatalogQuestion> remaining, Random rng) {
        int total = 0;
        for (PressCatalogQuestion q : remaining) total += Math.max(1, q.getWeight());
        int roll = rng.nextInt(total);
        int acc = 0;
        for (PressCatalogQuestion q : remaining) {
            acc += Math.max(1, q.getWeight());
            if (roll < acc) return q;
        }
        return remaining.get(remaining.size() - 1); // unreachable
    }

    private PressGeneratedQuestion freeze(PressCatalogQuestion q, PressContext context) {
        List<PressGeneratedAnswer> answers = new ArrayList<>();
        for (PressCatalogAnswer a : q.getAnswers()) {
            if (a.getEligibility() != null && !a.getEligibility().matches(context.getContextKeys())) continue;
            answers.add(new PressGeneratedAnswer(a.getId(), a.getCode(), a.getTone(), a.getStance(), a.getEffects()));
        }
        return new PressGeneratedQuestion(q.getId(), q.getContextKey(), template(q.getPrompt(), context), answers);
    }

    private String template(String prompt, PressContext c) {
        if (prompt == null) return "";
        String s = prompt;
        s = s.replace("{opponentName}", c.getOpponentName() == null ? "your opponents" : c.getOpponentName());
        s = s.replace("{competitionName}", c.getCompetitionName() == null ? "the competition" : c.getCompetitionName());
        s = s.replace("{teamScore}", String.valueOf(c.getTeamScore()));
        s = s.replace("{opponentScore}", String.valueOf(c.getOpponentScore()));
        return s;
    }

    private String nullSafe(String s) {
        return s == null ? "GENERIC" : s;
    }
}
