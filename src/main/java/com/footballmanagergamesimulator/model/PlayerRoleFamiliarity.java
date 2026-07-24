package com.footballmanagergamesimulator.model;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey;
import jakarta.persistence.*;

import java.util.Locale;

@Entity
@Table(name = "PLAYER_ROLE_FAMILIARITY",
        uniqueConstraints = @UniqueConstraint(name = "uk_player_role_familiarity",
                columnNames = {"PLAYER_ID", "POSITION_CODE", "ROLE_CODE"}),
        indexes = @Index(name = "idx_player_role_familiarity_player",
                columnList = "PLAYER_ID"))
public class PlayerRoleFamiliarity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "PLAYER_ID", nullable = false)
    private long playerId;

    @Column(name = "POSITION_CODE", nullable = false, length = 8)
    private String positionCode;

    @Column(name = "ROLE_CODE", nullable = false, length = 48)
    private String roleCode;

    @Column(name = "FAMILIARITY", nullable = false)
    private int familiarity;

    @Version
    @Column(name = "VERSION")
    private long version;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }
    public String getPositionCode() { return positionCode; }
    public void setPositionCode(String positionCode) { this.positionCode = positionCode; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public int getFamiliarity() { return familiarity; }
    public void setFamiliarity(int familiarity) { this.familiarity = familiarity; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public PlayerPosition position() {
        return PlayerPosition.require(positionCode);
    }

    public PlayerRole role() {
        return PlayerRole.valueOf(roleCode);
    }

    @PrePersist
    @PreUpdate
    void validate() {
        PlayerPosition position = PlayerPosition.require(positionCode);
        PlayerRole role = PlayerRole.valueOf(roleCode.trim().toUpperCase(Locale.ROOT));
        new PositionRoleKey(position, role);
        positionCode = position.code();
        roleCode = role.name();
        PlayerPositionFamiliarity.validateFamiliarity(familiarity, "familiarity");
    }
}
