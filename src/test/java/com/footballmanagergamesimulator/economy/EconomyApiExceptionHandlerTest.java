package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyApiExceptionHandlerTest {
    @Test
    void chairmanMandateExceptionKeepsTypedCodeAndMessage() {
        EconomyApiExceptionHandler handler = new EconomyApiExceptionHandler();

        ResponseEntity<EconomyDtos.ApiError> response = handler.conflict(
                new ChairmanTacticalMandateException("MANDATE_SLOT_NOT_IN_FORMATION", "lock is not in formation"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("MANDATE_SLOT_NOT_IN_FORMATION");
        assertThat(response.getBody().message()).isEqualTo("lock is not in formation");
    }
}
