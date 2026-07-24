package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.regent.market.core.AdviserSignal;
import com.footballmanagergamesimulator.regent.market.core.MarketQuoteKey;
import com.footballmanagergamesimulator.regent.market.core.TraderAdvice;
import com.footballmanagergamesimulator.regent.market.core.TraderAdviceModel;
import com.footballmanagergamesimulator.regent.market.core.TraderAdviser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TraderAdviserService {
    static final String ADVICE_V1 = "advice-v1";
    private static final LocalDate DATE_EPOCH = LocalDate.of(2000, 1, 1);
    private static final Map<String, AdviserTerms> CATALOG = catalog();

    private final TraderAdviserContractRepository contractRepository;
    private final TraderAdviceRecommendationRepository adviceRepository;
    private final PersonalAccountRepository accountRepository;
    private final PersonalAccountingService accountingService;
    private final MarketInstrumentRepository instrumentRepository;
    private final MarketPriceSnapshotRepository snapshotRepository;

    public TraderAdviserService(TraderAdviserContractRepository contractRepository,
                                TraderAdviceRecommendationRepository adviceRepository,
                                PersonalAccountRepository accountRepository,
                                PersonalAccountingService accountingService,
                                MarketInstrumentRepository instrumentRepository,
                                MarketPriceSnapshotRepository snapshotRepository) {
        this.contractRepository = contractRepository;
        this.adviceRepository = adviceRepository;
        this.accountRepository = accountRepository;
        this.accountingService = accountingService;
        this.instrumentRepository = instrumentRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /** Terms are selected from an immutable server-side catalog; callers never supply skill or salary. */
    @Transactional
    public HireResult hire(PersonProfile profile, int authenticatedUserId, String adviserCode,
                           int durationDays, int season, int day, String idempotencyKey) {
        requireOwnedChairman(profile, authenticatedUserId);
        validateDate(season, day);
        validateKey(idempotencyKey);
        if (durationDays < 1 || durationDays > 3_660) {
            throw new IllegalArgumentException("durationDays must be in [1, 3660]");
        }
        AdviserTerms terms = CATALOG.get(adviserCode);
        if (terms == null) throw new EconomyConflictException("ADVISER_NOT_FOUND", "Trader adviser is unavailable");
        long start = absoluteDay(season, day);
        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        requireAccountOwner(account, authenticatedUserId);
        TraderAdviserContract replay = contractRepository
                .findByAccountIdAndHireIdempotencyKey(account.getId(), idempotencyKey).orElse(null);
        if (replay != null) {
            if (!replay.getAdviserCode().equals(adviserCode)
                    || replay.getContractStartAbsoluteDay() != start
                    || replay.getContractEndAbsoluteDay() - replay.getContractStartAbsoluteDay() + 1 != durationDays) {
                throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for different adviser terms");
            }
            return new HireResult(replay, true);
        }
        if (contractRepository.findActiveForUpdate(profile.getId(), start).isPresent()) {
            throw new EconomyConflictException("ADVISER_ALREADY_HIRED", "An active trader adviser contract already exists");
        }
        TraderAdviserContract contract = new TraderAdviserContract();
        contract.setAccountId(account.getId());
        contract.setProfileId(profile.getId());
        contract.setAdviserCode(adviserCode);
        contract.setSkill(terms.skill());
        contract.setReputation(terms.reputation());
        contract.setSalaryPerDay(terms.salaryPerDay());
        contract.setContractStartAbsoluteDay(start);
        contract.setContractEndAbsoluteDay(Math.addExact(start, durationDays - 1L));
        contract.setLastPaidAbsoluteDay(start - 1L);
        contract.setAdviceSeed(MarketBootstrapService.stableSeed(adviserCode + ':' + profile.getId() + ':' + start));
        contract.setModelVersion(ADVICE_V1);
        contract.setHireIdempotencyKey(idempotencyKey);
        contract.setActive(true);
        return new HireResult(contractRepository.save(contract), false);
    }

    /** Pays every missing contractual day, making direct catch-up and ordinary daily Fast Forward equivalent. */
    @Transactional
    public void processDailyPayroll(int season, int day) {
        if (season < 1 || day < 1 || day > 366) return;
        long through = absoluteDay(season, day);
        List<Long> ids = contractRepository.findAllByActiveTrueOrderByIdAsc().stream()
                .map(TraderAdviserContract::getId).toList();
        for (long id : ids) {
            TraderAdviserContract contract = contractRepository.findByIdForUpdate(id).orElse(null);
            if (contract == null || !contract.isActive()) continue;
            PersonalAccount account = accountRepository.findByIdForUpdate(contract.getAccountId())
                    .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Adviser account is missing"));
            long lastDue = Math.min(through, contract.getContractEndAbsoluteDay());
            for (long due = Math.max(contract.getContractStartAbsoluteDay(), contract.getLastPaidAbsoluteDay() + 1L);
                 due <= lastDue; due++) {
                if (account.getCashBalance() < contract.getSalaryPerDay()) {
                    contract.setActive(false);
                    contract.setTerminationReason("INSUFFICIENT_FUNDS");
                    contractRepository.save(contract);
                    break;
                }
                GameDate date = gameDate(due);
                String key = "TRADER-ADVISER-SALARY:" + contract.getId() + ':' + due;
                accountingService.postLocked(account, LedgerEntryType.TRADER_ADVISER_SALARY,
                        Math.negateExact(contract.getSalaryPerDay()), 0, date.season(), date.day(), key, key,
                        null, null, "Trader adviser daily salary: " + contract.getAdviserCode());
                contract.setLastPaidAbsoluteDay(due);
            }
            if (through >= contract.getContractEndAbsoluteDay()
                    && contract.getLastPaidAbsoluteDay() >= contract.getContractEndAbsoluteDay()) {
                contract.setActive(false);
                contract.setTerminationReason("CONTRACT_COMPLETED");
            }
            contractRepository.save(contract);
        }
    }

    /** Uses only snapshots at or before the requested day; persisted replay cannot see a later quote. */
    @Transactional
    public AdviceResult advise(PersonProfile profile, int authenticatedUserId, long instrumentId,
                               int season, int day) {
        requireOwnedChairman(profile, authenticatedUserId);
        validateDate(season, day);
        long absolute = absoluteDay(season, day);
        TraderAdviserContract contract = contractRepository.findActiveForUpdate(profile.getId(), absolute)
                .orElseThrow(() -> new EconomyConflictException("ACTIVE_ADVISER_REQUIRED",
                        "An active trader adviser is required"));
        PersonalAccount account = accountRepository.findByIdForUpdate(contract.getAccountId()).orElseThrow();
        requireAccountOwner(account, authenticatedUserId);
        MarketInstrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument is missing"));
        TraderAdviceRecommendation replay = adviceRepository
                .findByContractIdAndInstrumentIdAndSeasonNumberAndGameDay(contract.getId(), instrumentId, season, day)
                .orElse(null);
        if (replay != null) return new AdviceResult(replay, true);

        List<MarketPriceSnapshot> observed = snapshotRepository.findObservedThrough(
                instrumentId, season, day, PageRequest.of(0, 8));
        Observation observation = observation(observed, instrument.getCurrentPrice());
        TraderAdviser adviser = new TraderAdviser(contract.getAdviserCode(), contract.getSkill(),
                contract.getReputation(), BigDecimal.valueOf(contract.getSalaryPerDay()),
                DATE_EPOCH, DATE_EPOCH.plusDays(
                        contract.getContractEndAbsoluteDay() - contract.getContractStartAbsoluteDay()),
                contract.getModelVersion());
        AdviserSignal signal = new AdviserSignal(instrument.getCode(), instrument.getRiskClass(),
                observation.trailingReturn(), observation.volatility());
        MarketQuoteKey key = new MarketQuoteKey(contract.getAdviceSeed() ^ instrument.getPriceSeed(),
                instrument.getCode(), absolute, "11:" + instrument.getRiskConfigVersion());
        TraderAdvice advice = new TraderAdviceModel().recommend(key, adviser, signal);

        TraderAdviceRecommendation recommendation = new TraderAdviceRecommendation();
        recommendation.setContractId(contract.getId());
        recommendation.setInstrumentId(instrumentId);
        recommendation.setSeasonNumber(season);
        recommendation.setGameDay(day);
        recommendation.setAction(advice.action());
        recommendation.setRiskClass(instrument.getRiskClass());
        recommendation.setHorizonDays(advice.horizonDays());
        recommendation.setConfidence(advice.confidence());
        recommendation.setRisk(advice.risk());
        recommendation.setTrailingReturn(observation.trailingReturn());
        recommendation.setObservedVolatility(observation.volatility());
        recommendation.setExplanation(advice.explanation());
        recommendation.setModelVersion(advice.modelVersion());
        return new AdviceResult(adviceRepository.save(recommendation), false);
    }

    private static Observation observation(List<MarketPriceSnapshot> values, long fallbackPrice) {
        if (values.isEmpty()) return new Observation(BigDecimal.ZERO, BigDecimal.ZERO);
        MarketPriceSnapshot latest = values.get(0);
        MarketPriceSnapshot oldest = values.get(values.size() - 1);
        long opening = oldest.getPreviousClose() > 0 ? oldest.getPreviousClose() : fallbackPrice;
        BigDecimal trailing = BigDecimal.valueOf(latest.getClosePrice()).subtract(BigDecimal.valueOf(opening))
                .divide(BigDecimal.valueOf(opening), MathContext.DECIMAL128)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal volatility = values.stream()
                .map(value -> BigDecimal.valueOf(Math.abs((long) value.getDailyChangeBps()), 4))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
        return new Observation(trailing, volatility);
    }

    private static void requireOwnedChairman(PersonProfile profile, int authenticatedUserId) {
        if (profile == null || profile.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
        if (profile.getUserId() == null || profile.getUserId() != authenticatedUserId) {
            throw new EconomyConflictException("PROFILE_OWNERSHIP_REQUIRED",
                    "Authenticated user does not own this chairman profile");
        }
    }

    private static void requireAccountOwner(PersonalAccount account, int authenticatedUserId) {
        if (account.getOwnerUserId() == null || account.getOwnerUserId() != authenticatedUserId) {
            throw new EconomyConflictException("ACCOUNT_OWNERSHIP_REQUIRED",
                    "Authenticated user does not own this personal account");
        }
    }

    private static long absoluteDay(int season, int day) {
        validateDate(season, day);
        return Math.addExact(Math.multiplyExact((long) season - 1L, 366L), day);
    }

    private static GameDate gameDate(long absoluteDay) {
        long zeroBased = absoluteDay - 1L;
        return new GameDate(Math.toIntExact(zeroBased / 366L + 1L), Math.toIntExact(zeroBased % 366L + 1L));
    }

    private static void validateDate(int season, int day) {
        if (season < 1 || day < 1 || day > 366) throw new IllegalArgumentException("Invalid game date");
    }

    private static void validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 140) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 140 characters");
        }
    }

    private static Map<String, AdviserTerms> catalog() {
        Map<String, AdviserTerms> result = new LinkedHashMap<>();
        result.put("ANALYST", new AdviserTerms(45, 35, 2_500L));
        result.put("STRATEGIST", new AdviserTerms(70, 65, 7_500L));
        result.put("VETERAN", new AdviserTerms(90, 92, 20_000L));
        return Map.copyOf(result);
    }

    public record HireResult(TraderAdviserContract contract, boolean replay) { }
    public record AdviceResult(TraderAdviceRecommendation recommendation, boolean replay) { }
    record AdviserTerms(int skill, int reputation, long salaryPerDay) { }
    private record Observation(BigDecimal trailingReturn, BigDecimal volatility) { }
    private record GameDate(int season, int day) { }
}
