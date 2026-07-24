package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
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
    private final TraderAdviserService traderAdviserService;
    private final RegentEconomyProperties properties;
    private final GameCalendarRepository calendarRepository;

    public MarketController(CurrentUserService currentUserService,
                            PersonProfileService profileService,
                            PersonalAccountingService accountingService,
                            MarketTradingService tradingService,
                            MarketQueryService queryService,
                            TraderAdviserService traderAdviserService,
                            RegentEconomyProperties properties,
                            GameCalendarRepository calendarRepository) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountingService = accountingService;
        this.tradingService = tradingService;
        this.queryService = queryService;
        this.traderAdviserService = traderAdviserService;
        this.properties = properties;
        this.calendarRepository = calendarRepository;
    }

    @GetMapping("/market/instruments")
    public List<MarketDtos.InstrumentView> instruments() {
        ensureRegentEnabled();
        return queryService.instruments();
    }

    @GetMapping("/market/instruments/{instrumentId}/history")
    public List<MarketDtos.PriceView> history(@PathVariable long instrumentId,
                                              @RequestParam(defaultValue = "30") int limit) {
        ensureRegentEnabled();
        return queryService.history(instrumentId, limit);
    }

    @GetMapping("/me/portfolio")
    public MarketDtos.PortfolioView portfolio() {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.portfolio(profile.getId());
    }

    @GetMapping("/me/trades")
    public MarketDtos.TradePage trades(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.trades(profile.getId(), page, size);
    }

    @PostMapping("/me/trades")
    public MarketDtos.TradeView trade(@Valid @RequestBody MarketDtos.TradeRequest request) {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.tradeView(tradingService.trade(profile, request.instrumentId(), request.side(),
                request.quantity(), request.idempotencyKey()));
    }

    @GetMapping("/me/market-adviser")
    public MarketDtos.AdviserDashboardView adviserDashboard() {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        return queryService.adviserDashboard(profile.getId());
    }

    @PostMapping("/me/market-adviser/hire")
    public MarketDtos.AdviserContractView hireAdviser(@Valid @RequestBody MarketDtos.HireAdviserRequest request) {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        GameDate current = currentDate();
        return queryService.contractView(traderAdviserService.hire(profile, currentUserService.requireUser().getId(),
                request.optionCode(), current.season(), current.day(), request.idempotencyKey()));
    }

    @PostMapping("/market/instruments/{instrumentId}/advice")
    public MarketDtos.AdviceView requestAdvice(@PathVariable long instrumentId) {
        ensureRegentEnabled();
        PersonProfile profile = currentProfile();
        accountingService.ensureAccount(profile);
        GameDate current = currentDate();
        return queryService.adviceView(traderAdviserService.advise(profile, currentUserService.requireUser().getId(),
                instrumentId, current.season(), current.day()));
    }

    private PersonProfile currentProfile() {
        return profileService.requireForUser(currentUserService.requireUser());
    }

    private void ensureRegentEnabled() {
        if (!properties.isEnabled()) {
            throw new EconomyConflictException("REGENT_FEATURE_DISABLED",
                    "Regent market is disabled until the feature flag is enabled");
        }
    }

    private GameDate currentDate() {
        return calendarRepository.findTopByOrderBySeasonDesc()
                .map(value -> new GameDate(value.getSeason(), Math.max(1, value.getCurrentDay())))
                .orElse(new GameDate(1, 1));
    }

    private record GameDate(int season, int day) { }
}
