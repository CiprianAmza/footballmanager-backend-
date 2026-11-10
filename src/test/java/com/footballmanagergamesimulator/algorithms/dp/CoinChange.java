package com.footballmanagergamesimulator.algorithms.dp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimum number of instalments that add up exactly to a transfer fee.
 *
 * <p>The point of this one is the unreachable state: with instalments of 4 and 6
 * there is no way to reach 7, and a table that only stores "best so far" cannot tell
 * "not computed yet" from "impossible" unless a sentinel says so.
 */
public final class CoinChange {

    /** Marks a sum no combination of the given instalments can reach. */
    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private CoinChange() {
    }

    /**
     * State = the exact sum s; value = fewest instalments summing to s.
     * dp[0] = 0 is the only free state - paying nothing needs no instalment.
     */
    public static int minInstalments(int feeM, int[] instalmentsM) {
        int[] dp = new int[feeM + 1];
        Arrays.fill(dp, UNREACHABLE);
        dp[0] = 0;

        for (int sum = 1; sum <= feeM; sum++) {
            for (int instalment : instalmentsM) {
                if (instalment > sum) {
                    continue;
                }
                int previous = dp[sum - instalment];
                if (previous == UNREACHABLE) {
                    continue;
                }
                dp[sum] = Math.min(dp[sum], previous + 1);
            }
        }
        return dp[feeM];
    }

    /** The same fill, but keeping which instalment first reached each sum, so the split can be printed. */
    public static List<Integer> split(int feeM, int[] instalmentsM) {
        int[] dp = new int[feeM + 1];
        int[] choice = new int[feeM + 1];
        Arrays.fill(dp, UNREACHABLE);
        Arrays.fill(choice, -1);
        dp[0] = 0;

        for (int sum = 1; sum <= feeM; sum++) {
            for (int instalment : instalmentsM) {
                if (instalment > sum || dp[sum - instalment] == UNREACHABLE) {
                    continue;
                }
                if (dp[sum - instalment] + 1 < dp[sum]) {
                    dp[sum] = dp[sum - instalment] + 1;
                    choice[sum] = instalment;
                }
            }
        }

        if (dp[feeM] == UNREACHABLE) {
            return List.of();
        }
        List<Integer> parts = new ArrayList<>();
        for (int sum = feeM; sum > 0; sum -= choice[sum]) {
            parts.add(choice[sum]);
        }
        return List.copyOf(parts);
    }
}
