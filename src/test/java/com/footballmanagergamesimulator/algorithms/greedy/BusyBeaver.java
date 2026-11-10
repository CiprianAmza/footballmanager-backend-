package com.footballmanagergamesimulator.algorithms.greedy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Laborator pentru Codeforces 2257E "Busy Beaver" (Round 1117 Div. 2).
 * Contine: oracolul brute force (BFS pe vectorul de inaltimi), solutia rapida
 * in doua faze (segmente cu profit net >= 0 construite in ordinea pragului,
 * apoi extinderea independenta a fiecarei cladiri) si un runner care produce
 * toate trace-urile citate in manualul de explicatie tripla.
 */
public class BusyBeaver {

    /** Un proiect de cladire: a[j] = costul etajului j, b[j] = incasarea imediata. */
    static final class Project {
        final long[] a;
        final long[] b;
        Project(long[] a, long[] b) { this.a = a; this.b = b; }
        int floors() { return a.length; }
    }

    /**
     * Un segment de etaje consecutive [from, to) al unei cladiri, cu profit net >= 0.
     * threshold = capitalul minim la intrare ca soldul sa nu scada sub 0 pe parcurs;
     * profit = suma (b - a) pe segment (>= 0 prin constructie).
     */
    static final class Segment {
        final int building;
        final int from;
        final int to;
        final long threshold;
        final long profit;
        Segment(int building, int from, int to, long threshold, long profit) {
            this.building = building;
            this.from = from;
            this.to = to;
            this.threshold = threshold;
            this.profit = profit;
        }
        @Override public String toString() {
            return String.format("cladirea %d, etaje [%d..%d), prag=%d, profit=%d",
                    building + 1, from + 1, to + 1, threshold, profit);
        }
    }

    /**
     * Cel mai scurt segment cu profit net >= 0 care incepe la etajul {@code from}
     * (0-based); null daca niciun asemenea segment nu mai exista in cladire.
     * Orice prefix propriu al segmentului intors are sold strict negativ - altfel
     * bucla s-ar fi oprit mai devreme.
     */
    static Segment nextSegment(int building, Project p, int from) {
        long balance = 0;
        long worst = 0;
        for (int j = from; j < p.floors(); j++) {
            balance -= p.a[j];
            worst = Math.max(worst, -balance);
            balance += p.b[j];
            if (balance >= 0) {
                return new Segment(building, from, j + 1, worst, balance);
            }
        }
        return null;
    }

    /** Rezultatul solutiei: inaltimea maxima si indexul (1-based) al cladirii. */
    static final class Answer {
        final int height;
        final int index;
        Answer(int height, int index) { this.height = height; this.index = index; }
        @Override public String toString() { return height + " " + index; }
    }

    /**
     * Solutia rapida in doua faze.
     * Faza 1: PriorityQueue pe prag; cat timp segmentul cu pragul minim e accesibil,
     * il construim integral, incasam profitul si bagam urmatorul segment al cladirii.
     * Faza 2: din inaltimile Fazei 1, fiecare cladire e intinsa independent,
     * etaj cu etaj, cu capitalul maxim; banii NU se pastreaza intre incercari.
     */
    static Answer solveFast(long x, Project[] projects, StringBuilder trace) {
        int n = projects.length;
        int[] built = new int[n];
        PriorityQueue<Segment> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.threshold));
        for (int i = 0; i < n; i++) {
            Segment s = nextSegment(i, projects[i], 0);
            if (s != null) pq.add(s);
        }
        long money = x;
        while (!pq.isEmpty() && pq.peek().threshold <= money) {
            Segment s = pq.poll();
            money += s.profit;
            built[s.building] = s.to;
            if (trace != null) {
                trace.append(String.format("  construiesc %s -> bani %d%n", s, money));
            }
            Segment nxt = nextSegment(s.building, projects[s.building], s.to);
            if (nxt != null) pq.add(nxt);
        }
        if (trace != null && !pq.isEmpty()) {
            trace.append(String.format("  stop Faza 1: pragul minim ramas %d > bani %d%n",
                    pq.peek().threshold, money));
        }
        if (trace != null) {
            trace.append(String.format("  Faza 1 inchisa: bani=%d, inaltimi=%s%n",
                    money, Arrays.toString(built)));
        }
        int bestHeight = 0;
        int bestIndex = 1;
        for (int i = 0; i < n; i++) {
            Project p = projects[i];
            long m2 = money;
            int h = built[i];
            while (h < p.floors() && m2 >= p.a[h]) {
                m2 += p.b[h] - p.a[h];
                h++;
            }
            if (trace != null) {
                trace.append(String.format("  Faza 2, cladirea %d: %d -> %d etaje (bani finali simulati %d)%n",
                        i + 1, built[i], h, m2));
            }
            if (h > bestHeight) {
                bestHeight = h;
                bestIndex = i + 1;
            }
        }
        return new Answer(bestHeight, bestIndex);
    }

    /**
     * Oracolul brute force: BFS pe vectorul de inaltimi construite.
     * Cheia care il face fezabil: banii unei stari NU depind de ordinea
     * constructiei, ci doar de multimea etajelor construite:
     * bani(stare) = x + suma (b - a) pe toate etajele construite.
     */
    static Answer solveOracle(long x, Project[] projects) {
        int n = projects.length;
        int[] start = new int[n];
        Set<List<Integer>> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(key(start));
        int bestHeight = 0;
        int bestIndex = 1;
        while (!queue.isEmpty()) {
            int[] st = queue.poll();
            long money = x;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < st[i]; j++) {
                    money += projects[i].b[j] - projects[i].a[j];
                }
            }
            for (int i = 0; i < n; i++) {
                if (st[i] > bestHeight) {
                    bestHeight = st[i];
                    bestIndex = i + 1;
                } else if (st[i] == bestHeight && i + 1 < bestIndex) {
                    bestIndex = i + 1;
                }
            }
            for (int i = 0; i < n; i++) {
                int h = st[i];
                if (h < projects[i].floors() && money >= projects[i].a[h]) {
                    int[] nxt = st.clone();
                    nxt[i] = h + 1;
                    if (seen.add(key(nxt))) queue.add(nxt);
                }
            }
        }
        return new Answer(bestHeight, bestIndex);
    }

    private static List<Integer> key(int[] st) {
        List<Integer> k = new ArrayList<>(st.length);
        for (int v : st) k.add(v);
        return k;
    }

    // ------------------------------------------------------------------
    // Runner: produce trace-urile pentru firele F1-F6 din manual.
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        f1ExempluDoi();
        f2ExempluUnu();
        f3TreiCladiri();
        f4TieBreakSiZero();
        f5PrefixPartial();
        f6Fuzz();
    }

    static void f1ExempluDoi() {
        System.out.println("== F1: exemplul 2 din enunt (x=3, doua cladiri) ==");
        Project[] pr = {
                new Project(new long[]{4, 4}, new long[]{5, 5}),
                new Project(new long[]{2, 20}, new long[]{4, 0}),
        };
        printSegments(pr);
        StringBuilder t = new StringBuilder();
        Answer fast = solveFast(3, pr, t);
        System.out.print(t);
        System.out.println("  fast=" + fast + "  oracol=" + solveOracle(3, pr));
    }

    static void f2ExempluUnu() {
        System.out.println("== F2: exemplul 1 din enunt (x=6, o cladire) ==");
        Project[] pr = {
                new Project(new long[]{4, 4, 2, 1}, new long[]{2, 4, 1, 1}),
        };
        printSegments(pr);
        StringBuilder t = new StringBuilder();
        Answer fast = solveFast(6, pr, t);
        System.out.print(t);
        System.out.println("  fast=" + fast + "  oracol=" + solveOracle(6, pr));
    }

    static void f3TreiCladiri() {
        System.out.println("== F3: trei cladiri, x=11 (bucla PriorityQueue) ==");
        Project[] pr = {
                new Project(new long[]{4, 10, 3}, new long[]{6, 12, 0}),
                new Project(new long[]{10, 0}, new long[]{0, 100}),
                new Project(new long[]{3, 50}, new long[]{3, 0}),
        };
        printSegments(pr);
        StringBuilder t = new StringBuilder();
        Answer fast = solveFast(11, pr, t);
        System.out.print(t);
        System.out.println("  fast=" + fast + "  oracol=" + solveOracle(11, pr));
        System.out.println("-- acelasi input cu x=5 (Faza 1 se opreste devreme) --");
        StringBuilder t5 = new StringBuilder();
        Answer fast5 = solveFast(5, pr, t5);
        System.out.print(t5);
        System.out.println("  fast=" + fast5 + "  oracol=" + solveOracle(5, pr));
    }

    static void f4TieBreakSiZero() {
        System.out.println("== F4: tie-break pe index si inaltimea 0 ==");
        Project[] tie = {
                new Project(new long[]{5, 6}, new long[]{0, 0}),
                new Project(new long[]{10}, new long[]{0}),
        };
        StringBuilder t = new StringBuilder();
        Answer a = solveFast(10, tie, t);
        System.out.print(t);
        System.out.println("  tie x=10: fast=" + a + "  oracol=" + solveOracle(10, tie));
        Project[] zero = {
                new Project(new long[]{7}, new long[]{0}),
                new Project(new long[]{9}, new long[]{0}),
        };
        Answer z = solveFast(2, zero, null);
        System.out.println("  zero x=2: fast=" + z + "  oracol=" + solveOracle(2, zero));
        Project[] second = {
                new Project(new long[]{20}, new long[]{0}),
                new Project(new long[]{5, 5}, new long[]{0, 0}),
        };
        Answer s = solveFast(10, second, null);
        System.out.println("  castiga indexul 2, x=10: fast=" + s + "  oracol=" + solveOracle(10, second));
    }

    static void f5PrefixPartial() {
        System.out.println("== F5: prefixul propriu al unui segment e mereu pierdere ==");
        Project editorial = new Project(new long[]{10, 0}, new long[]{0, 100});
        long balance = 0;
        for (int j = 0; j < editorial.floors(); j++) {
            balance -= editorial.a[j];
            long dupaPlata = balance;
            balance += editorial.b[j];
            System.out.printf("  etaj %d: sold dupa plata=%d, sold dupa incasare=%d%n",
                    j + 1, dupaPlata, balance);
        }
        Segment s = nextSegment(0, editorial, 0);
        System.out.println("  segmentul intreg: " + s);
        System.out.println("  cu bani=15: doar etajul 1 -> bani 5; segmentul intreg -> bani 105");
    }

    static void f6Fuzz() {
        System.out.println("== F6: fuzz fast vs oracol ==");
        Random rnd = new Random(20260822L);
        int runs = 2000;
        int mismatches = 0;
        for (int it = 0; it < runs; it++) {
            int n = 1 + rnd.nextInt(3);
            Project[] pr = new Project[n];
            for (int i = 0; i < n; i++) {
                int m = 1 + rnd.nextInt(4);
                long[] a = new long[m];
                long[] b = new long[m];
                for (int j = 0; j < m; j++) {
                    a[j] = rnd.nextInt(7);
                    b[j] = rnd.nextInt(7);
                }
                pr[i] = new Project(a, b);
            }
            long x = rnd.nextInt(13);
            Answer fast = solveFast(x, pr, null);
            Answer oracle = solveOracle(x, pr);
            if (fast.height != oracle.height || fast.index != oracle.index) {
                mismatches++;
                System.out.println("  MISMATCH la iteratia " + it + ": fast=" + fast
                        + " oracol=" + oracle + " x=" + x + " input=" + dump(pr));
            }
        }
        System.out.println("  " + runs + " inputuri aleatoare, " + mismatches + " nepotriviri");
    }

    static void printSegments(Project[] pr) {
        for (int i = 0; i < pr.length; i++) {
            int from = 0;
            Segment s;
            System.out.print("  segmente cladirea " + (i + 1) + ":");
            boolean any = false;
            while ((s = nextSegment(i, pr[i], from)) != null) {
                System.out.printf(" [%d..%d) prag=%d profit=%d;", s.from + 1, s.to + 1, s.threshold, s.profit);
                from = s.to;
                any = true;
            }
            if (!any) System.out.print(" niciunul");
            System.out.println();
        }
    }

    static String dump(Project[] pr) {
        StringBuilder sb = new StringBuilder();
        for (Project p : pr) {
            sb.append(" a=").append(Arrays.toString(p.a)).append(" b=").append(Arrays.toString(p.b));
        }
        return sb.toString();
    }
}
