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

@Entity
@Table(name = "personal_ledger_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_personal_ledger_idempotency",
                columnNames = {"account_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_personal_ledger_account_id", columnList = "account_id,id"),
                @Index(name = "idx_personal_ledger_correlation", columnList = "correlation_id")
        })
public class PersonalLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "profile_id", nullable = false)
    private long profileId;

    @Column(name = "season_number", nullable = false)
    private int seasonNumber;

    @Column(name = "game_day", nullable = false)
    private int gameDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 40)
    private LedgerEntryType entryType;

    @Column(name = "signed_amount", nullable = false)
    private long signedAmount;

    @Column(name = "career_earnings_delta", nullable = false)
    private long careerEarningsDelta;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "counterpart_team_id")
    private Long counterpartTeamId;

    @Column(name = "counterpart_asset_id")
    private Long counterpartAssetId;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public long getId() { return id; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long accountId) { this.accountId = accountId; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long profileId) { this.profileId = profileId; }
    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int value) { this.seasonNumber = value; }
    public int getGameDay() { return gameDay; }
    public void setGameDay(int value) { this.gameDay = value; }
    public LedgerEntryType getEntryType() { return entryType; }
    public void setEntryType(LedgerEntryType value) { this.entryType = value; }
    public long getSignedAmount() { return signedAmount; }
    public void setSignedAmount(long value) { this.signedAmount = value; }
    public long getCareerEarningsDelta() { return careerEarningsDelta; }
    public void setCareerEarningsDelta(long value) { this.careerEarningsDelta = value; }
    public long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(long value) { this.balanceAfter = value; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String value) { this.correlationId = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public Long getCounterpartTeamId() { return counterpartTeamId; }
    public void setCounterpartTeamId(Long value) { this.counterpartTeamId = value; }
    public Long getCounterpartAssetId() { return counterpartAssetId; }
    public void setCounterpartAssetId(Long value) { this.counterpartAssetId = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { this.createdAt = value; }
}
