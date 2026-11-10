package com.footballmanagergamesimulator.algorithms.dp;

import java.util.HashMap;
import java.util.Map;

/**
 * The same number computed four ways, with the work counted every time.
 *
 * <p>This is the smallest place where the two tests for dynamic programming are
 * visible at once: fib(n) is built from fib(n-1) and fib(n-2) (optimal substructure)
 * and those two branches ask for the same smaller values over and over
 * (overlapping subproblems). Everything else in this package is the same move on
 * a bigger state.
 */
public final class FibonacciLab {

    /** How many times a computing body actually ran during the last call. Reset with {@link #reset()}. */
    private static long work;

    private FibonacciLab() {
    }

    public static void reset() {
        work = 0;
    }

    public static long work() {
        return work;
    }

    /** Naive recursion: the call stack forgets every value the moment it returns. */
    public static long naive(int n) {
        work++;
        if (n < 2) {
            return n;
        }
        return naive(n - 1) + naive(n - 2);
    }

    /** Top-down memoization: identical recursion, but a table read guards every descent. */
    public static long memo(int n, Map<Integer, Long> table) {
        work++;
        if (n < 2) {
            return n;
        }
        Long cached = table.get(n);
        if (cached != null) {
            return cached;
        }
        long value = memo(n - 1, table) + memo(n - 2, table);
        table.put(n, value);
        return value;
    }

    public static long memo(int n) {
        return memo(n, new HashMap<>());
    }

    /** Bottom-up tabulation: the for loop, not the call stack, fixes the order of computation. */
    public static long table(int n) {
        long[] dp = new long[Math.max(2, n + 1)];
        dp[0] = 0;
        dp[1] = 1;
        for (int i = 2; i <= n; i++) {
            work++;
            dp[i] = dp[i - 1] + dp[i - 2];
        }
        return dp[n];
    }

    /**
     * Rolling array: the recurrence never reads further back than two states,
     * so the full n+1 table is dead weight. Same answer, O(1) memory.
     */
    public static long rolling(int n) {
        if (n < 2) {
            return n;
        }
        long twoBack = 0;
        long oneBack = 1;
        for (int i = 2; i <= n; i++) {
            work++;
            long current = oneBack + twoBack;
            twoBack = oneBack;
            oneBack = current;
        }
        return oneBack;
    }
}
