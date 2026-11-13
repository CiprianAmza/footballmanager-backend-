package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Competition bonuses agreed at the squad meeting (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): at season
 * end, for every target reached, the club pays the squad a share of one month of its wages (MIN 25%, MID 50%,
 * MAX 100%).
 */
@Service
public class SquadBonusService {

    private final SquadObjectiveRepository objectiveRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final CompetitionRepository competitionRepository;
    private final HumanRepository humanRepository;
    private final FinanceService financeService;

    public SquadBonusService(SquadObjectiveRepository objectiveRepository, CompetitionHistoryRepository historyRepository,
                             CompetitionRepository competitionRepository, HumanRepository humanRepository,
                             FinanceService financeService) {
        this.objectiveRepository = objectiveRepository;
        this.historyRepository = historyRepository;
        this.competitionRepository = competitionRepository;
        this.humanRepository = humanRepository;
        this.financeService = financeService;
    }

    /** Settles every objective of {@code season} once (after the final standings are recorded). Returns the total paid. */
    @Transactional
    public long settle(int season) {
        long total = 0;
        List<SquadObjective> objectives = objectiveRepository.findAllBySeason(season).stream()
                .filter(o -> !o.isBonusSettled()).toList();
        for (SquadObjective objective : objectives) {
            boolean league = competitionRepository.findById(objective.getCompetitionId()).map(Competition::isLeague).orElse(false);
            List<CompetitionHistory> table = historyRepository.findAllByCompetitionIdAndSeasonNumber(objective.getCompetitionId(), season);
            int place = table.stream().filter(h -> h.getTeamId() == objective.getTeamId())
                    .mapToInt(h -> (int) h.getLastPosition()).findFirst().orElse(0);
            long paid = 0;
            if (SquadObjectives.reached(league, objective.getManagerTarget(), place, table.size())) {
                long wages = humanRepository.findAllByTeamIdAndTypeId(objective.getTeamId(), TypeNames.PLAYER_TYPE).stream()
                        .filter(p -> !p.isRetired()).mapToLong(Human::getWage).sum();
                paid = Math.round(wages * SquadObjectives.Bonus.valueOf(objective.getBonusLevel()).wageShare);
                if (paid > 0) {
                    financeService.recordExpense(objective.getTeamId(), season, 0, "SQUAD_BONUS",
                            "Squad bonus: " + objective.getCompetitionName(), paid);
                }
            }
            objective.setBonusPaid(paid);
            objective.setBonusSettled(true);
            total += paid;
        }
        objectiveRepository.saveAll(objectives);
        return total;
    }
}
