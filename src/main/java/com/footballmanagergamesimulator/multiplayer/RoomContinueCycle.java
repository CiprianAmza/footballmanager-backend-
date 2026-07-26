package com.footballmanagergamesimulator.multiplayer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor
@Table(name = "room_continue_cycle", uniqueConstraints = @UniqueConstraint(name = "uk_cycle_date", columnNames = {"room_id", "season", "game_day"}))
public class RoomContinueCycle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "room_id", nullable = false) private Long roomId;
    @Column(nullable = false) private int season;
    @Column(name = "game_day", nullable = false) private int gameDay;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private CycleStatus status = CycleStatus.OPEN;
    @Column(nullable = false) private Instant openedAt;
    @Column(nullable = false) private Instant dayDeadline;
    private Instant majorityReachedAt;
    private Instant majorityDeadline;
    private Instant advanceStartedAt;
    private Instant completedAt;
    private String failureCode;
    @Column(name = "advance_token", length = 36)
    private String advanceToken;
    @Column(name = "advance_lease_until")
    private Instant advanceLeaseUntil;
    @Column(name = "advance_execution_started_at")
    private Instant advanceExecutionStartedAt;
    @Column(name = "advance_mode", length = 8)
    private String advanceMode;
    @Column(name = "advance_force_continue", nullable = false)
    private boolean advanceForceContinue = false;
    @Version private long version;
}
