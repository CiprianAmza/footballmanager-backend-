package com.footballmanagergamesimulator.algorithms.dp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 0/1 knapsack on a transfer budget: every target is either bought whole or not at all,
 * and the club wants the highest total rating it can fit under the budget.
 *
 * <p>The greedy variant here is the shape the real market code uses - sort the shortlist,
 * then buy down the list while money lasts. It is kept next to the exact solver so the
 * gap between the two can be measured instead of asserted.
 */
public final class SquadKnapsack {

    /** One transfer target. Fee is in whole millions so it can index a table directly. */
    public record Target(String name, int feeM, int rating) {
    }

    /** The chosen set plus its totals, for both solvers. */
    public record Plan(List<String> bought, int totalRating, int spentM) {
    }

    private SquadKnapsack() {
    }

    /**
     * Exact solution. State = (first i targets considered, budget b still unspent);
     * value = the best total rating reachable in that state.
     */
    public static Plan best(List<Target> targets, int budgetM) {
        int n = targets.size();
        int[][] dp = new int[n + 1][budgetM + 1];

        for (int i = 1; i <= n; i++) {
            Target target = targets.get(i - 1);
            for (int b = 0; b <= budgetM; b++) {
                int skip = dp[i - 1][b];
                if (target.feeM() > b) {
                    dp[i][b] = skip;
                    continue;
                }
                int buy = dp[i - 1][b - target.feeM()] + target.rating();
                dp[i][b] = Math.max(skip, buy);
            }
        }

        // Reconstruction: the table holds the best value, not the decisions.
        // Walking back asks at every cell "did this row change anything?".
        List<String> bought = new ArrayList<>();
        int spent = 0;
        int b = budgetM;
        for (int i = n; i > 0; i--) {
            if (dp[i][b] != dp[i - 1][b]) {
                Target target = targets.get(i - 1);
                bought.add(target.name());
                spent += target.feeM();
                b -= target.feeM();
            }
        }
        return new Plan(List.copyOf(bought), dp[n][budgetM], spent);
    }

    /** The market-style pass: best rating first, buy whatever still fits, never reconsider. */
    public static Plan greedyByRating(List<Target> targets, int budgetM) {
        List<Target> ordered = new ArrayList<>(targets);
        ordered.sort(Comparator.comparingInt(Target::rating).reversed());

        List<String> bought = new ArrayList<>();
        int left = budgetM;
        int rating = 0;
        for (Target target : ordered) {
            if (target.feeM() <= left) {
                bought.add(target.name());
                left -= target.feeM();
                rating += target.rating();
            }
        }
        return new Plan(List.copyOf(bought), rating, budgetM - left);
    }
}
