package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Collection;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findAllByBuyTeamIdAndSeasonNumber(long teamId, long seasonNumber);

    List<Transfer> findAllBySellTeamIdAndSeasonNumber(long teamId, long seasonNumber);

    List<Transfer> findAllBySeasonNumber(long seasonNumber);

    List<Transfer> findAllByPlayerIdOrderBySeasonNumberAscIdAsc(long playerId);

    @Query("select t from Transfer t where t.sellTeamId = :teamId order by t.playerTransferValue desc, t.id desc")
    List<Transfer> findRecordSalesByTeam(@Param("teamId") long teamId, Pageable pageable);

    @Query("select t from Transfer t order by t.playerTransferValue desc, t.id desc")
    List<Transfer> findWorldRecordSales(Pageable pageable);

    @Query("select t from Transfer t where t.playerId in :playerIds order by t.playerTransferValue desc, t.id desc")
    List<Transfer> findRecordSalesForPlayers(@Param("playerIds") Collection<Long> playerIds, Pageable pageable);

}
