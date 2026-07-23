package com.footballmanagergamesimulator.person;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PersonProfileRepository extends JpaRepository<PersonProfile, Long> {
    Optional<PersonProfile> findByUserId(Integer userId);
    Optional<PersonProfile> findByHumanId(Long humanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from PersonProfile profile where profile.userId = :userId")
    Optional<PersonProfile> findByUserIdForUpdate(@Param("userId") Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from PersonProfile profile where profile.humanId = :humanId")
    Optional<PersonProfile> findByHumanIdForUpdate(@Param("humanId") Long humanId);
}
