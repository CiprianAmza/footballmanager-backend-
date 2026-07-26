package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.chairman.command.ChairmanCommandCentreDtos;
import com.footballmanagergamesimulator.chairman.command.ChairmanCommandCentreService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateDtos;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final ChairmanCommandCentreService commandCentreService;
    private final ChairmanTacticalMandateService tacticalMandateService;
    @Autowired private ChairmanTransferBudgetService transferBudgetService;
    @Autowired private ChairmanCoachAuthorityService coachAuthorityService;

    public ClubController(CurrentUserService currentUserService,
                          PersonProfileService profileService,
                          PersonalAccountingService accountingService,
                          ClubQueryService queryService,
                          TakeoverService takeoverService,
                          ClubTreasuryService treasuryService,
                          ChairmanCommandCentreService commandCentreService,
                          ChairmanTacticalMandateService tacticalMandateService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.queryService = queryService;
        this.takeoverService = takeoverService;
        this.treasuryService = treasuryService;
        this.commandCentreService = commandCentreService;
        this.tacticalMandateService = tacticalMandateService;
    }

    @GetMapping
    public List<ClubDtos.ClubSummary> clubs(
            @RequestParam(defaultValue = "ALL") ClubCatalogScope scope) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.clubs(scope, profile.getId());
    }

    @GetMapping("/{teamId}/chairman-dashboard")
    public ClubDtos.Dashboard dashboard(@PathVariable long teamId) {
        PersonProfile profile = currentProfile();
        if (profile.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
        accountingService.ensureAccount(profile);
        return queryService.dashboard(teamId, profile);
    }

    @GetMapping("/{teamId}/chairman-command-centre")
    public ChairmanCommandCentreDtos.CommandCentreView commandCentre(@PathVariable long teamId) {
        return commandCentreService.commandCentre(teamId, currentProfile());
    }

    @GetMapping("/{teamId}/tactical-mandate")
    public ChairmanTacticalMandateDtos.MandateView tacticalMandate(@PathVariable long teamId) {
        return tacticalMandateService.get(teamId, currentProfile());
    }

    @PutMapping("/{teamId}/tactical-mandate")
    public ChairmanTacticalMandateDtos.MandateView updateTacticalMandate(
            @PathVariable long teamId, @Valid @RequestBody ChairmanTacticalMandateDtos.UpdateRequest request) {
        return tacticalMandateService.update(teamId, currentProfile(), request);
    }

    @GetMapping("/{teamId}/ownership")
    public ClubDtos.CapTableView ownership(@PathVariable long teamId) {
        currentProfile();
        return queryService.ownership(teamId);
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

    @PutMapping("/{teamId}/transfer-budget")
    public ClubDtos.TransferBudgetView updateTransferBudget(
            @PathVariable long teamId, @Valid @RequestBody ClubDtos.TransferBudgetRequest request) {
        return transferBudgetService.update(currentProfile(), teamId, request.amount());
    }

    @GetMapping("/{teamId}/coach-authority")
    public ClubDtos.CoachAuthorityView coachAuthority(@PathVariable long teamId) {
        return coachAuthorityService.get(currentProfile(), teamId);
    }

    @PutMapping("/{teamId}/coach-authority")
    public ClubDtos.CoachAuthorityView updateCoachAuthority(
            @PathVariable long teamId, @Valid @RequestBody ClubDtos.CoachAuthorityRequest request) {
        return coachAuthorityService.update(currentProfile(), teamId, request);
    }

    private PersonProfile currentProfile() {
        return profileService.requireForUser(currentUserService.requireUser());
    }
}
