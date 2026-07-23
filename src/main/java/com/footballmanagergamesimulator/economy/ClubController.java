package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clubs")
public class ClubController {
    private final CurrentUserService currentUserService;
    private final PersonProfileService profileService;
    private final PersonalAccountingService accountingService;
    private final ClubQueryService queryService;
    private final TakeoverService takeoverService;
    private final ClubTreasuryService treasuryService;

    public ClubController(CurrentUserService currentUserService,
                          PersonProfileService profileService,
                          PersonalAccountingService accountingService,
                          ClubQueryService queryService,
                          TakeoverService takeoverService,
                          ClubTreasuryService treasuryService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.queryService = queryService;
        this.takeoverService = takeoverService;
        this.treasuryService = treasuryService;
    }

    @GetMapping
    public List<ClubDtos.ClubSummary> clubs() {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.clubs(profile.getId());
    }

    @GetMapping("/{teamId}/chairman-dashboard")
    public ClubDtos.Dashboard dashboard(@PathVariable long teamId) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.dashboard(teamId, profile.getId());
    }

    @PostMapping("/{teamId}/takeover-quotes")
    public ClubDtos.TakeoverQuoteView quote(@PathVariable long teamId,
                                            @Valid @RequestBody ClubDtos.QuoteRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.quote(takeoverService.quote(profile, teamId, request.idempotencyKey()));
    }

    @PostMapping("/{teamId}/takeovers")
    public ClubDtos.TakeoverExecutionView takeover(@PathVariable long teamId,
                                                   @Valid @RequestBody ClubDtos.TakeoverRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        TakeoverService.ExecutionResult result = takeoverService.execute(
                profile, teamId, request.quoteId(), request.idempotencyKey());
        return queryService.execution(result);
    }

    @PostMapping("/{teamId}/treasury-transfers")
    public ClubDtos.TreasuryTransferView transfer(@PathVariable long teamId,
                                                  @Valid @RequestBody ClubDtos.TreasuryTransferRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.transfer(treasuryService.transfer(profile, teamId, request.direction(),
                request.amount(), request.idempotencyKey()));
    }

    private PersonProfile currentProfile() {
        return profileService.requireForUser(currentUserService.requireUser());
    }
}
