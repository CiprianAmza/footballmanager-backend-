package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface RoomContinueCycleRepository extends JpaRepository<RoomContinueCycle, Long> {
    Optional<RoomContinueCycle> findFirstByRoomIdAndStatusOrderByIdDesc(Long roomId, CycleStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RoomContinueCycle c where c.id = :id") Optional<RoomContinueCycle> findByIdForUpdate(@Param("id") Long id);
}
