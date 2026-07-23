package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalEconomyBootstrapService {

    private final PersonProfileRepository profileRepository;
    private final PersonalAccountingService accountingService;

    public PersonalEconomyBootstrapService(PersonProfileRepository profileRepository,
                                           PersonalAccountingService accountingService) {
        this.profileRepository = profileRepository;
        this.accountingService = accountingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    @Transactional
    public void initializeOnStartup() {
        ensureAllAccounts();
    }

    @Transactional
    public void ensureAllAccounts() {
        accountingService.ensureAccounts(profileRepository.findAll().stream()
                .filter(this::supportsPersonalEconomy).toList());
    }

    private boolean supportsPersonalEconomy(PersonProfile profile) {
        if (profile.getCareerType() == CareerType.CHAIRMAN || profile.getCareerType() == CareerType.MANAGER) {
            return true;
        }
        return profile.getCareerType() == CareerType.PLAYER && profile.getHumanId() != null;
    }
}
