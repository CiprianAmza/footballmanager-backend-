package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor
@Table(name = "room_continue_vote", uniqueConstraints = @UniqueConstraint(name = "uk_vote_user", columnNames = {"cycle_id", "user_id"}))
public class RoomContinueVote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "cycle_id", nullable = false) private Long cycleId;
    @Column(name = "user_id", nullable = false) private int userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private VoteSource source;
    @Column(nullable = false) private Instant votedAt;
}
