package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TransferOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, Long> {

    List<TransferOffer> findAllByToTeamIdAndStatus(long teamId, String status);

    List<TransferOffer> findAllByFromTeamIdAndStatus(long teamId, String status);

    List<TransferOffer> findAllByToTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<TransferOffer> findAllByFromTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<TransferOffer> findAllByPlayerIdAndSeasonNumberAndStatusNot(long playerId, int seasonNumber, String status);

}
