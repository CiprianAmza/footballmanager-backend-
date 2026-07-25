package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor
@Table(name = "game_room", uniqueConstraints = {
        @UniqueConstraint(name = "uk_game_room_singleton", columnNames = "singleton_key")
})
public class GameRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "singleton_key", nullable = false, unique = true)
    private short singletonKey = 1;
    @Column(nullable = false) private int hostUserId;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private RoomStatus status = RoomStatus.LOBBY;
    @Column(nullable = false) private int continueThresholdPercent = 50;
    @Column(nullable = false) private int dayTimeoutSeconds = 300;
    @Column(nullable = false) private int majorityTimeoutSeconds = 60;
    @Column(nullable = false) private int maxPlayers = 2;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant startedAt;
    @Version private long version;
}
