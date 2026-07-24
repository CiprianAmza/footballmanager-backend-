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
@Table(name = "trader_adviser_contract", uniqueConstraints =
        @UniqueConstraint(name = "uk_trader_adviser_hire_retry", columnNames = {"account_id", "hire_idempotency_key"}))
public class TraderAdviserContract {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Column(name = "profile_id", nullable = false)
    private long profileId;
    @Column(name = "adviser_code", nullable = false, length = 40)
    private String adviserCode;
    @Column(nullable = false)
    private int skill;
    @Column(nullable = false)
    private int reputation;
    @Column(name = "salary_per_day", nullable = false)
    private long salaryPerDay;
    @Column(name = "contract_start_absolute_day", nullable = false)
    private long contractStartAbsoluteDay;
    @Column(name = "contract_end_absolute_day", nullable = false)
    private long contractEndAbsoluteDay;
    @Column(name = "last_paid_absolute_day", nullable = false)
    private long lastPaidAbsoluteDay;
    @Column(name = "advice_seed", nullable = false)
    private long adviceSeed;
    @Column(name = "model_version", nullable = false, length = 32)
    private String modelVersion;
    @Column(name = "hire_idempotency_key", nullable = false, length = 140)
    private String hireIdempotencyKey;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "termination_reason", length = 80)
    private String terminationReason;
    @Version
    private long version;

    public long getId() { return id; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { accountId = value; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long value) { profileId = value; }
    public String getAdviserCode() { return adviserCode; }
    public void setAdviserCode(String value) { adviserCode = value; }
    public int getSkill() { return skill; }
    public void setSkill(int value) { skill = value; }
    public int getReputation() { return reputation; }
    public void setReputation(int value) { reputation = value; }
    public long getSalaryPerDay() { return salaryPerDay; }
    public void setSalaryPerDay(long value) { salaryPerDay = value; }
    public long getContractStartAbsoluteDay() { return contractStartAbsoluteDay; }
    public void setContractStartAbsoluteDay(long value) { contractStartAbsoluteDay = value; }
    public long getContractEndAbsoluteDay() { return contractEndAbsoluteDay; }
    public void setContractEndAbsoluteDay(long value) { contractEndAbsoluteDay = value; }
    public long getLastPaidAbsoluteDay() { return lastPaidAbsoluteDay; }
    public void setLastPaidAbsoluteDay(long value) { lastPaidAbsoluteDay = value; }
    public long getAdviceSeed() { return adviceSeed; }
    public void setAdviceSeed(long value) { adviceSeed = value; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String value) { modelVersion = value; }
    public String getHireIdempotencyKey() { return hireIdempotencyKey; }
    public void setHireIdempotencyKey(String value) { hireIdempotencyKey = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String value) { terminationReason = value; }
    public long getVersion() { return version; }
}
