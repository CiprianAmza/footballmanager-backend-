package com.footballmanagergamesimulator.economy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface PersonalLedgerEntryRepository extends JpaRepository<PersonalLedgerEntry, Long> {
    Optional<PersonalLedgerEntry> findByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey);
    List<PersonalLedgerEntry> findAllByCorrelationId(String correlationId);
    List<PersonalLedgerEntry> findAllByAccountIdOrderByCreatedAtAscIdAsc(long accountId);
    Page<PersonalLedgerEntry> findAllByAccountId(long accountId, Pageable pageable);

    @Query("select coalesce(sum(entry.signedAmount), 0) from PersonalLedgerEntry entry where entry.accountId = :accountId")
    long sumSignedAmount(@Param("accountId") long accountId);

    @Query("select coalesce(sum(entry.careerEarningsDelta), 0) from PersonalLedgerEntry entry where entry.accountId = :accountId")
    long sumCareerEarnings(@Param("accountId") long accountId);
}
