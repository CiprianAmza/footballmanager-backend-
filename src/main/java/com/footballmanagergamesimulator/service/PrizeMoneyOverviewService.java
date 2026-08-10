package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.EuropeanPrizePolicy;
import com.footballmanagergamesimulator.config.LeaguePrizePoolConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read model for the money committed to, and already paid by, every competition. */
@Service
public class PrizeMoneyOverviewService {

    private final CompetitionRepository competitionRepository;
    private final TeamRepository teamRepository;
    private final FinancialRecordRepository financialRecordRepository;
    private final FriendlyEventRepository friendlyEventRepository;
    private final LeagueStrengthService leagueStrengthService;
    private final EuropeanCoefficientService europeanCoefficientService;
    private final LeaguePrizePoolConfig leaguePrizePoolConfig;
    private final CompetitionFormatConfig competitionFormatConfig;
    private final EuropeanPrizePolicy europeanPrizePolicy;

    public PrizeMoneyOverviewService(CompetitionRepository competitionRepository,
                                     TeamRepository teamRepository,
                                     FinancialRecordRepository financialRecordRepository,
                                     FriendlyEventRepository friendlyEventRepository,
                                     LeagueStrengthService leagueStrengthService,
                                     EuropeanCoefficientService europeanCoefficientService,
                                     LeaguePrizePoolConfig leaguePrizePoolConfig,
                                     CompetitionFormatConfig competitionFormatConfig,
                                     EuropeanPrizePolicy europeanPrizePolicy) {
        this.competitionRepository = competitionRepository;
        this.teamRepository = teamRepository;
        this.financialRecordRepository = financialRecordRepository;
        this.friendlyEventRepository = friendlyEventRepository;
        this.leagueStrengthService = leagueStrengthService;
        this.europeanCoefficientService = europeanCoefficientService;
        this.leaguePrizePoolConfig = leaguePrizePoolConfig;
        this.competitionFormatConfig = competitionFormatConfig;
        this.europeanPrizePolicy = europeanPrizePolicy;
    }

    public PrizeMoneyOverview overview(int season) {
        List<Team> teams = teamRepository.findAll();
        List<FinancialRecord> records = financialRecordRepository.findAllBySeasonNumber(season);
        Map<String, Long> incomeByCategory = categoryTotals(records, true);
        Map<String, Long> spendingByCategory = categoryTotals(records, false);
        long paidPrizeMoney = records.stream()
                .filter(row -> "PRIZE_MONEY".equals(row.getCategory()) && row.getAmount() > 0)
                .mapToLong(FinancialRecord::getAmount).sum();

        List<CompetitionPrizeView> competitionViews = new ArrayList<>();
        for (LeaguePool league : leaguePools(season)) {
            List<DistributionLine> distribution = positionDistribution(league.pool(), league.teamCount()).stream()
                    .map(row -> new DistributionLine("Position " + row.position(), row.amount(), row.amount(), row.sharePercent()))
                    .toList();
            competitionViews.add(new CompetitionPrizeView(league.competitionId(), league.competitionName(),
                    "LEAGUE", "DOMESTIC", league.tier(), league.pool(), league.pool(),
                    paidForCompetition(records, league.competitionName()), "FINAL_POSITION", distribution));
        }

        Map<Long, Integer> coefficientTier = coefficientTiers();
        List<Competition> allCompetitions = competitionRepository.findAll();
        for (Competition cup : allCompetitions.stream().filter(c -> c.getTypeId() == Competition.CUP).toList()) {
            long topFlightId = allCompetitions.stream()
                    .filter(c -> c.isTopFlight() && c.getNationId() == cup.getNationId())
                    .mapToLong(Competition::getId).findFirst().orElse(0L);
            int tier = coefficientTier.getOrDefault(topFlightId, 3);
            long winnerPrize = cupPrize(tier);
            competitionViews.add(new CompetitionPrizeView(cup.getId(), cup.getName(), "CUP", "DOMESTIC",
                    cup.getTier(), winnerPrize, winnerPrize, paidForCompetition(records, cup.getName()),
                    "WINNER_TAKES_ALL", List.of(new DistributionLine("Winner", winnerPrize, winnerPrize, 100.0))));
        }

        for (long typeId : List.of(Competition.LEAGUE_OF_CHAMPIONS, Competition.STARS_CUP)) {
            Competition comp = allCompetitions.stream().filter(c -> c.getTypeId() == typeId).findFirst().orElse(null);
            EuropeanProjection projection = europeanProjection(typeId);
            String name = comp == null ? (typeId == Competition.LEAGUE_OF_CHAMPIONS ? "League of Champions" : "Stars Cup") : comp.getName();
            competitionViews.add(new CompetitionPrizeView(comp == null ? -typeId : comp.getId(), name,
                    "EUROPEAN", "EUROPE", 1, projection.minimum(), projection.maximum(),
                    paidForCompetition(records, name), "PER_TEAM_AND_PER_FIXTURE", projection.lines()));
        }

        long friendlyPools = friendlyEventRepository.findAllBySeasonOrderByStartDayAsc(season).stream()
                .filter(event -> !"CANCELLED".equals(event.getStatus()) && !"DRAFT".equals(event.getStatus()))
                .mapToLong(FriendlyEvent::getPrizePool).sum();
        if (friendlyPools > 0) {
            competitionViews.add(new CompetitionPrizeView(0, "Unofficial events", "FRIENDLY_EVENT", "UNOFFICIAL",
                    0, friendlyPools, friendlyPools, 0, "EVENT_RULES",
                    List.of(new DistributionLine("Confirmed event prize pools", friendlyPools, friendlyPools, 100.0))));
        }

        competitionViews.sort(Comparator.comparing(CompetitionPrizeView::scope)
                .thenComparing(CompetitionPrizeView::kind).thenComparing(CompetitionPrizeView::name));
        long domesticMin = competitionViews.stream().filter(v -> "DOMESTIC".equals(v.scope())).mapToLong(CompetitionPrizeView::poolMinimum).sum();
        long domesticMax = competitionViews.stream().filter(v -> "DOMESTIC".equals(v.scope())).mapToLong(CompetitionPrizeView::poolMaximum).sum();
        long europeMin = competitionViews.stream().filter(v -> "EUROPE".equals(v.scope())).mapToLong(CompetitionPrizeView::poolMinimum).sum();
        long europeMax = competitionViews.stream().filter(v -> "EUROPE".equals(v.scope())).mapToLong(CompetitionPrizeView::poolMaximum).sum();

        EconomySnapshot economy = new EconomySnapshot(
                teams.stream().mapToLong(Team::getTotalFinances).sum(),
                teams.stream().mapToLong(Team::getTransferBudget).sum(),
                teams.stream().mapToLong(Team::getSalaryBudget).sum(),
                teams.stream().mapToLong(Team::getDebt).sum(),
                incomeByCategory.values().stream().mapToLong(Long::longValue).sum(),
                spendingByCategory.values().stream().mapToLong(Long::longValue).sum(),
                paidPrizeMoney);
        PrizeSummary summary = new PrizeSummary(domesticMin, domesticMax, europeMin, europeMax,
                friendlyPools, domesticMin + europeMin + friendlyPools,
                domesticMax + europeMax + friendlyPools, paidPrizeMoney);
        return new PrizeMoneyOverview(season, economy, summary, toCategoryAmounts(incomeByCategory),
                toCategoryAmounts(spendingByCategory), competitionViews);
    }

    /** Exact pool split used by end-of-season settlement. */
    public List<LeaguePool> leaguePools(int season) {
        Map<Long, Competition> competitions = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, c -> c));
        List<LeagueStrengthService.LeagueStrengthEntry> top = new ArrayList<>();
        List<LeagueStrengthService.LeagueStrengthEntry> lower = new ArrayList<>();
        for (LeagueStrengthService.LeagueStrengthEntry entry : leagueStrengthService.calculate(season).ranking()) {
            (entry.tier() > 1 ? lower : top).add(entry);
        }
        double[] weights = new double[top.size()];
        double totalWeight = 0;
        for (int i = 0; i < top.size(); i++) {
            double strength = Math.max(top.get(i).averageTopElevenRating(), 1D);
            weights[i] = Math.pow(strength, leaguePrizePoolConfig.getStrengthExponent())
                    * leaguePrizePoolConfig.rankBonus(i + 1);
            totalWeight += weights[i];
        }
        if (totalWeight <= 0) return List.of();

        List<LeaguePool> result = new ArrayList<>();
        Map<Long, Long> topPoolByNation = new HashMap<>();
        long allocated = 0;
        for (int i = 0; i < top.size(); i++) {
            LeagueStrengthService.LeagueStrengthEntry entry = top.get(i);
            long pool = i == top.size() - 1
                    ? leaguePrizePoolConfig.getTotalPool() - allocated
                    : (long) (leaguePrizePoolConfig.getTotalPool() * weights[i] / totalWeight);
            allocated += pool;
            Competition comp = competitions.get(entry.competitionId());
            if (comp != null) topPoolByNation.put(comp.getNationId(), pool);
            result.add(new LeaguePool(entry.competitionId(), entry.competitionName(), entry.tier(), i + 1,
                    entry.averageTopElevenRating(), pool, entry.teamCount()));
        }
        int rank = 0;
        for (LeagueStrengthService.LeagueStrengthEntry entry : lower) {
            Competition comp = competitions.get(entry.competitionId());
            long parentPool = comp == null ? 0 : topPoolByNation.getOrDefault(comp.getNationId(), 0L);
            result.add(new LeaguePool(entry.competitionId(), entry.competitionName(), entry.tier(), ++rank,
                    entry.averageTopElevenRating(),
                    (long) (parentPool * leaguePrizePoolConfig.getSecondLeagueFraction()), entry.teamCount()));
        }
        return result;
    }

    /** Position ladder normalised so its rows add up to the pool exactly. */
    public List<PositionPrize> positionDistribution(long pool, int teamCount) {
        if (teamCount <= 0) return List.of();
        double spread = leaguePrizePoolConfig.getPositionSpread();
        double totalWeight = 0;
        for (int position = 1; position <= teamCount; position++) {
            totalWeight += 1.0 - spread * (position - 1.0) / Math.max(teamCount - 1, 1);
        }
        List<PositionPrize> result = new ArrayList<>();
        long allocated = 0;
        for (int position = 1; position <= teamCount; position++) {
            double weight = 1.0 - spread * (position - 1.0) / Math.max(teamCount - 1, 1);
            long amount = position == teamCount ? pool - allocated : (long) (pool * weight / totalWeight);
            allocated += amount;
            result.add(new PositionPrize(position, amount, pool == 0 ? 0 : amount * 100.0 / pool));
        }
        return result;
    }

    private EuropeanProjection europeanProjection(long typeId) {
        CompetitionFormat format = competitionFormatConfig.get((int) typeId);
        int participants = format.groupCount() * format.groupSize();
        int groupMatches = format.groupCount() * format.groupSize() * Math.max(format.groupSize() - 1, 0);
        long participation = participants * europeanPrizePolicy.groupParticipation(typeId);
        long resultsMin = groupMatches * 2L * europeanPrizePolicy.groupDrawPerTeam(typeId);
        long resultsMax = groupMatches * europeanPrizePolicy.groupWin(typeId);
        List<DistributionLine> lines = new ArrayList<>();
        lines.add(new DistributionLine("Group participation (" + participants + " clubs)", participation, participation, 0));
        lines.add(new DistributionLine("Group results (" + groupMatches + " matches)", resultsMin, resultsMax, 0));
        long knockout = 0;
        int firstPaidRound = typeId == Competition.STARS_CUP ? format.playoffRound() + 1 : format.knockoutStartRound();
        for (int round = firstPaidRound; round < format.finalRound(); round++) {
            int roundsFromFinal = format.finalRound() - round + 1;
            int clubs = 1 << roundsFromFinal;
            int fixtures = clubs / 2 * (format.isTwoLeg(round) ? 2 : 1);
            long stageTotal = fixtures * 2L * europeanPrizePolicy.knockoutFixtureBonus(typeId, roundsFromFinal);
            knockout += stageTotal;
            String label = roundsFromFinal == 3 ? "Quarter-finals" : roundsFromFinal == 2 ? "Semi-finals" : "Knockout round";
            lines.add(new DistributionLine(label + " (per fixture)", stageTotal, stageTotal, 0));
        }
        long finalPrizes = europeanPrizePolicy.winnerPrize(typeId) + europeanPrizePolicy.runnerUpPrize(typeId);
        lines.add(new DistributionLine("Final: winner + runner-up", finalPrizes, finalPrizes, 0));
        long minimum = participation + resultsMin + knockout + finalPrizes;
        long maximum = participation + resultsMax + knockout + finalPrizes;
        return new EuropeanProjection(minimum, maximum, lines);
    }

    private Map<Long, Integer> coefficientTiers() {
        List<Long> ids = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
        Map<Long, Integer> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) result.put(ids.get(i), i < 2 ? 1 : i < 4 ? 2 : 3);
        return result;
    }

    private long cupPrize(int tier) {
        return tier == 1 ? 10_000_000L : tier == 2 ? 4_000_000L : 1_500_000L;
    }

    private long paidForCompetition(List<FinancialRecord> records, String competitionName) {
        String prefix = competitionName.toLowerCase();
        return records.stream().filter(r -> "PRIZE_MONEY".equals(r.getCategory()) && r.getAmount() > 0)
                .filter(r -> r.getDescription() != null && r.getDescription().toLowerCase().startsWith(prefix))
                .mapToLong(FinancialRecord::getAmount).sum();
    }

    private Map<String, Long> categoryTotals(List<FinancialRecord> records, boolean income) {
        Map<String, Long> result = new LinkedHashMap<>();
        records.stream().filter(row -> income ? row.getAmount() > 0 : row.getAmount() < 0)
                .forEach(row -> result.merge(row.getCategory(), Math.abs(row.getAmount()), Long::sum));
        return result.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private List<CategoryAmount> toCategoryAmounts(Map<String, Long> values) {
        return values.entrySet().stream().map(entry -> new CategoryAmount(entry.getKey(), entry.getValue())).toList();
    }

    private record EuropeanProjection(long minimum, long maximum, List<DistributionLine> lines) {}
    public record LeaguePool(long competitionId, String competitionName, int tier, int rank,
                             double strength, long pool, int teamCount) {}
    public record PositionPrize(int position, long amount, double sharePercent) {}
    public record DistributionLine(String label, long amountMinimum, long amountMaximum, double sharePercent) {}
    public record CompetitionPrizeView(long competitionId, String name, String kind, String scope, int tier,
                                       long poolMinimum, long poolMaximum, long paidToDate, String distributionBasis,
                                       List<DistributionLine> distribution) {}
    public record CategoryAmount(String category, long amount) {}
    public record EconomySnapshot(long clubCash, long transferBudgets, long salaryBudgets, long clubDebt,
                                  long seasonIncome, long seasonSpending, long prizeMoneyPaid) {}
    public record PrizeSummary(long domesticMinimum, long domesticMaximum, long europeanMinimum, long europeanMaximum,
                               long unofficialPools, long totalMinimum, long totalMaximum, long paidToDate) {}
    public record PrizeMoneyOverview(int season, EconomySnapshot economy, PrizeSummary summary,
                                     List<CategoryAmount> incomeByCategory, List<CategoryAmount> spendingByCategory,
                                     List<CompetitionPrizeView> competitions) {}
}
