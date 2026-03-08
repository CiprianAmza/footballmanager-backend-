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
    private int matchDay; // the day of the upcoming match
    private String topic;
    private String responseChosen; // nullable
    private int moraleEffect;
    private int reputationEffect;
}
