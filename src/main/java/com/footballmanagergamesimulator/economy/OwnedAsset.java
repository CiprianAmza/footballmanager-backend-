package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "owned_asset",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_owned_asset_purchase_retry",
                        columnNames = {"account_id", "purchase_idempotency_key"}),
                @UniqueConstraint(name = "uk_owned_asset_sale_retry",
                        columnNames = {"account_id", "sale_idempotency_key"})
        },
        indexes = @Index(name = "idx_owned_asset_account_status", columnList = "account_id,status"))
public class OwnedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "profile_id", nullable = false)
    private long profileId;

    @Column(name = "catalog_item_id", nullable = false)
    private long catalogItemId;

    @Column(name = "purchase_price", nullable = false)
    private long purchasePrice;

    @Column(name = "current_value", nullable = false)
    private long currentValue;

    @Column(name = "purchase_season", nullable = false)
    private int purchaseSeason;

    @Column(name = "purchase_day", nullable = false)
    private int purchaseDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnedAssetStatus status;

    @Column(name = "purchase_idempotency_key", nullable = false, length = 160)
    private String purchaseIdempotencyKey;

    @Column(name = "sale_idempotency_key", length = 160)
    private String saleIdempotencyKey;

    @Column(name = "sale_price")
    private Long salePrice;

    @Column(name = "sale_season")
    private Integer saleSeason;

    @Column(name = "sale_day")
    private Integer saleDay;

    @Version
    private long version;

    public long getId() { return id; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { this.accountId = value; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long value) { this.profileId = value; }
    public long getCatalogItemId() { return catalogItemId; }
    public void setCatalogItemId(long value) { this.catalogItemId = value; }
    public long getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(long value) { this.purchasePrice = value; }
    public long getCurrentValue() { return currentValue; }
    public void setCurrentValue(long value) { this.currentValue = value; }
    public int getPurchaseSeason() { return purchaseSeason; }
    public void setPurchaseSeason(int value) { this.purchaseSeason = value; }
    public int getPurchaseDay() { return purchaseDay; }
    public void setPurchaseDay(int value) { this.purchaseDay = value; }
    public OwnedAssetStatus getStatus() { return status; }
    public void setStatus(OwnedAssetStatus value) { this.status = value; }
    public String getPurchaseIdempotencyKey() { return purchaseIdempotencyKey; }
    public void setPurchaseIdempotencyKey(String value) { this.purchaseIdempotencyKey = value; }
    public String getSaleIdempotencyKey() { return saleIdempotencyKey; }
    public void setSaleIdempotencyKey(String value) { this.saleIdempotencyKey = value; }
    public Long getSalePrice() { return salePrice; }
    public void setSalePrice(Long value) { this.salePrice = value; }
    public Integer getSaleSeason() { return saleSeason; }
    public void setSaleSeason(Integer value) { this.saleSeason = value; }
    public Integer getSaleDay() { return saleDay; }
    public void setSaleDay(Integer value) { this.saleDay = value; }
    public long getVersion() { return version; }
}
