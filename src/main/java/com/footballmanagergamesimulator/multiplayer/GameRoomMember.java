package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor
@Table(name = "game_room_member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_room_member_user", columnNames = {"room_id", "user_id"}),
        @UniqueConstraint(name = "uk_room_member_team", columnNames = {"room_id", "team_id"})
})
public class GameRoomMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "room_id", nullable = false) private Long roomId;
    @Column(name = "user_id", nullable = false) private int userId;
    @Column(name = "team_id", nullable = false) private long teamId;
    @Column(nullable = false) private boolean ready;
    @Enumerated(EnumType.STRING) @Column(name = "membership_status", nullable = false, length = 8) private MembershipStatus membershipStatus = MembershipStatus.ACTIVE;
    @Column(nullable = false) private boolean fastForwardEnabled;
    private Long fastForwardUntilAbsoluteDay;
    @Column(nullable = false) private Instant joinedAt = Instant.now();
    @Column(nullable = false) private Instant lastSeenAt = Instant.now();
    @Version private long version;
}
