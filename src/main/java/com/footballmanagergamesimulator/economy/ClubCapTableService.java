package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.Ownership;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.OwnershipRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClubCapTableService {
    public static final int MIGRATION_VERSION = 1;

    private final MarketBootstrapService marketBootstrapService;
    private final MarketInstrumentRepository instrumentRepository;
    private final PortfolioPositionRepository positionRepository;
    private final PersonalAccountRepository accountRepository;
    private final PersonProfileRepository profileRepository;
    private final ClubShareholdingRepository legacyShareRepository;
    private final OwnershipRepository legacyOwnershipRepository;
    private final ClubCapTableStateRepository stateRepository;
    private final RegentEconomyProperties properties;

    public ClubCapTableService(MarketBootstrapService marketBootstrapService,
                               MarketInstrumentRepository instrumentRepository,
                               PortfolioPositionRepository positionRepository,
                               PersonalAccountRepository accountRepository,
                               PersonProfileRepository profileRepository,
                               ClubShareholdingRepository legacyShareRepository,
                               OwnershipRepository legacyOwnershipRepository,
                               ClubCapTableStateRepository stateRepository,
                               RegentEconomyProperties properties) {
        this.marketBootstrapService = marketBootstrapService;
        this.instrumentRepository = instrumentRepository;
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
        this.profileRepository = profileRepository;
        this.legacyShareRepository = legacyShareRepository;
        this.legacyOwnershipRepository = legacyOwnershipRepository;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(40)
    @Transactional
    public void initializeOnStartup() {
        if (properties.isEnabled()) ensureAllMigrated();
    }

    @Transactional
    public void ensureAllMigrated() {
        marketBootstrapService.ensureAllInstruments();
        for (MarketInstrument instrument : instrumentRepository.findAllByActiveTrueOrderByCodeAsc()) {
            if (instrument.getInstrumentType() == MarketInstrumentType.CLUB) ensureMigrated(instrument.getTeamId());
        }
    }

    @Transactional
    public CapTable ensureMigrated(long teamId) {
        MarketInstrument listed = instrumentRepository.findByTeamId(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_INSTRUMENT_NOT_FOUND", "Club instrument is missing"));
        MarketInstrument instrument = instrumentRepository.findByIdForUpdate(listed.getId()).orElseThrow();
        if (instrument.getInstrumentType() != MarketInstrumentType.CLUB) {
            throw new EconomyConflictException("INVALID_CLUB_INSTRUMENT", "Instrument is not a club cap table");
        }
        ClubCapTableState state = stateRepository.findByInstrumentIdForUpdate(instrument.getId()).orElseGet(() -> {
            ClubCapTableState created = new ClubCapTableState();
            created.setInstrumentId(instrument.getId());
            created.setTeamId(teamId);
            created.setMigrationVersion(0);
            created.setControlThresholdBps(controlThreshold());
            return stateRepository.saveAndFlush(created);
        });
        if (state.getMigrationVersion() < MIGRATION_VERSION) migrateLegacy(instrument, state);
        return reconcileLocked(instrument, state);
    }

    CapTable reconcileLocked(MarketInstrument instrument, ClubCapTableState state) {
        List<PortfolioPosition> allPositions = positionRepository
                .findAllByInstrumentIdForUpdate(instrument.getId());
        long held = 0;
        for (PortfolioPosition position : allPositions) {
            if (position.getQuantity() < 0 || position.getTotalCostBasis() < 0) {
                throw new EconomyConflictException("CAP_TABLE_INVALID", "Negative club holding is not allowed");
            }
            held = add(held, position.getQuantity());
        }
        if (held > instrument.getTotalSupply()) {
            throw new EconomyConflictException("CAP_TABLE_OVER_ALLOCATED", "Club holdings exceed issued shares");
        }
        List<PortfolioPosition> positions = allPositions.stream()
                .filter(position -> position.getQuantity() > 0).toList();
        instrument.setAvailableSupply(instrument.getTotalSupply() - held);
        Long controller = positions.stream()
                .filter(position -> controls(position.getQuantity(), instrument.getTotalSupply(), controlThreshold()))
                .map(PortfolioPosition::getAccountId).findFirst().orElse(null);
        long controllers = positions.stream()
                .filter(position -> controls(position.getQuantity(), instrument.getTotalSupply(), controlThreshold()))
                .count();
        if (controllers > 1) {
            throw new EconomyConflictException("MULTIPLE_CONTROLLERS", "Cap table derives more than one controller");
        }
        state.setControllingAccountId(controller);
        state.setControlThresholdBps(controlThreshold());
        state.setMigrationVersion(MIGRATION_VERSION);
        instrumentRepository.save(instrument);
        stateRepository.save(state);
        syncCompatibilityProjection(state.getTeamId(), instrument, positions, controller);
        return view(instrument, state, positions);
    }

    @Transactional(readOnly = true)
    public CapTable view(long teamId) {
        MarketInstrument instrument = instrumentRepository.findByTeamId(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_INSTRUMENT_NOT_FOUND", "Club instrument is missing"));
        ClubCapTableState state = stateRepository.findByInstrumentId(instrument.getId())
                .orElseThrow(() -> new EconomyConflictException("CAP_TABLE_NOT_MIGRATED", "Club cap table is not migrated"));
        return view(instrument, state, positionRepository
                .findAllByInstrumentIdAndQuantityGreaterThanOrderByAccountIdAsc(instrument.getId(), 0));
    }

    public boolean isController(long accountId, long teamId) {
        return stateRepository.findByTeamId(teamId)
                .map(ClubCapTableState::getControllingAccountId)
                .map(value -> value == accountId).orElse(false);
    }

    private void migrateLegacy(MarketInstrument instrument, ClubCapTableState state) {
        // A pre-Phase-3 save may contain both genuine Phase-2 market positions and
        // older Boardroom stakes. Legacy rows are additional holdings: group them
        // deterministically, merge them into the matching account position once,
        // and reject the whole transaction before writing if combined supply does
        // not fit. The migration version makes a successful merge replay-safe.
        List<PortfolioPosition> existing = positionRepository
                .findAllByInstrumentIdForUpdate(instrument.getId());
        Map<Long, PortfolioPosition> positionsByAccount = new java.util.TreeMap<>();
        long existingHeld = 0;
        for (PortfolioPosition position : existing) {
            if (position.getQuantity() < 0 || position.getTotalCostBasis() < 0) {
                throw new EconomyConflictException("CAP_TABLE_INVALID", "Negative club holding is not allowed");
            }
            existingHeld = add(existingHeld, position.getQuantity());
            positionsByAccount.put(position.getAccountId(), position);
        }
        Map<Long, BigDecimal> percentagesByHuman = new java.util.TreeMap<>();
        for (ClubShareholding legacy : legacyShareRepository.findAllByTeamId(state.getTeamId())) {
            if (!Double.isFinite(legacy.getPercent()) || legacy.getPercent() < 0) {
                throw new EconomyConflictException("CAP_TABLE_MIGRATION_INVALID", "Legacy share percentage is invalid");
            }
            percentagesByHuman.merge(legacy.getHumanId(), BigDecimal.valueOf(legacy.getPercent()), BigDecimal::add);
        }
        BigDecimal allocated = percentagesByHuman.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new EconomyConflictException("CAP_TABLE_OVER_ALLOCATED", "Legacy club stakes exceed 100 percent");
        }
        Map<Long, Long> legacyQuantityByAccount = new java.util.TreeMap<>();
        Map<Long, PersonalAccount> legacyAccounts = new HashMap<>();
        for (Map.Entry<Long, BigDecimal> entry : percentagesByHuman.entrySet()) {
            PersonalAccount account = accountRepository.findByOwnerHumanId(entry.getKey())
                    .orElseThrow(() -> new EconomyConflictException("CAP_TABLE_MIGRATION_IDENTITY_MISSING",
                            "Legacy shareholder has no canonical personal account"));
            long quantity = entry.getValue().multiply(BigDecimal.valueOf(instrument.getTotalSupply()))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
            if (entry.getValue().signum() > 0 && quantity == 0) {
                throw new EconomyConflictException("CAP_TABLE_MIGRATION_PRECISION_LOSS",
                        "Legacy club stake is smaller than one issued share");
            }
            if (quantity == 0) continue;
            legacyQuantityByAccount.merge(account.getId(), quantity, ClubCapTableService::add);
            legacyAccounts.put(account.getId(), account);
        }
        long legacyHeld = legacyQuantityByAccount.values().stream()
                .reduce(0L, ClubCapTableService::add);
        if (add(existingHeld, legacyHeld) > instrument.getTotalSupply()) {
            throw new EconomyConflictException("CAP_TABLE_OVER_ALLOCATED",
                    "Combined market and legacy club holdings exceed issued shares");
        }
        for (Map.Entry<Long, Long> entry : legacyQuantityByAccount.entrySet()) {
            PersonalAccount account = legacyAccounts.get(entry.getKey());
            PortfolioPosition position = positionsByAccount.get(entry.getKey());
            if (position == null) {
                position = new PortfolioPosition();
                position.setAccountId(account.getId());
                position.setProfileId(account.getProfileId());
                position.setInstrumentId(instrument.getId());
                position.setQuantity(0);
                position.setTotalCostBasis(0);
            }
            position.setQuantity(add(position.getQuantity(), entry.getValue()));
            position.setTotalCostBasis(add(position.getTotalCostBasis(),
                    multiply(entry.getValue(), instrument.getCurrentPrice())));
            positionRepository.save(position);
        }
        state.setMigrationVersion(MIGRATION_VERSION);
        stateRepository.save(state);
    }

    private void syncCompatibilityProjection(long teamId, MarketInstrument instrument,
                                             List<PortfolioPosition> positions, Long controller) {
        legacyOwnershipRepository.deleteAll(legacyOwnershipRepository.findAllByTeamId(teamId));
        legacyShareRepository.deleteAll(legacyShareRepository.findAllByTeamId(teamId));
        Map<Long, PersonalAccount> accounts = new HashMap<>();
        accountRepository.findAllById(positions.stream().map(PortfolioPosition::getAccountId).toList())
                .forEach(account -> accounts.put(account.getId(), account));
        for (PortfolioPosition position : positions) {
            PersonalAccount account = accounts.get(position.getAccountId());
            if (account == null || account.getOwnerHumanId() == null) continue;
            ClubShareholding projection = new ClubShareholding();
            projection.setHumanId(account.getOwnerHumanId());
            projection.setTeamId(teamId);
            projection.setPercent(BigDecimal.valueOf(position.getQuantity()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(instrument.getTotalSupply()), 8, RoundingMode.HALF_UP).doubleValue());
            legacyShareRepository.save(projection);
            if (controller != null && controller == account.getId()) {
                Ownership ownership = new Ownership();
                ownership.setHumanId(account.getOwnerHumanId());
                ownership.setTeamId(teamId);
                legacyOwnershipRepository.save(ownership);
            }
        }
    }

    private CapTable view(MarketInstrument instrument, ClubCapTableState state,
                          List<PortfolioPosition> positions) {
        Map<Long, PersonalAccount> accounts = new HashMap<>();
        accountRepository.findAllById(positions.stream().map(PortfolioPosition::getAccountId).toList())
                .forEach(account -> accounts.put(account.getId(), account));
        Map<Long, PersonProfile> profiles = new HashMap<>();
        profileRepository.findAllById(accounts.values().stream().map(PersonalAccount::getProfileId).toList())
                .forEach(profile -> profiles.put(profile.getId(), profile));
        List<Holding> holdings = new ArrayList<>();
        for (PortfolioPosition position : positions.stream()
                .sorted(Comparator.comparingLong(PortfolioPosition::getAccountId)).toList()) {
            PersonalAccount account = accounts.get(position.getAccountId());
            PersonProfile profile = account == null ? null : profiles.get(account.getProfileId());
            holdings.add(new Holding(position.getAccountId(), position.getProfileId(),
                    profile == null ? "Unknown" : profile.getDisplayName(),
                    account != null && account.getOwnerUserId() != null,
                    position.getQuantity(), position.getTotalCostBasis(),
                    state.getControllingAccountId() != null
                            && state.getControllingAccountId() == position.getAccountId()));
        }
        long held = holdings.stream().mapToLong(Holding::quantity).reduce(0, ClubCapTableService::add);
        if (add(held, instrument.getAvailableSupply()) != instrument.getTotalSupply()) {
            throw new EconomyConflictException("SUPPLY_CONSERVATION_FAILED", "Club cap table does not reconcile");
        }
        return new CapTable(state.getTeamId(), instrument.getId(), instrument.getTotalSupply(),
                instrument.getAvailableSupply(), controlThreshold(), state.getControllingAccountId(),
                state.getVersion(), List.copyOf(holdings));
    }

    private int controlThreshold() {
        int value = properties.getClub().getControlThresholdBps();
        if (value <= 5_000 || value > 10_000) {
            throw new IllegalStateException("Control threshold must be above 50% and at most 100%");
        }
        return value;
    }

    static boolean controls(long quantity, long supply, int thresholdBps) {
        return BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(10_000L))
                .compareTo(BigInteger.valueOf(supply).multiply(BigInteger.valueOf(thresholdBps))) >= 0;
    }

    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static long multiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static EconomyConflictException overflow() {
        return new EconomyConflictException("MONEY_OVERFLOW", "Cap-table value exceeds supported range");
    }

    public record Holding(long accountId, long profileId, String displayName,
                          boolean protectedUser, long quantity, long costBasis,
                          boolean controlling) { }

    public record CapTable(long teamId, long instrumentId, long issuedShares,
                           long freeFloat, int controlThresholdBps,
                           Long controllingAccountId, long version,
                           List<Holding> holdings) { }
}
