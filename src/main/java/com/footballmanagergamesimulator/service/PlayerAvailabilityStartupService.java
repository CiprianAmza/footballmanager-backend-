package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Applies the availability feature switch consistently to an existing save. */
@Component
public class PlayerAvailabilityStartupService implements ApplicationRunner {
    private final GameplayFeatureConfig features;
    private final SuspensionRepository suspensionRepository;
    private final InjuryRepository injuryRepository;
    private final HumanRepository humanRepository;

    public PlayerAvailabilityStartupService(GameplayFeatureConfig features,
                                            SuspensionRepository suspensionRepository,
                                            InjuryRepository injuryRepository,
                                            HumanRepository humanRepository) {
        this.features = features;
        this.suspensionRepository = suspensionRepository;
        this.injuryRepository = injuryRepository;
        this.humanRepository = humanRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!features.isPlayerAvailabilityDisabled()) return;

        List<Suspension> suspensions = suspensionRepository.findAll().stream()
                .filter(Suspension::isActive).toList();
        suspensions.forEach(suspension -> suspension.setActive(false));
        suspensionRepository.saveAll(suspensions);

        List<Injury> injuries = injuryRepository.findAllByDaysRemainingGreaterThan(0);
        injuries.forEach(injury -> injury.setDaysRemaining(0));
        injuryRepository.saveAll(injuries);

        List<Human> unavailable = humanRepository.findAll().stream()
                .filter(human -> human.getCurrentStatus() != null)
                .filter(human -> human.getCurrentStatus().toLowerCase().startsWith("injur"))
                .toList();
        unavailable.forEach(human -> human.setCurrentStatus("Available"));
        humanRepository.saveAll(unavailable);

        System.out.printf("=== Player availability disabled: cleared %d active suspension(s) and %d active injury/injuries ===%n",
                suspensions.size(), injuries.size());
    }
}
