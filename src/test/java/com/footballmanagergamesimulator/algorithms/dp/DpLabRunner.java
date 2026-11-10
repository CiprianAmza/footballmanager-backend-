package com.footballmanagergamesimulator.algorithms.dp;

import java.util.List;

/**
 * Runs every lab in this package and prints the measured numbers the manual quotes.
 *
 * <p>Run it with the test classes on the classpath:
 * {@code java -cp target/test-classes com.footballmanagergamesimulator.algorithms.dp.DpLabRunner}
 */
public final class DpLabRunner {

    private static final List<SquadKnapsack.Target> SHORTLIST = List.of(
            new SquadKnapsack.Target("Ilie", 60, 84),
            new SquadKnapsack.Target("Barbu", 35, 79),
            new SquadKnapsack.Target("Costea", 30, 77),
            new SquadKnapsack.Target("Dima", 25, 74),
            new SquadKnapsack.Target("Enache", 20, 71));

    private static final List<FriendlySchedule.Offer> OFFERS = List.of(
            new FriendlySchedule.Offer("Ajax", 1, 6, 90),
            new FriendlySchedule.Offer("Genk", 2, 3, 30),
            new FriendlySchedule.Offer("Brugge", 4, 5, 35),
            new FriendlySchedule.Offer("Porto", 7, 9, 50),
            new FriendlySchedule.Offer("Vitesse", 8, 10, 20));

    private DpLabRunner() {
    }

    public static void main(String[] args) {
        fibonacci(30);
        knapsack(90);
        instalments();
        names("Ronaldiho", "Ronaldinho");
        friendlies();
    }

    private static void fibonacci(int n) {
        System.out.println("== F1 fibonacci(" + n + ") ==");

        FibonacciLab.reset();
        long naive = FibonacciLab.naive(n);
        long naiveWork = FibonacciLab.work();

        FibonacciLab.reset();
        long memo = FibonacciLab.memo(n);
        long memoWork = FibonacciLab.work();

        FibonacciLab.reset();
        long table = FibonacciLab.table(n);
        long tableWork = FibonacciLab.work();

        FibonacciLab.reset();
        long rolling = FibonacciLab.rolling(n);
        long rollingWork = FibonacciLab.work();

        System.out.println("naive   value=" + naive + " work=" + naiveWork);
        System.out.println("memo    value=" + memo + " work=" + memoWork);
        System.out.println("table   value=" + table + " work=" + tableWork);
        System.out.println("rolling value=" + rolling + " work=" + rollingWork);
    }

    private static void knapsack(int budgetM) {
        System.out.println("== F2 knapsack budget=" + budgetM + "M ==");
        SquadKnapsack.Plan exact = SquadKnapsack.best(SHORTLIST, budgetM);
        SquadKnapsack.Plan greedy = SquadKnapsack.greedyByRating(SHORTLIST, budgetM);
        System.out.println("dp     " + exact);
        System.out.println("greedy " + greedy);
    }

    private static void instalments() {
        int[] instalments = {4, 6};
        System.out.println("== F3 instalments " + java.util.Arrays.toString(instalments) + " ==");
        for (int fee : new int[] {7, 10, 14}) {
            int count = CoinChange.minInstalments(fee, instalments);
            System.out.println("fee=" + fee + "M count="
                    + (count == CoinChange.UNREACHABLE ? "UNREACHABLE" : count)
                    + " split=" + CoinChange.split(fee, instalments));
        }
    }

    private static void names(String source, String target) {
        System.out.println("== F4 edit distance " + source + " -> " + target + " ==");
        System.out.println("distance=" + EditDistance.distance(source, target));
        System.out.println("ops=" + EditDistance.operations(source, target));
    }

    private static void friendlies() {
        System.out.println("== F5 friendlies ==");
        System.out.println("dp     " + FriendlySchedule.best(OFFERS));
        System.out.println("greedy " + FriendlySchedule.greedyEarliestFinish(OFFERS));
    }
}
