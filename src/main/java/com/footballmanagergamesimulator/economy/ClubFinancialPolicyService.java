package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Scout;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScoutRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

@Service
public class ClubFinancialPolicyService {
    private final HumanRepository humanRepository;
    private final ScoutRepository scoutRepository;
    private final ClubFinancialObligationRepository obligationRepository;
    private final RegentEconomyProperties properties;

    public ClubFinancialPolicyService(HumanRepository humanRepository,
                                      ScoutRepository scoutRepository,
                                      ClubFinancialObligationRepository obligationRepository,
                                      RegentEconomyProperties properties) {
        this.humanRepository = humanRepository;
        this.scoutRepository = scoutRepository;
        this.obligationRepository = obligationRepository;
        this.properties = properties;
    }

    public Policy policy(Team team) {
        long monthlyWages = 0;
        for (Human human : humanRepository.findAllByTeamId(team.getId())) {
            if (human.isRetired()) continue;
            long wage = human.getTypeId() == TypeNames.MANAGER_TYPE ? human.getSalary() : human.getWage();
            monthlyWages = add(monthlyWages, Math.max(0, wage));
        }
        for (Scout scout : scoutRepository.findAllByTeamId(team.getId())) {
            monthlyWages = add(monthlyWages, Math.max(0, scout.getWage()));
        }
        long due = 0;
        boolean restricted = false;
        for (ClubFinancialObligation obligation : obligationRepository
                .findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(team.getId())) {
            due = add(due, Math.max(0, obligation.getAmountRemaining()));
            restricted |= obligation.isRestrictsWithdrawal();
        }
        RegentEconomyProperties.Club config = properties.getClub();
        if (config.getMinimumProtectedReserve() < 0 || config.getProtectedWageMonths() < 0) {
            throw new IllegalStateException("Invalid REGENT club reserve configuration");
        }
        long reserve = add(config.getMinimumProtectedReserve(),
                multiply(monthlyWages, config.getProtectedWageMonths()));
        long distributable;
        try {
            distributable = Math.subtractExact(Math.subtractExact(team.getTotalFinances(), reserve), due);
        } catch (ArithmeticException exception) {
            throw overflow();
        }
        distributable = Math.max(0, distributable);
        boolean debtRestricted = team.getDebt() > 0 && !config.isWithdrawalAllowedWithDebt();
        return new Policy(team.getTotalFinances(), Math.max(0, team.getDebt()), monthlyWages,
                reserve, due, distributable, restricted || debtRestricted);
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
        return new EconomyConflictException("MONEY_OVERFLOW", "Club reserve calculation exceeds supported range");
    }

    public record Policy(long treasuryBalance, long debt, long monthlyWages,
                         long protectedReserve, long dueObligations,
                         long distributableCash, boolean withdrawalRestricted) { }
}
