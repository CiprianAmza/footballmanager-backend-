package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClubCapTableStateRepository extends JpaRepository<ClubCapTableState, Long> {
    Optional<ClubCapTableState> findByInstrumentId(long instrumentId);
    Optional<ClubCapTableState> findByTeamId(long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from ClubCapTableState state where state.instrumentId = :instrumentId")
    Optional<ClubCapTableState> findByInstrumentIdForUpdate(@Param("instrumentId") long instrumentId);
}
