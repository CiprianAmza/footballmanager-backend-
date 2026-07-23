package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/club-cash-transfers")
public class ClubCashTransferController {
    private final CurrentUserService currentUserService;
    private final PersonProfileService profileService;
    private final PersonalAccountingService accountingService;
    private final ClubTreasuryService treasuryService;
    private final ClubQueryService queryService;

    public ClubCashTransferController(CurrentUserService currentUserService,
                                      PersonProfileService profileService,
                                      PersonalAccountingService accountingService,
                                      ClubTreasuryService treasuryService,
                                      ClubQueryService queryService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.treasuryService = treasuryService;
        this.queryService = queryService;
    }

    @PostMapping
    public ClubDtos.TreasuryTransferView transfer(
            @Valid @RequestBody ClubDtos.ClubCashTransferRequest request) {
        PersonProfile profile = profileService.requireForUser(currentUserService.requireUser());
        accountingService.ensureAccount(profile);
        return queryService.transfer(treasuryService.transfer(profile, request.teamId(), request.direction(),
                request.amount(), request.idempotencyKey()));
    }
}
