package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase2-market;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase2MarketIT {
    @Autowired private UserService userService;
    @Autowired private PersonProfileService profileService;
    @Autowired private PersonalAccountRepository accountRepository;
    @Autowired private PersonalLedgerEntryRepository ledgerRepository;
    @Autowired private PersonalAccountingService accountingService;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private MarketPriceSnapshotRepository snapshotRepository;
    @Autowired private PortfolioPositionRepository positionRepository;
    @Autowired private MarketTradeRepository tradeRepository;
    @Autowired private DeterministicMarketPriceService priceService;
    @Autowired private MarketTradingService tradingService;
    @Autowired private MarketQueryService queryService;
    @Autowired private WealthQueryService wealthQueryService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void dailyPricesAreBoundedDeterministicAndFastForwardCatchesUpWithoutRerolling() {
        assertThat(instrumentRepository.findAll()).extracting(MarketInstrument::getInstrumentType)
                .contains(MarketInstrumentType.COMPANY, MarketInstrumentType.CLUB);
        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();
        priceService.processDay(1, 35);
        List<MarketPriceSnapshot> first = snapshotRepository
                .findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                        instrument.getId(), org.springframework.data.domain.Pageable.unpaged());
        assertThat(first).hasSize(35);
        assertPriceBounds(instrument, first);
        List<Long> closes = first.stream().map(MarketPriceSnapshot::getClosePrice).toList();

        priceService.processDay(1, 35);
        assertThat(snapshotRepository.findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                instrument.getId(), org.springframework.data.domain.Pageable.unpaged()))
                .extracting(MarketPriceSnapshot::getClosePrice).containsExactlyElementsOf(closes);

        priceService.processDay(1, 60);
        List<MarketPriceSnapshot> caughtUp = snapshotRepository
                .findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                        instrument.getId(), org.springframework.data.domain.Pageable.unpaged());
        assertThat(caughtUp).hasSize(60);
        assertPriceBounds(instrument, caughtUp);
        for (MarketInstrument active : instrumentRepository.findAllByActiveTrueOrderByCodeAsc()) {
            List<MarketPriceSnapshot> snapshots = snapshotRepository
                    .findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                            active.getId(), org.springframework.data.domain.Pageable.unpaged());
            assertThat(snapshots).as(active.getCode()).hasSize(60);
            assertThat(snapshots).extracting(MarketPriceSnapshot::getAlgorithmVersion)
                    .containsOnly(DeterministicMarketPriceService.MARKET_V1);
            assertPriceBounds(active, snapshots);
        }
    }

    @Test
    void buySellRetryCostBasisAndWealthReconcileAtServerPrices() {
        PersonProfile profile = profileService.requireForUser(registerChairman("market-ledger", 5_000_000L));
        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();
        long instrumentId = instrument.getId();
        long price = instrument.getCurrentPrice();

        MarketTradingService.TradeResult buy = tradingService.trade(
                profile, instrumentId, MarketTradeSide.BUY, 1_000, "buy-1000");
        MarketTradingService.TradeResult replay = tradingService.trade(
                profile, instrumentId, MarketTradeSide.BUY, 1_000, "buy-1000");
        assertThat(buy.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(tradeRepository.findAll()).filteredOn(value -> value.getAccountId() == buy.trade().getAccountId())
                .hasSize(1);

        instrument = instrumentRepository.findById(instrumentId).orElseThrow();
        instrument.setCurrentPrice(price + 100);
        instrumentRepository.saveAndFlush(instrument);
        MarketTradingService.TradeResult sell = tradingService.trade(
                profile, instrumentId, MarketTradeSide.SELL, 400, "sell-400");
        assertThat(sell.trade().getCostBasisAmount()).isEqualTo(price * 400);
        assertThat(sell.trade().getRealizedGain()).isEqualTo(40_000L);

        PersonalAccount account = accountRepository.findByProfileId(profile.getId()).orElseThrow();
        PortfolioPosition position = positionRepository.findByAccountIdAndInstrumentId(
                account.getId(), instrumentId).orElseThrow();
        assertThat(position.getQuantity()).isEqualTo(600);
        assertThat(position.getTotalCostBasis()).isEqualTo(price * 600);
        assertThat(account.getCashBalance()).isEqualTo(5_000_000L - price * 1_000 + (price + 100) * 400);
        assertThat(account.getRealizedInvestmentGain()).isEqualTo(40_000L);
        assertSupplyConserved(instrumentId);
        accountingService.assertReconciled(account.getId());

        MarketDtos.PortfolioView portfolio = queryService.portfolio(profile.getId());
        assertThat(portfolio.marketValue().amount()).isEqualTo((price + 100) * 600);
        assertThat(portfolio.unrealizedGain().amount()).isEqualTo(60_000L);
        assertThat(wealthQueryService.wealth(profile.getId()).investmentValue().amount())
                .isEqualTo(portfolio.marketValue().amount());

        assertThatThrownBy(() -> tradingService.trade(
                profile, instrumentId, MarketTradeSide.SELL, 401, "buy-1000"))
                .isInstanceOf(EconomyConflictException.class)
                .hasMessageContaining("different trade");
    }

    @Test
    void concurrentLastShareAndLastCashHaveOneWinnerWithoutMintingOrOverdraft() throws Exception {
        PersonProfile first = profileService.requireForUser(registerChairman("last-share-a", 1_000_000L));
        PersonProfile second = profileService.requireForUser(registerChairman("last-share-b", 1_000_000L));
        MarketInstrument lastShare = instrument("RACE-SUPPLY", 100, 1);

        CountDownLatch shareStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> left = pool.submit(() -> tradeAfter(shareStart, first, lastShare, "share-a"));
            Future<String> right = pool.submit(() -> tradeAfter(shareStart, second, lastShare, "share-b"));
            shareStart.countDown();
            assertThat(List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INSUFFICIENT_SUPPLY");
        }
        assertSupplyConserved(lastShare.getId());

        PersonProfile cashOwner = profileService.requireForUser(registerChairman("last-cash", 1_000_000L));
        MarketInstrument cashA = instrument("RACE-CASH-A", 1_000_000L, 1);
        MarketInstrument cashB = instrument("RACE-CASH-B", 1_000_000L, 1);
        CountDownLatch cashStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> left = pool.submit(() -> tradeAfter(cashStart, cashOwner, cashA, "cash-a"));
            Future<String> right = pool.submit(() -> tradeAfter(cashStart, cashOwner, cashB, "cash-b"));
            cashStart.countDown();
            assertThat(List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INSUFFICIENT_FUNDS");
        }
        PersonalAccount account = accountRepository.findByProfileId(cashOwner.getId()).orElseThrow();
        assertThat(account.getCashBalance()).isZero();
        assertThat(positionRepository.findAllByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(
                account.getId(), 0)).hasSize(1);
        assertSupplyConserved(cashA.getId());
        assertSupplyConserved(cashB.getId());
        accountingService.assertReconciled(account.getId());
    }

    @Test
    void apiUsesAuthenticatedPrincipalAndServerPriceIgnoringSpoofedFields() throws Exception {
        registerChairman("market-api-a", 2_000_000L);
        registerChairman("market-api-b", 2_000_000L);
        MockHttpSession first = login("market-api-a");
        MockHttpSession second = login("market-api-b");
        MarketInstrument instrument = instrumentRepository.findByCode("SPORTTECH").orElseThrow();

        mockMvc.perform(post("/api/me/trades").session(first).with(csrf())
                        .contentType("application/json")
                        .content("{\"instrumentId\":" + instrument.getId()
                                + ",\"side\":\"BUY\",\"quantity\":10,\"idempotencyKey\":\"api-buy\""
                                + ",\"unitPrice\":1,\"accountId\":999999,\"profileId\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice.amount").value(instrument.getCurrentPrice()))
                .andExpect(jsonPath("$.quantityAfter").value(10));
        mockMvc.perform(get("/api/me/portfolio").session(first).header("X-User-Id", "999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.positions[0].quantity").value(10));
        mockMvc.perform(get("/api/me/portfolio").session(second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.positions").isEmpty());
    }

    @Test
    void overflowAfterLedgerPostingRollsBackCashHoldingSupplyAndTrade() {
        PersonProfile profile = profileService.requireForUser(registerChairman("market-overflow", 1_000_000L));
        MarketInstrument instrument = instrument("OVERFLOW-ROLLBACK", 100L, 1L);
        tradingService.trade(profile, instrument.getId(), MarketTradeSide.BUY, 1L, "overflow-buy");

        instrument = instrumentRepository.findById(instrument.getId()).orElseThrow();
        instrument.setCurrentPrice(200L);
        instrumentRepository.saveAndFlush(instrument);
        PersonalAccount account = accountRepository.findByProfileId(profile.getId()).orElseThrow();
        account.setRealizedInvestmentGain(Long.MAX_VALUE);
        accountRepository.saveAndFlush(account);
        long cashBefore = account.getCashBalance();
        long ledgerBefore = ledgerRepository.count();
        long tradesBefore = tradeRepository.count();

        long instrumentId = instrument.getId();
        assertThatThrownBy(() -> tradingService.trade(
                profile, instrumentId, MarketTradeSide.SELL, 1L, "overflow-sell"))
                .isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("MONEY_OVERFLOW"));

        account = accountRepository.findByProfileId(profile.getId()).orElseThrow();
        PortfolioPosition position = positionRepository.findByAccountIdAndInstrumentId(
                account.getId(), instrumentId).orElseThrow();
        assertThat(account.getCashBalance()).isEqualTo(cashBefore);
        assertThat(account.getRealizedInvestmentGain()).isEqualTo(Long.MAX_VALUE);
        assertThat(position.getQuantity()).isOne();
        assertThat(position.getTotalCostBasis()).isEqualTo(100L);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerBefore);
        assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
        assertSupplyConserved(instrumentId);

        assertThatThrownBy(() -> DeterministicMarketPriceService.applyBps(Long.MAX_VALUE, 700))
                .isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("MONEY_OVERFLOW"));
    }

    private void assertPriceBounds(MarketInstrument instrument, List<MarketPriceSnapshot> newestFirst) {
        List<MarketPriceSnapshot> chronological = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(chronological);
        for (MarketPriceSnapshot value : chronological) {
            long dailyMove = Math.abs(value.getClosePrice() - value.getPreviousClose()) * 10_000L;
            if (instrument.getRiskClass() == com.footballmanagergamesimulator.regent.market.core.MarketRiskClass.SAFE_COMPANY) {
                assertThat(dailyMove).isLessThanOrEqualTo(value.getPreviousClose() * 100L + 10_000L);
            } else if (instrument.getRiskClass()
                    == com.footballmanagergamesimulator.regent.market.core.MarketRiskClass.SPECULATIVE) {
                assertThat(dailyMove).isLessThanOrEqualTo(value.getPreviousClose() * 5_000L + 10_000L);
            } else {
                assertThat(value.getClosePrice()).isPositive();
            }
        }
    }

    private String tradeAfter(CountDownLatch start, PersonProfile profile,
                              MarketInstrument instrument, String key) throws InterruptedException {
        start.await();
        try {
            tradingService.trade(profile, instrument.getId(), MarketTradeSide.BUY, 1, key);
            return "OK";
        } catch (EconomyConflictException exception) {
            return exception.getCode();
        }
    }

    private MarketInstrument instrument(String code, long price, long supply) {
        MarketInstrument value = new MarketInstrument();
        value.setCode(code);
        value.setInstrumentType(MarketInstrumentType.COMPANY);
        value.setName(code);
        value.setTotalSupply(supply);
        value.setAvailableSupply(supply);
        value.setCurrentPrice(price);
        value.setPriceSeed(MarketBootstrapService.stableSeed(code));
        value.setDailyLimitBps(500);
        value.setWeeklyLimitBps(1_500);
        value.setActive(true);
        return instrumentRepository.saveAndFlush(value);
    }

    private void assertSupplyConserved(long instrumentId) {
        MarketInstrument value = instrumentRepository.findById(instrumentId).orElseThrow();
        assertThat(value.getAvailableSupply() + positionRepository.sumQuantityByInstrumentId(instrumentId))
                .isEqualTo(value.getTotalSupply());
    }

    private User registerChairman(String username, long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, CareerRole.CHAIRMAN, wealth));
    }

    private MockHttpSession login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username, "password", "correct-password"));
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
