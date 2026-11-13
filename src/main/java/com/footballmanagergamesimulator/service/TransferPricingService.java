package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
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
    private static final long TOP_LEAGUE_TYPE = 1;

    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired CompetitionHistoryRepository competitionHistoryRepository;

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

    /** Played in a top league last season and in another league now. */
    boolean relegated(Team club, int season) {
        return competitionHistoryRepository.findByTeamIdAndSeasonNumber(club.getId(), season - 1).stream()
                .filter(h -> h.getCompetitionTypeId() == TOP_LEAGUE_TYPE)
                .map(CompetitionHistory::getCompetitionId)
                .anyMatch(lastLeague -> lastLeague != club.getCompetitionId());
    }
}
