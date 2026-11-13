package com.footballmanagergamesimulator.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restoring a snapshot replaces the whole H2 schema, so tables and columns added to the entities after the
 * snapshot was taken would vanish. Before the restore, this captures the schema Hibernate built for the current
 * code; after it, it creates the missing tables and adds the missing columns (NOT NULL columns get a neutral
 * default so existing rows stay valid).
 */
public final class SnapshotSchemaCatchUp {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("^CREATE (?:CACHED |MEMORY )?TABLE \"PUBLIC\"\\.\"([A-Z0-9_]+)\"\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE =
            Pattern.compile("^ALTER TABLE \"PUBLIC\"\\.\"([A-Z0-9_]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN = Pattern.compile("^\\s*\"([A-Z0-9_]+)\"\\s+(.+?),?\\s*$");

    /** A table as Hibernate built it: its CREATE statement, its constraints, and each column's definition. */
    public record Table(String create, List<String> constraints, LinkedHashMap<String, String> columns) {}

    private SnapshotSchemaCatchUp() {}

    /** The current (pre-restore) schema of the PUBLIC tables. */
    public static Map<String, Table> capture(Statement st) throws SQLException {
        List<String> statements = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SCRIPT NODATA")) {
            while (rs.next()) statements.add(rs.getString(1));
        }
        return parse(statements);
    }

    static Map<String, Table> parse(List<String> statements) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (String sql : statements) {
            String trimmed = sql.trim();
            Matcher create = CREATE_TABLE.matcher(trimmed);
            if (create.find()) {
                LinkedHashMap<String, String> columns = new LinkedHashMap<>();
                for (String line : trimmed.split("\\R")) {
                    Matcher column = COLUMN.matcher(line);
                    if (column.matches()) columns.put(column.group(1), column.group(2).replaceAll("\\)\\s*;?$", "").trim());
                }
                tables.put(create.group(1), new Table(trimmed, new ArrayList<>(), columns));
                continue;
            }
            Matcher alter = ALTER_TABLE.matcher(trimmed);
            if (alter.find() && tables.containsKey(alter.group(1))) tables.get(alter.group(1)).constraints().add(trimmed);
        }
        return tables;
    }

    /** After the restore: create what the snapshot lacks. Returns what was added, for the log. */
    public static List<String> apply(Statement st, Map<String, Table> wanted) throws SQLException {
        Map<String, Set<String>> present = new HashMap<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) present.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        }
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Table> entry : wanted.entrySet()) {
            String table = entry.getKey();
            Set<String> columns = present.get(table);
            if (columns == null) {
                st.execute(entry.getValue().create());
                // Constraint names (CONSTRAINT_xx) may already be taken in the restored schema: let H2 name them again.
                for (String constraint : entry.getValue().constraints()) st.execute(unnamed(constraint));
                added.add(table);
                continue;
            }
            for (Map.Entry<String, String> column : entry.getValue().columns().entrySet()) {
                if (columns.contains(column.getKey())) continue;
                st.execute("ALTER TABLE \"PUBLIC\".\"" + table + "\" ADD COLUMN IF NOT EXISTS \""
                        + column.getKey() + "\" " + withDefault(column.getValue()));
                added.add(table + "." + column.getKey());
            }
        }
        return added;
    }

    static String unnamed(String constraint) {
        return constraint.replaceFirst("(?i)ADD CONSTRAINT (?:\"[^\"]+\"\\.)?\"[^\"]+\"\\s+", "ADD ");
    }

    /** A NOT NULL column without a default gets a neutral one, so rows already in the table stay valid. */
    static String withDefault(String definition) {
        String upper = definition.toUpperCase(Locale.ROOT);
        if (!upper.contains("NOT NULL") || upper.contains("DEFAULT") || upper.contains("IDENTITY")) return definition;
        String fallback;
        if (upper.startsWith("BOOLEAN")) fallback = "FALSE";
        else if (upper.startsWith("CHARACTER") || upper.startsWith("VARCHAR") || upper.startsWith("CLOB")) fallback = "''";
        else if (upper.matches("^(TINYINT|SMALLINT|INTEGER|INT|BIGINT|REAL|DOUBLE|FLOAT|NUMERIC|DECIMAL|DECFLOAT).*")) fallback = "0";
        else return definition.replaceFirst("(?i)\\s*NOT NULL", "");
        int at = upper.indexOf("NOT NULL");
        return definition.substring(0, at).trim() + " DEFAULT " + fallback + " " + definition.substring(at).trim();
    }
}
