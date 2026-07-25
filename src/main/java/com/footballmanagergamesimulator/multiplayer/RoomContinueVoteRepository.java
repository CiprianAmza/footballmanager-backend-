package com.footballmanagergamesimulator.multiplayer;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface RoomContinueVoteRepository extends JpaRepository<RoomContinueVote, Long> {
    List<RoomContinueVote> findAllByCycleId(Long cycleId);
    Optional<RoomContinueVote> findByCycleIdAndUserId(Long cycleId, int userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from RoomContinueVote v where v.cycleId = :cycleId and v.userId = :userId")
    Optional<RoomContinueVote> findForUpdate(@Param("cycleId") Long cycleId, @Param("userId") int userId);
    long countByCycleId(Long cycleId);
}
