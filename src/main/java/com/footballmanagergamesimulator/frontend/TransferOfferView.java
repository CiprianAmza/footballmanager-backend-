package com.footballmanagergamesimulator.frontend;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.TransferOffer;

/** Transfer offer enriched with the small player snapshot needed by list tooltips. */
public record TransferOfferView(
        long id,
        long playerId,
        String playerName,
        long fromTeamId,
        String fromTeamName,
        long toTeamId,
        String toTeamName,
        long offerAmount,
        long askingPrice,
        String status,
        int seasonNumber,
        String direction,
        long createdAt,
        String position,
        Double rating,
        Integer age,
        Integer contractEndSeason,
        Integer contractSeasonsRemaining,
        Long currentWage,
        Integer seasonMatchesPlayed
) {

    public static TransferOfferView from(TransferOffer offer, Human player, int currentSeason) {
        Integer contractEndSeason = player == null ? null : player.getContractEndSeason();
        Integer seasonsRemaining = contractEndSeason == null
                ? null : Math.max(0, contractEndSeason - currentSeason);
        return new TransferOfferView(
                offer.getId(), offer.getPlayerId(), offer.getPlayerName(),
                offer.getFromTeamId(), offer.getFromTeamName(),
                offer.getToTeamId(), offer.getToTeamName(),
                offer.getOfferAmount(), offer.getAskingPrice(), offer.getStatus(),
                offer.getSeasonNumber(), offer.getDirection(), offer.getCreatedAt(),
                player == null ? null : player.getPosition(),
                player == null ? null : player.getRating(),
                player == null ? null : player.getAge(),
                contractEndSeason, seasonsRemaining,
                player == null ? null : player.getWage(),
                player == null ? null : player.getSeasonMatchesPlayed()
        );
    }
}
