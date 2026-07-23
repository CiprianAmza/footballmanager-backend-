package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.StadiumRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
public class ClubValuationService {
    private static final BigInteger BPS = BigInteger.valueOf(10_000L);

    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final StadiumRepository stadiumRepository;
    private final TeamFacilitiesRepository facilitiesRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final ClubFinancialObligationRepository obligationRepository;
    private final RegentEconomyProperties properties;

    public ClubValuationService(TeamRepository teamRepository,
                                HumanRepository humanRepository,
                                StadiumRepository stadiumRepository,
                                TeamFacilitiesRepository facilitiesRepository,
                                CompetitionHistoryRepository historyRepository,
                                ClubFinancialObligationRepository obligationRepository,
                                RegentEconomyProperties properties) {
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.stadiumRepository = stadiumRepository;
        this.facilitiesRepository = facilitiesRepository;
        this.historyRepository = historyRepository;
        this.obligationRepository = obligationRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Valuation value(long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
        return value(team);
    }

    Valuation value(Team team) {
        RegentEconomyProperties.Club config = properties.getClub();
        validateConfiguration(config);
        long squad = squadValue(team.getId());
        long obligations = dueObligations(team.getId());
        long cashNet = exactSubtract(exactSubtract(team.getTotalFinances(), Math.max(0, team.getDebt())), obligations);
        long stadium = stadiumAndFacilitiesValue(team.getId(), team.getStadiumCapacity(), config);
        long brand = exactMultiply(Math.max(0, team.getReputation()), config.getReputationPointValue());
        long beforePerformance = exactAdd(exactAdd(squad, cashNet), exactAdd(stadium, brand));
        int performanceBps = performanceBps(team.getId(), config);
        long performance = applyBps(beforePerformance, performanceBps);
        long raw = exactAdd(beforePerformance, performance);
        long total = Math.max(config.getMinimumValuation(), raw);
        String stateVersion = stateVersion(config.getValuationVersion(), team.getId(), squad, team.getTotalFinances(),
                team.getDebt(), obligations, stadium, brand, performanceBps, performance, total);
        return new Valuation(team.getId(), config.getValuationVersion(), stateVersion, squad,
                team.getTotalFinances(), team.getDebt(), obligations, cashNet, stadium, brand,
                performanceBps, performance, total);
    }

    public long perSharePrice(Valuation valuation, long issuedShares) {
        if (issuedShares <= 0) throw new EconomyConflictException("INVALID_SHARE_SUPPLY", "Issued shares must be positive");
        return BigInteger.valueOf(valuation.totalValue()).add(BigInteger.valueOf(issuedShares - 1))
                .divide(BigInteger.valueOf(issuedShares)).max(BigInteger.ONE).longValueExact();
    }

    public long equityValue(Valuation valuation, long quantity, long issuedShares) {
        if (quantity < 0 || issuedShares <= 0 || quantity > issuedShares) {
            throw new EconomyConflictException("INVALID_SHARE_SUPPLY", "Equity quantity is outside issued supply");
        }
        return BigInteger.valueOf(valuation.totalValue()).multiply(BigInteger.valueOf(quantity))
                .divide(BigInteger.valueOf(issuedShares)).longValueExact();
    }

    private long squadValue(long teamId) {
        long total = 0;
        for (Human human : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)) {
            if (!human.isRetired()) total = exactAdd(total, Math.max(0, human.getTransferValue()));
        }
        return total;
    }

    private long dueObligations(long teamId) {
        long total = 0;
        for (ClubFinancialObligation obligation : obligationRepository
                .findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(teamId)) {
            total = exactAdd(total, Math.max(0, obligation.getAmountRemaining()));
        }
        return total;
    }

    private long stadiumAndFacilitiesValue(long teamId, int fallbackCapacity,
                                            RegentEconomyProperties.Club config) {
        Stadium stadium = stadiumRepository.findByTeamId(teamId).orElse(null);
        int capacity = stadium == null ? Math.max(0, fallbackCapacity) : Math.max(0, stadium.getEffectiveCapacity());
        long levels = 0;
        if (stadium != null) {
            levels = exactAdd(levels, Math.max(0, stadium.getExpansionLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getVipBoxesLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getCateringLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getFanShopLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getFastFoodLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getHeadquartersLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getTrainingPitchLevel()));
            levels = exactAdd(levels, Math.max(0, stadium.getParkingLevel()));
        }
        TeamFacilities facilities = facilitiesRepository.findByTeamId(teamId);
        long facilityLevels = facilities == null ? 0 : exactAdd(exactAdd(
                Math.max(0, facilities.getYouthAcademyLevel()), Math.max(0, facilities.getYouthTrainingLevel())),
                exactAdd(Math.max(0, facilities.getSeniorTrainingLevel()), Math.max(0, facilities.getScoutingLevel())));
        return exactAdd(exactMultiply(capacity, config.getStadiumSeatValue()),
                exactAdd(exactMultiply(levels, config.getStadiumLevelValue()),
                        exactMultiply(facilityLevels, config.getFacilityLevelValue())));
    }

    private int performanceBps(long teamId, RegentEconomyProperties.Club config) {
        List<CompetitionHistory> eligible = historyRepository.findByTeamId(teamId).stream()
                .filter(value -> value.getGames() > 0)
                .sorted(Comparator.comparingLong(CompetitionHistory::getSeasonNumber).reversed()
                        .thenComparingLong(CompetitionHistory::getCompetitionId))
                .toList();
        List<Long> recentSeasons = eligible.stream()
                .mapToLong(CompetitionHistory::getSeasonNumber)
                .distinct()
                .limit(config.getPerformanceLookbackSeasons())
                .boxed()
                .toList();
        List<CompetitionHistory> histories = eligible.stream()
                .filter(value -> recentSeasons.contains(value.getSeasonNumber()))
                .toList();
        if (histories.isEmpty()) return 0;
        long games = histories.stream().mapToLong(CompetitionHistory::getGames).sum();
        long points = histories.stream().mapToLong(CompetitionHistory::getPoints).sum();
        // 1.5 points/game is neutral; 0 and 3 points/game map to the configured caps.
        long numerator = (points * 2L - games * 3L) * config.getPerformanceCapBps();
        long denominator = games * 3L;
        long result = denominator == 0 ? 0 : numerator / denominator;
        return Math.toIntExact(Math.max(-config.getPerformanceCapBps(),
                Math.min(config.getPerformanceCapBps(), result)));
    }

    private static long applyBps(long value, int bps) {
        try {
            return BigInteger.valueOf(value).multiply(BigInteger.valueOf(bps)).divide(BPS).longValueExact();
        } catch (ArithmeticException exception) {
            throw overflow();
        }
    }

    private static void validateConfiguration(RegentEconomyProperties.Club value) {
        if (value.getValuationVersion() == null || value.getValuationVersion().isBlank()
                || value.getMinimumValuation() <= 0 || value.getPerformanceCapBps() < 0
                || value.getPerformanceCapBps() > 10_000 || value.getPerformanceLookbackSeasons() < 1
                || value.getStadiumSeatValue() < 0 || value.getStadiumLevelValue() < 0
                || value.getFacilityLevelValue() < 0 || value.getReputationPointValue() < 0) {
            throw new IllegalStateException("Invalid REGENT club valuation configuration");
        }
    }

    private static long exactAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static long exactSubtract(long left, long right) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static long exactMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static EconomyConflictException overflow() {
        return new EconomyConflictException("MONEY_OVERFLOW", "Club valuation exceeds supported range");
    }

    private static String stateVersion(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Valuation(long teamId, String formulaVersion, String stateVersion,
                            long squadMarketValue, long clubCash, long debt,
                            long dueObligations, long netCash, long stadiumFacilitiesValue,
                            long reputationBrandValue, int recentPerformanceBps,
                            long recentPerformanceValue, long totalValue) { }
}
