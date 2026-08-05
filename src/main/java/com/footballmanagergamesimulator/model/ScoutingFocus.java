package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** A broad recruitment mission covering a club, competition or nation. */
@Entity
@Data
@Table(name = "scouting_focus", indexes = {
        @Index(name = "idx_scouting_focus_team_status", columnList = "teamId,status"),
        @Index(name = "idx_scouting_focus_scout_status", columnList = "scoutId,status")
})
public class ScoutingFocus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long scoutId;
    private String scoutName;

    /** TEAM, COMPETITION or NATION. */
    private String targetType;
    private long targetId;
    private String targetName;

    private String position;
    private double minRating;
    private double maxRating;
    private int minAge;
    private int maxAge;

    @Column(length = 600)
    private String keyAttributes;
    private int minimumAttribute;

    /** BALANCED, CURRENT_ABILITY, POTENTIAL, KEY_ATTRIBUTES or VALUE. */
    private String emphasis;

    private int startDay;
    private int endDay;
    private int season;
    private long cost;
    private String status;
    private int candidatesFound;
}
