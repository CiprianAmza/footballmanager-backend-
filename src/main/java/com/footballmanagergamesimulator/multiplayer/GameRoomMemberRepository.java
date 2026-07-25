package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface GameRoomMemberRepository extends JpaRepository<GameRoomMember, Long> {
    List<GameRoomMember> findAllByRoomIdAndMembershipStatus(Long roomId, MembershipStatus status);
    Optional<GameRoomMember> findByRoomIdAndUserIdAndMembershipStatus(Long roomId, int userId, MembershipStatus status);
    Optional<GameRoomMember> findFirstByUserIdAndMembershipStatus(int userId, MembershipStatus status);
    boolean existsByRoomIdAndTeamIdAndMembershipStatus(Long roomId, long teamId, MembershipStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from GameRoomMember m where m.roomId = :roomId and m.membershipStatus = 'ACTIVE'")
    List<GameRoomMember> findActiveForUpdate(@Param("roomId") Long roomId);
}
