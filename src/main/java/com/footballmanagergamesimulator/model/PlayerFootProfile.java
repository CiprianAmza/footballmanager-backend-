package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;

@Entity
@Table(name = "PLAYER_FOOT_PROFILE",
        uniqueConstraints = @UniqueConstraint(name = "uk_player_foot_profile_player",
                columnNames = "PLAYER_ID"))
public class PlayerFootProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "PLAYER_ID", nullable = false)
    private long playerId;

    @Column(name = "LEFT_FOOT_RATING", nullable = false)
    private int leftFootRating;

    @Column(name = "RIGHT_FOOT_RATING", nullable = false)
    private int rightFootRating;

    @Version
    @Column(name = "VERSION")
    private long version;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }
    public int getLeftFootRating() { return leftFootRating; }
    public void setLeftFootRating(int leftFootRating) { this.leftFootRating = leftFootRating; }
    public int getRightFootRating() { return rightFootRating; }
    public void setRightFootRating(int rightFootRating) { this.rightFootRating = rightFootRating; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    @PrePersist
    @PreUpdate
    void validate() {
        PlayerPositionFamiliarity.validateFamiliarity(leftFootRating, "leftFootRating");
        PlayerPositionFamiliarity.validateFamiliarity(rightFootRating, "rightFootRating");
    }
}
