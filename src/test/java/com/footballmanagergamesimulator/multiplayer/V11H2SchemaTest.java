package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V11H2SchemaTest {
    @Test void v11UsesH2CompatibleSingletonConstraint() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/h2/V11__single_room_multiplayer.sql"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:v11_schema;MODE=LEGACY")) {
            for (String statement : sql.split(";")) if (!statement.isBlank()) connection.createStatement().execute(statement);
            connection.createStatement().execute("insert into game_room (host_user_id,password_hash,status,continue_threshold_percent,day_timeout_seconds,majority_timeout_seconds,max_players,created_at,version) values (1,'x','LOBBY',50,300,60,2,CURRENT_TIMESTAMP,0)");
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () -> connection.createStatement().execute("insert into game_room (host_user_id,password_hash,status,continue_threshold_percent,day_timeout_seconds,majority_timeout_seconds,max_players,created_at,version) values (2,'x','ACTIVE',50,300,60,2,CURRENT_TIMESTAMP,0)"));
            var result = connection.createStatement().executeQuery("select count(*) from game_room"); result.next(); assertEquals(1, result.getInt(1));
        }
    }
}
