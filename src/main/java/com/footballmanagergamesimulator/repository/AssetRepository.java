package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findAllByOwnerHumanId(long ownerHumanId);

    List<Asset> findAllByOwnerHumanIdAndType(long ownerHumanId, Asset.AssetType type);
}
