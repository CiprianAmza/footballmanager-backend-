package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FinanceService {

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private FinancialRecordRepository financialRecordRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private ScoutRepository scoutRepository;
    @Autowired
    private UserContext userContext;
    @Autowired
    @Lazy
    private StadiumRepository stadiumRepository;

    private final Random random = new Random();

    /**
     * Record a financial transaction and update team finances.
     */
    public void recordTransaction(long teamId, int season, int day, String category, String description, long amount) {
        FinancialRecord record = new FinancialRecord();
        record.setTeamId(teamId);
        record.setSeasonNumber(season);
        record.setDay(day);
        record.setCategory(category);
        record.setDescription(description);
        record.setAmount(amount);
        financialRecordRepository.save(record);

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        team.setTotalFinances(team.getTotalFinances() + amount);

        // Income goes to transfer budget based on board confidence percentage
        if (amount > 0) {
            int confidencePct = getTransferBudgetPercentage(team.getBoardConfidence());
            long toTransferBudget = (long) (amount * confidencePct / 100.0);
            team.setTransferBudget(team.getTransferBudget() + toTransferBudget);
        }

        teamRepository.save(team);
    }

    /**
     * Record an expense (negative amount). Expenses do NOT touch transfer budget.
     */
    public void recordExpense(long teamId, int season, int day, String category, String description, long amount) {
        recordTransaction(teamId, season, day, category, description, -Math.abs(amount));
    }

    /**
     * Returns the percentage of income that goes to transfer budget based on board confidence.
     * Confidence 0 = 20%, Confidence 100 = 80%
     */
    public int getTransferBudgetPercentage(int boardConfidence) {
        return 20 + (int) (boardConfidence * 0.6);
    }

    /**
     * Process match day income based on stadium capacity and match importance.
     * Called after each home match for a team.
     */
    public void processMatchDayIncome(long teamId, int season, int day, long opponentId, String competitionName) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        Team opponent = teamRepository.findById(opponentId).orElse(null);

        // Use Stadium entity for capacity (with expansions)
        Stadium stadium = stadiumRepository.findByTeamId(teamId).orElse(null);
        int capacity;
        double revenueMultiplier;
        if (stadium != null) {
            capacity = stadium.getEffectiveCapacity();
            revenueMultiplier = stadium.getRevenueMultiplier();
        } else {
            capacity = team.getStadiumCapacity();
            if (capacity <= 0) capacity = 30000;
            revenueMultiplier = 1.0;
        }

        // Occupancy: base 60-80%, boosted by team reputation and opponent reputation
        double baseOccupancy = 0.60 + random.nextDouble() * 0.20;
        double repBoost = Math.min(team.getReputation() / 20000.0, 0.15);
        double opponentBoost = (opponent != null) ? Math.min(opponent.getReputation() / 30000.0, 0.10) : 0;
        double occupancy = Math.min(baseOccupancy + repBoost + opponentBoost, 0.99);

        int attendance = (int) (capacity * occupancy);

        // Ticket price: base 25-50 based on reputation
        int ticketPrice = 25 + Math.min(team.getReputation() / 400, 25);

        long matchDayIncome = (long) (attendance * ticketPrice * revenueMultiplier);

        recordTransaction(teamId, season, day, "MATCH_DAY",
                "Match day vs " + (opponent != null ? opponent.getName() : "Unknown") + " (" + competitionName + ") - " + attendance + " fans",
                matchDayIncome);
    }

    /**
     * Process monthly merchandising income based on team reputation.
     * Called once per month.
     */
    public void processMerchandisingIncome(long teamId, int season, int day) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        // Monthly merchandising: reputation * 50-150
        long merchandising = (long) team.getReputation() * (50 + random.nextInt(101));
        recordTransaction(teamId, season, day, "MERCHANDISING",
                "Monthly merchandising revenue", merchandising);
    }

    /**
     * Process owner injection for wealthy clubs (9000+ reputation).
     * Called once per season at season start.
     */
    public void processOwnerInjection(long teamId, int season) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        if (team.getReputation() < 9000) return;

        // Scale injection by reputation: 9000 rep = ~100M, 10000 rep = ~200M
        long baseInjection = 100_000_000L;
        long reputationBonus = (long) ((team.getReputation() - 9000) * 100_000L);
        long injection = baseInjection + reputationBonus + (long) (random.nextDouble() * 50_000_000L);

        recordTransaction(teamId, season, 1, "OWNER_INJECTION",
                "Annual owner investment", injection);

        // Send inbox for human teams
        if (userContext.isHumanTeam(teamId)) {
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(0);
            inbox.setTitle("Owner Investment");
            inbox.setContent("The club owner has invested " + formatMoney(injection) + " into the club for this season.");
            inbox.setCategory("finance");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    /**
     * Process monthly wages for a single team. Returns total wages paid.
     */
    public long processTeamMonthlyWages(Team team, int season, int day) {
        List<Human> teamMembers = humanRepository.findAllByTeamId(team.getId());
        long totalWages = 0;
        for (Human h : teamMembers) {
            if (h.isRetired()) continue;
            totalWages += h.getWage();
        }

        List<Scout> scouts = scoutRepository.findAllByTeamId(team.getId());
        for (Scout s : scouts) {
            totalWages += s.getWage();
        }

        if (totalWages > 0) {
            recordExpense(team.getId(), season, day, "WAGES",
                    "Monthly wages", totalWages);
        }

        return totalWages;
    }

    /**
     * Send monthly financial report to human manager inbox.
     */
    public void sendMonthlyFinancialReport(long teamId, int season, int day, long wagesPaid) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        long seasonIncome = financialRecordRepository.sumByTeamIdAndSeasonNumber(teamId, season);
        long seasonWages = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "WAGES");
        long matchDayTotal = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "MATCH_DAY");
        long merchandisingTotal = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "MERCHANDISING");
        long sponsorshipTotal = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "SPONSORSHIP");
        long transferSales = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "TRANSFER_SALE");
        long transferBuys = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "TRANSFER_BUY");
        long prizeTotal = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, "PRIZE_MONEY");

        StringBuilder content = new StringBuilder();
        content.append("=== MONTHLY FINANCIAL REPORT ===\n\n");
        content.append("Club Balance: ").append(formatMoney(team.getTotalFinances())).append("\n");
        content.append("Transfer Budget: ").append(formatMoney(team.getTransferBudget())).append("\n");
        if (team.getDebt() > 0) {
            content.append("Outstanding Debt: ").append(formatMoney(team.getDebt())).append("\n");
        }
        content.append("\n--- Season Summary ---\n");
        content.append("Wages Paid (total): ").append(formatMoney(Math.abs(seasonWages))).append("\n");
        content.append("This Month's Wages: ").append(formatMoney(wagesPaid)).append("\n");
        content.append("Match Day Revenue: ").append(formatMoney(matchDayTotal)).append("\n");
        content.append("Merchandising: ").append(formatMoney(merchandisingTotal)).append("\n");
        content.append("Sponsorship: ").append(formatMoney(sponsorshipTotal)).append("\n");
        content.append("Prize Money: ").append(formatMoney(prizeTotal)).append("\n");
        content.append("Transfer Sales: ").append(formatMoney(transferSales)).append("\n");
        content.append("Transfer Purchases: ").append(formatMoney(Math.abs(transferBuys))).append("\n");
        content.append("\nNet P&L: ").append(formatMoney(seasonIncome)).append("\n");
        content.append("Board Confidence: ").append(team.getBoardConfidence()).append("%\n");
        content.append("Transfer Budget Allocation: ").append(getTransferBudgetPercentage(team.getBoardConfidence())).append("% of income\n");

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle("Monthly Financial Report");
        inbox.setContent(content.toString());
        inbox.setCategory("finance");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    /**
     * Process TV income distribution at end of season based on league position.
     * Top positions get more. Called from refreshTeamBudgets.
     */
    public long calculateTvIncome(int leaguePosition, int totalTeams, int leagueTier) {
        long tvPool;
        switch (leagueTier) {
            case 1: tvPool = 150_000_000L; break;  // Top league TV pool
            case 2: tvPool = 60_000_000L; break;    // Mid league TV pool
            default: tvPool = 20_000_000L; break;   // Lower league TV pool
        }

        // Equal share (50%) + merit share (50% based on position)
        long equalShare = tvPool / (2 * totalTeams);
        double meritFactor = 1.0 - (0.8 * (leaguePosition - 1.0) / Math.max(totalTeams - 1, 1));
        long meritShare = (long) ((tvPool / 2.0) * meritFactor / totalTeams * 2);

        return equalShare + meritShare;
    }

    /**
     * Update board confidence based on team performance.
     * Called at end of season.
     */
    public void updateBoardConfidence(long teamId, int leaguePosition, int totalTeams) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        int currentConfidence = team.getBoardConfidence();

        // Top half = confidence increases, bottom half = decreases
        double positionRatio = (double) leaguePosition / totalTeams;
        int change;
        if (positionRatio <= 0.1) change = 15;       // Top 10%
        else if (positionRatio <= 0.25) change = 10;  // Top 25%
        else if (positionRatio <= 0.5) change = 5;    // Top half
        else if (positionRatio <= 0.75) change = -5;   // Bottom half
        else change = -10;                             // Bottom 25%

        int newConfidence = Math.max(10, Math.min(100, currentConfidence + change));
        team.setBoardConfidence(newConfidence);
        teamRepository.save(team);
    }

    /**
     * Process debt interest. Called monthly if team has debt.
     * Interest rate: 2% per month.
     */
    public void processDebtInterest(long teamId, int season, int day) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null || team.getDebt() <= 0) return;

        long interest = (long) (team.getDebt() * 0.02);
        team.setTotalFinances(team.getTotalFinances() - interest);
        team.setDebt(team.getDebt() + interest);
        teamRepository.save(team);

        FinancialRecord record = new FinancialRecord();
        record.setTeamId(teamId);
        record.setSeasonNumber(season);
        record.setDay(day);
        record.setCategory("DEBT_INTEREST");
        record.setDescription("Monthly debt interest (2%)");
        record.setAmount(-interest);
        financialRecordRepository.save(record);
    }

    /**
     * Check if team finances went negative. If so, create debt automatically.
     */
    public void checkAndCreateDebt(long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        if (team.getTotalFinances() < 0) {
            long deficit = Math.abs(team.getTotalFinances());
            team.setDebt(team.getDebt() + deficit);
            team.setTotalFinances(0);
            teamRepository.save(team);
        }
    }

    /**
     * Get detailed financial report for a team and season.
     */
    public Map<String, Object> getFinancialReport(long teamId, int season) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return Map.of();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("teamId", teamId);
        report.put("season", season);
        report.put("totalFinances", team.getTotalFinances());
        report.put("transferBudget", team.getTransferBudget());
        report.put("debt", team.getDebt());
        report.put("boardConfidence", team.getBoardConfidence());
        report.put("transferBudgetPercentage", getTransferBudgetPercentage(team.getBoardConfidence()));
        report.put("stadiumCapacity", team.getStadiumCapacity());

        // Category breakdown
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (String cat : List.of("MATCH_DAY", "TV_INCOME", "MERCHANDISING", "SPONSORSHIP",
                "TRANSFER_SALE", "TRANSFER_BUY", "WAGES", "PRIZE_MONEY",
                "OWNER_INJECTION", "LOAN_FEE", "SCOUT_COST", "DEBT_INTEREST", "OTHER")) {
            long sum = financialRecordRepository.sumByTeamIdAndSeasonNumberAndCategory(teamId, season, cat);
            if (sum != 0) {
                breakdown.put(cat, sum);
            }
        }
        report.put("breakdown", breakdown);

        long totalIncome = breakdown.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .mapToLong(Map.Entry::getValue)
                .sum();
        long totalExpenses = breakdown.entrySet().stream()
                .filter(e -> e.getValue() < 0)
                .mapToLong(e -> Math.abs(e.getValue()))
                .sum();

        report.put("totalIncome", totalIncome);
        report.put("totalExpenses", totalExpenses);
        report.put("netProfit", totalIncome - totalExpenses);

        // Monthly wages calculation
        List<Human> teamMembers = humanRepository.findAllByTeamId(teamId);
        long monthlyWages = teamMembers.stream()
                .filter(h -> !h.isRetired())
                .mapToLong(Human::getWage)
                .sum();
        List<Scout> scouts = scoutRepository.findAllByTeamId(teamId);
        monthlyWages += scouts.stream().mapToLong(Scout::getWage).sum();
        report.put("monthlyWages", monthlyWages);

        // All records for this season
        List<FinancialRecord> records = financialRecordRepository.findAllByTeamIdAndSeasonNumber(teamId, season);
        report.put("transactions", records);

        return report;
    }

    public String formatMoney(long amount) {
        if (Math.abs(amount) >= 1_000_000L) {
            return String.format("$%.1fM", amount / 1_000_000.0);
        } else if (Math.abs(amount) >= 1_000L) {
            return String.format("$%dK", amount / 1_000);
        }
        return "$" + amount;
    }
}
