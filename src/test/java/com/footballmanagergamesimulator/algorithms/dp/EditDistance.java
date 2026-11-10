package com.footballmanagergamesimulator.algorithms.dp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Levenshtein distance between two player names, plus the operations that get from
 * one to the other. A save file that spells a name slightly differently is the
 * everyday version of this problem.
 *
 * <p>State = (first i characters of the source, first j characters of the target);
 * value = fewest single-character edits that turn one prefix into the other.
 */
public final class EditDistance {

    private EditDistance() {
    }

    public static int[][] table(String source, String target) {
        int rows = source.length();
        int cols = target.length();
        int[][] dp = new int[rows + 1][cols + 1];

        // Base row and column: turning a prefix into the empty string costs one delete per character.
        for (int i = 0; i <= rows; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= cols; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= rows; i++) {
            for (int j = 1; j <= cols; j++) {
                if (source.charAt(i - 1) == target.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                    continue;
                }
                int replace = dp[i - 1][j - 1];
                int delete = dp[i - 1][j];
                int insert = dp[i][j - 1];
                dp[i][j] = 1 + Math.min(replace, Math.min(delete, insert));
            }
        }
        return dp;
    }

    public static int distance(String source, String target) {
        int[][] dp = table(source, target);
        return dp[source.length()][target.length()];
    }

    /** Walks the finished table backwards from the corner and names each edit taken. */
    public static List<String> operations(String source, String target) {
        int[][] dp = table(source, target);
        Deque<String> steps = new ArrayDeque<>();
        int i = source.length();
        int j = target.length();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && source.charAt(i - 1) == target.charAt(j - 1)) {
                steps.push("keep " + source.charAt(i - 1));
                i--;
                j--;
            } else if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1) {
                steps.push("replace " + source.charAt(i - 1) + " with " + target.charAt(j - 1));
                i--;
                j--;
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                steps.push("delete " + source.charAt(i - 1));
                i--;
            } else {
                steps.push("insert " + target.charAt(j - 1));
                j--;
            }
        }
        return List.copyOf(steps);
    }
}
