package com.footballmanagergamesimulator.model;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import jakarta.persistence.*;

@Entity
@Table(name = "PLAYER_POSITION_FAMILIARITY",
        uniqueConstraints = @UniqueConstraint(name = "uk_player_position_familiarity",
                columnNames = {"PLAYER_ID", "POSITION_CODE"}),
        indexes = @Index(name = "idx_player_position_familiarity_player",
                columnList = "PLAYER_ID"))
public class PlayerPositionFamiliarity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "PLAYER_ID", nullable = false)
    private long playerId;

    @Column(name = "POSITION_CODE", nullable = false, length = 8)
    private String positionCode;

    @Column(name = "FAMILIARITY", nullable = false)
    private int familiarity;

    @Column(name = "PRIMARY_POSITION", nullable = false)
    private boolean primaryPosition;

    @Version
    @Column(name = "VERSION")
    private long version;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }
    public String getPositionCode() { return positionCode; }
    public void setPositionCode(String positionCode) { this.positionCode = positionCode; }
    public int getFamiliarity() { return familiarity; }
    public void setFamiliarity(int familiarity) { this.familiarity = familiarity; }
    public boolean isPrimaryPosition() { return primaryPosition; }
    public void setPrimaryPosition(boolean primaryPosition) { this.primaryPosition = primaryPosition; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public PlayerPosition position() {
        return PlayerPosition.require(positionCode);
    }

    @PrePersist
    @PreUpdate
    void validate() {
        validateFamiliarity(familiarity, "familiarity");
        positionCode = PlayerPosition.require(positionCode).code();
    }

    public static void validateFamiliarity(int value, String field) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException(field + " must be in [1,20]");
        }
    }
}
