package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Gathers the facts {@link TransferPricing} needs about a player and his club. */
@Service
public class TransferPricingService {

    /** Offers still in play: they make the player more wanted. */
    static final List<String> ACTIVE = List.of("pending", "negotiating", "counter", "talks", "final_offer", "fee_agreed");
    /** Second leagues of older worlds, before they became tier-2 leagues. */
    private static final long OLD_SECOND_LEAGUE_TYPE = 3;

    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired CompetitionRepository competitionRepository;

    /** The real price of {@code player} for a buyer {@code buyerTeamId} in {@code season}. */
    public long realPrice(Human player, long buyerTeamId, int season) {
        Team club = player.getTeamId() == null ? null : teamRepository.findById(player.getTeamId()).orElse(null);
        long otherOffers = transferOfferRepository.findAllByPlayerIdAndStatusIn(player.getId(), ACTIVE).stream()
                .filter(o -> o.getFromTeamId() != buyerTeamId).map(o -> o.getFromTeamId()).distinct().count();
        return TransferPricing.realPrice(new TransferPricing.Context(
                TransferValuation.of(player.getAge(), player.getRating()),
                player.getContractEndSeason() - season,
                player.isWantsTransfer(),
                squadRank(player),
                club != null && (club.getTransferBudget() < 0 || relegated(club, season)),
                (int) otherOffers,
                player.getReleaseClause()));
    }

    /** The selling club's situation, which decides what the extras of an offer are worth to it. */
    public TransferPoints.Situation sellerSituation(Human player, int season) {
        Team club = player.getTeamId() == null ? null : teamRepository.findById(player.getTeamId()).orElse(null);
        boolean trouble = club != null && (club.getTransferBudget() < 0 || relegated(club, season));
        return TransferPoints.situation(trouble, squadRank(player));
    }

    /** 1-based rank of the player by rating in his club. */
    int squadRank(Human player) {
        if (player.getTeamId() == null) return 1;
        List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(player.getTeamId(), TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired())
                .sorted(Comparator.comparingDouble(Human::getRating).reversed())
                .toList();
        for (int i = 0; i < squad.size(); i++) {
            if (squad.get(i).getId() == player.getId()) return i + 1;
        }
        return squad.size() + 1;
    }

    /**
     * Plays in a lower league this season than last season. The leagues come from the season's own records
     * ({@code CompetitionHistory} last season, {@code CompetitionTeamInfo} now): {@code Team.competitionId} is only
     * set when the world is created and never follows promotion or relegation. A promotion is not a relegation.
     */
    boolean relegated(Team club, int season) {
        Integer before = leagueTier(competitionHistoryRepository.findByTeamIdAndSeasonNumber(club.getId(), season - 1)
                .stream().map(CompetitionHistory::getCompetitionId).toList());
        Integer now = leagueTier(competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(club.getId(), season)
                .stream().map(CompetitionTeamInfo::getCompetitionId).toList());
        return before != null && now != null && now > before;
    }

    /** The tier (1 = top flight) of the league among these competitions; null when none is a league. */
    private Integer leagueTier(List<Long> competitionIds) {
        return competitionIds.stream().distinct()
                .map(competitionRepository::findById).flatMap(java.util.Optional::stream)
                .filter(c -> c.isLeague() || c.getTypeId() == OLD_SECOND_LEAGUE_TYPE)
                .map(c -> c.isLeague() ? Math.max(1, c.getTier()) : Math.max(2, c.getTier()))
                .min(Integer::compare)
                .orElse(null);
    }
}
