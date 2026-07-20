package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingCadenceServiceTest {

    @Test
    void updatesRatingsOnlyEveryConfiguredThreeWeeks() {
        TrainingCadenceService service = new TrainingCadenceService();
        ReflectionTestUtils.setField(service, "ratingUpdateIntervalDays", 21);

        assertThat(service.isRatingUpdateDay(1)).isTrue();
        assertThat(service.isRatingUpdateDay(2)).isFalse();
        assertThat(service.isRatingUpdateDay(21)).isFalse();
        assertThat(service.isRatingUpdateDay(22)).isTrue();
        assertThat(service.isRatingUpdateDay(43)).isTrue();
        assertThat(service.intervalDays()).isEqualTo(21);
    }
}
