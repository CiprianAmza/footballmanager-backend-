package com.footballmanagergamesimulator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "friendly_event")
public class FriendlyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int season;
    private long organizerTeamId;
    private String name;

    /** Stable identity shared by every seasonal edition of an unofficial competition. */
    @Column(length = 80)
    private String seriesId;
    private String seriesName;
    private int editionNumber;

    /** TRAINING_CAMP, MINI_LEAGUE or MINI_CUP. */
    private String eventType;

    /** DRAFT, CONFIRMED, COMPLETED or CANCELLED. */
    @Column(columnDefinition = "varchar(20) default 'DRAFT'")
    private String status = "DRAFT";

    private long hostNationId;
    private String locationName;
    private int startDay;
    private int endDay;

    /** FITNESS, TACTICAL, TEAM_BONDING or COMMERCIAL. */
    private String focus;

    /** ROUND_ROBIN or KNOCKOUT; null for a camp. */
    private String format;

    @Column(length = 2000)
    private String participantTeamIds;

    private int maxTeams;
    private long participationFee;
    private long prizePool;
    private long organizerCost;

    private Long winnerTeamId;
    private String winnerTeamName;

    private long createdAt;
}
