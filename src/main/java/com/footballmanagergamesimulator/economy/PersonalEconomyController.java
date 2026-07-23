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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PersonalEconomyController {

    private final CurrentUserService currentUserService;
    private final PersonProfileService profileService;
    private final PersonalAccountingService accountingService;
    private final PersonalAssetService assetService;
    private final WealthQueryService queryService;

    public PersonalEconomyController(CurrentUserService currentUserService,
                                     PersonProfileService profileService,
                                     PersonalAccountingService accountingService,
                                     PersonalAssetService assetService,
                                     WealthQueryService queryService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.assetService = assetService;
        this.queryService = queryService;
    }

    @GetMapping("/me/profile")
    public EconomyDtos.PublicProfileView myProfile() {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.publicProfile(profile.getId());
    }

    @GetMapping("/me/wealth")
    public EconomyDtos.WealthView myWealth() {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.wealth(profile.getId());
    }

    @GetMapping("/me/ledger")
    public EconomyDtos.LedgerPage myLedger(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.ledger(profile.getId(), page, size);
    }

    @GetMapping("/assets/catalog")
    public List<EconomyDtos.CatalogItemView> catalog() {
        return queryService.catalog();
    }

    @GetMapping("/me/assets")
    public List<EconomyDtos.OwnedAssetView> myAssets() {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.assets(profile.getId());
    }

    @PostMapping("/me/assets/purchases")
    public EconomyDtos.AssetMutationView purchase(
            @Valid @RequestBody EconomyDtos.PurchaseAssetRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.mutationView(assetService.purchase(profile,
                request.catalogItemId(), request.idempotencyKey()));
    }

    @PostMapping("/me/assets/{ownedAssetId}/sell")
    public EconomyDtos.AssetMutationView sell(@PathVariable long ownedAssetId,
                                               @Valid @RequestBody EconomyDtos.SellAssetRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.mutationView(assetService.sell(profile, ownedAssetId, request.idempotencyKey()));
    }

    @GetMapping("/people/{profileId}")
    public EconomyDtos.PublicProfileView publicProfile(@PathVariable long profileId) {
        return queryService.publicProfile(profileId);
    }

    @GetMapping("/wealth-rankings")
    public EconomyDtos.RankingPage rankings(@RequestParam(defaultValue = "ALL") String role,
                                             @RequestParam(defaultValue = "ALL") String control,
                                             @RequestParam(defaultValue = "NET_WORTH") String sort,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return queryService.rankings(role, control, sort, page, size);
    }

    private PersonProfile currentProfile() {
        return profileService.requireForUser(currentUserService.requireUser());
    }
}
