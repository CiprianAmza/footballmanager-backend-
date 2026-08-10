package com.footballmanagergamesimulator.tools;

import java.lang.reflect.Field;
import java.sql.DriverManager;
import java.util.Map;

public final class LocalH2Snapshot implements LocalH2SnapshotMBean {
    @Override
    public String snapshot(String outputPath) throws Exception {
        Class<?> engine = Class.forName("org.h2.engine.Engine");
        Field databasesField = engine.getDeclaredField("DATABASES");
        databasesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> databases = (Map<String, ?>) databasesField.get(null);
        String database = databases.keySet().stream()
                .filter(name -> name.startsWith("mem:"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No live in-memory H2 database found"));

        String literal = "'" + outputPath.replace("'", "''") + "'";
        try (var connection = DriverManager.getConnection("jdbc:h2:" + database, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("SCRIPT TO " + literal);
        }
        return database;
    }
}
