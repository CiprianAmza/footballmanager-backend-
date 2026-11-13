package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "press_conference")
public class PressConference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int seasonNumber;
    @Column(name = "conference_day")
    private int day;
    private int matchDay; // the round (matchday number) of the upcoming match
    /** The competition of the match (0 on conferences saved before 2026-11-13). */
    @Column(columnDefinition = "bigint default 0 not null")
    private long competitionId;
    /** 0 = single match, 1/2 = the leg of a two-leg tie. */
    @Column(columnDefinition = "integer default 0 not null")
    private int legNumber;
    private String topic;
    private String responseChosen; // nullable: still to answer; MISSED: a later conference replaced it unanswered
    private int moraleEffect;
    /** Pre-match: the reputation the answer actually won or lost once the match was played (0 until then). */
    private int reputationEffect;
}
