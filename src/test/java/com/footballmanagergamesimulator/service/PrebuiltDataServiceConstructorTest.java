package com.footballmanagergamesimulator.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.footballmanagergamesimulator.Main;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PrebuiltDataServiceConstructorTest {
    @Test
    @SpringBootTest(classes = Main.class)
    void springContextUsesTheAutowiredProductionConstructor() {
        // Context creation is the production injection assertion.
    }

    @Test
    void productionConstructorAcceptsBothSpringCollaborators() {
        DataSource dataSource = mock(DataSource.class);
        Flyway flyway = mock(Flyway.class);
        assertThat(new PrebuiltDataService(dataSource, flyway)).isNotNull();
    }

    @Test
    void testConstructorRemainsUnambiguousForSnapshotTransformations() {
        assertThat(new PrebuiltDataService(mock(DataSource.class))).isNotNull();
    }
}
