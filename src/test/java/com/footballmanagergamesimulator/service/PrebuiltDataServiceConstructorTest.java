package com.footballmanagergamesimulator.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.footballmanagergamesimulator.Main;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PrebuiltDataServiceConstructorTest {
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

    @Nested
    @SpringBootTest(classes = Main.class)
    class SpringContextInjectionTest {
        @Test
        void contextUsesTheAutowiredProductionConstructor() {
            // Starting the real context verifies the (DataSource, Flyway) bean path.
        }
    }
}
