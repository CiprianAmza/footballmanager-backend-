package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findAllByBuyTeamIdAndSeasonNumber(long teamId, long seasonNumber);

    List<Transfer> findAllBySellTeamIdAndSeasonNumber(long teamId, long seasonNumber);

}
