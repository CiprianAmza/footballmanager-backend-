package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface GameRoomMemberRepository extends JpaRepository<GameRoomMember, Long> {
    List<GameRoomMember> findAllByRoomIdAndMembershipStatus(Long roomId, MembershipStatus status);
    List<GameRoomMember> findAllByRoomId(Long roomId);
    Optional<GameRoomMember> findByRoomIdAndUserIdAndMembershipStatus(Long roomId, int userId, MembershipStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from GameRoomMember m where m.roomId = :roomId and m.userId = :userId and m.membershipStatus = 'ACTIVE'")
    Optional<GameRoomMember> findActiveForUpdate(@Param("roomId") Long roomId, @Param("userId") int userId);
    Optional<GameRoomMember> findFirstByUserIdAndMembershipStatus(int userId, MembershipStatus status);
    Optional<GameRoomMember> findByRoomIdAndUserId(Long roomId, int userId);
    Optional<GameRoomMember> findByRoomIdAndTeamId(Long roomId, Long teamId);
    boolean existsByRoomIdAndTeamIdAndMembershipStatus(Long roomId, Long teamId, MembershipStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from GameRoomMember m where m.roomId = :roomId and m.membershipStatus = 'ACTIVE'")
    List<GameRoomMember> findActiveForUpdate(@Param("roomId") Long roomId);
}
