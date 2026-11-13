package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.squad.SquadObjectives;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Season end: public promises are kept or missed, and the manager's reputation moves by their stake. */
@Service
public class PublicPromiseService {

    private final PublicPromiseRepository promiseRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final CompetitionRepository competitionRepository;
    private final HumanRepository humanRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.BoardTrustService boardTrust;

    public PublicPromiseService(PublicPromiseRepository promiseRepository, CompetitionHistoryRepository historyRepository,
                                CompetitionRepository competitionRepository, HumanRepository humanRepository) {
        this.promiseRepository = promiseRepository;
        this.historyRepository = historyRepository;
        this.competitionRepository = competitionRepository;
        this.humanRepository = humanRepository;
    }

    /** Settles the season's promises once. Returns the net reputation change applied. */
    @Transactional
    public int settle(int season) {
        int net = 0;
        List<PublicPromise> promises = promiseRepository.findAllBySeasonAndSettledFalse(season);
        for (PublicPromise promise : promises) {
            boolean league = competitionRepository.findById(promise.getCompetitionId()).map(Competition::isLeague).orElse(false);
            List<CompetitionHistory> table = historyRepository.findAllByCompetitionIdAndSeasonNumber(promise.getCompetitionId(), season);
            int place = table.stream().filter(h -> h.getTeamId() == promise.getTeamId())
                    .mapToInt(h -> (int) h.getLastPosition()).findFirst().orElse(0);
            boolean kept = SquadObjectives.reached(league, promise.getTarget(), place, table.size());
            int delta = kept ? promise.getStake() : -promise.getStake();
            humanRepository.findById(promise.getManagerId()).ifPresent(manager -> {
                manager.setManagerReputation(Math.max(0, Math.min(10_000, manager.getManagerReputation() + delta)));
                humanRepository.save(manager);
            });
            promise.setKept(kept);
            promise.setSettled(true);
            if (boardTrust != null) boardTrust.promiseSettled(promise.getTeamId(), kept, season);
            net += delta;
        }
        promiseRepository.saveAll(promises);
        return net;
    }
}
