package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TransferOffer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select offer from TransferOffer offer where offer.id = :offerId")
    Optional<TransferOffer> findByIdForUpdate(@Param("offerId") long offerId);

    List<TransferOffer> findAllByToTeamIdAndStatus(long teamId, String status);

    List<TransferOffer> findAllByFromTeamIdAndStatus(long teamId, String status);

    List<TransferOffer> findAllByToTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<TransferOffer> findAllByFromTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<TransferOffer> findAllByPlayerIdAndSeasonNumberAndStatusNot(long playerId, int seasonNumber, String status);

    List<TransferOffer> findAllByPlayerIdAndStatusIn(long playerId, Collection<String> statuses);

    List<TransferOffer> findAllByPlayerIdInAndStatusIn(
            Collection<Long> playerIds, Collection<String> statuses);

    boolean existsByPlayerIdAndFromTeamIdAndSeasonNumberAndStatusIn(
            long playerId, long fromTeamId, int seasonNumber, Collection<String> statuses);

}
