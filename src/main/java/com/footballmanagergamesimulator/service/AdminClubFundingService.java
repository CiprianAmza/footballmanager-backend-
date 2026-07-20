package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Administrative cash adjustments with a controlled, auditable reason. */
@Service
public class AdminClubFundingService {

    private static final long MAX_FUNDING_AMOUNT = 10_000_000_000_000L;
    private static final int MAX_NOTE_LENGTH = 200;

    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private FinanceService financeService;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private UserContext userContext;

    public record FundingCommand(Long teamId, Long amount, String reason, String note) {
    }

    public record FundingOption(String code, String label, String category, String description) {
    }

    public record FundingResult(
            boolean success,
            long teamId,
            String teamName,
            long amount,
            String reason,
            String reasonLabel,
            String category,
            String description,
            int season,
            int day,
            long totalFinances,
            long transferBudget,
            long transferBudgetAdded) {
    }

    public List<FundingOption> options() {
        return Arrays.stream(FundingReason.values())
                .map(reason -> new FundingOption(reason.name(), reason.label,
                        reason.category, reason.description))
                .toList();
    }

    public List<FundingOption> withdrawalOptions() {
        return Arrays.stream(WithdrawalReason.values())
                .map(reason -> new FundingOption(reason.name(), reason.label,
                        reason.category, reason.description))
                .toList();
    }

    @Transactional
    public FundingResult addFunding(FundingCommand command) {
        Team team = validate(command);
        FundingReason reason = FundingReason.from(command.reason());
        String note = normalizeNote(command.note());
        GameDate date = currentGameDate();

        long previousTransferBudget = team.getTransferBudget();
        String ledgerDescription = reason.label + (note.isEmpty() ? "" : " — " + note);
        financeService.recordTransaction(team.getId(), date.season(), date.day(), reason.category,
                ledgerDescription, command.amount());

        Team updated = teamRepository.findById(team.getId()).orElse(team);
        long transferBudgetAdded = updated.getTransferBudget() - previousTransferBudget;
        notifyHumanManager(updated, date.season(), date.day(), reason.label, reason.description,
                "Amount received", command.amount(),
                "Added to transfer budget: " + formatEuros(transferBudgetAdded), note);

        return new FundingResult(true, updated.getId(), updated.getName(), command.amount(),
                reason.name(), reason.label, reason.category, ledgerDescription,
                date.season(), date.day(),
                updated.getTotalFinances(), updated.getTransferBudget(), transferBudgetAdded);
    }

    @Transactional
    public FundingResult removeFunding(FundingCommand command) {
        Team team = validate(command);
        WithdrawalReason reason = WithdrawalReason.from(command.reason());
        String note = normalizeNote(command.note());
        GameDate date = currentGameDate();

        String ledgerDescription = reason.label + (note.isEmpty() ? "" : " — " + note);
        financeService.recordExpense(team.getId(), date.season(), date.day(), reason.category,
                ledgerDescription, command.amount());

        Team updated = teamRepository.findById(team.getId()).orElse(team);
        notifyHumanManager(updated, date.season(), date.day(), reason.label, reason.description,
                "Amount removed", command.amount(), "Transfer budget unchanged", note);

        return new FundingResult(true, updated.getId(), updated.getName(), command.amount(),
                reason.name(), reason.label, reason.category, ledgerDescription,
                date.season(), date.day(), updated.getTotalFinances(),
                updated.getTransferBudget(), 0L);
    }

    private Team validate(FundingCommand command) {
        if (command == null || command.teamId() == null || command.teamId() <= 0) {
            throw new IllegalArgumentException("teamId is required");
        }
        if (command.amount() == null || command.amount() <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        if (command.amount() > MAX_FUNDING_AMOUNT) {
            throw new IllegalArgumentException("amount cannot exceed " + MAX_FUNDING_AMOUNT);
        }
        return teamRepository.findById(command.teamId())
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + command.teamId()));
    }

    private GameDate currentGameDate() {
        GameCalendar calendar = gameCalendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        return calendar == null
                ? new GameDate(currentSeason(), 1)
                : new GameDate(calendar.getSeason(), Math.max(1, calendar.getCurrentDay()));
    }

    private void notifyHumanManager(Team team, int season, int day, String title,
                                    String summary, String amountLabel, long amount,
                                    String budgetLine, String note) {
        if (!userContext.isHumanTeam(team.getId())) return;

        StringBuilder content = new StringBuilder()
                .append(summary).append("\n\n")
                .append(amountLabel).append(": ").append(formatEuros(amount)).append("\n")
                .append(budgetLine);
        if (!note.isEmpty()) content.append("\n\nNote: ").append(note);

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(team.getId());
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content.toString());
        inbox.setCategory("finance");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    private int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L).intValue();
    }

    private String normalizeNote(String value) {
        if (value == null) return "";
        String note = value.trim().replaceAll("\\s+", " ");
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note cannot exceed " + MAX_NOTE_LENGTH + " characters");
        }
        return note;
    }

    private String formatEuros(long amount) {
        return String.format(Locale.ROOT, "€%,d", amount);
    }

    private record GameDate(int season, int day) {
    }

    private enum FundingReason {
        BENEFACTOR(
                "Benefactor contribution",
                "OWNER_INJECTION",
                "A private benefactor has provided additional funding to the club."),
        OWNER(
                "Owner investment",
                "OWNER_INJECTION",
                "The club owner has invested additional capital."),
        ASSOCIATES_GROUP(
                "Associates group investment",
                "OWNER_INJECTION",
                "A group of associates has invested additional capital in the club."),
        NEW_SPONSOR(
                "New sponsorship agreement",
                "SPONSORSHIP",
                "The club has signed a new sponsorship agreement."),
        COMMERCIAL_PARTNER(
                "Commercial partner contribution",
                "SPONSORSHIP",
                "A commercial partner has provided additional club funding."),
        BOARD_SUPPORT(
                "Board financial support",
                "OWNER_INJECTION",
                "The board has approved an exceptional financial contribution."),
        COMMUNITY_FUNDRAISING(
                "Community fundraising",
                "OTHER",
                "Supporters and local partners have raised funds for the club.");

        private final String label;
        private final String category;
        private final String description;

        FundingReason(String label, String category, String description) {
            this.label = label;
            this.category = category;
            this.description = description;
        }

        private static FundingReason from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown funding reason: " + value);
            }
        }
    }

    private enum WithdrawalReason {
        OWNER_WITHDRAWAL(
                "Owner withdrawal",
                "OWNER_WITHDRAWAL",
                "The club owner has withdrawn funds from the club."),
        ASSOCIATES_GROUP_WITHDRAWAL(
                "Associates group withdrawal",
                "OWNER_WITHDRAWAL",
                "A group of associates has withdrawn part of its financial support."),
        DIVIDEND_DISTRIBUTION(
                "Dividend distribution",
                "OWNER_WITHDRAWAL",
                "Club funds have been distributed to owners or shareholders."),
        REGULATORY_FINE(
                "Regulatory fine",
                "FINES",
                "The club has received a regulatory or competition fine."),
        EXTRAORDINARY_OPERATING_COST(
                "Extraordinary operating cost",
                "OTHER",
                "The club has incurred an exceptional operating expense."),
        EMERGENCY_INFRASTRUCTURE_COST(
                "Emergency infrastructure cost",
                "OTHER",
                "Urgent stadium or facilities work has required additional spending."),
        FINANCIAL_CORRECTION(
                "Financial correction",
                "FINANCIAL_ADJUSTMENT",
                "A manual financial correction has reduced the club balance.");

        private final String label;
        private final String category;
        private final String description;

        WithdrawalReason(String label, String category, String description) {
            this.label = label;
            this.category = category;
            this.description = description;
        }

        private static WithdrawalReason from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown withdrawal reason: " + value);
            }
        }
    }
}
