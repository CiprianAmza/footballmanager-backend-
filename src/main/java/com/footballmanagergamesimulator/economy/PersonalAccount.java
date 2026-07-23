package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "personal_account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_personal_account_profile", columnNames = "profile_id"),
        @UniqueConstraint(name = "uk_personal_account_user", columnNames = "owner_user_id"),
        @UniqueConstraint(name = "uk_personal_account_human", columnNames = "owner_human_id")
})
public class PersonalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "profile_id", nullable = false, unique = true)
    private long profileId;

    @Column(name = "owner_user_id", unique = true)
    private Integer ownerUserId;

    @Column(name = "owner_human_id", unique = true)
    private Long ownerHumanId;

    @Column(name = "cash_balance", nullable = false)
    private long cashBalance;

    @Column(name = "lifetime_career_earnings", nullable = false)
    private long lifetimeCareerEarnings;

    @Column(name = "realized_investment_gain", nullable = false)
    private long realizedInvestmentGain;

    @Version
    private long version;

    public long getId() { return id; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long profileId) { this.profileId = profileId; }
    public Integer getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Integer ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getOwnerHumanId() { return ownerHumanId; }
    public void setOwnerHumanId(Long ownerHumanId) { this.ownerHumanId = ownerHumanId; }
    public long getCashBalance() { return cashBalance; }
    void setCashBalance(long cashBalance) { this.cashBalance = cashBalance; }
    public long getLifetimeCareerEarnings() { return lifetimeCareerEarnings; }
    void setLifetimeCareerEarnings(long value) { this.lifetimeCareerEarnings = value; }
    public long getRealizedInvestmentGain() { return realizedInvestmentGain; }
    void setRealizedInvestmentGain(long value) { this.realizedInvestmentGain = value; }
    public long getVersion() { return version; }
}
