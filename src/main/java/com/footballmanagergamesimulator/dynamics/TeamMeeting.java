package com.footballmanagergamesimulator.dynamics;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A monthly team meeting. At most one meeting exists per team per calendar month
 * of a season, enforced by the unique key (team_id, season, month_index). The
 * cooldown is therefore persistent — it survives restart, retry and save/load.
 */
@Entity
@Data
@Table(name = "team_meeting",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_meeting_team_season_month",
                columnNames = {"teamId", "season", "monthIndex"}))
public class TeamMeeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int season;
    private int monthIndex;              // 1-12 calendar month within the season

    @Column(name = "held_day")
    private int day;                     // GameCalendar.currentDay when held

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private MeetingContext context;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private DynamicsTone tone;

    private double managerReputation;    // snapshot at meeting time
    private int participantCount;
    private double averageMoraleDelta;

    @Column(length = 1000)
    private String summary;

    private long createdAtEpochMillis;

    @Version
    private long version;
}
