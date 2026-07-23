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

@Entity
@Table(name = "club_cash_transfer", uniqueConstraints =
        @UniqueConstraint(name = "uk_club_cash_transfer_retry", columnNames = {"account_id", "idempotency_key"}))
public class ClubCashTransfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "transfer_key", nullable = false, length = 36, unique = true)
    private String transferKey;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Column(name = "profile_id", nullable = false)
    private long profileId;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClubCashTransferDirection direction;
    @Column(nullable = false)
    private long amount;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "game_day", nullable = false)
    private int gameDay;
    @Column(name = "idempotency_key", nullable = false, length = 140)
    private String idempotencyKey;
    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;
    @Column(name = "personal_balance_after", nullable = false)
    private long personalBalanceAfter;
    @Column(name = "club_balance_after", nullable = false)
    private long clubBalanceAfter;
    @Column(name = "distributable_before", nullable = false)
    private long distributableBefore;

    public long getId() { return id; }
    public String getTransferKey() { return transferKey; }
    public void setTransferKey(String value) { this.transferKey = value; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { this.accountId = value; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long value) { this.profileId = value; }
    public long getTeamId() { return teamId; }
    public void setTeamId(long value) { this.teamId = value; }
    public ClubCashTransferDirection getDirection() { return direction; }
    public void setDirection(ClubCashTransferDirection value) { this.direction = value; }
    public long getAmount() { return amount; }
    public void setAmount(long value) { this.amount = value; }
    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int value) { this.seasonNumber = value; }
    public int getGameDay() { return gameDay; }
    public void setGameDay(int value) { this.gameDay = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String value) { this.correlationId = value; }
    public long getPersonalBalanceAfter() { return personalBalanceAfter; }
    public void setPersonalBalanceAfter(long value) { this.personalBalanceAfter = value; }
    public long getClubBalanceAfter() { return clubBalanceAfter; }
    public void setClubBalanceAfter(long value) { this.clubBalanceAfter = value; }
    public long getDistributableBefore() { return distributableBefore; }
    public void setDistributableBefore(long value) { this.distributableBefore = value; }
}
