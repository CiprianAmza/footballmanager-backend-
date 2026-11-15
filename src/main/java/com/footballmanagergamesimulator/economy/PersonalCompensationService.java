package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.HumanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class PersonalCompensationService {

    private final HumanRepository humanRepository;
    private final PersonProfileService profileService;
    private final PersonalAccountingService accountingService;

    public PersonalCompensationService(HumanRepository humanRepository,
                                       PersonProfileService profileService,
                                       PersonalAccountingService accountingService) {
        this.humanRepository = humanRepository;
        this.profileService = profileService;
        this.accountingService = accountingService;
    }

    @Transactional
    public PersonalAccountingService.PostingResult creditCareerIncome(
            long humanId, LedgerEntryType type, long amount, int season, int day,
            long teamId, String correlationId, String idempotencyKey, String description) {
        if (type != LedgerEntryType.SALARY && type != LedgerEntryType.BONUS) {
            throw new IllegalArgumentException("Career income must be salary or bonus");
        }
        if (amount <= 0) throw new IllegalArgumentException("Career income must be positive");
        Human human = humanRepository.findById(humanId)
                .orElseThrow(() -> new EconomyConflictException("PERSON_NOT_FOUND", "Paid person was not found"));
        PersonProfile profile = profileService.ensureForHuman(human);
        accountingService.ensureAccount(profile);
        return accountingService.post(profile.getId(), type, amount, amount, season, day,
                correlationId, idempotencyKey, teamId, null, description);
    }

    @Transactional
    public void creditMonthlySalaries(Map<Long, Long> amounts, int season, int day, long teamId) {
        if (amounts.isEmpty()) return;
        List<Human> recipients = humanRepository.findAllById(amounts.keySet());
        if (recipients.size() != amounts.size()) {
            throw new EconomyConflictException("PERSON_NOT_FOUND", "Paid person was not found");
        }
        accountingService.postMonthlySalaries(profileService.ensureForHumans(recipients),
                amounts, season, day, teamId);
    }
}
