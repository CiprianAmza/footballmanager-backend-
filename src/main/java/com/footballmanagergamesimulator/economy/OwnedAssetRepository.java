package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OwnedAssetRepository extends JpaRepository<OwnedAsset, Long> {
    List<OwnedAsset> findAllByAccountIdOrderByIdAsc(long accountId);
    List<OwnedAsset> findAllByAccountIdAndStatusOrderByIdAsc(long accountId, OwnedAssetStatus status);
    List<OwnedAsset> findAllByStatus(OwnedAssetStatus status);
    Optional<OwnedAsset> findByAccountIdAndPurchaseIdempotencyKey(long accountId, String key);
    Optional<OwnedAsset> findByAccountIdAndSaleIdempotencyKey(long accountId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from OwnedAsset asset where asset.id = :assetId and asset.accountId = :accountId")
    Optional<OwnedAsset> findOwnedForUpdate(@Param("assetId") long assetId,
                                             @Param("accountId") long accountId);

    @Query("select coalesce(sum(asset.currentValue), 0) from OwnedAsset asset "
            + "where asset.accountId = :accountId and asset.status = :status")
    long sumCurrentValue(@Param("accountId") long accountId,
                         @Param("status") OwnedAssetStatus status);
}
