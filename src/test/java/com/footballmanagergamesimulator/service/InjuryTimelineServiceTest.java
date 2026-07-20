package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InjuryTimelineServiceTest {

    @InjectMocks private InjuryTimelineService service;
    @Mock private InjuryRepository injuryRepository;
    @Mock private HumanRepository humanRepository;
    @Mock private GameCalendarRepository gameCalendarRepository;
    @Mock private GameplayFeatureConfig gameplayFeatures;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void schedulesAndCalculatesRemainingDaysWithoutDailyWrites() {
        Injury injury = service.schedule(new Injury(), 3, 100, 14);

        assertThat(injury.getSeasonNumber()).isEqualTo(3);
        assertThat(injury.getReturnSeason()).isEqualTo(3);
        assertThat(injury.getReturnDay()).isEqualTo(114);
        assertThat(injury.getDaysRemaining()).isEqualTo(14);
        assertThat(service.remainingDays(injury, 3, 107)).isEqualTo(7);
        assertThat(service.remainingDays(injury, 3, 114)).isZero();
        assertThat(injury.getDaysRemaining()).isEqualTo(14);
    }

    @Test
    void returnDateRollsIntoTheNextSeason() {
        Injury injury = service.schedule(new Injury(), 4, 360, 12);

        assertThat(injury.getReturnSeason()).isEqualTo(5);
        assertThat(injury.getReturnDay()).isEqualTo(12);
        assertThat(service.remainingDays(injury, 5, 1)).isEqualTo(11);
        assertThat(service.remainingDays(injury, 5, 12)).isZero();
    }

    @Test
    void disabledAvailabilityDoesNotReadHistoricalInjuries() {
        when(gameplayFeatures.isPlayerAvailabilityDisabled()).thenReturn(true);

        assertThat(service.processRecoveries(7, 120)).isZero();

        verifyNoInteractions(injuryRepository, humanRepository);
    }
}
