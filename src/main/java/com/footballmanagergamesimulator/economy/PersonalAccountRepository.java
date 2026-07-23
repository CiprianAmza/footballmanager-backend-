package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PersonalAccountRepository extends JpaRepository<PersonalAccount, Long> {
    Optional<PersonalAccount> findByProfileId(long profileId);
    Optional<PersonalAccount> findByOwnerHumanId(Long ownerHumanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PersonalAccount account where account.id = :accountId")
    Optional<PersonalAccount> findByIdForUpdate(@Param("accountId") long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PersonalAccount account where account.profileId = :profileId")
    Optional<PersonalAccount> findByProfileIdForUpdate(@Param("profileId") long profileId);
}
