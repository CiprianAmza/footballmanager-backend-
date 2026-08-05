package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.Locale;

/** Converts real club-finance warning signs into escalating media coverage. */
@Service
public class FinancialTroubleMediaService {

    private final TeamRepository teams;
    private final FinancialRecordRepository financialRecords;
    private final ManagerInboxRepository inbox;

    public FinancialTroubleMediaService(TeamRepository teams, FinancialRecordRepository financialRecords,
                                        ManagerInboxRepository inbox) {
        this.teams = teams;
        this.financialRecords = financialRecords;
        this.inbox = inbox;
    }

    public void publishIfNeeded(long teamId, int season, int day, long monthlyPayroll) {
        Team team = teams.findById(teamId).orElse(null);
        if (team == null) return;
        long seasonNet = financialRecords.sumByTeamIdAndSeasonNumber(teamId, season);
        Severity severity = severity(team, monthlyPayroll, seasonNet);
        if (severity == Severity.HEALTHY) return;

        String deduplicationKey = "FINANCIAL_MEDIA:" + season + ":" + teamId + ":" + severity.name();
        if (inbox.existsByTeamIdAndDeduplicationKey(teamId, deduplicationKey)) return;

        long payrollBase = Math.max(1_000_000L, Math.max(0L, monthlyPayroll));
        String title = switch (severity) {
            case CONCERN -> "Questions raised over " + team.getName() + " cash reserves";
            case CRISIS -> team.getName() + " face growing financial pressure";
            case CRITICAL -> "Financial alarm as " + team.getName() + " consider emergency measures";
            default -> team.getName() + " finances under review";
        };
        String lead = switch (severity) {
            case CONCERN -> "Football finance reporters have placed the club on a watchlist after cash reserves fell close to its short-term commitments.";
            case CRISIS -> "Media scrutiny has intensified amid concern that current income is not keeping pace with the club's debt and operating costs.";
            case CRITICAL -> "The club's financial position is now being described as critical, with pressure building for immediate cost reductions or new funding.";
            default -> "The club's accounts are being monitored.";
        };
        String consequences = switch (severity) {
            case CONCERN -> "Analysts say recruitment flexibility could narrow unless results, matchday revenue or commercial income improve.";
            case CRISIS -> "Possible responses discussed in the media include player sales, a reduced wage bill and delays to non-essential investment.";
            case CRITICAL -> "Reporters expect the board to consider urgent player sales, strict wage controls and a suspension of major discretionary spending.";
            default -> "No immediate action is expected.";
        };

        String content = "FINANCIAL WATCH\n\n"
                + "Status: " + severity.label + "\n"
                + "Club balance: " + money(team.getTotalFinances()) + "\n"
                + "Outstanding debt: " + money(team.getDebt()) + "\n"
                + "Monthly payroll: " + money(monthlyPayroll) + "\n"
                + "Season net cash movement: " + signedMoney(seasonNet) + "\n\n"
                + lead + "\n\n" + consequences + "\n\n"
                + pressureLine(team, payrollBase, severity)
                + " The figures are drawn from the club's current accounts; no insolvency event is assumed unless the game records one.";

        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(season);
        message.setRoundNumber(day);
        message.setTitle(title);
        message.setContent(content);
        message.setCategory("MEDIA_FINANCIAL_TROUBLE");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(deduplicationKey);
        inbox.save(message);
    }

    private Severity severity(Team team, long monthlyPayroll, long seasonNet) {
        long base = Math.max(1_000_000L, Math.max(0L, monthlyPayroll));
        long debt = Math.max(0L, team.getDebt());
        long balance = Math.max(0L, team.getTotalFinances());
        if (debt >= multiply(base, 6) || (debt >= multiply(base, 4) && seasonNet <= -multiply(base, 4))) {
            return Severity.CRITICAL;
        }
        if (debt >= multiply(base, 2) || (debt > 0 && balance < base && seasonNet < 0)) {
            return Severity.CRISIS;
        }
        if (debt > 0 || (balance < multiply(base, 2) && seasonNet <= -base)) return Severity.CONCERN;
        return Severity.HEALTHY;
    }

    private String pressureLine(Team team, long payrollBase, Severity severity) {
        if (team.getDebt() <= 0) {
            return "The immediate concern is liquidity: available cash covers less than two protected payroll periods.";
        }
        double months = team.getDebt() / (double) payrollBase;
        return "Debt is equivalent to roughly " + String.format(Locale.US, "%.1f", months)
                + " protected monthly payrolls, placing the club in the " + severity.label.toLowerCase() + " band.";
    }

    private long multiply(long value, int factor) {
        try { return Math.multiplyExact(value, factor); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }

    private String money(long amount) {
        return "€" + NumberFormat.getIntegerInstance(Locale.US).format(Math.max(0L, amount));
    }
    private String signedMoney(long amount) {
        return (amount >= 0 ? "+" : "-") + money(amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount));
    }

    private enum Severity {
        HEALTHY("Healthy"), CONCERN("Financial watch"), CRISIS("Financial crisis"), CRITICAL("Critical");
        private final String label;
        Severity(String label) { this.label = label; }
    }
}
