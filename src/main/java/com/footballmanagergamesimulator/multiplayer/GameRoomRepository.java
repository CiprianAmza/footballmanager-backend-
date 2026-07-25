package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface GameRoomRepository extends JpaRepository<GameRoom, Long> {
    Optional<GameRoom> findFirstByStatusIn(Collection<RoomStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GameRoom r where r.status in :statuses") Optional<GameRoom> findOpenForUpdate(@Param("statuses") Collection<RoomStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GameRoom r where r.id = :id") Optional<GameRoom> findByIdForUpdate(@Param("id") Long id);
}
