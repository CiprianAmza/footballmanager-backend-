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
public class MarketController {
    private final CurrentUserService currentUserService;
    private final PersonProfileService profileService;
    private final PersonalAccountingService accountingService;
    private final MarketTradingService tradingService;
    private final MarketQueryService queryService;

    public MarketController(CurrentUserService currentUserService,
                            PersonProfileService profileService,
                            PersonalAccountingService accountingService,
                            MarketTradingService tradingService,
                            MarketQueryService queryService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.tradingService = tradingService;
        this.queryService = queryService;
    }

    @GetMapping("/market/instruments")
    public List<MarketDtos.InstrumentView> instruments() {
        return queryService.instruments();
    }

    @GetMapping("/market/instruments/{instrumentId}/history")
    public List<MarketDtos.PriceView> history(@PathVariable long instrumentId,
                                              @RequestParam(defaultValue = "30") int limit) {
        return queryService.history(instrumentId, limit);
    }

    @GetMapping("/me/portfolio")
    public MarketDtos.PortfolioView portfolio() {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.portfolio(profile.getId());
    }

    @GetMapping("/me/trades")
    public MarketDtos.TradePage trades(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.trades(profile.getId(), page, size);
    }

    @PostMapping("/me/trades")
    public MarketDtos.TradeView trade(@Valid @RequestBody MarketDtos.TradeRequest request) {
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.tradeView(tradingService.trade(profile, request.instrumentId(), request.side(),
                request.quantity(), request.idempotencyKey()));
    }

    private PersonProfile currentProfile() {
        return profileService.requireForUser(currentUserService.requireUser());
    }
}
