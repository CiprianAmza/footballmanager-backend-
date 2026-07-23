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
@Table(name = "club_cap_table_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_club_cap_table_instrument", columnNames = "instrument_id"),
        @UniqueConstraint(name = "uk_club_cap_table_team", columnNames = "team_id")
})
public class ClubCapTableState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(name = "migration_version", nullable = false)
    private int migrationVersion;
    @Column(name = "controlling_account_id")
    private Long controllingAccountId;
    @Column(name = "control_threshold_bps", nullable = false)
    private int controlThresholdBps;
    @Version
    private long version;

    public long getId() { return id; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public long getTeamId() { return teamId; }
    public void setTeamId(long value) { this.teamId = value; }
    public int getMigrationVersion() { return migrationVersion; }
    public void setMigrationVersion(int value) { this.migrationVersion = value; }
    public Long getControllingAccountId() { return controllingAccountId; }
    public void setControllingAccountId(Long value) { this.controllingAccountId = value; }
    public int getControlThresholdBps() { return controlThresholdBps; }
    public void setControlThresholdBps(int value) { this.controlThresholdBps = value; }
    public long getVersion() { return version; }
}
