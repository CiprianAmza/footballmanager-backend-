package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketQueryService {
    private final MarketInstrumentRepository instrumentRepository;
    private final MarketPriceSnapshotRepository snapshotRepository;
    private final PortfolioPositionRepository positionRepository;
    private final MarketTradeRepository tradeRepository;
    private final PersonalAccountRepository accountRepository;
    private final RegentEconomyProperties properties;
    private final ClubValuationService clubValuationService;
    private final TraderAdviserService traderAdviserService;
    private final TraderAdviserContractRepository contractRepository;
    private final TraderAdviceRecommendationRepository adviceRepository;
    private final GameCalendarRepository calendarRepository;

    public MarketQueryService(MarketInstrumentRepository instrumentRepository,
                              MarketPriceSnapshotRepository snapshotRepository,
                              PortfolioPositionRepository positionRepository,
                              MarketTradeRepository tradeRepository,
                              PersonalAccountRepository accountRepository,
                              RegentEconomyProperties properties,
                              ClubValuationService clubValuationService,
                              TraderAdviserService traderAdviserService,
                              TraderAdviserContractRepository contractRepository,
                              TraderAdviceRecommendationRepository adviceRepository,
                              GameCalendarRepository calendarRepository) {
        this.instrumentRepository = instrumentRepository;
        this.snapshotRepository = snapshotRepository;
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.clubValuationService = clubValuationService;
        this.traderAdviserService = traderAdviserService;
        this.contractRepository = contractRepository;
        this.adviceRepository = adviceRepository;
        this.calendarRepository = calendarRepository;
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.InstrumentView> instruments() {
        return instrumentRepository.findAllByActiveTrueOrderByCodeAsc().stream().map(this::instrumentView).toList();
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.PriceView> history(long instrumentId, int limit) {
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument was not found");
        }
        int safeLimit = Math.max(1, Math.min(366, limit));
        return snapshotRepository.findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                instrumentId, PageRequest.of(0, safeLimit)).stream().map(this::priceView).toList();
    }

    @Transactional(readOnly = true)
    public MarketDtos.PortfolioView portfolio(long profileId) {
        PersonalAccount account = requireAccount(profileId);
        Map<Long, MarketInstrument> instruments = instrumentMap();
        long totalBasis = 0;
        long totalMarket = 0;
        java.util.ArrayList<MarketDtos.PositionView> views = new java.util.ArrayList<>();
        for (PortfolioPosition position : positionRepository
                .findAllByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(account.getId(), 0)) {
            MarketInstrument instrument = instruments.get(position.getInstrumentId());
            if (instrument == null) continue;
            long marketValue = multiply(position.getQuantity(), instrument.getCurrentPrice());
            long unrealized = subtract(marketValue, position.getTotalCostBasis());
            totalBasis = add(totalBasis, position.getTotalCostBasis());
            totalMarket = add(totalMarket, marketValue);
            views.add(new MarketDtos.PositionView(instrument.getId(), instrument.getCode(),
                    instrument.getInstrumentType(), instrument.getTeamId(), instrument.getName(),
                    instrument.getRiskClass(),
                    position.getQuantity(), money(position.getTotalCostBasis()), money(marketValue), money(unrealized)));
        }
        return new MarketDtos.PortfolioView(List.copyOf(views), money(totalBasis), money(totalMarket),
                money(subtract(totalMarket, totalBasis)), money(account.getRealizedInvestmentGain()));
    }

    @Transactional(readOnly = true)
    public MarketDtos.TradePage trades(long profileId, int page, int size) {
        PersonalAccount account = requireAccount(profileId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Map<Long, MarketInstrument> instruments = instrumentMap();
        var result = tradeRepository.findAllByAccountId(account.getId(),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        return new MarketDtos.TradePage(result.stream()
                .map(trade -> tradeView(trade, instruments.get(trade.getInstrumentId()), false)).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public MarketDtos.TradeView tradeView(MarketTradingService.TradeResult result) {
        MarketInstrument instrument = instrumentRepository.findById(result.trade().getInstrumentId())
                .orElseThrow(() -> new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument is missing"));
        return tradeView(result.trade(), instrument, result.replayed());
    }

    private MarketDtos.InstrumentView instrumentView(MarketInstrument value) {
        ClubValuationService.Valuation valuation = value.getInstrumentType() == MarketInstrumentType.CLUB
                ? clubValuationService.value(value.getTeamId()) : null;
        return new MarketDtos.InstrumentView(value.getId(), value.getCode(), value.getInstrumentType(),
                value.getTeamId(), value.getName(), money(value.getCurrentPrice()), value.getTotalSupply(),
                value.getAvailableSupply(), value.getRiskClass(), value.getDailyLimitBps(), value.getWeeklyLimitBps(),
                value.getPriceAlgorithmVersion(), valuation == null ? null : money(valuation.totalValue()),
                valuation == null ? null : valuation.formulaVersion());
    }

    private MarketDtos.PriceView priceView(MarketPriceSnapshot value) {
        return new MarketDtos.PriceView(value.getId(), value.getSeasonNumber(), value.getGameDay(),
                money(value.getPreviousClose()), money(value.getClosePrice()),
                money(value.getWeeklyAnchorPrice()), value.getDailyChangeBps(), value.getAlgorithmVersion());
    }

    private MarketDtos.TradeView tradeView(MarketTrade trade, MarketInstrument instrument, boolean replayed) {
        String code = instrument == null ? "UNKNOWN" : instrument.getCode();
        return new MarketDtos.TradeView(trade.getId(), trade.getInstrumentId(), code, trade.getSide(),
                trade.getQuantity(), money(trade.getUnitPrice()), money(trade.getGrossAmount()),
                money(trade.getCostBasisAmount()), money(trade.getRealizedGain()), trade.getSeasonNumber(),
                trade.getGameDay(), trade.getIdempotencyKey(), money(trade.getCashBalanceAfter()),
                trade.getQuantityAfter(), money(trade.getCostBasisAfter()), replayed);
    }

    private Map<Long, MarketInstrument> instrumentMap() {
        Map<Long, MarketInstrument> result = new HashMap<>();
        instrumentRepository.findAll().forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private PersonalAccount requireAccount(long profileId) {
        return accountRepository.findByProfileId(profileId)
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
    }

    @Transactional(readOnly = true)
    public MarketDtos.AdviserDashboardView adviserDashboard(long profileId) {
        GameCalendar calendar = currentCalendar();
        TraderAdviserContract contract = traderAdviserService.latestContract(profileId);
        return new MarketDtos.AdviserDashboardView(gameDateView(calendar), contractView(contract, false),
                traderAdviserService.catalogueOptions().stream().map(this::optionView).toList());
    }

    @Transactional(readOnly = true)
    public MarketDtos.AdviserContractView contractView(TraderAdviserService.HireResult result) {
        return contractView(result.contract(), result.replay());
    }

    @Transactional(readOnly = true)
    public MarketDtos.AdviceView adviceView(TraderAdviserService.AdviceResult result) {
        TraderAdviceRecommendation recommendation = result.recommendation();
        MarketInstrument instrument = instrumentRepository.findById(recommendation.getInstrumentId())
                .orElseThrow(() -> new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument is missing"));
        return new MarketDtos.AdviceView(recommendation.getId(), recommendation.getInstrumentId(),
                instrument.getCode(), instrument.getName(), recommendation.getAction(), recommendation.getRiskClass(),
                recommendation.getSeasonNumber(), recommendation.getGameDay(), recommendation.getHorizonDays(),
                recommendation.getConfidence(), recommendation.getRisk(), recommendation.getTrailingReturn(),
                recommendation.getObservedVolatility(), recommendation.getExplanation(),
                recommendation.getModelVersion(), result.replay());
    }

    @Transactional(readOnly = true)
    public MarketDtos.AdviceView latestAdvice(long profileId, long instrumentId) {
        TraderAdviserContract contract = contractRepository.findTopByProfileIdOrderByIdDesc(profileId)
                .orElseThrow(() -> new EconomyConflictException("ACTIVE_ADVISER_REQUIRED",
                        "An active trader adviser is required"));
        TraderAdviceRecommendation recommendation = adviceRepository
                .findByContractIdAndInstrumentIdAndSeasonNumberAndGameDay(contract.getId(), instrumentId,
                        currentCalendar().getSeason(), Math.max(1, currentCalendar().getCurrentDay()))
                .orElseThrow(() -> new EconomyConflictException("ADVICE_NOT_FOUND",
                        "No trader advice exists for this instrument on the current day"));
        return adviceView(new TraderAdviserService.AdviceResult(recommendation, true));
    }

    private MarketDtos.AdviserContractView contractView(TraderAdviserContract contract, boolean replayed) {
        if (contract == null) return null;
        return new MarketDtos.AdviserContractView(contract.getId(), contract.getAdviserCode(),
                adviserName(contract.getAdviserCode()), contract.getSkill(), contract.getReputation(),
                money(contract.getSalaryPerDay()), gameDateView(contract.getContractStartAbsoluteDay()),
                gameDateView(contract.getContractEndAbsoluteDay()), contractStatus(contract),
                contract.getTerminationReason(), contract.getModelVersion(), replayed);
    }

    private MarketDtos.AdviserHireOptionView optionView(TraderAdviserService.AdviserTerms terms) {
        return new MarketDtos.AdviserHireOptionView(terms.adviserCode(), adviserName(terms.adviserCode()),
                terms.skill(), terms.reputation(), money(terms.salaryPerDay()), terms.durationDays(),
                TraderAdviserService.ADVICE_V1);
    }

    private GameCalendar currentCalendar() {
        return calendarRepository.findTopByOrderBySeasonDesc().orElseGet(() -> {
            GameCalendar fallback = new GameCalendar();
            fallback.setSeason(1);
            fallback.setCurrentDay(1);
            return fallback;
        });
    }

    private MarketDtos.GameDateView gameDateView(GameCalendar calendar) {
        return new MarketDtos.GameDateView(calendar.getSeason(), Math.max(1, calendar.getCurrentDay()));
    }

    private MarketDtos.GameDateView gameDateView(long absoluteDay) {
        long zeroBased = absoluteDay - 1L;
        return new MarketDtos.GameDateView(Math.toIntExact(zeroBased / 366L + 1L),
                Math.toIntExact(zeroBased % 366L + 1L));
    }

    private static String adviserName(String code) {
        return switch (code) {
            case "ANALYST" -> "Market Analyst";
            case "STRATEGIST" -> "Market Strategist";
            case "VETERAN" -> "Veteran Trader";
            default -> code;
        };
    }

    private static String contractStatus(TraderAdviserContract contract) {
        if (contract.isActive()) return "ACTIVE";
        return contract.getTerminationReason() == null || contract.getTerminationReason().isBlank()
                ? "INACTIVE" : contract.getTerminationReason();
    }

    private EconomyDtos.Money money(long amount) {
        return new EconomyDtos.Money(amount, properties.getEconomy().getCurrency(), 0);
    }

    private static long multiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }
    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }
    private static long subtract(long left, long right) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }
    private static EconomyConflictException overflow() {
        return new EconomyConflictException("MONEY_OVERFLOW", "Portfolio value exceeds supported range");
    }
}
