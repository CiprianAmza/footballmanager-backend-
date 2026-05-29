package com.footballmanagergamesimulator.testutil;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a markdown table whose cells are padded so the raw text — read in a
 * terminal or plain editor — visually aligns. Cells are still valid markdown
 * when rendered. Shared by the {@code *OutcomeIT} report builders.
 */
public final class MarkdownTable {

    public enum Align { LEFT, RIGHT }

    private final List<String> headers;
    private final List<Align> alignments;
    private final List<String[]> rows = new ArrayList<>();

    public MarkdownTable(List<String> headers, List<Align> alignments) {
        if (headers.size() != alignments.size()) {
            throw new IllegalArgumentException("headers and alignments must have same size");
        }
        this.headers = List.copyOf(headers);
        this.alignments = List.copyOf(alignments);
    }

    public void addRow(String... cells) {
        if (cells.length != headers.size()) {
            throw new IllegalArgumentException(
                    "row has " + cells.length + " cells but table has " + headers.size() + " columns");
        }
        rows.add(cells);
    }

    public String render() {
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) widths[c] = headers.get(c).length();
        for (String[] row : rows) {
            for (int c = 0; c < cols; c++) widths[c] = Math.max(widths[c], row[c].length());
        }

        StringBuilder sb = new StringBuilder();
        // Header row
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            sb.append(' ').append(pad(headers.get(c), widths[c], alignments.get(c))).append(" |");
        }
        sb.append('\n');
        // Separator row — right-align uses trailing colon
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            String bar = "-".repeat(widths[c] + 1);
            sb.append(bar).append(alignments.get(c) == Align.RIGHT ? ":|" : "-|");
        }
        sb.append('\n');
        // Data rows
        for (String[] row : rows) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(pad(row[c], widths[c], alignments.get(c))).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pad(String s, int width, Align align) {
        if (s.length() >= width) return s;
        String padding = " ".repeat(width - s.length());
        return align == Align.RIGHT ? padding + s : s + padding;
    }
}
