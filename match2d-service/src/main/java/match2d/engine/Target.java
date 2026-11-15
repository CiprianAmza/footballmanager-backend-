package match2d.engine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scorul tinta al unui meci (campul {@code target} al cererii, doar pe drumul nou): scorul il
 * hotaraste altcineva, motorul joaca un meci care se termina exact asa.
 *
 * <p>{@code score} = golurile din cele 90 de minute; {@code extraTime} (optional) = golurile marcate in
 * prelungiri, prezent = se joaca prelungiri; {@code penalties} (optional, cere {@code extraTime}) =
 * scorul loviturilor de departajare. {@code maxTries}: cate seminte se incearca (implicit 64, cel
 * mult 512). Cum e condus meciul: {@link Sim#guideGoal}.
 */
public final class Target {
    public static final int DEFAULT_TRIES = 64;
    public static final int MAX_TRIES = 512;
    /** cate seminte se incearca pentru departajare (fara a rejuca meciul), la fiecare incercare a meciului. */
    static final int SHOOTOUT_SEEDS = 4096;

    final int[] score;
    final int[] extraTime;
    final int[] penalties;
    public final int maxTries;

    private Target(int[] score, int[] extraTime, int[] penalties, int maxTries) {
        this.score = score;
        this.extraTime = extraTime;
        this.penalties = penalties;
        this.maxTries = maxTries;
    }

    /** Citeste campul {@code target}: null daca lipseste; forma gresita: IllegalArgumentException. */
    public static Target parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("\"target\" trebuie sa fie un obiect {\"score\": [gazde, oaspeti], ...}");
        }
        int[] score = pair(map.get("score"), "score");
        if (score == null) {
            throw new IllegalArgumentException("\"target.score\" lipseste");
        }
        int[] et = pair(map.get("extraTime"), "extraTime");
        int[] pen = pair(map.get("penalties"), "penalties");
        if (pen != null && et == null) {
            throw new IllegalArgumentException("\"target.penalties\" cere si \"target.extraTime\"");
        }
        if (pen != null && !shootoutPossible(pen[0], pen[1])) {
            throw new IllegalArgumentException("\"target.penalties\" " + pen[0] + "-" + pen[1]
                    + " nu poate fi scorul unei departajari (5 lovituri, apoi cate una)");
        }
        int tries = DEFAULT_TRIES;
        Object mt = map.get("maxTries");
        if (mt != null) {
            if (!(mt instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1) {
                throw new IllegalArgumentException("\"target.maxTries\" trebuie sa fie un intreg >= 1");
            }
            tries = (int) Math.min(MAX_TRIES, n.doubleValue());
        }
        return new Target(score, et, pen, tries);
    }

    private static int[] pair(Object v, String name) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> list) || list.size() != 2 || !(list.get(0) instanceof Number a)
                || !(list.get(1) instanceof Number b) || a.doubleValue() < 0 || b.doubleValue() < 0
                || a.doubleValue() != Math.rint(a.doubleValue()) || b.doubleValue() != Math.rint(b.doubleValue())
                || a.doubleValue() > 99 || b.doubleValue() > 99) {
            throw new IllegalArgumentException("\"target." + name + "\" trebuie sa fie [gazde, oaspeti], intregi 0..99");
        }
        return new int[] {a.intValue(), b.intValue()};
    }

    /** Forma canonica (pentru id-ul meciului). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", List.of(score[0], score[1]));
        if (extraTime != null) {
            m.put("extraTime", List.of(extraTime[0], extraTime[1]));
        }
        if (penalties != null) {
            m.put("penalties", List.of(penalties[0], penalties[1]));
        }
        m.put("maxTries", maxTries);
        return m;
    }

    // ------------------------------------------------------------------ departajarea
    private static Set<Long> finals;

    /** Scorul (a, b) poate iesi dintr-o departajare (cu oricare echipa la prima lovitura)? */
    static synchronized boolean shootoutPossible(int a, int b) {
        if (finals == null) {
            finals = new HashSet<>();
            for (int first = 0; first < 2; first++) {
                walk(first, new int[2], new int[2], new HashSet<>());
            }
        }
        return finals.contains(((long) a << 32) | b);
    }

    private static void walk(int first, int[] taken, int[] scored, Set<Long> seen) {
        long key = ((long) taken[0] << 48) | ((long) taken[1] << 32) | ((long) scored[0] << 16) | scored[1];
        if (!seen.add(key) || taken[0] > 60 || taken[1] > 60) {
            return;
        }
        if (Shootout.decided(taken, scored)) {
            finals.add(((long) scored[0] << 32) | scored[1]);
            return;
        }
        int ti = taken[0] == taken[1] ? first : 1 - first;
        for (int goal = 0; goal < 2; goal++) {
            int[] t2 = taken.clone();
            int[] s2 = scored.clone();
            t2[ti]++;
            s2[ti] += goal;
            walk(first, t2, s2, seen);
        }
    }
}
