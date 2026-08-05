package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ShotEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shot-event-schema;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShotEventRepositoryJpaTest {

    @Autowired private ShotEventRepository repository;
    @Autowired private DataSource dataSource;

    @Test
    void createsReservedWordSafeSchemaAndQueriesByMatchStats() throws Exception {
        ShotEvent shot = new ShotEvent();
        shot.setMatchStatsId(77L);
        shot.setShotIndex(0);
        shot.setCompetitionId(8L);
        shot.setSeasonNumber(4);
        shot.setRoundNumber(2);
        shot.setTeamId(86L);
        shot.setOpponentTeamId(91L);
        shot.setMinute(34);

        repository.saveAndFlush(shot);

        assertThat(repository.findAllByMatchStatsIdOrderByShotIndexAsc(77L))
                .singleElement()
                .extracting(ShotEvent::getMinute)
                .isEqualTo(34);

        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData()
                     .getColumns(null, "PUBLIC", "SHOT_EVENT", null)) {
            while (result.next()) columns.add(result.getString("COLUMN_NAME"));
        }
        assertThat(columns).contains("EVENT_MINUTE").doesNotContain("MINUTE");
    }
}
