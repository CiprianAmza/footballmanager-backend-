package com.footballmanagergamesimulator.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MatchEventLegacyColumnsTest {

    @Test
    void rowsImportedWithoutPlanColumnsReadAsLegacyMinusOne() {
        MatchEvent imported = new MatchEvent();
        // Hibernate writes the fields directly; v5 saves leave both columns NULL.
        ReflectionTestUtils.setField(imported, "slotIndex", null);
        ReflectionTestUtils.setField(imported, "eventOrder", null);

        assertThat(imported.getSlotIndex()).isEqualTo(-1);
        assertThat(imported.getEventOrder()).isEqualTo(-1);
    }

    @Test
    void canonicalEventsKeepTheirSlotAndOrder() {
        MatchEvent event = new MatchEvent();
        event.setSlotIndex(3);
        event.setEventOrder(MatchEvent.ORDER_ASSIST);

        assertThat(event.getSlotIndex()).isEqualTo(3);
        assertThat(event.getEventOrder()).isEqualTo(MatchEvent.ORDER_ASSIST);
        assertThat(new MatchEvent().getEventOrder()).isEqualTo(-1);
    }
}
