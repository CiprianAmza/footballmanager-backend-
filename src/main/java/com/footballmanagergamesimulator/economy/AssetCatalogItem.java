package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "asset_catalog_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_catalog_code", columnNames = "code"))
public class AssetCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private AssetType assetType;

    @Column(name = "apartment_rooms")
    private Integer apartmentRooms;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "icon_key", nullable = false, length = 80)
    private String iconKey;

    @Column(name = "purchase_price", nullable = false)
    private long purchasePrice;

    @Column(name = "resale_haircut_bps", nullable = false)
    private int resaleHaircutBps;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    public long getId() { return id; }
    public String getCode() { return code; }
    public AssetType getAssetType() { return assetType; }
    public Integer getApartmentRooms() { return apartmentRooms; }
    public String getName() { return name; }
    public String getIconKey() { return iconKey; }
    public long getPurchasePrice() { return purchasePrice; }
    public int getResaleHaircutBps() { return resaleHaircutBps; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
}
