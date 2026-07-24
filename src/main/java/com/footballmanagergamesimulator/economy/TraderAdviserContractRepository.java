package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TraderAdviserContractRepository extends JpaRepository<TraderAdviserContract, Long> {
    Optional<TraderAdviserContract> findByAccountIdAndHireIdempotencyKey(long accountId, String key);

    @Query("select contract.id from TraderAdviserContract contract where contract.active = true order by contract.id")
    List<Long> findAllActiveIdsOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from TraderAdviserContract contract where contract.id = :id")
    Optional<TraderAdviserContract> findByIdForUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from TraderAdviserContract contract where contract.profileId = :profileId "
            + "and contract.active = true and contract.contractStartAbsoluteDay <= :day "
            + "and contract.contractEndAbsoluteDay >= :day")
    Optional<TraderAdviserContract> findActiveForUpdate(@Param("profileId") long profileId,
                                                         @Param("day") long absoluteDay);
}
