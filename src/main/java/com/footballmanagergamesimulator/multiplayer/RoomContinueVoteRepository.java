package com.footballmanagergamesimulator.multiplayer;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RoomContinueVoteRepository extends JpaRepository<RoomContinueVote, Long> {
    List<RoomContinueVote> findAllByCycleId(Long cycleId);
    Optional<RoomContinueVote> findByCycleIdAndUserId(Long cycleId, int userId);
    long countByCycleId(Long cycleId);
}
